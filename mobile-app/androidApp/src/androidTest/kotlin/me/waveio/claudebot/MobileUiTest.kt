package me.waveio.claudebot

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
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
    @get:Rule val testName = org.junit.rules.TestName()
    private val bridge = SilentUiBridge()
    private val clients = CopyOnWriteArrayList<HttpClient>()
    private val sent = CopyOnWriteArrayList<JsonObject>()
    private val paired = CopyOnWriteArrayList<JsonObject>()
    @Volatile private var rejectPairing = false
    @Volatile private var rejectStartup = false
    @Volatile private var answered = false
    @Volatile private var sessionId = "conversation"
    @Volatile private var fileText = "# Shared workspace\nA note from the computer."
    private lateinit var controller: AppController
    private val fixtureScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val completeStream = CompletableDeferred<Unit>()
    @Volatile private var slowStream = false
    @Volatile private var tableStream = false
    @Volatile private var streamChannel: ByteChannel? = null
    @Volatile private var slowCatalog = false
    private val catalogReady = CompletableDeferred<Unit>()

    @After fun cleanup() { if (::controller.isInitialized) controller.close(); clients.forEach { it.close() }; fixtureScope.cancel() }

    private fun launch(language: String = "en", motion: Boolean = false, theme: String = "system", alternateModelLabel: String = "GPT") {
        bridge.reducedMotion = !motion
        compose.activityRule.scenario.onActivity { it.enableEdgeToEdge(); it.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) }
        bridge.preferences["preferences.v1"] = """{"language":"$language","theme":"$theme","haptics":false}"""
        compose.setContent {
            App(bridge, onSystemBarAppearance = compose.activity::systemBarAppearance) { platform -> AppController(platform, makeApi = { server, token ->
                val client = HttpClient(MockEngine { request ->
                    val path = request.url.encodedPath
                    val json = when {
                        path == "/api/mobile/pair/exchange" -> {
                            assertNull(request.headers[HttpHeaders.Authorization])
                            paired += Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                            if (rejectPairing) return@MockEngine respond("""{"detail":"invalid_pairing"}""",
                                HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "application/json"))
                            """{"token":"test-device-token","device_id":"test-device","expires_at":1999999999}"""
                        }
                        path == "/api/mobile/capabilities" -> {
                            if (rejectStartup) return@MockEngine respond("""{"detail":"unavailable"}""",
                                HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.ContentType, "application/json"))
                            """{"steer":false,"queue":true,"event_replay":true}"""
                        }
                        path == "/api/brain/models" -> {
                            if (slowCatalog) catalogReady.await()
                            """{"models":[{"id":"fixture/claude","label":"Claude Sonnet","provider":"fixture","brand":"anthropic","vision":true,"efforts":["none","low","high"]},{"id":"fixture/gpt","label":"$alternateModelLabel","provider":"fixture","brand":"openai","efforts":["none","high"]}],"selected":"fixture/claude"}"""
                        }
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
                                streamChannel = body
                                fixtureScope.launch {
                                    try {
                                        val initial = if (tableStream) "| Step | Details |\n| --- | --- |\n" else "Already arriving"
                                        val data = buildJsonObject { put("chunk", initial) }
                                        body.writeStringUtf8("id: 1\nevent: mobile_state\ndata: {\"state\":\"running\"}\n\nid: 2\nevent: delta\ndata: $data\n\n")
                                        completeStream.await()
                                        answered = true
                                        val finalId = if (tableStream) 1000 else 3
                                        body.writeStringUtf8("id: $finalId\nevent: done\ndata: {\"reply\":\"The full response\"}\n\nid: ${finalId + 1}\nevent: mobile_state\ndata: {\"state\":\"completed\"}\n\n")
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
                        path in setOf("/api/mobile/workspace/file", "/api/workspace/file") && request.method == HttpMethod.Get -> buildJsonObject { put("path", "notes.md"); put("content", fileText); put("binary", false); put("revision", "before") }.toString()
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
        try {
            compose.waitUntil(10000) { compose.onAllNodesWithText(label, substring = true).fetchSemanticsNodes().isNotEmpty() }
        } catch (error: Throwable) {
            screenshot("failure-${testName.methodName}")
            throw error
        }
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        // The Android IME/window animation runs outside the Compose test clock.
        android.os.SystemClock.sleep(300)
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val scenario = InstrumentationRegistry.getArguments().getString("screenshotDir", "default")!!
        require(scenario.matches(Regex("[a-zA-Z0-9_-]+")))
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-qa/$scenario").apply { mkdirs() }
        val path = File(directory, "$name.png")
        path.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun back() { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK) }

    @Test fun connectionCodePairsWithoutCameraAndDoesNotPersistCode() {
        launch()
        waitFor("Enter connection code")
        compose.onNodeWithText("Enter connection code").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Connection code").performTextInput("abcd-efgh")
        compose.onNodeWithContentDescription("Your server").performTextReplacement("https://typed.example/api/")
        screenshot("connection-code-filled")
        compose.onNodeWithText("Connect", substring = false).performScrollTo().performClick()
        waitFor("Claude Sonnet")
        assertEquals("ABCDEFGH", paired.single().getValue("code").jsonPrimitive.content)
        assertEquals("https://typed.example", bridge.preferences["server"])
        assertEquals("", controller.state.value.pairingCode)
        assertFalse(bridge.preferences.values.any { it.contains("ABCD", ignoreCase = true) })
    }

    @Test fun connectionCodeRejectionStaysVisibleAndAllowsRetry() {
        rejectPairing = true
        launch(theme = "light")
        waitFor("Enter connection code")
        compose.onNodeWithText("Enter connection code").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Connection code").performTextInput("ABCD-EFGH")
        compose.onNodeWithText("Connect", substring = false).performScrollTo().performClick()
        val en = runBlocking { LocaleText.load("en") }
        waitFor(en.get("connect.invalidCode"))
        compose.mainClock.advanceTimeBy(3000)
        compose.onNodeWithText(en.get("connect.invalidCode")).performScrollTo().assertIsDisplayed()
        assertEquals("ABCD-EFGH", controller.state.value.pairingCode)
        screenshot("connection-code-error")
        rejectPairing = false
        compose.onNodeWithText("Connect", substring = false).performScrollTo().performClick()
        waitFor("Claude Sonnet")
        assertEquals(2, paired.size)
    }

    @Test fun ukrainianCodeFormCancelsBackToWorkingQr() {
        launch("uk")
        val uk = runBlocking { LocaleText.load("uk") }
        waitFor(uk.get("connect.enterCode"))
        compose.onNodeWithText(uk.get("connect.enterCode")).performScrollTo().performClick()
        compose.onNodeWithContentDescription(uk.get("connect.codeLabel")).performTextInput("ABCD-EFGH")
        screenshot("connection-code-uk")
        compose.onNodeWithText(uk.get("connect.cancel")).performScrollTo().performClick()
        assertEquals("", controller.state.value.pairingCode)
        assertTrue(paired.isEmpty())
        compose.onNodeWithText(uk.get("connect.scan")).performScrollTo().performClick()
        waitFor("Claude Sonnet")
        assertEquals("test-pairing", paired.single().getValue("code").jsonPrimitive.content)
    }

    @Test fun pairedChatModelDrawerFilesAndAppearance() {
        launch()
        waitFor("Scan QR code")
        compose.onNodeWithText("Scan QR code").performClick()
        waitFor("Claude Sonnet")
        screenshot("mobile-new-chat")
        compose.onNodeWithText("Claude Sonnet").performClick()
        waitFor("Effort")
        compose.onAllNodesWithContentDescription("Supports images").assertCountEquals(0)
        compose.onNodeWithText("Effort").performClick()
        compose.onNodeWithText("High").performClick()
        screenshot("mobile-model-picker")
        compose.onNodeWithText("Done").performClick()
        try {
            compose.onNode(hasSetTextAction()).performTextInput("Find the project notes")
            compose.runOnIdle {
                assertEquals("The composer must accept input immediately after closing the picker",
                    "Find the project notes", controller.state.value.draft)
            }
            // Native IME motion runs outside the Compose clock; let the Send target
            // settle before injecting a physical tap at its window coordinates.
            android.os.SystemClock.sleep(300)
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals("Native IME startup must preserve the accepted draft",
                    "Find the project notes", controller.state.value.draft)
            }
            compose.onNodeWithContentDescription("Send").assertIsEnabled().performClick()
            compose.waitUntil(10000) { sent.isNotEmpty() }
            assertEquals("One Send tap must submit exactly one fixture request", 1, sent.size)
            waitFor("They are in your shared workspace.")
        } catch (failure: Throwable) {
            val state = controller.state.value
            val diagnostic = "Paired flow: screen=${state.screen}, connected=${state.connected}, " +
                "session=${state.sessionId}, loading=${state.loading}, busy=${state.busy}, " +
                "picker=${state.modelPickerOpen}, menu=${state.menuOpen}, " +
                "draft=${JsonPrimitive(state.draft)}, sent=${sent.size}, answered=$answered, " +
                "messages=${state.messages.map { Triple(it.id, it.role, it.text.length) }}, " +
                "pending=${state.pending.map { it.id to it.state }}, error=${state.error}"
            println(diagnostic)
            runCatching { screenshot("failure-${testName.methodName}") }
            throw AssertionError(diagnostic, failure)
        }
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
        compose.waitUntil(10000) {
            controller.state.value.previewPath == "notes.md" &&
                controller.state.value.previewEditable && !controller.state.value.loading
        }
        compose.onNodeWithTag("media-preview").assertIsDisplayed()
        val editFileLabel = runBlocking { LocaleText.load("en") }.get("files.edit")
        compose.onNodeWithContentDescription(editFileLabel).assertIsEnabled().performClick()
        compose.waitUntil(10000) {
            controller.state.value.openFile == "notes.md" && controller.state.value.fileEditable &&
                !controller.state.value.fileLoading && controller.state.value.previewTitle == null
        }
        compose.onNodeWithTag("workspace-file-editor").assertIsDisplayed()
        compose.onNodeWithTag("media-preview").assertDoesNotExist()

        // Native dialogs have separate view trees; poll the real bundled editor
        // rather than accidentally typing into the Compose chat composer.
        fun findWebView(view: android.view.View): android.webkit.WebView? {
            if (view is android.webkit.WebView && view.isAttachedToWindow && view.isShown) return view
            if (view is android.view.ViewGroup) for (index in 0 until view.childCount) {
                findWebView(view.getChildAt(index))?.let { return it }
            }
            return null
        }
        fun editorJavascript(source: String): String? {
            val result = java.util.concurrent.atomic.AtomicReference<String?>()
            val completed = java.util.concurrent.CountDownLatch(1)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val web = android.view.inspector.WindowInspector.getGlobalWindowViews()
                    .firstNotNullOfOrNull(::findWebView)
                if (web == null) completed.countDown()
                else web.evaluateJavascript(source) { value -> result.set(value); completed.countDown() }
            }
            assertTrue("The bundled file editor must answer without blocking the UI thread",
                completed.await(3, java.util.concurrent.TimeUnit.SECONDS))
            return result.get()
        }
        compose.waitUntil(20000) {
            editorJavascript("""Boolean(window.BlinkWorkspace && document.querySelector('[data-testid="rich-editor"] [contenteditable="true"] h1')?.textContent === 'Shared workspace')""") == "true"
        }
        assertEquals("The file edit must use Tiptap's DOM input path", "true", editorJavascript("""
            (() => {
                const editable = document.querySelector('[data-testid="rich-editor"] [contenteditable="true"]');
                const heading = editable.querySelector('h1');
                editable.focus();
                const range = document.createRange(); range.selectNodeContents(heading);
                const selection = getSelection(); selection.removeAllRanges(); selection.addRange(range);
                return document.execCommand('insertText', false, 'Updated on the phone');
            })()
        """.trimIndent()))
        compose.waitUntil(10000) {
            fileText.startsWith("# Updated on the phone") &&
                fileText.contains("A note from the computer.") && controller.state.value.fileSaveState == "saved"
        }
        assertEquals("Autosave must persist the content produced by the bundled editor",
            fileText, controller.state.value.fileText)
        waitFor("Saved")
        screenshot("mobile-file-editor")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithText("Profile").performClick()
        compose.onNodeWithText("Appearance").performClick()
        compose.onNodeWithText("Light", substring = false).performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithText("A shared conversation").performClick()
        waitFor("They are in your shared workspace.")
        screenshot("mobile-chat-light")
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.onNodeWithText("Profile").performClick()
        compose.onNodeWithText("Appearance").performClick()
        compose.onNodeWithText("Dark", substring = false).performClick()
        compose.runOnIdle {
            assertFalse(androidx.core.view.WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView).isAppearanceLightStatusBars)
        }
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
        // Android animates the IME outside Compose's clock. Wait for the real
        // tap target to settle before asserting streamed provider progress.
        var previousBounds = compose.onNodeWithContentDescription("Send").fetchSemanticsNode().boundsInRoot
        var stableSince = android.os.SystemClock.uptimeMillis()
        compose.waitUntil(5000) {
            val bounds = compose.onNodeWithContentDescription("Send").fetchSemanticsNode().boundsInRoot
            val now = android.os.SystemClock.uptimeMillis()
            if (bounds != previousBounds) { previousBounds = bounds; stableSince = now }
            now - stableSince >= 300
        }
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

    @Test fun growingStreamedTableRecordsFrameCostsBeforeCompletion() {
        slowStream = true; tableStream = true
        launch(motion = true)
        waitFor("Scan QR code"); compose.onNodeWithText("Scan QR code").performClick()
        waitFor("Claude Sonnet")
        compose.onNode(hasSetTextAction()).performTextInput("Show a growing table")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.waitUntil(10000) { streamChannel != null }
        val frames = androidx.core.app.FrameMetricsAggregator(androidx.core.app.FrameMetricsAggregator.TOTAL_DURATION)
        compose.activityRule.scenario.onActivity { frames.add(it) }
        val halfway = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val producer = fixtureScope.launch {
            repeat(60) { index ->
                val data = buildJsonObject { put("chunk", "| Row $index | A complete explanation for row $index that stays readable while more table rows arrive. |\n") }
                streamChannel!!.writeStringUtf8("id: ${index + 3}\nevent: delta\ndata: $data\n\n")
                if (index == 15) { halfway.complete(Unit); resume.await() }
                delay(40)
            }
        }
        compose.waitUntil(10000) { halfway.isCompleted }
        waitFor("Row 15")
        compose.onNodeWithText("Row 15", substring = true).assertIsDisplayed()
        assertFalse(completeStream.isCompleted)
        compose.onNodeWithContentDescription("Stop").assertIsDisplayed()
        screenshot("mobile-streaming-table-halfway")
        resume.complete(Unit)
        compose.waitUntil(15000) { producer.isCompleted }
        assertTrue(controller.state.value.messages.last().text.contains("Row 59"))
        assertFalse(completeStream.isCompleted)
        screenshot("mobile-streaming-table-final-frame")
        val histogram = frames.remove(compose.activity)?.get(0)
        val samples = buildList { if (histogram != null) for (i in 0 until histogram.size()) repeat(histogram.valueAt(i)) { add(histogram.keyAt(i)) } }.sorted()
        assertTrue(samples.isNotEmpty())
        val summary = "{\"frames\":${samples.size},\"medianMs\":${samples[samples.size / 2]},\"p95Ms\":${samples[(samples.lastIndex * .95).toInt()]},\"maximumMs\":${samples.last()}}"
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "stream-frame-metrics.json").writeText(summary)
        completeStream.complete(Unit)
        waitFor("They are in your shared workspace.")
    }

    private fun assertTextFits(node: SemanticsNodeInteraction) {
        node.assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Text layout must be available", layouts.isNotEmpty())
        layouts.forEach { result ->
            // Semantics can retain the paragraph's loose width after Text wraps
            // its measured bounds. Check glyph lines, not that paragraph width.
            assertFalse("Truncated text: ${result.layoutInput.text}", result.multiParagraph.didExceedMaxLines)
            for (line in 0 until result.lineCount) {
                assertTrue("Clipped line: ${result.layoutInput.text}", result.getLineRight(line) - result.getLineLeft(line) <= result.size.width + 1f)
                assertTrue("Clipped height: ${result.layoutInput.text}", result.getLineBottom(line) <= result.size.height + 1f)
            }
        }
    }

    @Test fun largeTextModelSearchAndEffortRemainReachable() {
        launch(theme = "dark")
        waitFor("Scan QR code"); compose.onNodeWithText("Scan QR code").performScrollTo().performClick()
        waitFor("Claude Sonnet")
        compose.onNodeWithText("Claude Sonnet").performClick()
        screenshot("pixel-model-large")
        compose.onNodeWithContentDescription("Find a model").performClick().performTextInput("GPT")
        screenshot("pixel-model-keyboard-large")
        assertTextFits(compose.onNodeWithText("fixture", useUnmergedTree = true))
        compose.onAllNodesWithText("GPT", substring = false).onLast().assertIsDisplayed().performClick()
        waitFor("Effort")
        screenshot("pixel-effort-large")
        compose.onNodeWithText("High").performScrollTo().performClick()
        assertTextFits(compose.onNodeWithText("Done"))
        compose.onNodeWithText("Done").performClick()
        assertEquals("high", controller.state.value.effort)
    }

    @Test fun largeTextCalendarNumbersAndActionsRemainReadable() {
        launch(theme = "light")
        waitFor("Scan QR code"); compose.onNodeWithText("Scan QR code").performScrollTo().performClick()
        waitFor("Claude Sonnet")
        compose.onNode(hasSetTextAction()).performTextInput("Send this later")
        compose.onNodeWithContentDescription("Attach").performClick()
        compose.onNodeWithText("Send later").performScrollTo().performClick()
        waitFor("Schedule message")
        compose.onNodeWithText("Hours").performScrollTo()
        screenshot("pixel-calendar-large")
        val twoDigits = SemanticsMatcher("two-digit time or date") { node ->
            node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text.matches(Regex("\\d{2}")) }
        }
        val numbers = compose.onAllNodes(twoDigits, useUnmergedTree = true)
        assertTrue(numbers.fetchSemanticsNodes().isNotEmpty())
        for (index in numbers.fetchSemanticsNodes().indices) {
            numbers[index].performScrollTo()
            assertTextFits(numbers[index])
            val layouts = mutableListOf<TextLayoutResult>()
            numbers[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("Calendar and time numbers must not wrap", layouts.all { it.lineCount == 1 })
        }
        val dates = compose.onAllNodes(SemanticsMatcher("ISO date") { node ->
            node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.any { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
        }.and(isEnabled()))
        for (index in dates.fetchSemanticsNodes().indices) {
            dates[index].performScrollTo().performClick().assertIsSelected()
        }
        compose.onNodeWithContentDescription("Increase Minutes").performScrollTo()
        screenshot("pixel-calendar-time-large")
        compose.onAllNodesWithText("Schedule message").onLast().performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(10000) { sent.isNotEmpty() }
    }

    @Test fun largeUkrainianAttachmentMenuKeepsAllActionsReachable() {
        val uk = runBlocking { LocaleText.load("uk") }
        launch("uk", theme = "dark")
        waitFor(uk.get("connect.scan")); compose.onNodeWithText(uk.get("connect.scan")).performScrollTo().performClick()
        waitFor("Claude Sonnet")
        compose.onNodeWithContentDescription(uk.get("input.attach")).performClick()
        screenshot("pixel-attachments-large-uk")
        listOf("input.camera", "input.photo", "input.document", "input.skills", "files.root", "queue.later").forEach { key ->
            compose.onNodeWithText(uk.get(key)).performScrollTo()
            assertTextFits(compose.onNode(hasText(uk.get(key)).and(hasAnyAncestor(isPopup())), useUnmergedTree = true))
        }
        compose.onNodeWithText(uk.get("input.photo")).performScrollTo().performClick()
        assertEquals("photo", bridge.lastPicker)
    }

    @Test fun catalogLoadingAndEmptySearchHaveVisibleFeedback() {
        slowCatalog = true
        val en = runBlocking { LocaleText.load("en") }
        launch(theme = "dark")
        waitFor("Scan QR code"); compose.onNodeWithText("Scan QR code").performScrollTo().performClick()
        waitFor(en.get("startup.title"))
        compose.onNodeWithText(en.get("startup.body")).assertIsDisplayed()
        compose.onNodeWithText("Scan QR code").assertDoesNotExist()
        compose.onNodeWithContentDescription("Find a model").assertDoesNotExist()
        screenshot("pixel-startup-loading")
        catalogReady.complete(Unit)
        waitFor("Claude Sonnet")
        compose.onNodeWithText(en.get("startup.title")).assertDoesNotExist()
        compose.onNodeWithText("Claude Sonnet").performClick()
        compose.onNodeWithContentDescription("Find a model").performClick().performTextInput("missing-model")
        compose.onNodeWithText(en.get("model.empty")).assertIsDisplayed()
        screenshot("pixel-model-empty")
    }

    @Test fun savedConnectionLoadsWithoutFlashingQrAndPreloadsHistory() {
        slowCatalog = true
        bridge.writeSecret("device_token", "test-device-token")
        bridge.preferences["server"] = "https://mobile-test.example"
        val en = runBlocking { LocaleText.load("en") }
        launch(theme = "dark")
        waitFor(en.get("startup.title"))
        compose.onNodeWithText(en.get("startup.body")).assertIsDisplayed()
        compose.onNodeWithText(en.get("connect.scan")).assertDoesNotExist()
        screenshot("pixel-restored-startup")
        catalogReady.complete(Unit)
        waitFor("Claude Sonnet")
        compose.onNodeWithContentDescription(en.get("nav.menu")).performClick()
        waitFor("A shared conversation")
        compose.onNodeWithText(en.get("connect.scan")).assertDoesNotExist()
        assertTrue(paired.isEmpty())
    }

    @Test fun failedStartupOffersLocalizedRetryWithoutAnotherQr() {
        rejectStartup = true
        bridge.writeSecret("device_token", "test-device-token")
        bridge.preferences["server"] = "https://mobile-test.example"
        val uk = runBlocking { LocaleText.load("uk") }
        launch(language = "uk", theme = "light")
        waitFor(uk.get("startup.failed"))
        compose.onNodeWithText(uk.get("error.service")).assertIsDisplayed()
        compose.onNodeWithText(uk.get("connect.scan")).assertDoesNotExist()
        screenshot("pixel-startup-retry-uk")
        compose.runOnIdle { controller.dismissNotice(); rejectStartup = false }
        compose.onNodeWithText(uk.get("action.retry")).performScrollTo().performClick()
        waitFor("Claude Sonnet")
        assertTrue(paired.isEmpty())
        assertEquals("test-device-token", bridge.readSecret("device_token"))
    }

    @Test fun longModelNameKeepsPickerAndEffortActionsAvailable() {
        val label = "GPT Professional Extended Context Preview"
        launch(theme = "light", alternateModelLabel = label)
        waitFor("Scan QR code"); compose.onNodeWithText("Scan QR code").performScrollTo().performClick()
        waitFor("Claude Sonnet")
        compose.onNodeWithText("Claude Sonnet").performClick()
        compose.onNodeWithContentDescription("Find a model").performClick().performTextInput("Extended")
        compose.onNodeWithText(label).assertIsDisplayed().performClick()
        compose.onNodeWithText("High").performScrollTo().performClick()
        compose.onNodeWithText("Done").assertIsDisplayed()
        screenshot("pixel-long-model-effort")
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText(label).assertIsDisplayed()
        compose.onNodeWithContentDescription("New chat").assertIsDisplayed()
        compose.onNodeWithContentDescription("Menu").assertIsDisplayed()
        screenshot("pixel-long-model-header")
    }

    @Test fun ukrainianConnectionAndWallpaperActionsFitLargeText() {
        val uk = runBlocking { LocaleText.load("uk") }
        launch("uk", theme = "light")
        waitFor(uk.get("connect.scan"))
        compose.onNodeWithText(uk.get("connect.scan")).performScrollTo()
        assertTextFits(compose.onNodeWithText(uk.get("connect.scan")))
        screenshot("pixel-connection-large-uk")
        compose.onNodeWithText(uk.get("connect.scan")).performClick()
        waitFor("Claude Sonnet")
        compose.onNodeWithContentDescription(uk.get("nav.menu")).performClick()
        compose.onNodeWithText(uk.get("nav.profile")).performClick()
        compose.onNodeWithText(uk.get("profile.appearance")).performClick()
        compose.runOnIdle {
            assertTrue(androidx.core.view.WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView).isAppearanceLightStatusBars)
        }
        compose.onNodeWithText(uk.get("appearance.change")).performScrollTo()
        assertTextFits(compose.onNodeWithText(uk.get("appearance.change")))
        compose.onNodeWithText(uk.get("appearance.reset")).performScrollTo()
        screenshot("pixel-appearance-large-uk")
        assertTextFits(compose.onNodeWithText(uk.get("appearance.reset")))
        compose.onNodeWithText(uk.get("appearance.change")).performClick()
        assertEquals("wallpaper", bridge.lastPicker)
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
