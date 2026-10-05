package me.waveio.claudebot

import android.graphics.Bitmap
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Real app, bounded window constraints and fake host; no account or provider is contacted. */
class TabletLayoutRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val testName = org.junit.rules.TestName()
    private val width = mutableStateOf(1100.dp)
    private val bridge = TabletBridge()
    private val clients = CopyOnWriteArrayList<HttpClient>()
    private val sends = CopyOnWriteArrayList<JsonObject>()
    private lateinit var controller: AppController
    private var controllerCount = 0

    @After fun cleanup() {
        if (::controller.isInitialized) controller.close()
        clients.forEach { it.close() }
    }

    private fun launch(paired: Boolean = true) {
        if (!paired) bridge.secrets.clear()
        compose.activityRule.scenario.onActivity {
            it.enableEdgeToEdge()
            it.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.width(width.value).fillMaxHeight().testTag("test-window")) {
                    App(bridge) { platform ->
                        controllerCount++
                        AppController(platform, makeApi = { server, token ->
                            val client = HttpClient(MockEngine { request ->
                                val path = request.url.encodedPath
                                val body = when {
                                    path == "/api/mobile/capabilities" -> """{"queue":true,"event_replay":true}"""
                                    path == "/api/brain/models" -> """{"models":[{"id":"openai/gpt-6-sol","label":"GPT Sol","provider":"openai","brand":"openai","efforts":["none","high"]}],"default":"openai/gpt-6-sol"}"""
                                    path == "/api/brain/intelligence" -> """{"available":false}"""
                                    path == "/api/sessions" -> """{"sessions":[{"id":"tablet-chat","title":"Tablet conversation","updated":1791185000}]}"""
                                    path == "/api/sessions/tablet-chat" -> buildJsonObject {
                                        put("messages", buildJsonArray {
                                            repeat(24) { index -> add(buildJsonObject {
                                                put("id", "answer-$index"); put("role", "assistant")
                                                put("content", "History line $index\n\nA readable tablet response with enough detail to occupy more than a single line on a compact screen.")
                                            }) }
                                        })
                                    }.toString()
                                    path == "/api/setup" -> """{"profile":{"name":"Fixture bot","language":"en"}}"""
                                    path == "/api/mobile/messages" && request.method == HttpMethod.Post -> {
                                        sends += Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                                        """{"id":"fixture-job","session_id":"tablet-chat","state":"queued"}"""
                                    }
                                    path == "/api/mobile/messages" -> """{"messages":[]}"""
                                    path.endsWith("/events") -> return@MockEngine respond(": keepalive\n\n", headers = headersOf(HttpHeaders.ContentType,"text/event-stream"))
                                    path == "/api/chat/upload" -> """{"url":"/uploads/tablet.png","name":"tablet.png","type":"image/png","size":128}"""
                                    path == "/api/workspace/list" -> """{"entries":[]}"""
                                    else -> error("Unexpected tablet fixture route: ${request.method.value} $path")
                                }
                                respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                            })
                            clients += client
                            BotApi(server, token, client)
                        }).also { controller = it }
                    }
                }
            }
        }
        waitFor(if (paired) "GPT Sol" else "Enter connection code")
    }

    private fun waitFor(text: String) {
        compose.waitUntil(10000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun resize(dp: Int) { compose.runOnIdle { width.value = dp.dp }; compose.waitForIdle() }
    private fun back() { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); compose.waitForIdle() }
    private fun screenshot(label: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = instrumentation.uiAutomation.takeScreenshot()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "tablet-qa").apply { mkdirs() }
        File(directory, "$label.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
        image.recycle()
    }

    @Test fun expandedSidebarAndChatStaySeparateAndNavigationWorks() {
        launch()
        compose.onNodeWithTag("tablet-sidebar").assertIsDisplayed()
        compose.onNodeWithContentDescription("Menu").assertDoesNotExist()
        compose.onNodeWithText("Tablet conversation").performScrollTo().performClick()
        waitFor("History line 23")
        val sidebar = compose.onNodeWithTag("tablet-sidebar").getUnclippedBoundsInRoot()
        val chat = compose.onNodeWithTag("chat-surface").getUnclippedBoundsInRoot()
        assertTrue("Sidebar must not cover the chat", chat.left.value >= sidebar.right.value - 1f)
        assertTrue("The sidebar has a practical width", sidebar.width.value in 250f..340f)
        val composer = compose.onNodeWithTag("chat-composer").getUnclippedBoundsInRoot()
        assertTrue("Composer stays in the conversation pane", composer.left.value >= sidebar.right.value)
        assertTrue("Composer does not stretch across an entire tablet", composer.width.value <= 760f)
        screenshot("tablet-expanded-chat")
        compose.onNodeWithText("Profile", substring = false).performClick()
        waitFor("Appearance")
        compose.onNodeWithTag("tablet-sidebar").assertIsDisplayed()
        compose.onNodeWithText("Appearance", substring = false).performClick()
        waitFor("Theme")
        screenshot("tablet-appearance")
    }

    @Test fun resizingRetainsDraftConversationAndScrollPosition() {
        launch()
        compose.onNodeWithText("Tablet conversation").performScrollTo().performClick()
        waitFor("History line 23")
        compose.onNode(hasSetTextAction()).performTextInput("A draft kept through resizing")
        back()
        // Only a real reading gesture relinquishes automatic following. A
        // semantics-only scroll is intentionally followed back to the live tail.
        val history = compose.onNodeWithTag("chat-history")
        val bounds = history.fetchSemanticsNode().boundsInRoot
        val top = compose.onNodeWithTag("chat-header").fetchSemanticsNode().boundsInRoot.bottom - bounds.top
        val bottom = compose.onNodeWithTag("chat-composer").fetchSemanticsNode().boundsInRoot.top - bounds.top
        history.performTouchInput {
            swipe(Offset(centerX, top + (bottom - top) * .2f),
                Offset(centerX, top + (bottom - top) * .8f), 600)
        }
        compose.onNodeWithContentDescription("New messages").assertIsDisplayed()
        compose.onNodeWithTag("chat-history").performScrollToNode(hasText("History line 10", substring = true))
        for (dp in listOf(720, 390, 1100)) {
            resize(dp)
            assertEquals(1, controllerCount)
            assertEquals("tablet-chat", controller.state.value.sessionId)
            assertEquals("A draft kept through resizing", controller.state.value.draft)
            compose.onNodeWithText("History line 10", substring = true).assertIsDisplayed()
            if (dp >= 840) compose.onNodeWithTag("tablet-sidebar").assertIsDisplayed()
            else {
                compose.onNodeWithTag("tablet-sidebar").assertDoesNotExist()
                compose.onNodeWithContentDescription("Menu").assertIsDisplayed()
            }
            screenshot("tablet-resize-$dp")
        }
        assertTrue(sends.isEmpty())
    }

    @Test fun tabletAttachmentAndModelPanelsRemainBoundedAndBackDismisses() {
        launch()
        compose.onNodeWithContentDescription("Attach").performClick()
        waitFor("Camera")
        compose.onNodeWithText("Photos", substring = false).assertIsDisplayed()
        val panel = compose.onNodeWithTag("attachment-menu").getUnclippedBoundsInRoot()
        assertTrue("Tablet attachment panel is bounded", panel.width.value <= 720f)
        screenshot("tablet-attachments")
        back()
        compose.waitUntil(5000) { !controller.state.value.attachmentPickerOpen }
        compose.onNodeWithText("GPT Sol", substring = false).performClick()
        waitFor("Find a model")
        val picker = compose.onNodeWithTag("model-picker").getUnclippedBoundsInRoot()
        assertTrue("Model picker remains compact", picker.width.value <= 500f)
        screenshot("tablet-models")
        back()
        compose.waitUntil(5000) { !controller.state.value.modelPickerOpen }
        assertFalse(compose.activity.isFinishing)
    }

    @Test fun imageSendFromTabletPreservesExplicitModelAndManifest() {
        launch()
        compose.runOnIdle { controller.selectModel("openai/gpt-6-sol") }
        compose.onNodeWithContentDescription("Attach").performClick()
        waitFor("Photos")
        compose.onNodeWithText("Photos", substring = false).performClick()
        compose.waitUntil(10000) { controller.state.value.attachments.size == 1 && !controller.state.value.uploading }
        compose.onNode(hasSetTextAction()).performTextInput("Describe this photo")
        back()
        compose.onNodeWithContentDescription("Send").performClick()
        compose.waitUntil(10000) { sends.isNotEmpty() }
        val request = sends.single()
        assertEquals("openai/gpt-6-sol", request.getValue("model").jsonPrimitive.content)
        val image = request.getValue("attachments").jsonArray.single().jsonObject
        assertEquals("/uploads/tablet.png", image.getValue("url").jsonPrimitive.content)
        assertEquals("image/png", image.getValue("type").jsonPrimitive.content)
        assertEquals("Describe this photo", request.getValue("message").jsonPrimitive.content)
        assertEquals("",controller.state.value.draft)
    }

    @Test fun connectionFormUsesReadableWidthOnExpandedWindow() {
        launch(paired = false)
        compose.onNodeWithText("Enter connection code").performClick()
        waitFor("Connection code")
        val field = compose.onNodeWithContentDescription("Connection code").getUnclippedBoundsInRoot()
        assertTrue("Connection field should not stretch across the tablet", field.width.value <= 600f)
        val window = compose.onNodeWithTag("test-window").getUnclippedBoundsInRoot()
        assertEquals("Connection form is centered", window.left.value + window.width.value / 2f,
            field.left.value + field.width.value / 2f, 2f)
        compose.onNodeWithText("Connect", substring = false).assertIsDisplayed()
        screenshot("tablet-connection")
    }
}

private class TabletBridge : PlatformBridge {
    override val platformName = "android"
    override val systemLanguage = "en"
    override val reducedMotion = true
    override val foreground = MutableStateFlow(true)
    override val incomingPairing = MutableStateFlow<String?>(null)
    val preferences = mutableMapOf("server" to "https://tablet.example", "device_id" to "tablet-fixture", "preferences.v1" to """{"language":"en","wallpaper":false,"theme":"light","haptics":false}""")
    val secrets = mutableMapOf("device_token" to "fixture-token")
    private var nextId = 0
    override fun readPreference(key: String) = preferences[key]
    override fun writePreference(key: String, value: String?) { if (value == null) preferences.remove(key) else preferences[key] = value }
    override fun readSecret(key: String) = secrets[key]
    override fun writeSecret(key: String, value: String?) { if (value == null) secrets.remove(key) else secrets[key] = value }
    override fun scanQr(onResult: (String?) -> Unit) = onResult(null)
    override fun pickFile(kind: String, onResult: (PickedFile?) -> Unit) {
        val bitmap=Bitmap.createBitmap(12,20,Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.CYAN) }
        val bytes=ByteArrayOutputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG,100,stream); stream.toByteArray() }
        bitmap.recycle(); onResult(PickedFile("tablet.png","image/png",bytes))
    }
    override fun startRecording(onAmplitude: (Float) -> Unit, onResult: (PickedFile?) -> Unit, onPartial: (PickedFile) -> Unit) = Unit
    override fun stopRecording() = Unit
    override fun cancelRecording() = Unit
    override fun haptic() = Unit
    override fun copyText(value: String) = Unit
    override fun shareText(value: String) = Unit
    override fun requestNotifications(onResult: (Boolean) -> Unit) = onResult(false)
    override fun notifyReply(title: String, body: String, conversationId: String) = Unit
    override fun nowMillis() = System.currentTimeMillis()
    override fun newId() = "tablet-${++nextId}"
}
