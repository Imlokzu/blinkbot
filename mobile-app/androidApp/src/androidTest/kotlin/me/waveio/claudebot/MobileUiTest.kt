package me.waveio.claudebot

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.*
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.serialization.json.*
import me.waveio.claudebot.data.BotApi
import me.waveio.claudebot.platform.PickedFile
import me.waveio.claudebot.platform.PlatformBridge
import me.waveio.claudebot.state.AppController
import me.waveio.claudebot.ui.LocaleText
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
    private val fixtureScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val completeStream = CompletableDeferred<Unit>()
    @Volatile private var slowStream = false

    @After fun cleanup() { if (::controller.isInitialized) controller.close(); clients.forEach { it.close() }; fixtureScope.cancel() }

    private fun launch(language: String = "en", motion: Boolean = false) {
        bridge.reducedMotion = !motion
        compose.activityRule.scenario.onActivity { it.enableEdgeToEdge(); it.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) }
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
                        path == "/api/mobile/skills" -> """{"skills":[{"name":"release-notes","description":"Summarize the changes","source":"fixture","invocation":"${'$'}release-notes","enabled":true,"eligible":true,"user_invocable":true,"model_visible":true,"command_visible":true,"selectable":true},{"name":"unavailable-skill","description":"Missing requirement","selectable":false}]}"""
                        path == "/api/setup" -> """{"profile":{"name":"Claude Bot","persona":"friendly","persona_custom":"Keep things clear."}}"""
                        path == "/api/mobile/messages" && request.method == HttpMethod.Post -> {
                            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                            sent += body; sessionId = body.getValue("session_id").jsonPrimitive.content
                            """{"id":"job1","session_id":"$sessionId","state":"running"}"""
                        }
                        path == "/api/mobile/messages" -> """{"messages":[]}"""
                        path.endsWith("/events") -> {
                            if (slowStream) {
                                val body = ByteChannel(autoFlush = true)
                                fixtureScope.launch {
                                    try {
                                        body.writeStringUtf8("id: 1\nevent: mobile_state\ndata: {\"state\":\"running\"}\n\nid: 2\nevent: delta\ndata: {\"chunk\":\"Already arriving\"}\n\n")
                                        completeStream.await()
                                        answered = true
                                        body.writeStringUtf8("id: 3\nevent: done\ndata: {\"reply\":\"The full response\"}\n\nid: 4\nevent: mobile_state\ndata: {\"state\":\"completed\"}\n\n")
                                    } finally { body.close() }
                                }
                                return@MockEngine respond(body, headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
                            }
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
        compose.onNodeWithText("Effort").performClick()
        compose.onNodeWithText("High").performClick()
        screenshot("mobile-model-picker")
        compose.onNodeWithText("Done").performClick()
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
        val uk = runBlocking { LocaleText.load("uk") }
        launch("uk")
        waitFor(uk.get("connect.scan"))
        compose.onNodeWithText(uk.get("connect.scan"), substring = true).performClick()
        waitFor("Claude Sonnet")
        compose.onNodeWithContentDescription(uk.get("input.microphone")).performClick()
        waitFor(uk.get("input.listening"))
        compose.onNodeWithContentDescription(uk.get("chat.placeholder")).assertDoesNotExist()
        waitFor("A dictated message")
        assertTrue(sent.isEmpty())
        screenshot("mobile-dictation-uk")
        compose.onNodeWithContentDescription(uk.get("chat.stop")).performClick()
        waitFor("A dictated message")
        assertTrue(sent.isEmpty())
        screenshot("mobile-dictated-draft-uk")
    }
    @Test fun streamedTextIsVisibleWhileTheServerIsStillWorking() {
        slowStream = true
        launch()
        waitFor("Scan QR code"); compose.onNodeWithText("Scan QR code").performClick()
        waitFor("Claude Sonnet")
        compose.onNode(hasSetTextAction()).performTextInput("Show live progress")
        compose.onNodeWithContentDescription("Send").performClick()
        waitFor("Already arriving")
        compose.onNodeWithContentDescription("Stop").assertIsDisplayed()
        assertFalse(completeStream.isCompleted)
        assertFalse(answered)
        screenshot("mobile-live-stream")
        completeStream.complete(Unit)
        waitFor("They are in your shared workspace.")
        compose.onNodeWithContentDescription("Stop").assertDoesNotExist()
    }

    @Test fun attachmentTilesSkillsAndLongPressActionsAreConnected() {
        launch()
        waitFor("Scan QR code"); compose.onNodeWithText("Scan QR code").performClick()
        waitFor("Claude Sonnet")
        compose.onNode(hasSetTextAction()).performTextInput("Please summarize")
        compose.onNodeWithContentDescription("Attach").performClick()
        compose.onNodeWithText("Camera").assertIsDisplayed()
        compose.onNodeWithText("Photos").assertIsDisplayed()
        compose.onNodeWithText("Files").assertIsDisplayed()
        screenshot("mobile-attachment-menu")
        compose.onNodeWithText("Camera").performClick()
        assertEquals("camera", bridge.lastPicker)
        compose.onNodeWithContentDescription("Attach").performClick()
        compose.onNodeWithText("Skills").performClick()
        waitFor("release-notes")
        compose.onNodeWithText("unavailable-skill").assertIsNotEnabled()
        compose.onNodeWithText("release-notes").performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("${'$'}release-notes Please summarize")
        assertTrue(sent.isEmpty())
        compose.onNodeWithContentDescription("Send").performClick()
        waitFor("They are in your shared workspace.")
        compose.onNodeWithText("I found the project notes.").performTouchInput { longClick() }
        compose.onNodeWithText("Copy").assertIsDisplayed()
        compose.onNodeWithText("Share").assertIsDisplayed()
        compose.onNodeWithText("Select text").assertIsDisplayed()
        screenshot("mobile-message-actions")
        compose.onNodeWithText("Copy").performClick()
        assertEquals("I found the project notes.", bridge.copied)
    }

    @Test fun customScheduleSubmitsAnActualFutureTimestamp() {
        launch()
        waitFor("Scan QR code"); compose.onNodeWithText("Scan QR code").performClick()
        waitFor("Claude Sonnet")
        compose.onNode(hasSetTextAction()).performTextInput("Send this later")
        compose.onNodeWithContentDescription("Attach").performClick()
        compose.onNodeWithText("Send later").performClick()
        waitFor("Schedule message")
        compose.onNodeWithText("Hours").assertIsDisplayed()
        compose.onNodeWithText("Minutes").assertIsDisplayed()
        screenshot("mobile-schedule")
        compose.onAllNodesWithText("Schedule message").onLast().performClick()
        compose.waitUntil(10000) { sent.isNotEmpty() }
        val timestamp = sent.single().getValue("scheduled_at").jsonPrimitive.content
        assertTrue(java.time.Instant.parse(timestamp).toEpochMilli() > System.currentTimeMillis())
    }

    @Test fun modelSearchAndEffortRemainReachableWithKeyboard() {
        launch()
        waitFor("Scan QR code"); compose.onNodeWithText("Scan QR code").performClick()
        waitFor("Claude Sonnet")
        compose.onNodeWithText("Claude Sonnet").performClick()
        compose.onNodeWithContentDescription("Find a model").performClick().performTextInput("GPT")
        compose.onAllNodesWithText("GPT", substring = false).onLast().performClick()
        waitFor("Effort")
        compose.onNodeWithText("High").performClick()
        compose.onNodeWithText("Done").assertIsDisplayed().performClick()
        compose.onNodeWithText("GPT", substring = false).assertIsDisplayed()
        assertEquals("fixture/gpt", controller.state.value.selectedModel)
        assertEquals("high", controller.state.value.effort)
    }

    @Test fun drawerMotionRetainsNavigationAndRecordsFrameCosts() {
        launch(motion = true)
        waitFor("Scan QR code"); compose.onNodeWithText("Scan QR code").performClick()
        waitFor("Claude Sonnet")
        val frames = androidx.core.app.FrameMetricsAggregator(androidx.core.app.FrameMetricsAggregator.TOTAL_DURATION)
        compose.activityRule.scenario.onActivity { frames.add(it) }
        // Pace the Compose clock against real wall time. Auto-advancing a whole
        // spring inside one Android frame produces meaningless frame histograms.
        compose.mainClock.autoAdvance = false
        try {
            repeat(8) { cycle ->
                InstrumentationRegistry.getInstrumentation().runOnMainSync { controller.menu(cycle % 2 == 0) }
                repeat(22) { compose.mainClock.advanceTimeByFrame(); Thread.sleep(16) }
            }
        } finally { compose.mainClock.autoAdvance = true }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Menu").assertIsDisplayed()
        val histogram = frames.remove(compose.activity)?.get(0)
        val samples = buildList { if (histogram != null) for (i in 0 until histogram.size()) repeat(histogram.valueAt(i)) { add(histogram.keyAt(i)) } }.sorted()
        val summary = if (samples.isEmpty()) "{\"frames\":0}" else "{\"frames\":${samples.size},\"medianMs\":${samples[samples.size / 2]},\"p95Ms\":${samples[(samples.lastIndex * .95).toInt()]}}"
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "motion-frame-metrics.json").writeText(summary)
        assertTrue("Window frame metrics must be captured", samples.isNotEmpty())
    }

}

private class SilentUiBridge : PlatformBridge {
    override val platformName = "android"
    override val systemLanguage = "en"
    override var reducedMotion = true
    override val foreground = MutableStateFlow(true)
    override val incomingPairing = MutableStateFlow<String?>(null)
    val preferences = mutableMapOf<String, String>()
    private val secrets = mutableMapOf<String, String>()
    private var id = 0
    var lastPicker: String? = null
    var copied: String? = null
    private var recordingResult: ((PickedFile?) -> Unit)? = null
    override fun readPreference(key: String) = preferences[key]
    override fun writePreference(key: String, value: String?) { if (value == null) preferences.remove(key) else preferences[key] = value }
    override fun readSecret(key: String) = secrets[key]
    override fun writeSecret(key: String, value: String?) { if (value == null) secrets.remove(key) else secrets[key] = value }
    override fun scanQr(onResult: (String?) -> Unit) { onResult("claudebot://pair?server=https%3A%2F%2Fmobile-test.example&code=test-pairing") }
    override fun pickFile(kind: String, onResult: (PickedFile?) -> Unit) { lastPicker = kind; onResult(null) }
    override fun startRecording(onAmplitude: (Float) -> Unit, onResult: (PickedFile?) -> Unit, onPartial: (PickedFile) -> Unit) { recordingResult = onResult; onAmplitude(0.75f); onPartial(PickedFile("partial.wav", "audio/wav", byteArrayOf(0, 0))) }
    override fun stopRecording() { recordingResult?.invoke(PickedFile("fixture.wav", "audio/wav", byteArrayOf(0, 0))) }
    override fun cancelRecording() { recordingResult = null }
    override fun haptic() = Unit
    override fun copyText(value: String) { copied = value }
    override fun shareText(value: String) = Unit
    override fun requestNotifications(onResult: (Boolean) -> Unit) { onResult(false) }
    override fun notifyReply(title: String, body: String, conversationId: String) = Unit
    override fun nowMillis() = System.currentTimeMillis()
    override fun newId() = "ui-${++id}"
}
