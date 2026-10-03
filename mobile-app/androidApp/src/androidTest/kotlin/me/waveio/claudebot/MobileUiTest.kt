package me.waveio.claudebot

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import me.waveio.claudebot.data.BotApi
import me.waveio.claudebot.platform.PickedFile
import me.waveio.claudebot.platform.PlatformBridge
import me.waveio.claudebot.state.AppController
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Exercises the real Compose app/controller with deterministic host responses and silent OS fixtures. */
class MobileUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val bridge = SilentUiBridge()
    private val clients = CopyOnWriteArrayList<HttpClient>()
    private val sent = CopyOnWriteArrayList<JsonObject>()
    @Volatile private var answered = false
    @Volatile private var sessionId = "conversation"
    @Volatile private var fileText = "# Shared workspace\nA note from the computer."
    private lateinit var controller: AppController

    @After fun cleanup() { if (::controller.isInitialized) controller.close(); clients.forEach { it.close() } }

    private fun launch(language: String = "en") {
        bridge.preferences["preferences.v1"] = """{"language":"$language","haptics":false}"""
        compose.setContent {
            App(bridge) { platform -> AppController(platform, makeApi = { server, token ->
                val client = HttpClient(MockEngine { request ->
                    val path = request.url.encodedPath
                    val json = when {
                        path == "/api/mobile/pair/exchange" -> """{"token":"test-device-token","device_id":"test-device","expires_at":1999999999}"""
                        path == "/api/mobile/capabilities" -> """{"steer":false,"queue":true,"event_replay":true}"""
                        path == "/api/brain/models" -> """{"models":[{"id":"fixture/claude","label":"Claude Sonnet","provider":"fixture","brand":"anthropic","efforts":["none","low","high"]},{"id":"fixture/gpt","label":"GPT","provider":"fixture","brand":"openai","efforts":["none","high"]}],"selected":"fixture/claude"}"""
                        path == "/api/sessions" -> """{"sessions":[{"id":"$sessionId","title":"A shared conversation","updated":1791043200}]}"""
                        path.startsWith("/api/sessions/") -> if (answered) """{"messages":[{"id":"u1","role":"user","content":"Find the project notes"},{"id":"a1","role":"assistant","content":"I found the project notes.\n\nThey are in your shared workspace.","model":"fixture/claude","steps":[{"id":"s1","label":"Search workspace","detail":"Found notes.md","status":"done"}],"parts":[{"type":"text","text":"I found the project notes."},{"type":"steps","ids":["s1"]},{"type":"text","text":"They are in your shared workspace."}]}]}""" else """{"messages":[]}"""
                        path == "/api/setup" -> """{"profile":{"name":"Claude Bot","persona":"friendly","persona_custom":"Keep things clear."}}"""
                        path == "/api/mobile/messages" && request.method == HttpMethod.Post -> {
                            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                            sent += body; sessionId = body.getValue("session_id").jsonPrimitive.content
                            """{"id":"job1","session_id":"$sessionId","state":"running"}"""
                        }
                        path == "/api/mobile/messages" -> """{"messages":[]}"""
                        path.endsWith("/events") -> {
                            answered = true
                            return@MockEngine respond("id: 1\nevent: delta\ndata: {\"chunk\":\"I found the project notes.\"}\n\nid: 2\nevent: done\ndata: {\"reply\":\"I found the project notes.\"}\n\nid: 3\nevent: mobile_state\ndata: {\"state\":\"completed\"}\n\n", headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
                        }
                        path == "/api/workspace/list" -> """{"entries":[{"path":"notes.md","name":"notes.md","type":"file","size":42}]}"""
                        path == "/api/mobile/workspace/file" && request.method == HttpMethod.Post -> {
                            fileText = Json.parseToJsonElement((request.body as TextContent).text).jsonObject.getValue("content").jsonPrimitive.content
                            """{"ok":true,"path":"notes.md","revision":"after"}"""
                        }
                        path == "/api/mobile/workspace/file" -> buildJsonObject { put("path", "notes.md"); put("content", fileText); put("binary", false); put("revision", "before") }.toString()
                        path == "/api/asr" || path == "/api/asr/partial" -> """{"text":"A dictated message"}"""
                        else -> error("Unexpected test route: ${request.method.value} $path")
                    }
                    respond(json, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                })
                clients += client
                BotApi(server, token, client)
            }).also { controller = it } }
        }
    }

    private fun waitFor(label: String) {
        compose.waitUntil(10000) { compose.onAllNodesWithText(label, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val path = File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png")
        path.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun back() { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK) }

    @Test fun pairedChatModelDrawerFilesAndAppearance() {
        launch()
        waitFor("Scan QR code")
        compose.onNodeWithText("Scan QR code").performClick()
        waitFor("Claude Sonnet")
        screenshot("mobile-new-chat")
        compose.onNodeWithText("Claude Sonnet").performClick()
        waitFor("Effort")
        compose.onNodeWithText("High").performClick()
        screenshot("mobile-model-picker")
        back()
        compose.onNode(hasSetTextAction()).performTextInput("Find the project notes")
        compose.onNodeWithContentDescription("Send").performClick()
        waitFor("They are in your shared workspace.")
        assertEquals("high", sent.single().getValue("reasoning_effort").jsonPrimitive.content)
        compose.onNodeWithText("Details").performClick()
        compose.onNodeWithText("Search workspace").assertIsDisplayed()
        screenshot("mobile-chat")
        compose.onNodeWithContentDescription("Menu").performClick()
        waitFor("Files")
        screenshot("mobile-drawer")
        compose.onNodeWithText("Files").performClick()
        waitFor("notes.md")
        compose.onNodeWithText("notes.md").performClick()
        waitFor("Text editor")
        compose.onNode(hasSetTextAction()).performTextReplacement("# Updated on the phone")
        compose.waitUntil(10000) { fileText == "# Updated on the phone" }
        waitFor("Saved")
        screenshot("mobile-file-editor")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithText("Profile").performClick()
        compose.onNodeWithText("Appearance").performClick()
        compose.onNodeWithText("Dark", substring = false).performClick()
        screenshot("mobile-appearance-dark")
        compose.onNodeWithContentDescription("New chat").performClick()
        screenshot("mobile-new-chat-dark")
    }

    @Test fun ukrainianDictationInsertsIntoComposerWithoutSending() {
        launch("uk")
        waitFor("Сканувати QR")
        compose.onNodeWithText("Сканувати QR", substring = true).performClick()
        waitFor("Claude Sonnet")
        compose.onNodeWithContentDescription("Диктувати").performClick()
        waitFor("Слухаю")
        screenshot("mobile-dictation-uk")
        compose.onNodeWithContentDescription("Зупинити").performClick()
        waitFor("A dictated message")
        assertTrue(sent.isEmpty())
        screenshot("mobile-dictated-draft-uk")
    }
}

private class SilentUiBridge : PlatformBridge {
    override val platformName = "android"
    override val systemLanguage = "en"
    override val reducedMotion = true
    override val foreground = MutableStateFlow(true)
    override val incomingPairing = MutableStateFlow<String?>(null)
    val preferences = mutableMapOf<String, String>()
    private val secrets = mutableMapOf<String, String>()
    private var id = 0
    private var recordingResult: ((PickedFile?) -> Unit)? = null
    override fun readPreference(key: String) = preferences[key]
    override fun writePreference(key: String, value: String?) { if (value == null) preferences.remove(key) else preferences[key] = value }
    override fun readSecret(key: String) = secrets[key]
    override fun writeSecret(key: String, value: String?) { if (value == null) secrets.remove(key) else secrets[key] = value }
    override fun scanQr(onResult: (String?) -> Unit) { onResult("claudebot://pair?server=https%3A%2F%2Fmobile-test.example&code=test-pairing") }
    override fun pickFile(kind: String, onResult: (PickedFile?) -> Unit) { onResult(null) }
    override fun startRecording(onAmplitude: (Float) -> Unit, onResult: (PickedFile?) -> Unit, onPartial: (PickedFile) -> Unit) { recordingResult = onResult; onAmplitude(0.75f) }
    override fun stopRecording() { recordingResult?.invoke(PickedFile("fixture.wav", "audio/wav", byteArrayOf(0, 0))) }
    override fun cancelRecording() { recordingResult = null }
    override fun haptic() = Unit
    override fun copyText(value: String) = Unit
    override fun shareText(value: String) = Unit
    override fun requestNotifications(onResult: (Boolean) -> Unit) { onResult(false) }
    override fun notifyReply(title: String, body: String, conversationId: String) = Unit
    override fun nowMillis() = System.currentTimeMillis()
    override fun newId() = "ui-${++id}"
}
