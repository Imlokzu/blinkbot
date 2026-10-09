package me.waveio.claudebot

import android.os.SystemClock
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.waveio.claudebot.data.BotApi
import me.waveio.claudebot.platform.PickedFile
import me.waveio.claudebot.platform.PlatformBridge
import me.waveio.claudebot.state.AppController
import me.waveio.claudebot.state.Screen
import me.waveio.claudebot.ui.LocaleText
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Full App/controller/editor flows. Only authenticated HTTP and silent OS services are fixtures. */
class FileWorkspaceIntegrationTest {
    @get:Rule(order = 0) val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule(order = 1) val failures = object : TestWatcher() {
        override fun starting(description: Description) { currentTestName = description.methodName }
        override fun failed(error: Throwable, description: Description) { captureFailure(description.methodName) }
        override fun finished(description: Description) {
            try { cleanup() } catch (failure: Throwable) { captureFailure(description.methodName); throw failure }
        }
    }
    private val host = WorkspaceFixtureHost()
    private val platform = WorkspaceFixtureBridge()
    private lateinit var controller: AppController
    private lateinit var strings: LocaleText
    private var currentTestName = "workspace"

    private fun cleanup() {
        compose.mainClock.autoAdvance = true
        if (::controller.isInitialized) compose.runOnIdle { controller.closePreview(); controller.close() }
        compose.waitForIdle()
        host.close()
        assertTrue("Unexpected fixture requests: ${host.unexpected}", host.unexpected.isEmpty())
        assertTrue("Workspace navigation must not leave the application", platform.externalLinks.isEmpty())
    }

    private fun launch(fontScale: Float = 1f) {
        strings = runBlocking { LocaleText.load("en") }
        InstrumentationRegistry.getArguments().getString("workspaceTheme")?.let { theme ->
            require(theme in setOf("light", "dark"))
            val preferences = Json.parseToJsonElement(requireNotNull(platform.readPreference("preferences.v1"))).jsonObject
            platform.writePreference("preferences.v1", JsonObject(preferences + ("theme" to JsonPrimitive(theme))).toString())
        }
        compose.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                App(platform, onSystemBarAppearance = compose.activity::systemBarAppearance) { bridge ->
                    AppController(bridge, makeApi = host::makeApi).also { controller = it }
                }
            }
        }
        await { ::controller.isInitialized && controller.state.value.connected && !controller.state.value.initializing }
    }

    private fun await(condition: () -> Boolean) = compose.waitUntil(WORKSPACE_TIMEOUT, condition)
    private fun label(key: String) = strings.get(key)
    private fun clickText(value: String) = compose.onNode(hasText(value) and hasClickAction()).performClick()
    private fun clickKey(key: String) = clickText(label(key))

    private fun openMenu() {
        if (compose.onAllNodesWithContentDescription(label("nav.menu")).fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithContentDescription(label("nav.menu")).performClick()
    }

    private fun openFiles() {
        openMenu()
        clickKey("nav.files")
        await { controller.state.value.screen == Screen.Files && !controller.state.value.loading && controller.state.value.openFile == null }
        compose.onNodeWithTag("file-workspace").assertIsDisplayed()
        captureSuccess("files-browser")
    }

    private fun previewFile(path: String) {
        compose.onNode(hasScrollToNodeAction() and hasAnyAncestor(hasTestTag("file-workspace")))
            .performScrollToNode(hasText(path.substringAfterLast('/')))
        compose.onNodeWithText(path.substringAfterLast('/')).performScrollTo().performClick()
        await { controller.state.value.previewPath == path && !controller.state.value.loading }
        compose.onNodeWithTag("media-preview").assertIsDisplayed()
    }

    private fun editPreview(): WebView {
        compose.onNodeWithContentDescription(label("files.edit")).performClick()
        await { controller.state.value.fileEditable && !controller.state.value.fileLoading }
        compose.onNodeWithTag("workspace-file-editor").assertIsDisplayed()
        val web = editor(".prose-note[contenteditable=true], [data-testid=source-editor] .cm-content, [data-testid=drawing-editor] canvas")
        if (controller.state.value.openFile?.let { it.endsWith(".md") || it.endsWith(".markdown") } == true)
            captureSuccess("markdown-editor")
        return web
    }

    private fun closePreview() {
        compose.onNode(hasContentDescription(label("action.close")) and hasAnyAncestor(hasTestTag("media-preview")))
            .performClick()
        await { controller.state.value.previewTitle == null }
    }

    private fun back() = compose.onNodeWithContentDescription(label("nav.back")).performClick()

    private fun openChat() {
        openMenu()
        compose.onNodeWithText(WORKSPACE_CHAT_TITLE).performScrollTo().performClick()
        await { controller.state.value.sessionId == "chat_1" && !controller.state.value.loading }
    }

    private fun openLinkedNote() = openLinkedDocument(WORKSPACE_LINK, "session/notes.md")

    private fun openLinkedDocument(title: String, path: String) {
        val node = compose.onNodeWithText(title, useUnmergedTree = true).performScrollTo()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        node.performTouchInput { click(layouts.single().getBoundingBox(title.length / 2).center) }
        await { controller.state.value.previewPath == path && !controller.state.value.loading }
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView && view.isShown && view.isAttachedToWindow) return view
        if (view is ViewGroup) for (index in view.childCount - 1 downTo 0) findWebView(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun visibleWebViews(): List<WebView> {
        var found = emptyList<WebView>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            found = WindowInspector.getGlobalWindowViews().mapNotNull(::findWebView)
        }
        return found
    }

    private fun editor(selector: String): WebView {
        compose.waitForIdle()
        var found: WebView? = null
        await {
            found = visibleWebViews().lastOrNull { web ->
                javascript(web, "Boolean(window.BlinkWorkspace && document.querySelector(${JsonPrimitive(selector)}))") == "true"
            }
            found != null
        }
        return requireNotNull(found)
    }

    private fun javascript(web: WebView, source: String): String {
        val result = AtomicReference<String>()
        val completed = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            web.evaluateJavascript(source) { result.set(it); completed.countDown() }
        }
        assertTrue("The editor UI thread must remain responsive", completed.await(4, TimeUnit.SECONDS))
        return requireNotNull(result.get())
    }

    private fun clickDom(web: WebView, role: String, name: String) {
        val selector = if (role == "tab") "[role=tab]" else "button"
        val matched = javascript(web, """(() => {
            const button = [...document.querySelectorAll(${JsonPrimitive(selector)})].find(element =>
                element.getAttribute('aria-label') === ${JsonPrimitive(name)} || element.textContent.trim() === ${JsonPrimitive(name)});
            if (!button) return false; button.click(); return true;
        })()""".trimIndent())
        assertEquals("Expected reachable editor action: $name", "true", matched)
    }

    private fun append(web: WebView, selector: String, value: String) {
        assertEquals("true", javascript(web, """(() => {
            const element = document.querySelector(${JsonPrimitive(selector)});
            if (!element) return false;
            const editable = element.closest('[contenteditable=true]') || element;
            editable.focus();
            const range = document.createRange(); range.selectNodeContents(element); range.collapse(false);
            const selection = getSelection(); selection.removeAllRanges(); selection.addRange(range);
            return document.execCommand('insertText', false, ${JsonPrimitive(value)});
        })()""".trimIndent()))
    }

    @Test fun filesRouteMarkdownHtmlAndOriginalImageToTheirRealReaders() {
        host.includeOtherFormats()
        launch(); openFiles()
        previewFile("notes.md")
        val rich = editor(".prose-note h1")
        assertEquals("\"Workspace note\"", javascript(rich, "document.querySelector('.prose-note h1').textContent"))
        assertEquals("1", javascript(rich, "document.querySelectorAll('.prose-note table').length"))
        assertTrue(host.accepted.isEmpty())
        captureSuccess("markdown-reader")
        closePreview()

        previewFile("index.html")
        compose.onNodeWithTag("native-web-app-preview").assertIsDisplayed()
        var page: WebView? = null
        await {
            page = visibleWebViews().lastOrNull { javascript(it, "Boolean(window.workspacePreviewReady)") == "true" }
            page != null
        }
        javascript(requireNotNull(page), "document.getElementById('counter').click()")
        assertEquals("\"1\"", javascript(requireNotNull(page), "document.getElementById('counter').textContent"))
        assertEquals("\"undefined\"", javascript(requireNotNull(page), "typeof window.BlinkNative"))
        captureSuccess("html-reader")
        closePreview()

        previewFile("photo.png")
        await { controller.state.value.previewBytes != null }
        await { compose.onAllNodesWithTag("media-image:photo.png").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("media-image:photo.png").assertIsDisplayed()
        assertArrayEquals(host.image, controller.state.value.previewBytes)
        compose.onNodeWithTag("native-workspace-editor").assertDoesNotExist()
        clickKey("media.save")
        await { platform.saved.isNotEmpty() }
        assertArrayEquals(host.image, platform.saved.single().bytes)
        captureSuccess("image-reader")
        closePreview()

        previewFile("vector.svg")
        compose.onNodeWithTag("native-web-app-preview").assertIsDisplayed()
        var svgPage: WebView? = null
        await {
            svgPage = visibleWebViews().lastOrNull { web -> javascript(web, """(() => {
                const image = document.querySelector('img[src="image.svg"]');
                const bounds = image?.getBoundingClientRect();
                return Boolean(image?.complete && image.naturalWidth === 32 && image.naturalHeight === 24 &&
                    bounds.width > 0 && bounds.height > 0 && document.elementFromPoint(bounds.x+bounds.width/2,bounds.y+bounds.height/2) === image);
            })()""".trimIndent()) == "true" }
            svgPage != null
        }
        assertEquals("\"undefined\"", javascript(requireNotNull(svgPage), "typeof window.BlinkNative"))
        assertEquals("A fitted image must not add document scrollbars", "true", javascript(requireNotNull(svgPage),
            "document.documentElement.scrollHeight <= innerHeight && document.documentElement.scrollWidth <= innerWidth"))
        val closeBounds = compose.onNode(hasContentDescription(label("action.close")) and hasAnyAncestor(hasTestTag("media-preview")))
            .fetchSemanticsNode().boundsInWindow
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val web = requireNotNull(svgPage)
            val position = IntArray(2); web.getLocationInWindow(position)
            assertTrue("The image viewport must remain below the native header", position[1] >= closeBounds.bottom)
        }
        compose.onNodeWithContentDescription(label("files.edit")).assertDoesNotExist()
        captureSuccess("svg-reader")
        clickKey("media.save")
        await { platform.saved.size == 2 }
        assertEquals("vector.svg", platform.saved.last().name)
        assertEquals("image/svg+xml", platform.saved.last().mimeType)
        assertArrayEquals(host.svg, platform.saved.last().bytes)
        closePreview()

        previewFile("portable.pdf")
        assertEquals("application/pdf", controller.state.value.previewMimeType)
        assertTrue("The fixture deliberately looks like UTF-8 text", controller.state.value.previewText.startsWith("%PDF-"))
        compose.onNodeWithText(label("files.previewUnavailable")).assertIsDisplayed()
        compose.onAllNodesWithText("%PDF-", substring = true).assertCountEquals(0)
        compose.onNodeWithTag("native-workspace-editor").assertDoesNotExist()
        compose.onNodeWithContentDescription(label("files.edit")).assertDoesNotExist()
        captureSuccess("pdf-original-export")
        clickKey("media.save")
        await { platform.saved.size == 3 }
        assertEquals("portable.pdf", platform.saved.last().name)
        assertEquals("application/pdf", platform.saved.last().mimeType)
        assertArrayEquals(host.pdf, platform.saved.last().bytes)
        assertTrue(host.accepted.isEmpty())
    }

    @Test fun richTableTaskAndSourceEditsSurviveImmediateBackAndReopen() {
        launch(); openFiles(); previewFile("notes.md")
        val web = editPreview()
        append(web, ".prose-note td:first-of-type", " Pro")
        javascript(web, "document.querySelector('.prose-note input[type=checkbox]:not(:checked)').click()")
        clickDom(web, "tab", "Source")
        val source = editor("[data-testid=source-editor] .cm-content")
        append(source, ".cm-content", "\n\nThe final keystroke survives closing.")
        // Exercise the native close/flush gate immediately, before autosave's debounce.
        back()
        await { controller.state.value.openFile == null && host.content("notes.md").contains("final keystroke") }
        val saved = host.content("notes.md")
        assertTrue(saved.contains("Laptop Pro"))
        assertTrue(saved.contains("- [x] Review the table"))
        assertTrue(host.accepted.all { it.getValue("revision").jsonPrimitive.content != "missing" })
        previewFile("notes.md")
        val reopened = editor(".prose-note table")
        assertEquals("true", javascript(reopened, "document.querySelector('.prose-note').textContent.includes('final keystroke')"))
        assertEquals("2", javascript(reopened, "document.querySelectorAll('.prose-note input[type=checkbox]:checked').length"))
    }

    @Test fun noteAndDrawingCreationRejectCollisionsBeforeCreatingDistinctFiles() {
        launch(); openFiles()
        val original = host.content("notes.md")
        clickKey("files.newNote")
        compose.onNodeWithContentDescription(label("files.name")).performTextInput("notes")
        clickKey("files.create")
        await { controller.state.value.error == "files.alreadyExists" }
        assertEquals(original, host.content("notes.md"))
        compose.onNodeWithContentDescription(label("files.name")).performTextReplacement("new-note")
        clickKey("files.create")
        await { controller.state.value.openFile == "new-note.md" && !controller.state.value.fileLoading }
        editor(".prose-note[contenteditable=true]")
        assertEquals("", host.content("new-note.md"))
        back(); await { controller.state.value.openFile == null }

        clickKey("files.newDrawing")
        compose.onNodeWithContentDescription(label("files.name")).performTextInput("scene")
        clickKey("files.create")
        await { controller.state.value.error == "files.alreadyExists" }
        compose.onNodeWithContentDescription(label("files.name")).performTextReplacement("new-scene")
        clickKey("files.create")
        await { controller.state.value.openFile == "new-scene.excalidraw" && !controller.state.value.fileLoading }
        editor("[data-testid=drawing-editor] canvas")
        val scene = Json.parseToJsonElement(host.content("new-scene.excalidraw")).jsonObject
        assertEquals("excalidraw", scene.getValue("type").jsonPrimitive.content)
        assertTrue(scene.getValue("elements").jsonArray.isEmpty())
        assertEquals(2, host.rejectedCreates.get())
        assertEquals(2, host.accepted.count { it.getValue("revision").jsonPrimitive.content == "missing" })
    }

    @Test fun aiDrawingArtifactEditsPersistAndCompletedHostReplacementReopens() {
        launch(); openChat()
        compose.onNodeWithTag("file:workspace:scene.excalidraw").performScrollTo().performClick()
        await { controller.state.value.previewPath == "scene.excalidraw" && !controller.state.value.loading }
        editor("[data-testid=drawing-editor] canvas")
        assertTrue("Viewing an AI artifact must not write a normalized scene", host.accepted.isEmpty())
        val web = editPreview()
        tapDom(web, "[data-testid=toolbar-rectangle]")
        await { javascript(web, "Boolean(document.querySelector('[data-testid=toolbar-rectangle]')?.checked)") == "true" }
        drawRectangle(web)
        await { host.content("scene.excalidraw").contains("\"type\": \"rectangle\"") ||
            runCatching { Json.parseToJsonElement(host.content("scene.excalidraw")).jsonObject["elements"]?.jsonArray?.isNotEmpty() == true }.getOrDefault(false) }
        await { controller.state.value.fileSaveState == "saved" }
        val edited = host.content("scene.excalidraw")
        assertTrue(Json.parseToJsonElement(edited).jsonObject.getValue("elements").jsonArray.any { it.jsonObject["type"]?.jsonPrimitive?.content == "rectangle" })
        captureSuccess("drawing-editor")
        clickDom(web, "button", "Export PNG")
        await { platform.saved.isNotEmpty() }
        val exported = platform.saved.single()
        assertEquals("scene.png", exported.name)
        assertEquals("image/png", exported.mimeType)
        assertArrayEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a), exported.bytes.copyOfRange(0, 8))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(exported.bytes, 0, exported.bytes.size, bounds)
        assertTrue(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096)
        assertEquals("Export must not rewrite the scene source", edited, host.content("scene.excalidraw"))
        back(); await { controller.state.value.openFile == null && controller.state.value.screen == Screen.Chat }
        // A completed host/AI replacement must be read anew, never overwritten by the disposed canvas.
        host.replace("scene.excalidraw", """{"type":"excalidraw","version":2,"elements":[{"type":"rectangle","id":"host-update","x":0,"y":0,"width":220,"height":90,"label":{"text":"Host revision"}}],"appState":{},"files":{}}""")
        val writesBefore = host.accepted.size
        compose.onNodeWithTag("file:workspace:scene.excalidraw").performScrollTo().performClick()
        await { !controller.state.value.loading && controller.state.value.previewText.contains("Host revision") }
        val reopened = editor("[data-testid=drawing-editor] canvas")
        clickDom(reopened, "tab", "Source")
        val source = editor("[data-testid=source-editor] .cm-content")
        assertEquals("The reopened renderer must receive the completed host revision", "true",
            javascript(source, "document.querySelector('.cm-content').textContent.includes('Host revision')"))
        clickDom(source, "tab", "Preview")
        editor("[data-testid=drawing-editor] canvas")
        assertEquals(writesBefore, host.accepted.size)
        assertEquals("chat_1", controller.state.value.previewSessionId)
    }

    @Test fun embeddedDrawingUsesLinkedSessionAndBackRestoresTheParentAndChatDraft() {
        launch(); openChat(); openLinkedNote()
        val web = editPreview()
        await { javascript(web, "Boolean(document.querySelector('.document-drawing canvas'))") == "true" }
        clickDom(web, "button", "Open drawing")
        await { controller.state.value.openFile == "session/notes.drawings/embedded.excalidraw" && !controller.state.value.fileLoading }
        editor("[data-testid=drawing-editor] canvas")
        assertEquals("chat_2", controller.state.value.fileSessionId)
        back()
        await { controller.state.value.openFile == "session/notes.md" && !controller.state.value.fileLoading }
        editor(".prose-note h1")
        assertEquals("chat_2", controller.state.value.fileSessionId)
        back()
        await { controller.state.value.screen == Screen.Chat && controller.state.value.openFile == null }
        assertEquals("chat_1", controller.state.value.sessionId)
        assertEquals(WORKSPACE_DRAFT, controller.state.value.draft)
        assertTrue(host.reads.filter { it.first.startsWith("session/") }.all { it.second == "chat_2" })
        assertTrue(host.accepted.isEmpty())
    }

    @Test fun linkedMermaidConvertsToANewEditableDrawingAndReopensInItsOwnSession() {
        host.includeMermaid()
        launch(); openChat()
        openLinkedDocument(WORKSPACE_MERMAID_LINK, "session/diagram.mmd")
        val reader = editor(".save-state.readonly")
        await { javascript(reader, "Boolean(document.querySelector('[data-testid=drawing-editor] canvas'))") == "true" }
        assertEquals("chat_2", controller.state.value.previewSessionId)
        assertEquals("true", javascript(reader, "document.querySelector('[data-testid=toolbar-rectangle]') === null"))
        assertTrue("Rendering Mermaid must not rewrite its source", host.accepted.isEmpty())
        captureSuccess("mermaid-reader")

        clickDom(reader, "button", "Save as drawing")
        await {
            controller.state.value.openFile?.let { it.startsWith("session/diagram-") && it.endsWith(".excalidraw") } == true &&
                controller.state.value.fileEditable && !controller.state.value.fileLoading && controller.state.value.previewTitle == null
        }
        val createdPath = requireNotNull(controller.state.value.openFile)
        val editable = editor("[data-testid=drawing-editor] [data-testid=toolbar-rectangle]")
        assertEquals("chat_2", controller.state.value.fileSessionId)
        assertEquals("chat_1", controller.state.value.sessionId)
        val request = host.accepted.single()
        assertEquals("missing", request.getValue("revision").jsonPrimitive.content)
        assertEquals("chat_2", request.getValue("session_id").jsonPrimitive.content)
        assertEquals(createdPath, request.getValue("path").jsonPrimitive.content)
        val scene = Json.parseToJsonElement(host.content(createdPath, "chat_2")).jsonObject
        assertEquals("excalidraw", scene.getValue("type").jsonPrimitive.content)
        assertTrue(scene.getValue("elements").jsonArray.size >= 2)
        assertTrue("Conversion must retain the diagram's visible text", scene.getValue("elements").jsonArray.any {
            it.jsonObject["text"]?.jsonPrimitive?.content?.contains("Start") == true
        })
        assertEquals("true", javascript(editable, "Boolean(document.querySelector('[data-testid=toolbar-rectangle]'))"))
        captureSuccess("mermaid-converted-editor")

        // Reopen the saved scene through the real native reader action, then
        // enter its editor again. Neither path may normalize or rewrite it.
        clickKey("files.openReader")
        await { controller.state.value.previewPath == createdPath && !controller.state.value.loading }
        val reopened = editor(".save-state.readonly")
        await { javascript(reopened, "Boolean(document.querySelector('[data-testid=drawing-editor] canvas'))") == "true" }
        assertEquals("chat_2", controller.state.value.previewSessionId)
        clickDom(reopened, "tab", "Source")
        await { javascript(reopened, "Boolean(document.querySelector('[data-testid=source-editor] .cm-content'))") == "true" }
        assertEquals("The saved Excalidraw source must reach the reopened renderer", "true", javascript(reopened,
            """/"type"\s*:\s*"excalidraw"/.test(document.querySelector('.cm-content').textContent)"""))
        editPreview()
        assertEquals(createdPath, controller.state.value.openFile)
        assertEquals("chat_2", controller.state.value.fileSessionId)
        assertEquals(WORKSPACE_MERMAID, host.content("session/diagram.mmd", "chat_2"))
        assertEquals(WORKSPACE_SCENE, host.content("session/diagram.excalidraw", "chat_2"))
        assertEquals("Only the explicit conversion may create a file", 1, host.accepted.size)
        assertTrue(host.reads.filter { it.first.startsWith("session/diagram") }.all { it.second == "chat_2" })
    }

    @Test fun conflictActionsKeepLocalReloadServerAndSaveACopyWithoutOverwriting() {
        launch(); openFiles(); previewFile("notes.md")
        var web = editPreview()
        host.replace("notes.md", "# Server one\n\nChanged on the computer.")
        append(web, ".prose-note p:last-child", " Local one.")
        await { controller.state.value.fileConflict != null }
        clickKey("files.resolveConflict")
        clickKey("files.remoteVersion")
        compose.onNodeWithText("# Server one\n\nChanged on the computer.").assertIsDisplayed()
        captureSuccess("save-conflict")
        clickKey("files.keepLocal")
        await { controller.state.value.fileConflict == null && controller.state.value.fileSaveState == "saved" }
        assertTrue(host.content("notes.md").contains("Local one."))

        host.replace("notes.md", "# Server two\n\nKeep this server version.")
        append(web, ".prose-note p:last-child", " Local two.")
        await { controller.state.value.fileConflict != null }
        clickKey("files.resolveConflict"); clickKey("files.loadRemote")
        await { controller.state.value.fileConflict == null && controller.state.value.fileText.contains("Server two") }
        web = editor(".prose-note h1")
        await { javascript(web, "document.querySelector('.prose-note h1')?.textContent === 'Server two'") == "true" }
        assertEquals("\"Server two\"", javascript(web, "document.querySelector('.prose-note h1').textContent"))
        assertFalse(controller.state.value.fileText.contains("Local two"))

        host.replace("notes.md", "# Server three\n\nThe original remains untouched.")
        append(web, ".prose-note p:last-child", " Save this local copy.")
        await { controller.state.value.fileConflict != null }
        clickKey("files.resolveConflict")
        compose.onAllNodes(hasText(label("files.saveCopy")) and hasClickAction()).onLast().performClick()
        compose.onNodeWithContentDescription(label("files.name")).performTextReplacement("notes-copy.md")
        compose.onAllNodes(hasText(label("files.saveCopy")) and hasClickAction()).onLast().performClick()
        await { controller.state.value.openFile == "notes-copy.md" && !controller.state.value.fileLoading }
        editor(".prose-note h1")
        assertTrue(host.content("notes-copy.md").contains("Save this local copy."))
        assertEquals("# Server three\n\nThe original remains untouched.", host.content("notes.md"))
        assertEquals("missing", host.accepted.last().getValue("revision").jsonPrimitive.content)
    }

    @Test fun largerTextBrowserSearchAndSortKeepActionsAndFilesReachable() {
        launch(fontScale = 1.4f); openFiles()
        compose.onNodeWithText(label("files.newNote")).assertIsDisplayed()
        compose.onNodeWithText(label("files.newDrawing")).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput("NOTES")
        compose.onNodeWithText("notes.md").assertIsDisplayed()
        compose.onNodeWithText("photo.png").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performTextReplacement("does-not-exist")
        compose.onNodeWithText(label("files.noResults")).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextReplacement("")
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val manager = compose.activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            manager.hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
            compose.activity.currentFocus?.clearFocus()
        }
        clickKey("files.sort.size")
        compose.onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag("file-workspace"))).performScrollToIndex(0)
        val largest = compose.onNodeWithText("index.html").fetchSemanticsNode().boundsInRoot
        val note = compose.onNodeWithText("notes.md").fetchSemanticsNode().boundsInRoot
        assertTrue("Size sorting must place the larger HTML file ahead of the note", largest.top < note.top)
        previewFile("alpha.txt")
        editor("[data-testid=source-editor] .cm-content")
    }

    private fun drawRectangle(web: WebView) {
        val geometry = Json.parseToJsonElement(javascript(web, """JSON.stringify((() => {
            const canvas = document.querySelector('.excalidraw canvas.interactive') || document.querySelector('.excalidraw canvas');
            const r = canvas.getBoundingClientRect();
            return {x:r.left+r.width*.3,y:r.top+r.height*.35,dx:r.width*.3,dy:r.height*.2,width:innerWidth};
        })())""".trimIndent())).jsonPrimitive.content
        val position = Json.parseToJsonElement(geometry).jsonObject
        var scale = 1f
        InstrumentationRegistry.getInstrumentation().runOnMainSync { scale = web.width / position.getValue("width").jsonPrimitive.float }
        val x = position.getValue("x").jsonPrimitive.float * scale
        val y = position.getValue("y").jsonPrimitive.float * scale
        val dx = position.getValue("dx").jsonPrimitive.float * scale
        val dy = position.getValue("dy").jsonPrimitive.float * scale
        val started = SystemClock.uptimeMillis()
        fun send(action: Int, fraction: Float) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val event = touchEvent(started, action, x + dx * fraction, y + dy * fraction)
                try { web.dispatchTouchEvent(event) } finally { event.recycle() }
            }
        }
        send(MotionEvent.ACTION_DOWN, 0f)
        for (step in 1..5) { SystemClock.sleep(25); send(MotionEvent.ACTION_MOVE, step / 5f) }
        SystemClock.sleep(25); send(MotionEvent.ACTION_UP, 1f)
    }

    private fun tapDom(web: WebView, selector: String) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { web.requestFocus() }
        javascript(web, """(() => {
            window.workspacePointerDiagnostics = [];
            for (const type of ['pointerdown','pointerup','click','change']) document.addEventListener(type, event => {
                if (window.workspacePointerDiagnostics.length >= 20) return;
                window.workspacePointerDiagnostics.push({type, tag:event.target?.tagName,
                    target:event.target?.getAttribute?.('data-testid'), checked:event.target?.checked,
                    x:event.clientX, y:event.clientY, trusted:event.isTrusted});
            }, {capture:true});
        })()""".trimIndent())
        // Excalidraw performs a size-observer layout after the first canvas is
        // attached. Wait for a stable visible toolbar before physical input.
        var previousBounds = ""
        var stableFrames = 0
        await {
            val bounds = javascript(web, """JSON.stringify((() => {
                const element = document.querySelector(${JsonPrimitive(selector)});
                const rect = (element?.closest('label') || element)?.getBoundingClientRect();
                return rect && rect.width > 0 && rect.height > 0 ? [rect.x,rect.y,rect.width,rect.height] : null;
            })())""".trimIndent())
            stableFrames = if (bounds.startsWith("\"[") && bounds == previousBounds) stableFrames + 1 else 0
            previousBounds = bounds
            stableFrames >= 2
        }
        val raw = Json.parseToJsonElement(javascript(web, """JSON.stringify((() => {
            const element = document.querySelector(${JsonPrimitive(selector)});
            if (!element) return null;
            const target = element.closest('label') || element;
            const bounds = target.getBoundingClientRect();
            const x=bounds.left+bounds.width/2, y=bounds.top+bounds.height/2;
            const hit=document.elementFromPoint(x,y);
            window.workspaceTapDiagnostics={x,y,tag:target.tagName,hitTag:hit?.tagName,
                hitTarget:target===hit || target.contains(hit),hitTestId:hit?.getAttribute('data-testid')};
            return {...window.workspaceTapDiagnostics,width:innerWidth};
        })())""".trimIndent())).jsonPrimitive.content
        val point = Json.parseToJsonElement(raw).jsonObject
        assertTrue("The visible toolbar label must receive the intended DOM hit", point.getValue("hitTarget").jsonPrimitive.boolean)
        val started = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val scale = web.width / point.getValue("width").jsonPrimitive.float
                val event = touchEvent(started, action,
                    point.getValue("x").jsonPrimitive.float * scale, point.getValue("y").jsonPrimitive.float * scale)
                try { web.dispatchTouchEvent(event) } finally { event.recycle() }
            }
            SystemClock.sleep(25)
        }
    }

    private fun touchEvent(started: Long, action: Int, x: Float, y: Float): MotionEvent = MotionEvent.obtain(
        started, SystemClock.uptimeMillis(), action, 1,
        arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }),
        arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f; size = 1f }),
        0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
    )

    /** Opt-in native painting evidence; normal runs pay no screenshot or wait cost. */
    private fun captureSuccess(name: String) {
        val scenario = InstrumentationRegistry.getArguments().getString("screenshotDir") ?: return
        require(scenario.matches(Regex("[a-zA-Z0-9_-]+")))
        val safeTest = currentTestName.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(140)
        compose.waitForIdle()
        // WebView/GPU and system-window painting run outside the Compose clock.
        SystemClock.sleep(300)
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val directory = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "ui-qa/$scenario").apply { mkdirs() }
            File(directory, "$safeTest-$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    /** Capture before controller/activity teardown, without logging content or credentials. */
    private fun captureFailure(name: String) {
        val safeName = name.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(140)
        try {
            val state = if (::controller.isInitialized) controller.state.value else null
            val diagnostics = buildString {
                appendLine("test=$safeName")
                if (state != null) appendLine("screen=${state.screen}, connected=${state.connected}, session=${state.sessionId}, " +
                    "openFile=${state.openFile}, fileSession=${state.fileSessionId}, generation=${state.fileEditorGeneration}, " +
                    "editable=${state.fileEditable}, loading=${state.fileLoading}, save=${state.fileSaveState}, " +
                    "conflict=${state.fileConflict != null}, textLength=${state.fileText.length}, error=${state.error}, " +
                    "preview=${state.previewPath}, previewSession=${state.previewSessionId}, previewLoading=${state.loading}, " +
                    "previewByteCount=${state.previewBytes?.size}, previewTextLength=${state.previewText.length}")
                appendLine("acceptedWrites=${host.accepted.size}, reads=${host.reads.size}, unexpected=${host.unexpected}")
                visibleWebViews().forEachIndexed { index, web ->
                    val dom = runCatching { javascript(web, """JSON.stringify((() => {
                        const tool = document.querySelector('[data-testid=toolbar-rectangle]');
                        const bounds = (tool?.closest('label') || tool)?.getBoundingClientRect();
                        const ancestors = ['html','body','#root','main'].map(selector => {
                            const element = document.querySelector(selector);
                            if (!element) return {selector,present:false};
                            const style = getComputedStyle(element);
                            return {selector,bounds:element.getBoundingClientRect().toJSON(),display:style.display,
                                height:style.height,minHeight:style.minHeight,maxHeight:style.maxHeight,
                                position:style.position,overflow:style.overflow,visibility:style.visibility,opacity:style.opacity};
                        });
                        return {ready:!!window.BlinkWorkspace, width:innerWidth, height:innerHeight,
                            viewport:window.visualViewport && {width:visualViewport.width,height:visualViewport.height,scale:visualViewport.scale,top:visualViewport.offsetTop,left:visualViewport.offsetLeft},
                            activeTag:document.activeElement?.tagName,
                            tabs:[...document.querySelectorAll('[role=tab]')].map(tab=>({name:tab.textContent,selected:tab.getAttribute('aria-selected')})),
                            editable:document.querySelectorAll('[contenteditable=true]').length, canvases:document.querySelectorAll('canvas').length,
                            rectangle:tool && {tag:tool.tagName,checked:tool.checked,disabled:tool.disabled,bounds:bounds?.toJSON()},
                            ancestors,tap:window.workspaceTapDiagnostics, pointers:window.workspacePointerDiagnostics || []};
                    })())""".trimIndent()) }.getOrElse { "JavaScript unavailable (${it.javaClass.simpleName})" }
                    appendLine("web[$index]=$dom")
                }
            }
            Log.e("FileWorkspaceIntegration", diagnostics)
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val directory = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "workspace-failures").apply { mkdirs() }
            File(directory, "$safeName.txt").writeText(diagnostics)
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                try { File(directory, "$safeName.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                finally { bitmap.recycle() }
            }
            Log.i("FileWorkspaceIntegration", "Failure artifacts: ${directory.absolutePath}/$safeName")
        } catch (failure: Exception) {
            Log.w("FileWorkspaceIntegration", "Failure capture unavailable (${failure.javaClass.simpleName})")
        }
    }
}

private class WorkspaceFixtureHost {
    private data class FileValue(val content: String, val revision: Int)
    private val clients = CopyOnWriteArrayList<HttpClient>()
    private val files = ConcurrentHashMap<String, FileValue>()
    @Volatile private var mermaidLink = false
    val accepted = CopyOnWriteArrayList<JsonObject>()
    val unexpected = CopyOnWriteArrayList<String>()
    val reads = CopyOnWriteArrayList<Pair<String, String>>()
    val rejectedCreates = AtomicInteger()
    val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="32" height="24"><rect width="32" height="24" fill="#b95f3d"/></svg>""".toByteArray()
    val pdf = asciiPdf()
    // Produce a real Android PNG. The old tiny Base64 sample had a corrupt IDAT
    // CRC which browsers tolerated but Android's image decoder rejected.
    val image: ByteArray = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).let { bitmap ->
        try {
            bitmap.eraseColor(Color.rgb(33, 132, 180))
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally { bitmap.recycle() }
    }

    init {
        replace("notes.md", WORKSPACE_NOTE)
        replace("alpha.txt", "Small file.")
        replace("scene.excalidraw", WORKSPACE_SCENE)
        replace("index.html", "<!doctype html><title>Workspace fixture</title><button id=counter>0</button><script>let count=0;document.getElementById('counter').onclick=e=>e.target.textContent=String(++count);window.workspacePreviewReady=true;</script>")
        replace("session/notes.md", "# Linked note\n\n![Embedded scene](./notes.drawings/embedded.excalidraw)\n\nReturn to the same conversation.", "chat_2")
        replace("session/notes.drawings/embedded.excalidraw", WORKSPACE_SCENE, "chat_2")
    }

    private fun key(path: String, session: String) = if (path.startsWith("session/")) "$session|$path" else path
    fun includeOtherFormats() {
        replace("vector.svg", svg.toString(Charsets.UTF_8))
        replace("portable.pdf", pdf.toString(Charsets.UTF_8))
    }
    fun includeMermaid() {
        replace("session/diagram.mmd", WORKSPACE_MERMAID, "chat_2")
        replace("session/diagram.excalidraw", WORKSPACE_SCENE, "chat_2")
        mermaidLink = true
    }
    fun content(path: String, session: String = "") = files.getValue(key(path, session)).content
    fun replace(path: String, value: String, session: String = "") {
        files.compute(key(path, session)) { _, previous -> FileValue(value, (previous?.revision ?: 0) + 1) }
    }

    fun makeApi(origin: String, token: String): BotApi {
        val client = HttpClient(MockEngine { request ->
            val endpoint = request.url.encodedPath
            check(request.headers[HttpHeaders.Authorization] == "Bearer $WORKSPACE_TOKEN")
            val path = request.url.parameters["path"].orEmpty()
            val session = request.url.parameters["session_id"].orEmpty()
            val body = when {
                endpoint == "/api/mobile/capabilities" -> """{"steer":false,"queue":true,"event_replay":true}"""
                endpoint == "/api/brain/intelligence" -> """{"available":false}"""
                endpoint == "/api/brain/models" -> """{"models":[{"id":"fixture/model","label":"Workspace fixture","provider":"fixture","brand":"anthropic"}],"selected":"fixture/model"}"""
                endpoint == "/api/setup" -> """{"profile":{"name":"Workspace fixture","language":"en"}}"""
                endpoint == "/api/sessions" -> """{"sessions":[{"id":"chat_1","title":"$WORKSPACE_CHAT_TITLE","updated":1791043200}]}"""
                endpoint == "/api/sessions/chat_1" -> buildJsonObject { put("messages", buildJsonArray { add(buildJsonObject {
                    put("id", "workspace-artifacts"); put("role", "assistant")
                    put("content", "[$WORKSPACE_LINK](/file/session/notes.md?session_id=chat_2)" +
                        if (mermaidLink) "\n\n[$WORKSPACE_MERMAID_LINK](/file/session/diagram.mmd?session_id=chat_2)" else "")
                    put("steps", buildJsonArray { add(buildJsonObject {
                        put("id", "created-scene"); put("label", "workspace_write"); put("status", "done")
                        put("input", buildJsonObject { put("path", "scene.excalidraw") })
                        put("result", buildJsonObject { put("path", "scene.excalidraw") })
                    }) })
                }) }) }.toString()
                endpoint == "/api/mobile/messages" && request.method == HttpMethod.Get -> """{"messages":[]}"""
                endpoint == "/api/workspace/list" -> buildJsonObject {
                    put("path", path)
                    put("entries", buildJsonArray {
                        files.entries.filter { !it.key.contains('|') && it.key.substringBeforeLast('/', "") == path }.sortedBy { it.key }.forEach { (name, value) -> add(buildJsonObject {
                            put("name", name.substringAfterLast('/')); put("path", name); put("type", "file"); put("size", value.content.toByteArray().size)
                        }) }
                        if (path.isEmpty()) add(buildJsonObject { put("name", "photo.png"); put("path", "photo.png"); put("type", "file"); put("size", image.size) })
                    })
                }.toString()
                endpoint in setOf("/api/workspace/file", "/api/mobile/workspace/file") && request.method == HttpMethod.Get -> {
                    reads += path to session
                    val value = files[key(path, session)] ?: return@MockEngine respond("""{"detail":"file_not_found"}""", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/json"))
                    buildJsonObject {
                        put("path", path); put("content", value.content); put("revision", "revision-${value.revision}")
                        put("binary", false); put("too_large", false); put("size", value.content.toByteArray().size)
                    }.toString()
                }
                endpoint == "/api/mobile/workspace/file" && request.method == HttpMethod.Post -> {
                    val value = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    val destination = value.getValue("path").jsonPrimitive.content
                    val owner = value["session_id"]?.jsonPrimitive?.content.orEmpty()
                    val revision = value.getValue("revision").jsonPrimitive.content
                    val stored = files[key(destination, owner)]
                    if (revision != (stored?.let { "revision-${it.revision}" } ?: "missing")) {
                        if (revision == "missing") rejectedCreates.incrementAndGet()
                        return@MockEngine respond("""{"detail":{"code":"workspace_revision_conflict","revision":"revision-${stored?.revision ?: 0}"}}""", HttpStatusCode.Conflict, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                    replace(destination, value.getValue("content").jsonPrimitive.content, owner)
                    accepted += value
                    buildJsonObject { put("ok", true); put("path", destination); put("revision", "revision-${files.getValue(key(destination, owner)).revision}") }.toString()
                }
                endpoint == "/api/mobile/workspace/download" && path == "photo.png" -> return@MockEngine respond(image, headers = headersOf(HttpHeaders.ContentType, "image/png"))
                endpoint == "/api/mobile/workspace/download" && path == "vector.svg" -> return@MockEngine respond(svg, headers = headersOf(HttpHeaders.ContentType, "image/svg+xml"))
                endpoint == "/api/mobile/workspace/download" && path == "portable.pdf" -> return@MockEngine respond(pdf, headers = headersOf(HttpHeaders.ContentType, "application/pdf"))
                endpoint == "/api/mobile/workspace/web-preview" && path == "index.html" -> """{"ready":true,"kind":"web","root":"site","entry":"index.html","project_path":"site","buildable":false}"""
                endpoint == "/api/mobile/workspace/web-resource" && path == "index.html" -> return@MockEngine respond(content("index.html"), headers = headersOf(HttpHeaders.ContentType, "text/html"))
                endpoint == "/api/mobile/workspace/web-resource" && path == "favicon.ico" -> return@MockEngine respond("", headers = headersOf(HttpHeaders.ContentType, "image/x-icon"))
                else -> { unexpected += "${request.method.value} $endpoint path=$path"; error("Unexpected workspace fixture route: $endpoint") }
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        clients += client
        return BotApi(origin, token, client)
    }
    fun close() = clients.forEach { it.close() }

    private fun asciiPdf(): ByteArray {
        val stream = "q 0.8 0.2 0.1 rg 0 0 32 24 re f Q\n"
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 32 24] /Resources << >> /Contents 4 0 R >>",
            "<< /Length ${stream.length} >>\nstream\n${stream}endstream",
        )
        val body = StringBuilder("%PDF-1.4\n")
        val offsets = mutableListOf<Int>()
        objects.forEachIndexed { index, value -> offsets += body.length; body.append("${index + 1} 0 obj\n$value\nendobj\n") }
        val xref = body.length
        body.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { body.append("${it.toString().padStart(10, '0')} 00000 n \n") }
        body.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return body.toString().toByteArray(Charsets.US_ASCII)
    }
}

private class WorkspaceFixtureBridge : PlatformBridge {
    override val platformName = "android"
    override val systemLanguage = "en"
    override val reducedMotion = true
    override val foreground = MutableStateFlow(true)
    override val incomingPairing = MutableStateFlow<String?>(null)
    private val preferences = ConcurrentHashMap(mapOf(
        "device_id" to "workspace-owner", "server" to "https://workspace-fixture.example",
        "preferences.v1" to """{"language":"en","theme":"light","wallpaper":false,"haptics":false,"answerHaptics":false}""",
        "workspace-owner.draft.chat_1" to WORKSPACE_DRAFT,
    ))
    private val secrets = ConcurrentHashMap(mapOf("device_token" to WORKSPACE_TOKEN))
    private val ids = AtomicInteger()
    val externalLinks = CopyOnWriteArrayList<String>()
    val saved = CopyOnWriteArrayList<PickedFile>()
    override fun readPreference(key: String) = preferences[key]
    override fun writePreference(key: String, value: String?) { if (value == null) preferences.remove(key) else preferences[key] = value }
    override fun readSecret(key: String) = secrets[key]
    override fun writeSecret(key: String, value: String?) { if (value == null) secrets.remove(key) else secrets[key] = value }
    override fun scanQr(onResult: (String?) -> Unit) = onResult(null)
    override fun pickFile(kind: String, onResult: (PickedFile?) -> Unit) = onResult(null)
    override fun startRecording(onAmplitude: (Float) -> Unit, onResult: (PickedFile?) -> Unit, onPartial: (PickedFile) -> Unit) = Unit
    override fun stopRecording() = Unit
    override fun cancelRecording() = Unit
    override fun haptic() = Unit
    override fun copyText(value: String) = Unit
    override fun shareText(value: String) = Unit
    override fun openExternalUrl(url: String) { externalLinks += url }
    override fun saveFile(file: PickedFile, onResult: (Boolean) -> Unit) { saved += file; onResult(true) }
    override fun requestNotifications(onResult: (Boolean) -> Unit) = onResult(false)
    override fun notifyReply(title: String, body: String, conversationId: String) = Unit
    override fun nowMillis() = System.currentTimeMillis()
    override fun newId() = "workspace-${ids.incrementAndGet()}"
}

private const val WORKSPACE_TIMEOUT = 25_000L
private const val WORKSPACE_TOKEN = "synthetic-workspace-device-token"
private const val WORKSPACE_CHAT_TITLE = "Workspace integration conversation"
private const val WORKSPACE_LINK = "Open linked note"
private const val WORKSPACE_MERMAID_LINK = "Open linked diagram"
private const val WORKSPACE_DRAFT = "Keep this unsent workspace question."
private const val WORKSPACE_SCENE = """{"type":"excalidraw","version":2,"elements":[],"appState":{},"files":{}}"""
private const val WORKSPACE_MERMAID = "flowchart LR\n  A[Start] --> B[Done]\n"
private const val WORKSPACE_NOTE = "# Workspace note\n\nRead and edit the same file as the computer.\n\n| Item | Quantity |\n| --- | --- |\n| Laptop | 2 |\n\n- [x] Read the brief\n- [ ] Review the table\n\nFinal notes."
