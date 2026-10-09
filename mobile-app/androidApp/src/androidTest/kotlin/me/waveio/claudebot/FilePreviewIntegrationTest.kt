package me.waveio.claudebot

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.webkit.WebView
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
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
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real App/controller navigation and native WebView; only authenticated host responses are mocked. */
class FilePreviewIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val bridge = FilePreviewBridge()
    private val host = FilePreviewHost()
    private lateinit var controller: AppController
    private lateinit var strings: LocaleText

    @After fun cleanup() {
        if (::controller.isInitialized) compose.runOnIdle {
            controller.closePreview()
            controller.close()
        }
        compose.waitForIdle()
        host.close()
        assertTrue("Unexpected fixture requests: ${host.unexpected}", host.unexpected.isEmpty())
        assertTrue("Owned links must remain inside the app", bridge.externalLinks.isEmpty())
    }

    private fun launchChat() {
        strings = runBlocking { LocaleText.load("en") }
        compose.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        compose.setContent {
            App(bridge, onSystemBarAppearance = compose.activity::systemBarAppearance) { platform ->
                AppController(platform, makeApi = host::makeApi).also { controller = it }
            }
        }
        compose.waitUntil(PREVIEW_TIMEOUT) {
            ::controller.isInitialized && controller.state.value.connected && controller.state.value.conversations.isNotEmpty()
        }
        compose.waitUntil(PREVIEW_TIMEOUT) {
            compose.onAllNodesWithContentDescription(strings.get("nav.menu")).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText(PREVIEW_CHAT_TITLE).fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithContentDescription(strings.get("nav.menu")).fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithContentDescription(strings.get("nav.menu")).performClick()
        compose.onNodeWithText(PREVIEW_CHAT_TITLE).performScrollTo().performClick()
        compose.waitUntil(PREVIEW_TIMEOUT) { controller.state.value.sessionId == "chat_1" && !controller.state.value.loading }
        waitForText(REPORT_LINK)
        compose.onNode(hasSetTextAction()).assertTextEquals(PREVIEW_DRAFT)
    }

    private fun waitForText(text: String) {
        compose.waitUntil(PREVIEW_TIMEOUT) {
            compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun textLayout(text: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }

    private fun clickMarkdownLink(label: String) {
        waitForText(label)
        val node = compose.onNodeWithText(label, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        // Tap a measured link glyph, exercising Markdown's UriHandler rather than calling the controller directly.
        val point = textLayout(label).getBoundingBox(label.length / 2).center
        node.performTouchInput { click(point) }
        compose.waitUntil(PREVIEW_TIMEOUT) { controller.state.value.previewTitle != null && !controller.state.value.loading }
        compose.onNodeWithTag("media-preview").assertIsDisplayed()
    }

    @Test fun markdownLinkPreviewsFormattingAndEditsItsOwnSessionWithoutLosingTheChatDraft() {
        launchChat()
        clickMarkdownLink(REPORT_LINK)
        assertEquals("chat_1", controller.state.value.sessionId)
        assertEquals("chat_2", controller.state.value.previewSessionId)
        assertEquals("session/notes.md", controller.state.value.previewPath)
        val reader = editorWebView(".prose-note h1")
        assertEquals("\"Linked report\"", javascript(reader, "document.querySelector('.prose-note h1').textContent"))
        assertEquals("\"important\"", javascript(reader, "document.querySelector('.prose-note strong').textContent"))
        assertEquals("\"false\"", javascript(reader, "document.querySelector('.prose-note').getAttribute('contenteditable')"))
        assertTrue("Opening the rich reader must never rewrite the file", host.writes.isEmpty())
        compose.onNodeWithText(PREVIEW_MARKDOWN).assertDoesNotExist()
        capturePreview("markdown")

        compose.onNodeWithContentDescription(strings.get("files.edit")).assertIsEnabled().performClick()
        compose.waitUntil(PREVIEW_TIMEOUT) { controller.state.value.fileEditable && !controller.state.value.loading }
        assertEquals(Screen.Files, controller.state.value.screen)
        assertEquals("chat_2", controller.state.value.fileSessionId)
        compose.onNodeWithTag("media-preview").assertDoesNotExist()
        val editor = editorWebView(".prose-note[contenteditable=true]")
        javascript(editor, """(() => {
            const paragraph = document.querySelector('.prose-note p');
            document.querySelector('.prose-note').focus();
            const range = document.createRange(); range.selectNodeContents(paragraph);
            const selection = getSelection(); selection.removeAllRanges(); selection.addRange(range);
            document.execCommand('insertText', false, 'Changed through the native editor.');
        })()""".trimIndent())
        compose.waitUntil(PREVIEW_TIMEOUT) { host.writes.isNotEmpty() && controller.state.value.fileSaveState == "saved" }
        val saved = host.writes.single()
        assertEquals("session/notes.md", saved.getValue("path").jsonPrimitive.content)
        assertEquals("chat_2", saved.getValue("session_id").jsonPrimitive.content)
        assertEquals("before-edit", saved.getValue("revision").jsonPrimitive.content)
        val edited = saved.getValue("content").jsonPrimitive.content
        assertTrue(edited.contains("# Linked report"))
        assertTrue(edited.contains("Changed through the native editor."))
        assertFalse(edited.contains("important"))
        assertFalse(saved.getValue("append").jsonPrimitive.boolean)
        assertTrue(host.fileReads.isNotEmpty())
        assertTrue("Preview, editor, and save-conflict reads must all retain the linked session",
            host.fileReads.all { it == ("session/notes.md" to "chat_2") })

        compose.onNodeWithContentDescription(strings.get("nav.back")).performClick()
        compose.waitUntil(PREVIEW_TIMEOUT) { controller.state.value.screen == Screen.Chat && controller.state.value.openFile == null }
        assertEquals("chat_1", controller.state.value.sessionId)
        assertEquals(PREVIEW_DRAFT, controller.state.value.draft)
        compose.onNode(hasSetTextAction()).assertTextEquals(PREVIEW_DRAFT)
        waitForText(REPORT_LINK)
    }

    @Test fun htmlProjectLinkRunsAnInteractiveNativePreviewThroughAuthenticatedResources() {
        launchChat()
        clickMarkdownLink(SITE_LINK)
        compose.onNodeWithTag("web-preview").assertIsDisplayed()
        compose.onNodeWithTag("native-web-app-preview").assertIsDisplayed()
        assertEquals("chat_1", controller.state.value.sessionId)
        assertEquals("chat_2", controller.state.value.previewSessionId)
        assertEquals("site", controller.state.value.previewPath)
        assertTrue(controller.state.value.previewWeb?.ready == true)

        // The preview lives in a native Dialog window, outside the activity's decor-view tree.
        fun findWebView(view: View): WebView? {
            if (view is WebView) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) {
                findWebView(view.getChildAt(index))?.let { return it }
            }
            return null
        }
        var selected: WebView? = null
        compose.runOnIdle { selected = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::findWebView) }
        val web = requireNotNull(selected)
        compose.waitUntil(PREVIEW_TIMEOUT) { javascript(web, "Boolean(window.fixtureReady)") == "true" }
        assertEquals("\"rgb(18, 52, 86)\"", javascript(web, "getComputedStyle(document.getElementById('counter')).color"))
        javascript(web, "document.getElementById('counter').click()")
        assertEquals("\"1\"", javascript(web, "document.getElementById('counter').textContent"))
        assertEquals("\"\"", javascript(web, "document.cookie"))
        assertFalse("Resource credentials must not become page content", javascript(web, "document.documentElement.outerHTML").contains(PREVIEW_TOKEN))
        assertTrue(host.webRequests.map { it.getValue("path") }.containsAll(listOf("index.html", "assets/app.js", "assets/site.css")))
        assertTrue("Native callbacks must retain the resolved root, entry, and linked session", host.webRequests.all {
            it["root"] == "sessions/chat_2/site" && it["entry"] == "index.html" && it["session_id"] == "chat_2"
        })
        assertFalse(controller.state.value.previewWebError)
        capturePreview("web")

        compose.onNodeWithContentDescription(strings.get("action.close")).performClick()
        compose.waitUntil(PREVIEW_TIMEOUT) { controller.state.value.previewTitle == null }
        compose.onNodeWithTag("media-preview").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertTextEquals(PREVIEW_DRAFT)
        assertEquals("chat_1", controller.state.value.sessionId)
    }

    private fun capturePreview(name: String) {
        compose.waitForIdle()
        try {
            // Dialog fades and WebView painting finish outside the Compose clock.
            SystemClock.sleep(300)
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                val directory = requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
                val file = File(directory, "file-preview-$name.png")
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                Log.i("FilePreviewIntegration", "Screenshot: ${file.absolutePath}")
            } finally {
                bitmap.recycle()
            }
        } catch (failure: Exception) {
            Log.w("FilePreviewIntegration", "Could not capture file-preview-$name.png", failure)
        }
    }

    private fun javascript(web: WebView, source: String): String {
        val value = AtomicReference<String>()
        val completed = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            web.evaluateJavascript(source) { result -> value.set(result); completed.countDown() }
        }
        assertTrue("The native page must answer without blocking its UI thread", completed.await(3, TimeUnit.SECONDS))
        return requireNotNull(value.get())
    }

    private fun editorWebView(selector: String): WebView {
        fun find(view: View): WebView? {
            if (view is WebView) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
            return null
        }
        var selected: WebView? = null
        compose.waitUntil(20_000) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                selected = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::find)
            }
            selected?.let { javascript(it, "Boolean(window.BlinkWorkspace && document.querySelector(${JsonPrimitive(selector)}))") == "true" } == true
        }
        return requireNotNull(selected)
    }
}

private class FilePreviewHost {
    private val clients = CopyOnWriteArrayList<HttpClient>()
    val unexpected = CopyOnWriteArrayList<String>()
    val writes = CopyOnWriteArrayList<JsonObject>()
    val fileReads = CopyOnWriteArrayList<Pair<String?, String?>>()
    val webRequests = CopyOnWriteArrayList<Map<String, String?>>()
    @Volatile private var markdown = PREVIEW_MARKDOWN
    @Volatile private var revision = "before-edit"

    fun makeApi(origin: String, token: String): BotApi {
        val client = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            check(request.headers[HttpHeaders.Authorization] == "Bearer $PREVIEW_TOKEN") { "Missing fixture device authentication: $path" }
            val body = when {
                path == "/api/mobile/capabilities" -> """{"steer":false,"queue":true,"event_replay":true}"""
                path == "/api/brain/intelligence" -> """{"available":false}"""
                path == "/api/brain/models" -> """{"models":[{"id":"fixture/model","label":"Preview fixture model","provider":"fixture","brand":"anthropic"}],"selected":"fixture/model"}"""
                path == "/api/setup" -> """{"profile":{"name":"Preview bot","language":"en","persona":"friendly","persona_custom":"","greeting":""}}"""
                path == "/api/sessions" -> """{"sessions":[{"id":"chat_1","title":"$PREVIEW_CHAT_TITLE","updated":1791043200}]}"""
                path == "/api/sessions/chat_1" -> buildJsonObject {
                    put("messages", buildJsonArray { add(buildJsonObject {
                        put("id", "file-links"); put("role", "assistant")
                        put("content", "[$REPORT_LINK](/file/session/notes.md?session_id=chat_2)\n\n[$SITE_LINK](/preview/site?session_id=chat_2)")
                    }) })
                }.toString()
                path == "/api/mobile/messages" && request.method == HttpMethod.Get -> """{"messages":[]}"""
                path in setOf("/api/workspace/file", "/api/mobile/workspace/file") && request.method == HttpMethod.Get -> {
                    fileReads += request.url.parameters["path"] to request.url.parameters["session_id"]
                    check(request.url.parameters["path"] == "session/notes.md")
                    check(request.url.parameters["session_id"] == "chat_2")
                    buildJsonObject {
                        put("path", "session/notes.md"); put("content", markdown); put("binary", false)
                        put("too_large", false); put("mime_type", "text/markdown"); put("revision", revision)
                    }.toString()
                }
                path == "/api/mobile/workspace/file" && request.method == HttpMethod.Post -> {
                    val saved = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    writes += saved
                    markdown = saved.getValue("content").jsonPrimitive.content
                    revision = "after-edit"
                    """{"ok":true,"path":"session/notes.md","revision":"after-edit"}"""
                }
                path == "/api/mobile/workspace/web-preview" -> {
                    check(request.url.parameters["path"] == "site")
                    check(request.url.parameters["session_id"] == "chat_2")
                    """{"ready":true,"kind":"web","root":"sessions/chat_2/site","entry":"index.html","project_path":"sessions/chat_2/site","buildable":false}"""
                }
                path == "/api/mobile/workspace/web-resource" -> {
                    webRequests += request.url.parameters.names().associateWith { request.url.parameters[it] }
                    val resource = when (request.url.parameters["path"]) {
                        "index.html" -> """<!doctype html><html><head><meta charset="utf-8"><link rel="stylesheet" href="/assets/site.css"></head><body><button id="counter">0</button><script src="/assets/app.js"></script></body></html>""" to "text/html"
                        "assets/site.css" -> "#counter { color: rgb(18, 52, 86); }" to "text/css"
                        "assets/app.js" -> "const button=document.getElementById('counter');button.onclick=()=>{button.textContent=String(Number(button.textContent)+1);};window.fixtureReady=true;" to "text/javascript"
                        "favicon.ico" -> "" to "image/x-icon"
                        else -> error("Unexpected native resource: ${request.url.parameters["path"]}")
                    }
                    return@MockEngine respond(resource.first, headers = headersOf(HttpHeaders.ContentType, resource.second))
                }
                else -> {
                    unexpected += "${request.method.value} $path"
                    error("Unexpected file preview route: ${request.method.value} $path")
                }
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        clients += client
        return BotApi(origin, token, client)
    }

    fun close() { clients.forEach { it.close() } }
}

private class FilePreviewBridge : PlatformBridge {
    override val platformName = "android"
    override val systemLanguage = "en"
    override val reducedMotion = true
    override val foreground = MutableStateFlow(true)
    override val incomingPairing = MutableStateFlow<String?>(null)
    private val preferences = mutableMapOf(
        "device_id" to "preview-owner", "server" to "https://file-preview.example",
        "preferences.v1" to """{"language":"en","theme":"light","wallpaper":false,"haptics":false,"answerHaptics":false}""",
        "preview-owner.draft.chat_1" to PREVIEW_DRAFT,
    )
    private val secrets = mutableMapOf("device_token" to PREVIEW_TOKEN)
    private val ids = AtomicInteger()
    val externalLinks = CopyOnWriteArrayList<String>()
    override fun readPreference(key: String) = preferences[key]
    override fun writePreference(key: String, value: String?) { if (value == null) preferences.remove(key) else preferences[key] = value }
    override fun readSecret(key: String) = secrets[key]
    override fun writeSecret(key: String, value: String?) { if (value == null) secrets.remove(key) else secrets[key] = value }
    override fun scanQr(onResult: (String?) -> Unit) { onResult(null) }
    override fun pickFile(kind: String, onResult: (PickedFile?) -> Unit) { onResult(null) }
    override fun startRecording(onAmplitude: (Float) -> Unit, onResult: (PickedFile?) -> Unit, onPartial: (PickedFile) -> Unit) = Unit
    override fun stopRecording() = Unit
    override fun cancelRecording() = Unit
    override fun haptic() = Unit
    override fun copyText(value: String) = Unit
    override fun shareText(value: String) = Unit
    override fun openExternalUrl(url: String) { externalLinks += url }
    override fun requestNotifications(onResult: (Boolean) -> Unit) { onResult(false) }
    override fun notifyReply(title: String, body: String, conversationId: String) = Unit
    override fun nowMillis() = System.currentTimeMillis()
    override fun newId() = "preview-${ids.incrementAndGet()}"
}

private const val PREVIEW_TIMEOUT = 10_000L
private const val PREVIEW_TOKEN = "synthetic-file-preview-token"
private const val PREVIEW_CHAT_TITLE = "Preview integration conversation"
private const val PREVIEW_DRAFT = "Keep this unsent chat draft."
private const val PREVIEW_MARKDOWN = "# Linked report\n\nThe **important** detail stays readable."
private const val REPORT_LINK = "Read linked report"
private const val SITE_LINK = "Open interactive site"
