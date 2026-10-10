package me.waveio.claudebot.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.Modifier
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.waveio.claudebot.data.WebPreviewResource
import me.waveio.claudebot.data.WorkspaceEditorDocument
import me.waveio.claudebot.data.WorkspaceEditorSession
import org.cef.browser.CefBrowser
import org.cef.browser.CefDevToolsClient
import org.junit.AfterClass
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.runners.MethodSorters
import org.junit.Test

/** Runs real JCEF only in the opt-in native desktop test lane. */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class DesktopEditorIntegrationTest {
    private var window: ComposeWindow? = null

    @Before
    fun createNativeWindow() {
        SwingUtilities.invokeAndWait {
            window = ComposeWindow().apply {
                title = "Blink JCEF integration test"
                setSize(800, 600)
                isVisible = true
            }
        }
    }

    @After
    fun disposeNativeWindow() {
        SwingUtilities.invokeAndWait {
            window?.dispose()
            window = null
        }
        waitUntil("Compose window browser hosts to close", 15_000) { browserHandles().isEmpty() }
    }

    @Test
    fun aPreviewOnlyLoadsPaintsAndExposesSyntheticHtmlInAFreshWindow() {
        val requestedPaths = CopyOnWriteArrayList<String>()
        val errors = CopyOnWriteArrayList<String>()
        val html = """<!doctype html><html><head><link rel="icon" href="data:,"></head><body>
            <script>window.previewMessages=[]; addEventListener('message',e=>previewMessages.push(e.data));</script>
            <p id="asset">asset pending</p><script type="module" src="/assets/probe.js"></script>
            <iframe srcdoc="<script>parent.postMessage(JSON.stringify({kind:'child probe',origin:window.origin,bridge:typeof BlinkNative,parent:(()=>{try{void parent.document;return 'accessible'}catch(e){return e.name}})(),storage:(()=>{try{void localStorage;return 'accessible'}catch(e){return e.name}})()}),'*')</script>"></iframe>
            <button id="go" onclick="document.getElementById('result').textContent='clicked'">Run action</button>
            <p id="result">waiting</p><p id="bridge">checking</p>
            <a id="external" href="https://outside.invalid/landing" onclick="document.getElementById('result').textContent='navigation requested'">External link</a>
            <iframe src="https://outside.invalid/frame"></iframe>
        </body></html>""".trimIndent()

        SwingUtilities.invokeAndWait {
            requireNotNull(window).setContent {
                NativeWebAppPreview(
                    entry = "/index.html",
                    revision = 1,
                    loadResource = { path ->
                        requestedPaths += path
                        when (path) {
                            "/index.html" -> WebPreviewResource(html.encodeToByteArray(), "text/html")
                            "/assets/probe.js" -> WebPreviewResource("document.getElementById('asset').textContent='local module ready';".encodeToByteArray(), "application/javascript")
                            else -> null
                        }
                    },
                    onError = { errors += "preview_failed" },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        val preview = awaitBrowser("preview-")
        var previewContext: PreviewContext? = null
        try {
            val context = awaitPreviewContext(preview).also { previewContext = it }
            waitForPreviewText(context, "waiting")
            waitForPreviewText(context, "local module ready")
            waitUntil("opaque inline frame probe", 15_000) {
                evaluatePreview(context, "JSON.stringify(window.previewMessages)")?.jsonPrimitive?.contentOrNull?.contains("child probe") == true
            }
            val child = Json.parseToJsonElement(requireNotNull(evaluatePreview(context,
                "window.previewMessages.find(value => JSON.parse(value).kind === 'child probe')")?.jsonPrimitive?.contentOrNull)).jsonObject
            assertEquals("null", child.getValue("origin").jsonPrimitive.contentOrNull)
            assertEquals("undefined", child.getValue("bridge").jsonPrimitive.contentOrNull)
            assertEquals("SecurityError", child.getValue("parent").jsonPrimitive.contentOrNull)
            assertEquals("SecurityError", child.getValue("storage").jsonPrimitive.contentOrNull)
            val initialState = requireNotNull(previewDocumentState(context)) {
                "The sandboxed preview frame should expose its document state through Runtime.evaluate"
            }
            assertTrue("The native preview content should have an opaque origin: $initialState", initialState.contains("\"origin\":\"null\""))
            assertTrue("The sandboxed preview owns its isolated top-level document: $initialState", initialState.contains("\"topLevel\":true"))
            assertTrue("The opaque sandbox must prevent local storage access: $initialState", initialState.contains("\"storage\":\"SecurityError\""))
            assertTrue("The preview page must not receive a native bridge: $initialState", initialState.contains("\"bridge\":\"undefined\""))
            waitUntil("preview entry loader request", 15_000) { "/index.html" in requestedPaths }
            assertTrue("The loader must only see the native-owned local entry", requestedPaths.toSet() == setOf("/index.html", "/assets/probe.js"))
            assertTrue(errors.isEmpty())
            val surface = preview.getUIComponent() as DesktopBrowserSurface
            waitUntil("preview-only native JCEF paint", 15_000) { surface.paintedFrame?.let { it.width > 0 && it.height > 0 } == true }
            assertTrue("The preview should paint into the native window viewport", surface.width >= 480 && surface.height >= 400)
        } catch (failure: Throwable) {
            val threadDump = saveJvmThreadDump()
            val nativeSamples = sampleNativeBrowserProcesses()
            throw AssertionError("Preview-only failure; JVM stacks: $threadDump; native samples: $nativeSamples; ${browserDiagnostic(preview, requestedPaths, errors, previewContext)}", failure)
        } finally {
            previewContext?.close()
            saveEvidenceFrame(preview, "preview-only")
        }
    }

    @Test
    fun editorReceivesRealInputAndFlushesWhilePreviewExecutesWithNetworkLockedDown() {
        val document = WorkspaceEditorDocument(
            id = "jcef-integration-document",
            path = "session/notes.md",
            kind = "markdown",
            content = "# Original document",
            theme = "light",
            language = "en",
        )
        val session = WorkspaceEditorSession()
        val changes = CopyOnWriteArrayList<String>()
        val errors = CopyOnWriteArrayList<String>()
        val requestedPaths = CopyOnWriteArrayList<String>()
        val html = """<!doctype html><html><head><link rel="icon" href="data:,"></head><body>
            <button id="go" onclick="document.getElementById('result').textContent='clicked'">Run action</button>
            <p id="result">waiting</p><p id="bridge">checking</p>
            <a id="external" href="https://outside.invalid/landing" onclick="document.getElementById('result').textContent='navigation requested'">External link</a>
            <iframe src="https://outside.invalid/frame"></iframe>
        </body></html>""".trimIndent()
        val showEditor = mutableStateOf(true)

        SwingUtilities.invokeAndWait {
            requireNotNull(window).setContent {
                if (showEditor.value) {
                    NativeWorkspaceEditor(
                        document = document,
                        session = session,
                        onChange = changes::add,
                        onAction = {},
                        onError = errors::add,
                        loadResource = { null },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    NativeWebAppPreview(
                        entry = "/index.html",
                        revision = 1,
                        loadResource = { path ->
                            requestedPaths += path
                            if (path == "/index.html") WebPreviewResource(html.encodeToByteArray(), "text/html") else null
                        },
                        onError = { errors += "preview_failed" },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        val editor = awaitBrowser("editor-")
        waitForText(editor, "Original document")
        editor.executeJavaScript(
            """(() => {
                const editor = document.querySelector('.ProseMirror[contenteditable="true"]');
                if (!editor) return false;
                editor.focus();
                document.execCommand('selectAll', false);
                return document.execCommand('insertText', false, 'Edited by JCEF');
            })()""",
            editor.getURL(),
            0,
        )
        waitUntil("native editor input callback", 15_000) { changes.any { "Edited by JCEF" in it } }
        assertTrue("The native editor should report the edited document", changes.last().contains("Edited by JCEF"))
        assertTrue("The bundled editor should complete its native ready handshake", errors.isEmpty())

        val flushDone = CompletableFuture<Boolean>()
        SwingUtilities.invokeAndWait { session.flush { success -> flushDone.complete(success); Unit } }
        assertTrue("The editor flush callback should complete within the bridge's 3-second evaluation limit",
            flushDone.get(3, TimeUnit.SECONDS))
        assertEquals(true, flushDone.getNow(false))
        val surface = editor.getUIComponent() as DesktopBrowserSurface
        waitUntil("native JCEF surface paint", 15_000) { surface.paintedFrame?.let { it.width > 0 && it.height > 0 } == true }
        assertTrue("The live browser surface should match the native window viewport",
            surface.width >= 480 && surface.height >= 400)
        saveEvidenceFrame(editor, "editor")

        SwingUtilities.invokeAndWait { showEditor.value = false }
        waitUntil("editor host disposal", 15_000) { editor !in browserHandles() }

        val preview = awaitBrowser("preview-")
        var previewContext: PreviewContext? = null
        try {
            val context = awaitPreviewContext(preview).also { previewContext = it }
            waitForPreviewText(context, "waiting")
            assertEquals("The sandboxed page interaction should execute in its own frame",
                "scheduled", evaluatePreview(
                context,
                """(() => {
                    document.getElementById('bridge').textContent =
                        typeof window.BlinkNative === 'undefined' ? 'native bridge absent' : 'native bridge present';
                    document.getElementById('go').click();
                    document.getElementById('external').click();
                    setTimeout(() => document.getElementById('result').textContent = 'navigation stayed blocked', 250);
                    return 'scheduled';
                })()""",
                )?.jsonPrimitive?.contentOrNull)

            waitForPreviewText(context, "navigation stayed blocked")
            waitForPreviewText(context, "native bridge absent")
            assertTrue("A blocked external navigation must keep the preview on its ephemeral origin",
                preview.getURL().startsWith("http://preview-") && preview.getURL().endsWith("/index.html"))
            assertEquals("Only the native-owned entry document should reach the resource loader",
                listOf("/index.html"), requestedPaths.toList())
            assertFalse(errors.contains("preview_failed"))
        } catch (failure: Throwable) {
            val threadDump = saveJvmThreadDump()
            val nativeSamples = sampleNativeBrowserProcesses()
            throw AssertionError("Preview failed; JVM stacks: $threadDump; native samples: $nativeSamples; ${browserDiagnostic(preview, requestedPaths, errors, previewContext)}", failure)
        } finally {
            previewContext?.close()
            saveEvidenceFrame(preview, "preview")
        }
    }

    @Test
    fun drawingCanvasAcceptsNativePointerInputAndFlushesTheChangedScene() {
        val changes = CopyOnWriteArrayList<String>()
        val errors = CopyOnWriteArrayList<String>()
        val session = WorkspaceEditorSession()
        SwingUtilities.invokeAndWait {
            requireNotNull(window).setContent {
                NativeWorkspaceEditor(
                    document = WorkspaceEditorDocument(id = "native-drawing", path = "session/drawing.excalidraw",
                        kind = "drawing", content = """{"type":"excalidraw","version":2,"elements":[],"appState":{},"files":{}}""",
                        theme = "light", language = "en"),
                    session = session, onChange = changes::add, onAction = {}, onError = errors::add,
                    loadResource = { null }, modifier = Modifier.fillMaxSize(),
                )
            }
        }
        val browser = awaitBrowser("editor-")
        val context = awaitPreviewContext(browser)
        try {
            waitUntil("Excalidraw canvas and rectangle tool", 15_000) {
                evaluatePreview(context, "Boolean(document.querySelector('.excalidraw canvas') && document.querySelector('[data-testid=toolbar-rectangle]'))")?.toString() == "true"
            }
            assertEquals("true", evaluatePreview(context,
                "(() => { document.querySelector('[data-testid=toolbar-rectangle]').click(); return true; })()")?.toString())
            val surface = browser.getUIComponent() as DesktopBrowserSurface
            SwingUtilities.invokeAndWait {
                browser.setFocus(true)
                val now = System.currentTimeMillis()
                surface.dispatchEvent(java.awt.event.MouseEvent(surface, java.awt.event.MouseEvent.MOUSE_PRESSED, now,
                    java.awt.event.InputEvent.BUTTON1_DOWN_MASK, 320, 300, 1, false, java.awt.event.MouseEvent.BUTTON1))
                surface.dispatchEvent(java.awt.event.MouseEvent(surface, java.awt.event.MouseEvent.MOUSE_DRAGGED, now + 20,
                    java.awt.event.InputEvent.BUTTON1_DOWN_MASK, 430, 370, 0, false, java.awt.event.MouseEvent.NOBUTTON))
                surface.dispatchEvent(java.awt.event.MouseEvent(surface, java.awt.event.MouseEvent.MOUSE_RELEASED, now + 40,
                    0, 430, 370, 1, false, java.awt.event.MouseEvent.BUTTON1))
            }
            waitUntil("native drawing scene change", 15_000) {
                changes.any { content -> Json.parseToJsonElement(content).jsonObject["elements"]?.jsonArray?.isNotEmpty() == true }
            }
            val elements = Json.parseToJsonElement(changes.last()).jsonObject.getValue("elements").jsonArray
            assertEquals("rectangle", elements.first().jsonObject.getValue("type").jsonPrimitive.contentOrNull)
            val flushed = CompletableFuture<Boolean>()
            SwingUtilities.invokeAndWait { session.flush { flushed.complete(it); Unit } }
            assertTrue(flushed.get(3, TimeUnit.SECONDS))
            assertTrue("The native drawing editor should have no resource errors: $errors", errors.isEmpty())
            waitUntil("native drawing stroke paint", 15_000) {
                surface.paintedFrame?.let { frame ->
                    val scale = frame.width.toDouble() / surface.width
                    val x0 = (310 * scale).toInt(); val x1 = (440 * scale).toInt().coerceAtMost(frame.width - 1)
                    val y0 = (290 * scale).toInt(); val y1 = (380 * scale).toInt().coerceAtMost(frame.height - 1)
                    var ink = 0
                    for (x in x0..x1) for (y in y0..y1) {
                        val color = frame.getRGB(x, y)
                        if (color ushr 24 > 200 && color and 0xff < 60 && color ushr 8 and 0xff < 60 && color ushr 16 and 0xff < 60) ink++
                    }
                    ink > 30
                } == true
            }
            saveEvidenceFrame(browser, "drawing")
        } finally { context.close() }
    }

    @Test
    fun sourceEditorReceivesInputAndFlushesItsDocument() {
        val session = WorkspaceEditorSession()
        val changes = CopyOnWriteArrayList<String>()
        val errors = CopyOnWriteArrayList<String>()
        SwingUtilities.invokeAndWait {
            requireNotNull(window).setContent {
                NativeWorkspaceEditor(
                    document = WorkspaceEditorDocument(id = "native-source", path = "session/example.kt", kind = "code",
                        content = "val original = true", theme = "dark", language = "en"),
                    session = session, onChange = changes::add, onAction = {}, onError = errors::add,
                    loadResource = { null }, modifier = Modifier.fillMaxSize(),
                )
            }
        }
        val browser = awaitBrowser("editor-")
        waitForText(browser, "val original = true")
        browser.executeJavaScript("""(() => {
            const editor = document.querySelector('.cm-content[contenteditable=true]');
            editor.focus(); document.execCommand('selectAll', false);
            document.execCommand('insertText', false, 'val edited = true');
        })()""", browser.getURL(), 0)
        waitUntil("CodeMirror native input callback", 15_000) { changes.any { "val edited = true" in it } }
        val flushed = CompletableFuture<Boolean>()
        SwingUtilities.invokeAndWait { session.flush { flushed.complete(it); Unit } }
        assertTrue(flushed.get(3, TimeUnit.SECONDS))
        assertTrue("The source editor should have no resource errors: $errors", errors.isEmpty())
        saveEvidenceFrame(browser, "source")
    }

    @Test
    fun inlineFrameCannotSendWebRtcPacketsOutsideThePreviewTransport() {
        val udp = java.net.DatagramSocket(java.net.InetSocketAddress("127.0.0.1", 0)).apply { soTimeout = 5_000 }
        val tcp = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).apply { soTimeout = 5_000 }
        val udpPackets = java.util.concurrent.atomic.AtomicInteger()
        val tcpConnections = java.util.concurrent.atomic.AtomicInteger()
        val udpListener = Thread {
            runCatching {
                val packet = java.net.DatagramPacket(ByteArray(2_048), 2_048)
                udp.receive(packet)
                if (packet.length >= 8 && packet.data.sliceArray(4..7).contentEquals(byteArrayOf(0x21, 0x12, 0xA4.toByte(), 0x42)))
                    udpPackets.incrementAndGet()
            }
        }.apply { start() }
        val tcpListener = Thread {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline) {
                runCatching {
                    tcp.soTimeout = ((deadline - System.nanoTime()) / 1_000_000).toInt().coerceAtLeast(1)
                    tcp.accept().use { socket ->
                        socket.soTimeout = 1_000
                        val header = socket.getInputStream().readNBytes(8)
                        // Local HTTP service probes also reach listening test ports. TURN
                        // starts with a STUN header and its fixed magic cookie, not HTTP.
                        if (header.size == 8 && header.sliceArray(4..7).contentEquals(byteArrayOf(0x21, 0x12, 0xA4.toByte(), 0x42)))
                            tcpConnections.incrementAndGet()
                    }
                }
            }
        }.apply { start() }
        val html = """<html><head><link rel="icon" href="data:,"></head><body>
            <script>window.rtcProbe=[];addEventListener('message',e=>rtcProbe.push(e.data));</script>
            <p>RTC sandbox fixture</p><iframe srcdoc="<script>
              try { const peer=new RTCPeerConnection({iceServers:[{urls:'stun:127.0.0.1:${udp.localPort}'},{urls:'turn:127.0.0.1:${tcp.localPort}?transport=tcp',username:'fixture',credential:'fixture'}]});
              peer.createDataChannel('fixture');peer.createOffer().then(o=>peer.setLocalDescription(o)).then(()=>parent.postMessage('rtc started','*')).catch(e=>parent.postMessage('rtc rejected','*')); } catch(e) { parent.postMessage('rtc rejected','*'); }
            </script>"></iframe></body></html>"""
        val errors = CopyOnWriteArrayList<String>()
        var context: PreviewContext? = null
        try {
            SwingUtilities.invokeAndWait {
                requireNotNull(window).setContent {
                    NativeWebAppPreview("/index.html", 1,
                        { path -> if (path == "/index.html") WebPreviewResource(html.toByteArray(), "text/html") else null },
                        { errors += "preview_failed" }, Modifier.fillMaxSize())
                }
            }
            val browser = awaitBrowser("preview-")
            val activeContext = awaitPreviewContext(browser).also { context = it }
            waitUntil("inline frame RTC attempt", 15_000) {
                evaluatePreview(activeContext, "window.rtcProbe.includes('rtc started') || window.rtcProbe.includes('rtc rejected')")?.toString() == "true"
            }
            udpListener.join(6_000); tcpListener.join(6_000)
            assertEquals("The unbootstrapped inline frame must not send STUN packets", 0, udpPackets.get())
            assertEquals("The unbootstrapped inline frame must not send TURN requests over TCP", 0, tcpConnections.get())
            assertTrue(errors.isEmpty())
        } finally { context?.close(); udp.close(); tcp.close(); udpListener.join(1_000); tcpListener.join(1_000) }
    }

    private fun awaitBrowser(prefix: String): CefBrowser {
        var result: CefBrowser? = null
        waitUntil("$prefix browser initialization", 15_000) {
            result = browserHandles().firstOrNull { it.getIdentifier() > 0 && it.getURL().startsWith("http://$prefix") }
            result != null
        }
        return requireNotNull(result)
    }

    private fun waitForText(browser: CefBrowser, text: String) {
        waitUntil("browser text '$text'", 15_000) { browserText(browser)?.contains(text) == true }
    }

    private fun browserText(browser: CefBrowser): String? {
        if (browser.getIdentifier() <= 0) return null
        val result = CompletableFuture<String>()
        browser.getText { value -> result.complete(value) }
        return try { result.get(3, TimeUnit.SECONDS) } catch (_: TimeoutException) { null }
    }

    private fun browserSource(browser: CefBrowser): String? {
        if (browser.getIdentifier() <= 0) return null
        val result = CompletableFuture<String>()
        browser.getSource { value -> result.complete(value) }
        return try { result.get(3, TimeUnit.SECONDS).take(4_000) } catch (_: TimeoutException) { null }
    }

    private data class PreviewContext(val client: CefDevToolsClient) : AutoCloseable {
        override fun close() = client.close()
    }

    private fun awaitPreviewContext(browser: CefBrowser): PreviewContext {
        val client = browser.getDevToolsClient()
        var context: PreviewContext? = null
        try {
            waitUntil("sandboxed preview main frame", 15_000) {
                val response = client.executeDevToolsMethod("Page.getFrameTree").get(3, TimeUnit.SECONDS)
                val root = Json.parseToJsonElement(response).jsonObject["frameTree"]?.jsonObject
                val frame = root?.get("frame")?.jsonObject
                if (frame != null && frame["url"]?.jsonPrimitive?.contentOrNull == browser.getURL()) {
                    context = PreviewContext(client)
                }
                context != null
            }
            return requireNotNull(context)
        } catch (failure: Throwable) {
            client.close()
            throw failure
        }
    }

    private fun previewDocumentState(context: PreviewContext): String? = evaluatePreview(
        context,
        """JSON.stringify({
            text: document.body.innerText,
            bridge: typeof window.BlinkNative,
            origin: window.origin,
            topLevel: window.parent === window,
            storage: (() => { try { void window.localStorage; return 'accessible'; } catch (e) { return e.name; } })()
        })""",
    )?.jsonPrimitive?.contentOrNull

    private fun waitForPreviewText(context: PreviewContext, text: String) {
        waitUntil("sandboxed preview text '$text'", 15_000) { previewDocumentState(context)?.contains(text) == true }
    }

    private fun evaluatePreview(context: PreviewContext, expression: String): JsonElement? {
        val params = buildJsonObject {
            put("expression", expression)
            put("returnByValue", true)
            put("awaitPromise", true)
        }
        val response = context.client.executeDevToolsMethod("Runtime.evaluate", params.toString())
            .get(3, TimeUnit.SECONDS)
        val result = Json.parseToJsonElement(response).jsonObject
        result["exceptionDetails"]?.let { throw AssertionError("Preview script evaluation failed: $it") }
        return result["result"]?.jsonObject?.get("value")
    }

    private fun browserDiagnostic(
        browser: CefBrowser,
        requestedPaths: List<String>,
        errors: List<String>,
        previewContext: PreviewContext? = null,
    ): String =
        "url=${browser.getURL()}, paths=$requestedPaths, errors=$errors, " +
            "text=${browserText(browser)?.take(1_000)}, source=${browserSource(browser)}, " +
            "preview=${runCatching { previewContext?.let(::previewDocumentState) }.getOrNull()}"

    private fun saveJvmThreadDump(): Path? = runCatching {
        val output = Path.of("/tmp/blink-macos-native-java-threads.txt")
        val text = Thread.getAllStackTraces().entries
            .sortedBy { it.key.name }
            .joinToString("\n") { (thread, frames) ->
                "${thread.name}\n" + frames.joinToString("\n") { "\tat $it" }
            }
        Files.writeString(output, text)
        output
    }.getOrNull()

    private fun sampleNativeBrowserProcesses(): List<Path> = runCatching {
        val samples = mutableListOf<Path>()
        ProcessHandle.current().descendants().forEach { process ->
            val info = process.info()
            val command = info.command().orElse("")
            val arguments = info.arguments().orElse(emptyArray()).joinToString(" ")
            val output = when {
                command.endsWith("/cef_server") -> Path.of("/tmp/blink-macos-cef-native.sample")
                command.contains("Helper (Renderer)") || arguments.contains("Helper (Renderer)") ->
                    Path.of("/tmp/blink-macos-cef-native-renderer.sample")
                else -> null
            } ?: return@forEach
            runCatching {
                val sampler = ProcessBuilder("/usr/bin/sample", process.pid().toString(), "1", "-file", output.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                if (sampler.waitFor(3, TimeUnit.SECONDS)) samples.add(output) else sampler.destroyForcibly()
            }
        }
        samples
    }.getOrDefault(emptyList())

    private fun saveEvidenceFrame(browser: CefBrowser, name: String) {
        runCatching {
            val surface = browser.getUIComponent() as? DesktopBrowserSurface ?: return
            val raster = surface.paintedFrame ?: return
            val frame = java.awt.image.BufferedImage(raster.width, raster.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            SwingUtilities.invokeAndWait {
                val graphics = frame.createGraphics()
                try {
                    graphics.scale(raster.width.toDouble() / surface.width, raster.height.toDouble() / surface.height)
                    surface.paint(graphics)
                } finally { graphics.dispose() }
            }
            val directory = Path.of("/tmp/blink-macos-browser-evidence")
            Files.createDirectories(directory)
            ImageIO.write(frame, "png", directory.resolve("$name.png").toFile())
        }
    }

    private fun browserHandles(): List<CefBrowser> {
        var result = emptyList<CefBrowser>()
        SwingUtilities.invokeAndWait { result = DesktopBrowserRuntime.browserHandles.toList() }
        return result
    }

    private fun waitUntil(description: String, timeoutMillis: Long, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        throw AssertionError("Timed out waiting for $description after ${timeoutMillis}ms")
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun initializeJcef() {
            assumeTrue("Enable with -Dblink.nativeBrowserTests=true on the pinned desktop runtime",
                System.getProperty("blink.nativeBrowserTests") == "true")
            DesktopBrowserRuntime.initialize()
        }

        @JvmStatic
        @AfterClass
        fun closeJcef() {
            if (System.getProperty("blink.nativeBrowserTests") == "true") DesktopBrowserRuntime.close()
        }
    }
}
