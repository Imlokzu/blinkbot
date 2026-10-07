package me.waveio.claudebot

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.MutableStateFlow
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

/** Captures the actual KMP app shell, controller and Compose chat for the landing page.
 * Only the host and native side effects are fixtures; no owner data or network is used.
 */
class LandingScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val bridge = LandingCaptureBridge()
    private val clients = CopyOnWriteArrayList<HttpClient>()
    private lateinit var controller: AppController

    @After fun cleanup() {
        if (::controller.isInitialized) controller.close()
        clients.forEach { it.close() }
    }

    @Test fun captureEnglishNativeChatWithDrawerClosed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val history = instrumentation.context.assets.open("landing-chat-en.json").bufferedReader().use { it.readText() }
        compose.activityRule.scenario.onActivity {
            it.enableEdgeToEdge()
            it.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        compose.setContent {
            App(bridge, onSystemBarAppearance = compose.activity::systemBarAppearance) { platform ->
                AppController(platform, makeApi = { server, token ->
                    val client = HttpClient(MockEngine { request ->
                        val body = when (val path = request.url.encodedPath) {
                            "/api/mobile/capabilities" -> """{"steer":false,"queue":true,"event_replay":true}"""
                            "/api/brain/models" -> """{"models":[{"id":"fixture/gpt","label":"GPT-6 Sol","provider":"fixture","brand":"openai","vision":true,"efforts":["none","high"]}],"selected":"fixture/gpt"}"""
                            "/api/sessions" -> """{"sessions":[{"id":"landing-chat","title":"Saturday with friends","updated":1791378000}]}"""
                            "/api/sessions/landing-chat" -> history
                            "/api/mobile/messages" -> """{"messages":[]}"""
                            "/api/mobile/skills" -> """{"skills":[]}"""
                            "/api/setup" -> """{"profile":{"name":"Blink","persona":"friendly"}}"""
                            else -> error("Unexpected landing fixture request: $path")
                        }
                        respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    })
                    clients += client
                    BotApi(server, token, client)
                }).also { controller = it }
            }
        }
        compose.waitUntil(15_000) { ::controller.isInitialized && controller.state.value.conversations.isNotEmpty() }
        compose.runOnIdle { controller.openChat("landing-chat") }
        compose.waitUntil(15_000) { !controller.state.value.loading && controller.state.value.messages.size == 2 }
        compose.onNodeWithText("GPT-6 Sol").assertIsDisplayed()
        compose.onNodeWithContentDescription("Menu").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("en", controller.state.value.preferences.language)
            assertFalse(controller.state.value.menuOpen)
            assertNull(controller.state.value.error)
        }
        compose.waitForIdle()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "landing-capture").apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory, "mobile.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}

private class LandingCaptureBridge : PlatformBridge {
    override val platformName = "android"
    override val systemLanguage = "en"
    override val reducedMotion = true
    override val foreground = MutableStateFlow(true)
    override val incomingPairing = MutableStateFlow<String?>(null)
    private val preferences = mutableMapOf(
        "server" to "https://landing.example",
        "preferences.v1" to """{"language":"en","theme":"dark","wallpaper":false,"haptics":false,"answerHaptics":false}""",
    )
    private val secrets = mutableMapOf("device_token" to "landing-fixture-token")
    private var sequence = 0
    override fun readPreference(key: String) = preferences[key]
    override fun writePreference(key: String, value: String?) { if (value == null) preferences.remove(key) else preferences[key] = value }
    override fun readSecret(key: String) = secrets[key]
    override fun writeSecret(key: String, value: String?) { if (value == null) secrets.remove(key) else secrets[key] = value }
    override fun scanQr(onResult: (String?) -> Unit) = onResult(null)
    override fun pickFile(kind: String, onResult: (PickedFile?) -> Unit) = onResult(null)
    override fun startRecording(onAmplitude: (Float) -> Unit, onResult: (PickedFile?) -> Unit, onPartial: (PickedFile) -> Unit) = onResult(null)
    override fun stopRecording() = Unit
    override fun cancelRecording() = Unit
    override fun haptic() = Unit
    override fun copyText(value: String) = Unit
    override fun shareText(value: String) = Unit
    override fun requestNotifications(onResult: (Boolean) -> Unit) = onResult(false)
    override fun notifyReply(title: String, body: String, conversationId: String) = Unit
    override fun nowMillis() = 1791378000000L
    override fun newId() = "landing-${++sequence}"
}
