package me.waveio.claudebot

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.platform.app.InstrumentationRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Real App/controller/transport; the strict host and silent native services are fixtures. */
class ReplyImageRenderingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val bridge = ReplyImageBridge()
    private val server = ReplyImageHost()
    private lateinit var controller: AppController
    private lateinit var strings: LocaleText

    @After fun cleanup() {
        try {
            if (::controller.isInitialized) controller.close()
        } finally {
            server.close()
        }
        assertTrue("Unexpected fixture requests: ${server.unexpected}", server.unexpected.isEmpty())
        assertTrue("Inline images must never launch an external browser", bridge.openedLinks.isEmpty())
    }

    private fun launch(language: String = "en") {
        strings = runBlocking { LocaleText.load(language) }
        bridge.preferences["preferences.v1"] = buildJsonObject {
            put("language", language); put("theme", "light"); put("wallpaper", false)
            put("haptics", false); put("answerHaptics", false)
        }.toString()
        compose.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        compose.setContent {
            App(bridge) { platform ->
                AppController(platform, makeApi = server::makeApi).also { controller = it }
            }
        }
        waitFor(hasText(strings.get("connect.scan")))
        compose.onNodeWithText(strings.get("connect.scan")).performScrollTo().performClick()
        waitFor(hasText(IMAGE_MODEL_LABEL))
        compose.waitUntil(IMAGE_TIMEOUT) { controller.state.value.conversations.isNotEmpty() }
        compose.onNodeWithContentDescription(strings.get("nav.menu")).performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(IMAGE_CHAT_TITLE))
        compose.onNodeWithText(IMAGE_CHAT_TITLE).performClick()
        compose.waitUntil(IMAGE_TIMEOUT) {
            controller.state.value.sessionId == IMAGE_CHAT_ID && !controller.state.value.loading
        }
    }

    private fun waitFor(matcher: SemanticsMatcher) {
        compose.waitUntil(IMAGE_TIMEOUT) {
            compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun imageMatcher(description: String): SemanticsMatcher {
        val (source, path) = when (description) {
            "Portrait" -> "remote" to PORTRAIT_URL
            "raw.png" -> "remote" to RAW_IMAGE_URL
            "Extensionless image" -> "remote" to EXTENSIONLESS_IMAGE_URL
            "Owned upload" -> "upload" to OWNED_IMAGE_PATH
            "Retry portrait" -> "remote" to RETRY_IMAGE_URL
            "Streaming portrait" -> "remote" to STREAM_IMAGE_URL
            else -> error("Unknown fixture image: $description")
        }
        return hasTestTag("reply-image:$source:$path").and(hasContentDescription(description))
    }

    private fun imageNode(description: String) = compose.onNode(imageMatcher(description), useUnmergedTree = true)

    private fun awaitImage(description: String) {
        waitFor(imageMatcher(description))
        imageNode(description).performScrollTo().assertIsDisplayed()
    }

    private fun readEarlierReplyContent() {
        val history = compose.onNodeWithTag("chat-history")
        val bounds = history.fetchSemanticsNode().boundsInRoot
        val top = compose.onNodeWithTag("chat-header").fetchSemanticsNode().boundsInRoot.bottom - bounds.top
        val bottom = compose.onNodeWithTag("chat-composer").fetchSemanticsNode().boundsInRoot.top - bounds.top
        // Only a real finger drag establishes reading intent. Semantics scrolling
        // alone competes with tail-following on this single, very tall reply.
        history.performTouchInput {
            swipe(Offset(centerX, top + (bottom - top) * .2f),
                Offset(centerX, top + (bottom - top) * .8f), durationMillis = 600)
        }
        waitFor(hasContentDescription(strings.get("chat.latest")))
    }

    private fun assertCaption(caption: String) {
        waitFor(hasText(caption))
        compose.onNodeWithText(caption, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        // Native dialog fades settle outside the Compose test clock.
        SystemClock.sleep(300)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)),
            "ui-qa/inline-reply-images").apply { check(isDirectory || mkdirs()) }
        val path = File(directory, "$name.png")
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            path.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        Log.i("ReplyImageRendering", "Screenshot: ${path.absolutePath}")
    }

    @Test fun markdownPortraitRendersInReplyAndExistingPreviewSavesExactBytes() {
        val portrait = replyImageFile("portrait.jpg", jpeg = true)
        server.images[PORTRAIT_URL] = portrait
        server.history = imageHistory("A portrait for your collection.\n\n![Portrait]($PORTRAIT_URL)\n\nThe caption stays below the portrait.")
        launch()

        // The first bitmap must appear with no image/link click or attachment manifest.
        awaitImage("Portrait")
        screenshot("portrait-inline")
        assertEquals(listOf(PORTRAIT_URL), server.imageRequests.toList())
        compose.onNodeWithText(strings.get("chat.openLink")).assertDoesNotExist()
        compose.onNodeWithTag("media-preview").assertDoesNotExist()
        val bounds = imageNode("Portrait").getUnclippedBoundsInRoot()
        assertTrue("An inline portrait must not become a landscape thumbnail", bounds.height.value > bounds.width.value)
        assertEquals("The image must preserve its 2:3 portrait ratio", 2f / 3f, bounds.width.value / bounds.height.value, .025f)
        val pixels = imageNode("Portrait").captureToImage().toPixelMap()
        val center = pixels[pixels.width / 2, pixels.height / 2]
        assertTrue("The decoded fixture, not an empty image placeholder, must be painted", center.green > center.red + .25f && center.green > center.blue + .15f)
        assertCaption("A portrait for your collection.")
        assertCaption("The caption stays below the portrait.")

        imageNode("Portrait").performScrollTo().performClick()
        compose.waitUntil(IMAGE_TIMEOUT) {
            controller.state.value.previewPath == PORTRAIT_URL && !controller.state.value.loading &&
                compose.onAllNodesWithTag("media-image:$PORTRAIT_URL").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("media-preview").assertIsDisplayed()
        compose.onNodeWithTag("media-image:$PORTRAIT_URL").assertIsDisplayed()
        assertArrayEquals(portrait.bytes, controller.state.value.previewBytes)
        screenshot("portrait-preview")
        compose.onNodeWithText(strings.get("media.save")).assertIsEnabled().performClick()
        compose.waitUntil(IMAGE_TIMEOUT) { bridge.savedFiles.size == 1 && !controller.state.value.previewExporting }
        assertArrayEquals("Save must hand the native bridge the original server bytes", portrait.bytes, bridge.savedFiles.single().bytes)
        assertEquals(portrait.mimeType, bridge.savedFiles.single().mimeType)
        assertTrue("Preview and Save must use the same proxied source", server.imageRequests.all { it == PORTRAIT_URL })
        compose.onNode(hasContentDescription(strings.get("action.close"))
            .and(hasAnyAncestor(hasTestTag("media-preview")))).performClick()
        assertCaption("The caption stays below the portrait.")
    }

    @Test fun bareExtensionlessAndOwnedUploadImagesUseTheirAuthorizedRoutes() {
        server.images[RAW_IMAGE_URL] = replyImageFile("raw.png")
        server.images[EXTENSIONLESS_IMAGE_URL] = replyImageFile("render.png")
        server.images[OWNED_IMAGE_PATH] = replyImageFile("x.png")
        server.history = imageHistory(
            "$RAW_IMAGE_URL\n\n![Extensionless image]($EXTENSIONLESS_IMAGE_URL)\n\n![Owned upload]($OWNED_IMAGE_PATH)\n\nAll three images keep this caption.",
        )
        launch()

        listOf("raw.png", "Extensionless image", "Owned upload").forEach { waitFor(imageMatcher(it)) }
        readEarlierReplyContent()
        awaitImage("raw.png")
        awaitImage("Extensionless image")
        awaitImage("Owned upload")
        assertCaption("All three images keep this caption.")
        compose.onNodeWithText(strings.get("chat.openLink")).assertDoesNotExist()
        assertEquals(setOf(RAW_IMAGE_URL, EXTENSIONLESS_IMAGE_URL, OWNED_IMAGE_PATH), server.imageRequests.toSet())
        assertEquals("One initial load per source is sufficient", 3, server.imageRequests.size)
        assertEquals(listOf(OWNED_IMAGE_PATH), server.uploadRequests.toList())
        assertEquals(setOf(RAW_IMAGE_URL, EXTENSIONLESS_IMAGE_URL), server.proxyRequests.toSet())
        assertTrue("Restored bot text must not invent user attachment manifests", controller.state.value.messages.all { it.attachments.isEmpty() })
    }

    @Test fun failedImageOffersLocalizedRetryAndRetainsCaptionOnSuccess() {
        server.images[RETRY_IMAGE_URL] = replyImageFile("retry.png")
        server.brokenImage = RETRY_IMAGE_URL
        server.history = imageHistory("![Retry portrait]($RETRY_IMAGE_URL)\n\nThe explanation survives a failed download.")
        launch(language = "uk")

        val failure = strings.get("media.imageFailed")
        val retry = strings.get("action.retry")
        val english = runBlocking { LocaleText.load("en") }
        assertNotEquals("The image failure must have a translated locale entry", english.get("media.imageFailed"), failure)
        assertNotEquals(english.get("action.retry"), retry)
        waitFor(hasText(failure))
        compose.onNodeWithText(failure, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(retry).performScrollTo().assertIsDisplayed()
        imageNode("Retry portrait").assertDoesNotExist()
        assertEquals("Failures must wait for the user's explicit retry", listOf(RETRY_IMAGE_URL), server.imageRequests.toList())
        assertCaption("The explanation survives a failed download.")
        compose.onNodeWithText(retry).performScrollTo().performClick()

        // A nonempty response that cannot decode must be retryable too; leaving
        // its bytes cached would make the second Retry silently skip the fetch.
        compose.waitUntil(IMAGE_TIMEOUT) {
            val state = controller.state.value
            server.imageRequests.size == 2 && (RETRY_IMAGE_URL in state.imageFailures ||
                state.attachmentThumbnails[RETRY_IMAGE_URL]?.contentEquals(CORRUPT_IMAGE_BYTES) == true)
        }
        waitFor(hasText(failure))
        compose.onNodeWithText(retry).performScrollTo().performClick()
        awaitImage("Retry portrait")
        compose.onNodeWithText(failure).assertDoesNotExist()
        compose.onNodeWithText(retry).assertDoesNotExist()
        assertCaption("The explanation survives a failed download.")
        assertEquals(List(3) { RETRY_IMAGE_URL }, server.imageRequests.toList())
    }

    @Test fun incompleteStreamedUrlWaitsForClosingMarkdownThenRendersBeforeDone() {
        server.images[STREAM_IMAGE_URL] = replyImageFile("streamed.png")
        server.history = JsonArray(emptyList())
        server.streaming = true
        launch()
        compose.onNode(hasSetTextAction()).performTextInput(IMAGE_PROMPT)
        compose.onNodeWithContentDescription(strings.get("chat.send")).assertIsEnabled().performClick()
        compose.waitUntil(IMAGE_TIMEOUT) { server.submitted.size == 1 && controller.state.value.busy }

        fun assertIncomplete(snapshot: String) {
            compose.waitUntil(IMAGE_TIMEOUT) { controller.state.value.messages.any { it.live && it.text == snapshot } }
            // Parser/decoder work runs off the Compose clock. Keep the provider
            // gated while both the UI and any incorrect eager requests settle.
            val settleAt = SystemClock.uptimeMillis() + 300
            compose.waitUntil(IMAGE_TIMEOUT) { SystemClock.uptimeMillis() >= settleAt }
            compose.waitForIdle()
            imageNode("Streaming portrait").assertDoesNotExist()
            assertTrue("An incomplete image URL must never be fetched: ${server.imageRequests}", server.imageRequests.isEmpty())
            assertTrue(controller.state.value.busy)
        }
        assertIncomplete(STREAM_PARTIAL)
        server.finishUrl.complete(Unit)
        assertIncomplete(STREAM_UNCLOSED)
        server.closeMarkdown.complete(Unit)

        awaitImage("Streaming portrait")
        assertCaption(STREAM_CAPTION)
        assertEquals(listOf(STREAM_IMAGE_URL), server.imageRequests.toList())
        assertTrue("The bitmap must be visible while the controller is still streaming", controller.state.value.busy)
        assertTrue(controller.state.value.messages.any { it.live && it.text == STREAM_COMPLETE })
        assertFalse("The fixture must not release done to make image rendering pass", server.completeStream.isCompleted)
        server.completeStream.complete(Unit)
        compose.waitUntil(IMAGE_TIMEOUT) { server.completed && !controller.state.value.busy }
        awaitImage("Streaming portrait")
        assertCaption(STREAM_CAPTION)
        assertEquals(1, server.submitted.size)
    }
}

/** Reject every unlisted method, origin, credential, query, or image destination. */
private class ReplyImageHost {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clients = CopyOnWriteArrayList<HttpClient>()
    val unexpected = CopyOnWriteArrayList<String>()
    val images = linkedMapOf<String, PickedFile>()
    val imageRequests = CopyOnWriteArrayList<String>()
    val proxyRequests = CopyOnWriteArrayList<String>()
    val uploadRequests = CopyOnWriteArrayList<String>()
    val submitted = CopyOnWriteArrayList<JsonObject>()
    val finishUrl = CompletableDeferred<Unit>()
    val closeMarkdown = CompletableDeferred<Unit>()
    val completeStream = CompletableDeferred<Unit>()
    @Volatile var history = JsonArray(emptyList())
    @Volatile var brokenImage: String? = null
    @Volatile var streaming = false
    @Volatile var completed = false

    private fun requireFixture(condition: Boolean, description: String) {
        if (!condition) { unexpected += description; error(description) }
    }

    fun makeApi(origin: String, token: String): BotApi {
        requireFixture(origin == IMAGE_ORIGIN, "Unexpected configured origin: $origin")
        val client = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            val method = request.method
            requireFixture(request.url.protocol.name == "https" && request.url.host == IMAGE_HOST && request.url.port == 443 &&
                request.url.user == null && request.url.password == null, "Request escaped the paired host: ${request.url}")
            val pairing = path == "/api/mobile/pair/exchange" && method == HttpMethod.Post
            requireFixture(request.headers[HttpHeaders.Authorization] == if (pairing) null else "Bearer $IMAGE_DEVICE_TOKEN",
                "Incorrect authentication for ${method.value} $path")
            fun query(expected: Map<String, String> = emptyMap()) {
                requireFixture(request.url.parameters.names() == expected.keys && expected.all { (key, value) ->
                    request.url.parameters.getAll(key) == listOf(value)
                }, "Unexpected query for ${method.value} $path: ${request.url.parameters}")
            }
            fun body() = Json.parseToJsonElement((request.body as TextContent).text).jsonObject

            if (path == IMAGE_PROXY_PATH || path.startsWith("/uploads/")) {
                requireFixture(method == HttpMethod.Get, "Images require GET: ${method.value} $path")
                val target = if (path == IMAGE_PROXY_PATH) request.url.parameters["url"].orEmpty() else path
                imageRequests += target
                query(if (path == IMAGE_PROXY_PATH) mapOf("url" to target) else emptyMap())
                requireFixture(target in images, "Unexpected image target: $target")
                requireFixture((path == IMAGE_PROXY_PATH) == !target.startsWith('/'), "Image used the wrong route: $target via $path")
                requireFixture(!streaming || closeMarkdown.isCompleted, "An incomplete streamed image was fetched: $target")
                if (path == IMAGE_PROXY_PATH) proxyRequests += target else uploadRequests += target
                val image = images.getValue(target)
                val bytes = if (target == brokenImage) when (imageRequests.count { it == target }) {
                    1 -> ByteArray(0)
                    2 -> CORRUPT_IMAGE_BYTES
                    else -> image.bytes
                } else image.bytes
                return@MockEngine respond(bytes, headers = headersOf(HttpHeaders.ContentType, image.mimeType))
            }

            val json = when {
                pairing -> {
                    query()
                    requireFixture(body()["code"]?.jsonPrimitive?.content == "reply-image-pairing", "Unexpected pairing code")
                    """{"token":"$IMAGE_DEVICE_TOKEN","device_id":"reply-image-device","expires_at":1999999999}"""
                }
                path == "/api/mobile/capabilities" && method == HttpMethod.Get -> {
                    query(mapOf("platform" to "android", "version_code" to "0"))
                    """{"steer":false,"queue":true,"event_replay":true}"""
                }
                path == "/api/brain/models" && method == HttpMethod.Get -> {
                    query(mapOf("refresh" to "false"))
                    """{"models":[{"id":"fixture/claude","label":"$IMAGE_MODEL_LABEL","provider":"fixture","brand":"anthropic","efforts":["none"]}],"selected":"fixture/claude"}"""
                }
                path == "/api/setup" && method == HttpMethod.Get -> {
                    query()
                    """{"profile":{"name":"Claude Bot","language":"en","persona":"friendly","persona_custom":"Keep things clear.","greeting":"Hello"}}"""
                }
                path == "/api/sessions" && method == HttpMethod.Get -> {
                    query()
                    """{"sessions":[{"id":"$IMAGE_CHAT_ID","title":"$IMAGE_CHAT_TITLE"}]}"""
                }
                path == "/api/sessions/$IMAGE_CHAT_ID" && method == HttpMethod.Get -> {
                    query()
                    buildJsonObject { put("messages", history) }.toString()
                }
                path == "/api/mobile/messages" && method == HttpMethod.Get -> {
                    val session = request.url.parameters["session_id"].orEmpty()
                    requireFixture(session in setOf("", IMAGE_CHAT_ID), "Unexpected job session: $session")
                    query(mapOf("session_id" to session))
                    if (submitted.isNotEmpty() && !completed) """{"messages":[{"id":"$IMAGE_JOB_ID","session_id":"$IMAGE_CHAT_ID","state":"running","message":"$IMAGE_PROMPT"}]}"""
                    else """{"messages":[]}"""
                }
                path == "/api/mobile/messages" && method == HttpMethod.Post -> {
                    query()
                    val payload = body()
                    requireFixture(streaming && submitted.isEmpty(), "Unexpected message submission")
                    requireFixture(payload["session_id"]?.jsonPrimitive?.content == IMAGE_CHAT_ID &&
                        payload["message"]?.jsonPrimitive?.content == IMAGE_PROMPT &&
                        payload["attachments"]?.jsonArray?.isEmpty() == true, "Unexpected image-test message payload")
                    submitted += payload
                    """{"id":"$IMAGE_JOB_ID","session_id":"$IMAGE_CHAT_ID","state":"running"}"""
                }
                path == "/api/mobile/messages/$IMAGE_JOB_ID/events" && method == HttpMethod.Get -> {
                    query(mapOf("after" to "0"))
                    requireFixture(streaming && submitted.size == 1, "Unexpected image stream")
                    val channel = ByteChannel(autoFlush = true)
                    scope.launch {
                        try {
                            var sequence = 0
                            suspend fun event(name: String, data: JsonObject) {
                                channel.writeStringUtf8("id: ${++sequence}\nevent: $name\ndata: $data\n\n")
                            }
                            suspend fun snapshot(text: String) = event("reply_snapshot", buildJsonObject { put("id", "image-answer"); put("text", text) })
                            event("mobile_state", buildJsonObject { put("state", "running") })
                            snapshot(STREAM_PARTIAL)
                            finishUrl.await()
                            snapshot(STREAM_UNCLOSED)
                            closeMarkdown.await()
                            snapshot(STREAM_COMPLETE)
                            completeStream.await()
                            history = imageHistory(STREAM_COMPLETE)
                            event("done", buildJsonObject {
                                put("reply", STREAM_COMPLETE); put("parts", JsonArray(listOf(imageTextPart(STREAM_COMPLETE))))
                                put("assistant_message_id", IMAGE_REPLY_ID)
                            })
                            completed = true
                            event("mobile_state", buildJsonObject { put("state", "completed") })
                        } finally { channel.close() }
                    }
                    return@MockEngine respond(channel, headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
                }
                else -> {
                    requireFixture(false, "Unexpected route: ${method.value} $path")
                    error("Unreachable fixture route")
                }
            }
            respond(json, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        clients += client
        return BotApi(origin, token, client)
    }

    fun close() { scope.cancel(); clients.forEach { it.close() } }
}

private class ReplyImageBridge : PlatformBridge {
    override val platformName = "android"
    override val systemLanguage = "en"
    override val reducedMotion = true
    override val foreground = MutableStateFlow(true)
    override val incomingPairing = MutableStateFlow<String?>(null)
    val preferences = mutableMapOf<String, String>()
    private val secrets = mutableMapOf<String, String>()
    private val ids = AtomicInteger()
    val savedFiles = CopyOnWriteArrayList<PickedFile>()
    val openedLinks = CopyOnWriteArrayList<String>()
    override fun readPreference(key: String) = preferences[key]
    override fun writePreference(key: String, value: String?) { if (value == null) preferences.remove(key) else preferences[key] = value }
    override fun readSecret(key: String) = secrets[key]
    override fun writeSecret(key: String, value: String?) { if (value == null) secrets.remove(key) else secrets[key] = value }
    override fun scanQr(onResult: (String?) -> Unit) { onResult("claudebot://pair?server=https%3A%2F%2F$IMAGE_HOST&code=reply-image-pairing") }
    override fun pickFile(kind: String, onResult: (PickedFile?) -> Unit) { onResult(null) }
    override fun saveFile(file: PickedFile, onResult: (Boolean) -> Unit) { savedFiles += file; onResult(true) }
    override fun openExternalUrl(url: String) { openedLinks += url }
    override fun startRecording(onAmplitude: (Float) -> Unit, onResult: (PickedFile?) -> Unit, onPartial: (PickedFile) -> Unit) = Unit
    override fun stopRecording() = Unit
    override fun cancelRecording() = Unit
    override fun haptic() = Unit
    override fun copyText(value: String) = Unit
    override fun shareText(value: String) = Unit
    override fun requestNotifications(onResult: (Boolean) -> Unit) { onResult(false) }
    override fun notifyReply(title: String, body: String, conversationId: String) = Unit
    override fun nowMillis() = System.currentTimeMillis()
    override fun newId() = "reply-image-${ids.incrementAndGet()}"
}

private fun replyImageFile(name: String, jpeg: Boolean = false): PickedFile {
    val bytes = ByteArrayOutputStream().use { output ->
        val bitmap = Bitmap.createBitmap(80, 120, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.rgb(40, 200, 80))
            check(bitmap.compress(if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 95, output))
            output.toByteArray()
        } finally { bitmap.recycle() }
    }
    return PickedFile(name, if (jpeg) "image/jpeg" else "image/png", bytes)
}

private fun imageTextPart(text: String) = buildJsonObject { put("type", "text"); put("text", text) }
private fun imageHistory(text: String) = JsonArray(listOf(buildJsonObject {
    put("id", IMAGE_REPLY_ID); put("role", "assistant"); put("content", text); put("model", "fixture/claude")
    put("parts", JsonArray(listOf(imageTextPart(text))))
}))

private const val IMAGE_TIMEOUT = 10_000L
private const val IMAGE_HOST = "reply-images.example"
private const val IMAGE_ORIGIN = "https://$IMAGE_HOST"
private const val IMAGE_DEVICE_TOKEN = "synthetic-reply-image-token"
private const val IMAGE_CHAT_ID = "reply-images-chat"
private const val IMAGE_CHAT_TITLE = "Reply image conversation"
private const val IMAGE_REPLY_ID = "image-reply"
private const val IMAGE_JOB_ID = "image-job"
private const val IMAGE_MODEL_LABEL = "Claude image fixture"
private const val IMAGE_PROMPT = "Show the image as it arrives"
private const val IMAGE_PROXY_PATH = "/api/mobile/images/fetch"
private const val PORTRAIT_URL = "https://images.example/portrait.jpg"
private const val RAW_IMAGE_URL = "https://images.example/raw.png?size=full&variant=blue"
private const val EXTENSIONLESS_IMAGE_URL = "https://images.example/render?id=42&size=original"
private const val OWNED_IMAGE_PATH = "/uploads/x.png"
private const val RETRY_IMAGE_URL = "https://images.example/retry.png"
private val CORRUPT_IMAGE_BYTES = "not a decodable PNG".encodeToByteArray()
private const val STREAM_IMAGE_URL = "https://images.example/streamed.png?width=80&height=120"
private const val STREAM_PARTIAL = "Here is the requested image.\n\n![Streaming portrait](https://images.example/streamed.png?width=80"
private const val STREAM_UNCLOSED = "Here is the requested image.\n\n![Streaming portrait]($STREAM_IMAGE_URL"
private const val STREAM_CAPTION = "The streamed image keeps its caption."
private const val STREAM_COMPLETE = "$STREAM_UNCLOSED)\n\n$STREAM_CAPTION"
