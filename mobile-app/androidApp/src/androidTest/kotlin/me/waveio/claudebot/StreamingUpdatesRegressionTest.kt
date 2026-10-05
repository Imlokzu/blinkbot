package me.waveio.claudebot

import android.graphics.Bitmap
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real App/controller flows; only the authenticated host and native integrations are fixtures. */
class StreamingUpdatesRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val bridge = StreamingUpdatesBridge()
    private val host = StreamingUpdatesHost()
    private lateinit var controller: AppController
    private lateinit var strings: LocaleText

    @After fun cleanup() {
        try {
            if (::controller.isInitialized) compose.runOnIdle { controller.close() }
        } finally {
            host.close()
        }
        assertTrue("Unexpected fixture requests: ${host.unexpected}", host.unexpected.isEmpty())
    }

    private fun launch() {
        strings = runBlocking { LocaleText.load("en") }
        compose.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        compose.setContent {
            App(bridge, onSystemBarAppearance = compose.activity::systemBarAppearance) { platform ->
                AppController(platform, makeApi = host::makeApi).also { controller = it }
            }
        }
        compose.waitUntil(REGRESSION_TIMEOUT) {
            ::controller.isInitialized && controller.state.value.connected && controller.state.value.conversations.isNotEmpty()
        }
    }

    private fun openDrawer() {
        // Phones reveal a modal menu; tablets already expose the same navigation in their sidebar.
        compose.waitUntil(REGRESSION_TIMEOUT) {
            compose.onAllNodesWithContentDescription(strings.get("nav.menu")).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText(REGRESSION_CHAT_TITLE).fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithContentDescription(strings.get("nav.menu")).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithContentDescription(strings.get("nav.menu")).performClick()
        }
    }

    @Test fun whitespaceSnapshotAndDeltaKeepTwoSeparateLiveSurfacesBeforeProviderDone() {
        host.streamingJob = true
        launch()
        openDrawer()
        compose.onNodeWithText(REGRESSION_CHAT_TITLE).performScrollTo().performClick()
        compose.waitUntil(REGRESSION_TIMEOUT) {
            controller.state.value.sessionId == "chat_1" && !controller.state.value.loading &&
                controller.state.value.messages.any { it.id == "a-job" && it.text == SNAPSHOT_TEXT }
        }
        assertLiveSurfaces("Second par", SNAPSHOT_TEXT)
        assertFalse("The suffix must remain gated until snapshot surfaces are checked", host.allowDelta.isCompleted)

        host.allowDelta.complete(Unit)
        compose.waitUntil(REGRESSION_TIMEOUT) {
            controller.state.value.messages.any { it.id == "a-job" && it.text == SNAPSHOT_TEXT + "t." }
        }
        assertLiveSurfaces("Second par t.", SNAPSHOT_TEXT + "t.")
        compose.onNode(renderedText("Second par"), useUnmergedTree = true).assertDoesNotExist()
        captureScreenshot("live-two-bubbles.png")

        // Neither done nor history carries repaired parts: the already-visible split must survive completion.
        host.allowDone.complete(Unit)
        compose.waitUntil(REGRESSION_TIMEOUT) {
            !controller.state.value.busy && controller.state.value.messages.singleOrNull { it.role == "assistant" }?.live == false &&
                host.historyRequests.get() > 1
        }
        assertSurfaces("Second par t.")
        assertTrue(host.providerDone.get())
        assertEquals("Only the original human history may have reached the controller", 1, host.historyResponses.get())
        assertEquals("The fixture must use one continuous SSE connection", 1, host.streamRequests.get())
    }

    @Test fun profileOpensUpdatesWithInstalledVersionAndInlineCheckFailureCanRetry() {
        host.queueCapabilities(NO_RELEASE)
        host.queueCapabilities("""{"error":"fixture_unavailable"}""", HttpStatusCode.ServiceUnavailable)
        val retry = host.queueCapabilities(CURRENT_RELEASE, held = true)
        launch()
        openUpdates()
        waitForUpdateIdle(2)
        updateText("update.installed", "version" to INSTALLED_VERSION_NAME).assertIsDisplayed()
        updateText("update.noRelease").assertIsDisplayed()
        updateAction("update.check").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(REGRESSION_TIMEOUT) {
            !controller.state.value.updateChecking && controller.state.value.updateError == "update.checkFailed"
        }
        updateText("update.checkFailed").performScrollTo().assertIsDisplayed()
        assertNull("Check errors belong on the update screen, not in a global toast", controller.state.value.error)
        assertNull(controller.state.value.notice)
        assertFalse(controller.state.value.updatePromptOpen)
        compose.onAllNodes(isDialog()).assertCountEquals(0)

        updateAction("update.check").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(REGRESSION_TIMEOUT) { host.capabilityRequests.get() == 4 && controller.state.value.updateChecking }
        updateAction("update.checking").assertIsNotEnabled()
        updateText("update.checkFailed").assertDoesNotExist()
        retry.complete(Unit)
        waitForUpdateIdle(4)
        updateText("update.current").performScrollTo().assertIsDisplayed()
        updateAction("update.check").assertIsEnabled()
        assertNull(controller.state.value.updateError)
        assertEquals(Screen.Updates, controller.state.value.screen)
        compose.onNodeWithContentDescription(strings.get("nav.back")).performClick()
        compose.waitUntil(REGRESSION_TIMEOUT) { controller.state.value.screen == Screen.Profile }
        compose.onNodeWithTag("app-updates").assertDoesNotExist()
    }

    @Test fun returnedReleaseShowsChangelogAndInstallsFromTheDedicatedUpdatesScreen() {
        val release = host.queueCapabilities(availableRelease(), held = true)
        launch()
        openUpdates()
        compose.waitUntil(REGRESSION_TIMEOUT) { host.capabilityRequests.get() == 2 && controller.state.value.updateChecking }
        updateText("update.installed", "version" to INSTALLED_VERSION_NAME).assertIsDisplayed()
        updateAction("update.checking").performScrollTo().assertIsNotEnabled()
        release.complete(Unit)
        waitForUpdateIdle(2)
        assertFalse("A manual update screen must not be hidden behind the startup prompt", controller.state.value.updatePromptOpen)
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        updateText("update.available", "version" to RELEASE_VERSION).performScrollTo().assertIsDisplayed()
        updateText("update.changelog").performScrollTo().assertIsDisplayed()
        compose.onNode(hasText(RELEASE_CHANGE) and hasAnyAncestor(hasTestTag("app-updates")))
            .performScrollTo().assertIsDisplayed()
        updateAction("update.install").performScrollTo().assertIsEnabled()
        captureScreenshot("updates-page.png")
        updateAction("update.install").performClick()
        compose.waitUntil(REGRESSION_TIMEOUT) { bridge.installed.size == 1 && !controller.state.value.updateInstalling }
        compose.waitUntil(REGRESSION_TIMEOUT) {
            compose.onAllNodesWithText(strings.get("update.installerOpened"), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(strings.get("update.installerOpened"), useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        val installed = bridge.installed.single()
        assertEquals("ClaudeBot-$RELEASE_VERSION.apk", installed.name)
        assertEquals("application/vnd.android.package-archive", installed.mimeType)
        assertArrayEquals(UPDATE_BYTES, installed.bytes)
        assertEquals(1, host.downloads.get())
        assertEquals(Screen.Updates, controller.state.value.screen)
        assertNull(controller.state.value.updateError)
    }

    private fun openUpdates() {
        openDrawer()
        compose.onNodeWithText(strings.get("nav.profile")).performClick()
        compose.waitUntil(REGRESSION_TIMEOUT) { controller.state.value.screen == Screen.Profile }
        compose.onNodeWithText(strings.get("profile.update")).performScrollTo().performClick()
        compose.waitUntil(REGRESSION_TIMEOUT) {
            controller.state.value.screen == Screen.Updates &&
                compose.onAllNodesWithTag("app-updates").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("app-updates").assertIsDisplayed()
    }

    private fun waitForUpdateIdle(requests: Int) {
        compose.waitUntil(REGRESSION_TIMEOUT) { host.capabilityRequests.get() == requests && !controller.state.value.updateChecking }
    }

    private fun updateText(key: String, vararg arguments: Pair<String, Any>) = compose.onNode(
        hasText(strings.get(key, *arguments)) and hasAnyAncestor(hasTestTag("app-updates")), useUnmergedTree = true,
    )

    private fun updateAction(key: String) = compose.onNode(
        hasText(strings.get(key)) and hasClickAction() and hasAnyAncestor(hasTestTag("app-updates")),
    )

    private fun assertLiveSurfaces(second: String, authoritativeText: String) {
        assertSurfaces(second)
        val state = controller.state.value
        val answer = state.messages.single { it.role == "assistant" }
        assertEquals("a-job", answer.id)
        assertEquals(authoritativeText, answer.text)
        assertEquals(listOf("First bubble.", second), answer.parts.map { it.text.trim() })
        assertEquals("Splitting surfaces must preserve every authoritative byte", authoritativeText, answer.parts.joinToString("\n\n") { it.text })
        assertTrue(answer.live)
        assertTrue(state.busy)
        assertEquals("job", state.activeJobId)
        assertFalse("Both surfaces must render while the provider is still open", host.providerDone.get())
        assertFalse(host.allowDone.isCompleted)
        assertEquals("Live rendering must not need a history refresh", 1, host.historyRequests.get())
        assertEquals(1, host.historyResponses.get())
    }

    private fun assertSurfaces(second: String) {
        val bubbleTags = SemanticsMatcher("assistant bubble surface") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("message-bubble:a-job:") == true
        }
        compose.waitUntil(REGRESSION_TIMEOUT) {
            compose.onAllNodes(renderedText("First bubble."), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(renderedText(second), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodes(bubbleTags, useUnmergedTree = true).assertCountEquals(2)
        val firstSurface = compose.onNodeWithTag("message-bubble:a-job:0", useUnmergedTree = true).assertIsDisplayed()
        val secondSurface = compose.onNodeWithTag("message-bubble:a-job:1", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodes(renderedText("First bubble."), useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodes(renderedText(second), useUnmergedTree = true).assertCountEquals(1)
        compose.onNode(renderedText("First bubble."), useUnmergedTree = true)
            .assert(hasAnyAncestor(hasTestTag("message-bubble:a-job:0")))
        compose.onNode(renderedText(second), useUnmergedTree = true)
            .assert(hasAnyAncestor(hasTestTag("message-bubble:a-job:1")))
        val firstBounds = firstSurface.fetchSemanticsNode().boundsInRoot
        val secondBounds = secondSurface.fetchSemanticsNode().boundsInRoot
        assertTrue("Paragraphs must occupy separate, non-overlapping bubble surfaces", firstBounds.bottom < secondBounds.top)
        assertEquals(1, controller.state.value.messages.count { it.role == "assistant" })
    }

    private fun renderedText(expected: String) = SemanticsMatcher("rendered text: $expected") {
        it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text?.trim() == expected
    }

    private fun captureScreenshot(name: String) {
        try {
            compose.waitForIdle()
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                val directory = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "update-qa")
                check(directory.isDirectory || directory.mkdirs())
                val file = File(directory, name)
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                Log.i("StreamingUpdatesQA", "Screenshot: ${file.absolutePath}")
            } finally {
                bitmap.recycle()
            }
        } catch (failure: Exception) {
            // Visual artifacts are optional; functional assertions remain authoritative.
            Log.w("StreamingUpdatesQA", "Could not capture $name", failure)
        }
    }
}

private class StreamingUpdatesHost {
    private val clients = CopyOnWriteArrayList<HttpClient>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stream = ByteChannel(autoFlush = true)
    private val historyRepair = CompletableDeferred<Unit>()
    private val capabilityReplies = Channel<CapabilitiesReply>(Channel.UNLIMITED)
    val allowDelta = CompletableDeferred<Unit>()
    val allowDone = CompletableDeferred<Unit>()
    val providerDone = AtomicBoolean()
    val historyRequests = AtomicInteger()
    val historyResponses = AtomicInteger()
    val streamRequests = AtomicInteger()
    val capabilityRequests = AtomicInteger()
    val downloads = AtomicInteger()
    val unexpected = CopyOnWriteArrayList<String>()
    @Volatile var streamingJob = false

    fun queueCapabilities(body: String, status: HttpStatusCode = HttpStatusCode.OK, held: Boolean = false): CompletableDeferred<Unit> {
        val gate = CompletableDeferred<Unit>()
        if (!held) gate.complete(Unit)
        capabilityReplies.trySend(CapabilitiesReply(body, status, gate)).getOrThrow()
        return gate
    }

    fun makeApi(origin: String, token: String): BotApi {
        val client = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            check(request.headers[HttpHeaders.Authorization] == "Bearer $REGRESSION_TOKEN") { "Missing fixture authentication: $path" }
            check(request.method == HttpMethod.Get) { "Unexpected fixture method: ${request.method.value} $path" }
            val body = when (path) {
                "/api/mobile/capabilities" -> {
                    check(request.url.parameters["platform"] == "android")
                    check(request.url.parameters["version_code"] == INSTALLED_VERSION_CODE.toString())
                    if (capabilityRequests.incrementAndGet() > 1) {
                        val reply = withTimeout(REGRESSION_TIMEOUT) { capabilityReplies.receive().also { it.gate.await() } }
                        return@MockEngine respond(reply.body, reply.status, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                    NO_RELEASE
                }
                "/api/brain/intelligence" -> """{"available":false}"""
                "/api/brain/models" -> """{"models":[{"id":"fixture/model","label":"Regression model","provider":"fixture","brand":"anthropic"}],"selected":"fixture/model"}"""
                "/api/setup" -> """{"profile":{"name":"Regression bot","language":"en"}}"""
                "/api/sessions" -> """{"sessions":[{"id":"chat_1","title":"$REGRESSION_CHAT_TITLE","updated":1791043200}]}"""
                "/api/sessions/chat_1" -> {
                    if (historyRequests.incrementAndGet() > 1) historyRepair.await()
                    historyResponses.incrementAndGet()
                    """{"messages":[{"id":"u-client","role":"user","content":"Please send two bubbles."}]}"""
                }
                "/api/mobile/messages" -> if (streamingJob && request.url.parameters["session_id"] == "chat_1") {
                    """{"messages":[{"id":"job","session_id":"chat_1","client_id":"client","message":"Please send two bubbles.","state":"running"}]}"""
                } else """{"messages":[]}"""
                "/api/mobile/messages/job/events" -> {
                    check(streamRequests.incrementAndGet() == 1) { "The open provider must not reconnect" }
                    scope.launch {
                        try {
                            withTimeout(3 * REGRESSION_TIMEOUT) {
                                event(1, "reply_snapshot", buildJsonObject {
                                    put("text", SNAPSHOT_TEXT)
                                    put("bubbles", buildJsonArray { add("First bubble."); add("Second par") })
                                })
                                allowDelta.await()
                                event(2, "delta", buildJsonObject { put("chunk", "t.") })
                                allowDone.await()
                                providerDone.set(true)
                                event(3, "done", buildJsonObject { put("reply", SNAPSHOT_TEXT + "t.") })
                                event(4, "mobile_state", buildJsonObject { put("state", "completed") })
                            }
                        } finally {
                            stream.close()
                        }
                    }
                    return@MockEngine respond(stream, headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
                }
                "/updates/fixture.apk" -> {
                    downloads.incrementAndGet()
                    return@MockEngine respond(UPDATE_BYTES, headers = headersOf(HttpHeaders.ContentType, "application/vnd.android.package-archive"))
                }
                else -> {
                    unexpected += "${request.method.value} $path"
                    error("Unexpected regression fixture route: ${request.method.value} $path")
                }
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        clients += client
        return BotApi(origin, token, client)
    }

    private suspend fun event(id: Int, kind: String, body: JsonObject) {
        stream.writeStringUtf8("id: $id\nevent: $kind\ndata: $body\n\n")
    }

    fun close() {
        scope.cancel()
        stream.close()
        historyRepair.cancel()
        allowDelta.cancel()
        allowDone.cancel()
        capabilityReplies.cancel()
        clients.forEach { it.close() }
    }

    private data class CapabilitiesReply(val body: String, val status: HttpStatusCode, val gate: CompletableDeferred<Unit>)
}

private class StreamingUpdatesBridge : PlatformBridge {
    override val platformName = "android"
    override val appVersionCode = INSTALLED_VERSION_CODE
    override val appVersionName = INSTALLED_VERSION_NAME
    override val systemLanguage = "en"
    override val reducedMotion = true
    override val foreground = MutableStateFlow(true)
    override val incomingPairing = MutableStateFlow<String?>(null)
    private val preferences = mutableMapOf(
        "device_id" to "regression-owner", "server" to "https://regression.example",
        "preferences.v1" to """{"language":"en","theme":"light","wallpaper":false,"haptics":false,"answerHaptics":false}""",
    )
    private val secrets = mutableMapOf("device_token" to REGRESSION_TOKEN)
    private val ids = AtomicInteger()
    val installed = CopyOnWriteArrayList<PickedFile>()
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
    override fun sha256(bytes: ByteArray) = fixtureDigest(bytes)
    override fun installPackage(file: PickedFile, onResult: (Boolean) -> Unit) { installed += file; onResult(true) }
    override fun requestNotifications(onResult: (Boolean) -> Unit) { onResult(false) }
    override fun notifyReply(title: String, body: String, conversationId: String) = Unit
    override fun nowMillis() = System.currentTimeMillis()
    override fun newId() = "regression-${ids.incrementAndGet()}"
}

private const val REGRESSION_TIMEOUT = 10_000L
private const val REGRESSION_TOKEN = "synthetic-streaming-updates-token"
private const val REGRESSION_CHAT_TITLE = "Streaming regression conversation"
private const val SNAPSHOT_TEXT = "First bubble.  \n\nSecond par "
private const val INSTALLED_VERSION_NAME = "0.4.8"
private const val INSTALLED_VERSION_CODE = 15
private const val RELEASE_VERSION = "0.4.9"
private const val RELEASE_CHANGE = "Keep live replies in separate bubbles."
private const val NO_RELEASE = """{"steer":false,"queue":true,"event_replay":true,"update":{"available":false}}"""
private const val CURRENT_RELEASE = """{"update":{"available":false,"version_name":"0.4.8","version_code":15}}"""
private val UPDATE_BYTES = "Synthetic package bytes; no operating-system installer is invoked.".encodeToByteArray()

private fun fixtureDigest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }

private fun availableRelease() = buildJsonObject {
    put("update", buildJsonObject {
        put("available", true); put("version_name", RELEASE_VERSION); put("version_code", 16)
        put("changelog", buildJsonArray { add(RELEASE_CHANGE) })
        put("url", "https://regression.example/updates/fixture.apk")
        put("sha256", fixtureDigest(UPDATE_BYTES)); put("mandatory", false)
    })
}.toString()
