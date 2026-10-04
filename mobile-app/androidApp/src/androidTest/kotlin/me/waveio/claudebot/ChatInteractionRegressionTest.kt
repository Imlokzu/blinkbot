package me.waveio.claudebot

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.io.readByteArray
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
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Real Compose/controller interactions; only the host and silent native services are fixtures. */
class ChatInteractionRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val testName = org.junit.rules.TestName()
    private val bridge = RegressionUiBridge()
    private val server = ChatRegressionHost()
    private lateinit var strings: LocaleText
    private lateinit var controller: AppController

    @After fun cleanup() {
        if (::controller.isInitialized) controller.close()
        server.close()
        assertTrue("Unexpected fixture requests: ${server.unexpected}", server.unexpected.isEmpty())
    }

    private fun launch(language: String = "en") {
        strings = runBlocking { LocaleText.load(language) }
        bridge.preferences["preferences.v1"] = buildJsonObject {
            put("language", language); put("theme", "light"); put("wallpaper", false)
            put("haptics", false); put("answerHaptics", false)
        }.toString()
        compose.activityRule.scenario.onActivity {
            it.enableEdgeToEdge()
            it.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        compose.setContent {
            App(bridge) { platform ->
                AppController(platform, makeApi = server::makeApi).also { controller = it }
            }
        }
        waitForText(strings.get("connect.scan"))
        compose.onNodeWithText(strings.get("connect.scan")).performScrollTo().performClick()
        waitForText(MODEL_LABEL)
        compose.waitUntil(TIMEOUT) { controller.state.value.conversations.isNotEmpty() }
    }

    private fun openFixtureChat() {
        compose.onNodeWithContentDescription(strings.get("nav.menu")).performClick()
        waitForText(server.todayTitle)
        compose.onNodeWithText(server.todayTitle).performClick()
        compose.waitUntil(TIMEOUT) {
            controller.state.value.sessionId == CHAT_ID && !controller.state.value.loading
        }
    }

    private fun waitForText(text: String, matcher: SemanticsMatcher = hasText(text)) {
        try {
            compose.waitUntil(TIMEOUT) {
                compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (failure: Throwable) {
            captureWaitFailure(text, failure)
            throw failure
        }
    }

    private fun captureWaitFailure(expected: String, failure: Throwable) {
        try {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val directory = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)),
                "tmp/chat-interaction-regression").apply { check(isDirectory || mkdirs()) }
            val name = "failure-${testName.methodName}"
            val diagnostic = buildString {
                appendLine("Expected exact text: ${JsonPrimitive(expected)}")
                if (::controller.isInitialized) {
                    val state = controller.state.value
                    appendLine("Controller: session=${state.sessionId}, loading=${state.loading}, error=${state.error}")
                    state.messages.forEach { message ->
                        appendLine("Message ${message.id}: ${JsonPrimitive(message.text)}")
                        message.parts.forEach { part -> appendLine("Part ${part.type}: ${JsonPrimitive(part.text)}") }
                    }
                }
                appendLine("Unmerged text values (JSON preserves boundary whitespace):")
                compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
                    .fetchSemanticsNodes().forEach { node ->
                        node.config[SemanticsProperties.Text].forEach { appendLine(JsonPrimitive(it.text).toString()) }
                    }
                appendLine("Unmerged semantics tree:")
                appendLine(compose.onAllNodes(isRoot(), useUnmergedTree = true).printToString())
            }
            File(directory, "$name.txt").writeText(diagnostic)
            diagnostic.chunked(3_000).forEachIndexed { index, chunk -> Log.e("ChatUiRegression", "Diagnostic part $index:\n$chunk") }
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
            Log.e("ChatUiRegression", "Failure artifacts: ${directory.absolutePath}/$name.{txt,png}", failure)
        } catch (diagnosticFailure: Throwable) {
            failure.addSuppressed(diagnosticFailure)
            Log.e("ChatUiRegression", "Could not capture waitForText diagnostics", diagnosticFailure)
        }
    }

    private fun imageNode() = compose.onNode(
        hasContentDescription(IMAGE_NAME).and(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image)),
        useUnmergedTree = true,
    )

    private fun imeVisible() = compose.runOnIdle {
        ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
    }

    // Table semantics retain delimiter padding; compare the complete cell content after trimming it.
    private fun tableCellText(expected: String) = SemanticsMatcher("exact table cell content: $expected") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.trim() == expected } == true
    }

    @Test fun markdownTableWrapsTheEntireCellAndScrollsHorizontally() {
        server.history = JsonArray(listOf(assistantMessage(TABLE)))
        launch(); openFixtureChat()
        val cellText = tableCellText(LONG_CELL)
        waitForText(LONG_CELL, cellText)
        val cell = compose.onNode(cellText, useUnmergedTree = true)
        cell.performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        cell.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("The real table cell must expose its text layout", layouts.isNotEmpty())
        layouts.forEach { layout ->
            assertEquals(LONG_CELL, layout.layoutInput.text.text.trim())
            assertTrue("A long cell must wrap", layout.lineCount > 1)
            assertFalse("The tail of a table cell must not be truncated", layout.multiParagraph.didExceedMaxLines)
            for (line in 0 until layout.lineCount) {
                assertFalse("Table cells must not ellipsize", layout.isLineEllipsized(line))
                assertTrue("Wrapped glyphs must fit the cell", layout.getLineRight(line) - layout.getLineLeft(line) <= layout.size.width + 1f)
                assertTrue("Every wrapped line must have measured height", layout.getLineBottom(line) <= layout.size.height + 1f)
            }
        }
        val horizontal = SemanticsMatcher("scrollable table viewport") { node ->
            node.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)?.maxValue()?.let { it > 0f } == true
        }
        val viewport = compose.onNode(horizontal, useUnmergedTree = true)
        val before = viewport.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value()
        viewport.performTouchInput {
            swipe(Offset(width - 8f, centerY), Offset(8f, centerY), durationMillis = 400)
        }
        compose.waitForIdle()
        val after = viewport.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value()
        assertTrue("A real leftward swipe must move the table horizontally", after > before)
        assertFalse("Scrolling a table must not reveal navigation", controller.state.value.menuOpen)
        compose.onNode(tableCellText(RIGHT_CELL), useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        cell.performScrollTo().assertIsDisplayed()
    }

    @Test fun drawerOpensFromEightyDpInsideChatAndClosesByGesture() {
        launch(); openFixtureChat()
        waitForText(SECOND_BUBBLE)
        val density = compose.activity.resources.displayMetrics.density
        compose.onRoot().performTouchInput {
            swipe(Offset(80f * density, height * .42f), Offset(width * .94f, height * .42f), durationMillis = 400)
        }
        waitForText(server.todayTitle)
        compose.onNodeWithText(strings.get("nav.files")).assertIsDisplayed()
        compose.onNodeWithContentDescription(strings.get("nav.closeMenu")).assertIsDisplayed()
        assertTrue(controller.state.value.menuOpen)
        compose.onRoot().performTouchInput {
            swipe(Offset(width * .9f, height * .42f), Offset(width * .1f, height * .42f), durationMillis = 400)
        }
        compose.waitUntil(TIMEOUT) { !controller.state.value.menuOpen }
        compose.onNodeWithContentDescription(strings.get("nav.closeMenu")).assertDoesNotExist()
        compose.onNodeWithText(SECOND_BUBBLE).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput("The chat still accepts input")
        assertEquals("The chat still accepts input", controller.state.value.draft)
        assertTrue(server.submitted.isEmpty())
    }

    @Test fun longPressSendOptionsKeepTheKeyboardAndDraftFocused() {
        launch()
        val draft = "Keep this draft in the composer"
        val input = compose.onNode(hasSetTextAction())
        input.performClick().performTextInput(draft)
        compose.waitUntil(TIMEOUT) { imeVisible() }
        input.assertIsFocused()
        compose.onNodeWithContentDescription(strings.get("chat.send")).performTouchInput { longClick() }
        waitForText(strings.get("queue.later"))
        compose.onNodeWithText(strings.get("queue.title")).assertIsDisplayed()
        compose.onNodeWithText(strings.get("queue.later")).assertIsDisplayed()
        // Native window focus and IME animations do not use the Compose clock.
        val observationEnd = SystemClock.uptimeMillis() + 500
        compose.waitUntil(2_000) {
            assertTrue("Send options must leave the real IME visible", imeVisible())
            input.assertIsFocused()
            SystemClock.uptimeMillis() >= observationEnd
        }
        input.performTextInput(" while choosing delivery")
        val editedDraft = draft + " while choosing delivery"
        input.assertTextEquals(editedDraft)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(TIMEOUT) { !controller.state.value.sendModeOpen || !imeVisible() }
        compose.waitForIdle()
        // Android may consume the first Back to hide the IME before the app receives one.
        if (controller.state.value.sendModeOpen) {
            assertFalse("Only the IME may consume the first Back", imeVisible())
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        }
        compose.waitUntil(TIMEOUT) { !controller.state.value.sendModeOpen }
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.onNodeWithText(strings.get("queue.later")).assertDoesNotExist()
        input.assertIsDisplayed().assertTextEquals(editedDraft)
        assertEquals("Back must preserve the edited composer draft", editedDraft, controller.state.value.draft)
        assertFalse("Dismissing Send options must keep the chat activity open", compose.activity.isFinishing)
        assertTrue(server.submitted.isEmpty())
    }

    @Test fun bubbleLongPressCopiesOnlyThatBubbleAndPersistsItsReaction() {
        server.savedReactions = buildJsonObject { put("1", "❤️") }
        launch(); openFixtureChat()
        waitForText(SECOND_BUBBLE)
        val bubble = compose.onNodeWithText(SECOND_BUBBLE)
        bubble.performTouchInput { longClick() }
        waitForText(strings.get("chat.copy"))
        val popup = compose.onNode(isPopup()).fetchSemanticsNode().boundsInRoot
        val density = compose.activity.resources.displayMetrics.density
        assertTrue("Bubble actions must remain a compact floating menu", popup.width <= 320f * density && popup.height <= 360f * density)
        compose.onNodeWithText(strings.get("chat.copy")).performClick()
        assertEquals("Copy must not include another bubble or its narration", SECOND_BUBBLE, bridge.copied)
        bubble.performTouchInput { longClick() }
        compose.onNodeWithText("👍").performClick()
        compose.waitUntil(TIMEOUT) { server.reactionRequests.size == 1 }
        val request = server.reactionRequests.single()
        assertEquals(REPLY_ID, request.getValue("message_id").jsonPrimitive.content)
        // Note bubbles occupy an index in the backend's existing decimal-key map.
        assertEquals(2, request.getValue("bubble").jsonPrimitive.int)
        assertEquals("👍", request.getValue("emoji").jsonPrimitive.content)
        val badge = strings.get("reaction.yours", "emoji" to "👍")
        compose.waitUntil(TIMEOUT) { server.savedReactions["2"]?.jsonPrimitive?.content == "👍" }
        compose.onNodeWithContentDescription(badge).assertIsDisplayed()
        val reads = server.historyReads.get()
        openFixtureChat()
        compose.waitUntil(TIMEOUT) { server.historyReads.get() > reads }
        compose.onNodeWithContentDescription(badge).assertIsDisplayed()
        assertEquals("❤️", controller.state.value.messages.single().reactions["1"])
        compose.onNodeWithContentDescription(badge).performClick()
        compose.waitUntil(TIMEOUT) { server.reactionRequests.size == 2 && "2" !in server.savedReactions }
        assertEquals(JsonNull, server.reactionRequests.last()["emoji"])
        compose.onNodeWithContentDescription(badge).assertDoesNotExist()
        compose.onNodeWithContentDescription(strings.get("reaction.yours", "emoji" to "❤️")).assertIsDisplayed()
    }

    @Test fun pickedImageUploadsExactBytesAndShowsDraftAndRestoredThumbnails() {
        server.history = JsonArray(emptyList())
        bridge.pickedImage = PickedFile(IMAGE_NAME, "image/png", server.imageBytes)
        launch(); openFixtureChat()
        compose.onNodeWithContentDescription(strings.get("input.attach")).performClick()
        compose.onNodeWithText(strings.get("input.photo")).performClick()
        compose.waitUntil(TIMEOUT) { server.uploads.isNotEmpty() && !controller.state.value.uploading }
        assertEquals(listOf("photo"), bridge.pickerKinds.toList())
        val upload = server.uploads.single()
        assertTrue(upload.contentType.orEmpty().startsWith("multipart/form-data;"))
        val headers = upload.bytes.decodeToString()
        assertTrue("The upload must use the real file multipart field", headers.contains("name=file") || headers.contains("name=\"file\""))
        assertTrue(headers.contains("filename=\"$IMAGE_NAME\""))
        assertTrue(headers.contains("Content-Type: image/png"))
        assertTrue("The selected PNG bytes must reach the host unchanged", upload.bytes.containsBytes(server.imageBytes))
        compose.waitUntil(TIMEOUT) {
            compose.onAllNodes(hasContentDescription(IMAGE_NAME).and(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image)), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        imageNode().assertIsDisplayed()
        val pixels = imageNode().captureToImage().toPixelMap()
        val center = pixels[pixels.width / 2, pixels.height / 2]
        assertEquals("A decoded thumbnail must show the selected image", 192f / 255f, center.green, .03f)
        assertEquals(64f / 255f, center.red, .03f)
        assertEquals(112f / 255f, center.blue, .03f)
        // Attachment-only sends must use the same manifest as text-plus-image sends.
        compose.onNodeWithContentDescription(strings.get("chat.send")).performClick()
        compose.waitUntil(TIMEOUT) { server.submitted.isNotEmpty() }
        val sent = server.submitted.single()
        assertEquals("", sent.getValue("message").jsonPrimitive.content)
        assertEquals(CHAT_ID, sent.getValue("session_id").jsonPrimitive.content)
        assertEquals(JsonArray(listOf(server.imageManifest)), sent.getValue("attachments"))
        waitForText(IMAGE_REPLY)
        val reads = server.historyReads.get()
        openFixtureChat()
        compose.waitUntil(TIMEOUT) { server.historyReads.get() > reads && server.downloads.isNotEmpty() }
        compose.waitUntil(TIMEOUT) {
            compose.onAllNodes(hasContentDescription(IMAGE_NAME).and(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image)), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription(IMAGE_NAME))
        imageNode().assertIsDisplayed()
        assertEquals("Bearer $DEVICE_TOKEN", server.downloads.single())
    }

    @Test fun historyIsGroupedByLocalDayAndLongPressRenameSurvivesReload() {
        launch()
        compose.onNodeWithContentDescription(strings.get("nav.menu")).performClick()
        waitForText(server.todayTitle)
        val history = compose.onNode(hasScrollToNodeAction())
        listOf(
            "date.today" to server.todayTitle,
            "date.yesterday" to "Yesterday conversation",
            "date.thisWeek" to "This week conversation",
            "date.older" to "Older conversation",
            "date.unknown" to "Undated conversation",
        ).forEach { (key, title) ->
            history.performScrollToNode(hasText(strings.get(key)))
            val heading = compose.onNodeWithText(strings.get(key)).assertIsDisplayed()
            val row = compose.onNodeWithText(title).assertIsDisplayed()
            assertTrue("A conversation must follow its day heading", heading.fetchSemanticsNode().boundsInRoot.top < row.fetchSemanticsNode().boundsInRoot.top)
        }
        history.performScrollToNode(hasText(server.todayTitle))
        compose.onNodeWithText(server.todayTitle).performTouchInput { longClick() }
        compose.onNodeWithText(strings.get("chat.rename")).performClick()
        waitForText(strings.get("chat.renameTitle"))
        val renamed = "Renamed from the conversation drawer"
        compose.onNodeWithContentDescription(strings.get("chat.renamePlaceholder")).performTextReplacement(renamed)
        assertTrue("Editing the title must not save it before confirmation", server.renameRequests.isEmpty())
        compose.onNodeWithText(strings.get("chat.renameSave")).performClick()
        compose.waitUntil(TIMEOUT) { server.renameRequests.size == 1 && server.todayTitle == renamed }
        assertEquals(buildJsonObject { put("title", renamed) }, server.renameRequests.single())
        history.performScrollToNode(hasText(renamed))
        compose.onNodeWithText(renamed).performClick()
        compose.waitUntil(TIMEOUT) { controller.state.value.sessionId == CHAT_ID && !controller.state.value.loading }
        // Re-pairing re-fetches the host list rather than trusting the optimistic title.
        compose.runOnIdle { controller.disconnect() }
        waitForText(strings.get("connect.scan"))
        compose.onNodeWithText(strings.get("connect.scan")).performClick()
        waitForText(MODEL_LABEL)
        compose.waitUntil(TIMEOUT) { controller.state.value.conversations.any { it.id == CHAT_ID && it.title == renamed } }
        compose.onNodeWithContentDescription(strings.get("nav.menu")).performClick()
        waitForText(renamed)
        compose.onNodeWithText(renamed).assertIsDisplayed()
        assertEquals(1, server.renameRequests.size)
    }

    @Test fun technicalToolNamesAreLocalizedInSummaryRowsAndDetails() {
        launch("uk"); openFixtureChat()
        waitForText(SECOND_BUBBLE)
        val label = strings.get("tool.web_search")
        assertNotEquals("Ukrainian tool labels must use the locale override", runBlocking { LocaleText.load("en") }.get("tool.web_search"), label)
        compose.onNodeWithText(label).assertIsDisplayed()
        compose.onAllNodesWithText("tools__web_search", substring = true, useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithText(strings.get("chat.details")).performClick()
        compose.onNodeWithText(label).assertIsDisplayed().performClick()
        waitForText(strings.get("action.done"))
        compose.onNode(hasText(label).and(hasAnyAncestor(isDialog())), useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(hasText(TOOL_DETAIL).and(hasAnyAncestor(isDialog())), useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithText("tools__web_search", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun snapshotsAndNotesAreVisibleAndReplaceableBeforeProviderCompletion() {
        server.history = JsonArray(emptyList())
        server.holdStream = true
        launch(); openFixtureChat()
        compose.onNode(hasSetTextAction()).performTextInput("Show progress before completing")
        compose.onNodeWithContentDescription(strings.get("chat.send")).performClick()
        waitForText(LIVE_FIRST); waitForText(NOTE_FIRST)
        compose.onNodeWithText(LIVE_FIRST).assertIsDisplayed()
        compose.onNodeWithText(NOTE_FIRST).assertIsDisplayed()
        compose.onNodeWithContentDescription(strings.get("chat.stop")).assertIsDisplayed()
        assertFalse("The provider must still be held behind the fixture gate", server.completeStream.isCompleted)
        assertFalse(server.completed)
        server.nextStreamFrame.complete(Unit)
        waitForText(LIVE_SECOND); waitForText(NOTE_SECOND)
        compose.onNodeWithText(LIVE_SECOND).assertIsDisplayed()
        compose.onNodeWithText(NOTE_SECOND).assertIsDisplayed()
        compose.onNodeWithText(LIVE_FIRST).assertDoesNotExist()
        compose.onNodeWithText(NOTE_FIRST).assertDoesNotExist()
        assertFalse(server.completeStream.isCompleted)
        assertTrue(controller.state.value.busy)
        server.completeStream.complete(Unit)
        compose.waitUntil(TIMEOUT) { server.completed && !controller.state.value.busy }
        compose.onNodeWithText(LIVE_SECOND).assertIsDisplayed()
        compose.onNodeWithText(NOTE_SECOND).assertIsDisplayed()
        compose.onNodeWithContentDescription(strings.get("chat.stop")).assertDoesNotExist()
        assertEquals(1, server.submitted.size)
    }

    @Test fun noteOnlyGrowthResumesFollowingAfterUserDragAndLatest() {
        server.history = JsonArray(emptyList())
        server.noteOnlyStream = true
        launch(); openFixtureChat()
        compose.onNode(hasSetTextAction()).performTextInput("Keep following the narration")
        compose.onNodeWithContentDescription(strings.get("chat.send")).performClick()
        val chat = compose.onNode(hasScrollToNodeAction())

        fun publishNote(rows: Int) {
            server.noteRows.trySend(rows).getOrThrow()
            // Wait for the actual parsed row, not only the immediately updated note caption.
            waitForText(noteRowLabel(rows), tableCellText(noteRowLabel(rows)))
            waitForText(noteTail(rows))
        }
        fun awaitFollowedTail(rows: Int) {
            try {
                compose.waitUntil(TIMEOUT) {
                    val range = chat.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
                    range.value() >= range.maxValue() - .001f
                }
                compose.onNode(tableCellText(noteRowLabel(rows)), useUnmergedTree = true).assertIsDisplayed()
                compose.onNodeWithText(noteTail(rows)).assertIsDisplayed()
                compose.onNodeWithText(strings.get("chat.latest")).assertDoesNotExist()
            } catch (failure: Throwable) {
                captureWaitFailure(noteTail(rows), failure)
                throw failure
            }
        }

        publishNote(4)
        awaitFollowedTail(4)
        publishNote(24)
        awaitFollowedTail(24)
        val assistant = controller.state.value.messages.single { it.role == "assistant" }
        assertEquals("Notes must not masquerade as answer text to remove the typing item", "", assistant.text)
        assertTrue(assistant.live && assistant.parts.isNotEmpty() && assistant.parts.all { it.note })
        assertTrue(controller.state.value.busy)
        compose.onNodeWithContentDescription(strings.get("chat.stop")).assertIsDisplayed()

        // Drag toward the bottom while already there, then hold the pointer across growth.
        // Following is still enabled, but the real user scroll owns the mutation.
        chat.performTouchInput {
            down(Offset(centerX, height * .8f))
            advanceEventTime(100)
            moveTo(Offset(centerX, height * .45f))
        }
        try {
            publishNote(36)
            compose.onNodeWithText(noteTail(36)).assertIsNotDisplayed()
        } finally {
            chat.performTouchInput {
                // Release without a fling that could hide a dead following collector.
                advanceEventTime(500)
                moveBy(Offset.Zero)
                up()
            }
        }
        awaitFollowedTail(36)

        // Reading older text disables following; another host update must respect that position.
        chat.performTouchInput {
            swipe(Offset(centerX, height * .2f), Offset(centerX, height * .75f), durationMillis = 600)
        }
        waitForText(strings.get("chat.latest"))
        publishNote(48)
        compose.onNodeWithText(noteTail(48)).assertIsNotDisplayed()
        compose.onNodeWithText(strings.get("chat.latest")).assertIsDisplayed().performClick()
        awaitFollowedTail(48)
        publishNote(60)
        awaitFollowedTail(60)
        assertFalse("Every scroll assertion must precede provider completion", server.completeStream.isCompleted)
        assertTrue(controller.state.value.busy)
        assertEquals("", controller.state.value.messages.single { it.role == "assistant" }.text)

        server.noteRows.close()
        server.completeStream.complete(Unit)
        compose.waitUntil(TIMEOUT) { server.completed && !controller.state.value.busy }
        waitForText(NOTE_COMPLETE)
        compose.onNodeWithText(NOTE_COMPLETE).assertIsDisplayed()
        assertEquals(1, server.submitted.size)
    }
}

private data class RegressionUpload(val bytes: ByteArray, val contentType: String?)

/** Strict host routes exercise serialization, persisted history, and an actually open SSE body. */
private class ChatRegressionHost {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clients = CopyOnWriteArrayList<HttpClient>()
    val unexpected = CopyOnWriteArrayList<String>()
    val submitted = CopyOnWriteArrayList<JsonObject>()
    val reactionRequests = CopyOnWriteArrayList<JsonObject>()
    val renameRequests = CopyOnWriteArrayList<JsonObject>()
    val uploads = CopyOnWriteArrayList<RegressionUpload>()
    val downloads = CopyOnWriteArrayList<String?>()
    val historyReads = AtomicInteger()
    val nextStreamFrame = CompletableDeferred<Unit>()
    val completeStream = CompletableDeferred<Unit>()
    val noteRows = Channel<Int>(Channel.UNLIMITED)
    @Volatile var holdStream = false
    @Volatile var noteOnlyStream = false
    @Volatile var completed = false
    @Volatile var todayTitle = "Today conversation"
    @Volatile var savedReactions = JsonObject(emptyMap())
    @Volatile var history = JsonArray(listOf(assistantMessage(FIRST_BUBBLE + "\n\n" + SECOND_BUBBLE, listOf(
        textPart(SAVED_NOTE, note = true), textPart(FIRST_BUBBLE),
        buildJsonObject { put("type", "steps"); put("ids", JsonArray(listOf(JsonPrimitive("tool1")))) },
        textPart(SECOND_BUBBLE),
    ), tool = true)))
    val imageBytes = ByteArrayOutputStream().use { output ->
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.rgb(64, 192, 112))
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        } finally { bitmap.recycle() }
    }
    val imageManifest = buildJsonObject {
        put("url", IMAGE_PATH); put("name", IMAGE_NAME); put("type", "image/png"); put("size", imageBytes.size)
    }

    fun makeApi(origin: String, token: String): BotApi {
        val client = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            fun body() = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            val json = when {
                path == "/api/mobile/pair/exchange" && request.method == HttpMethod.Post ->
                    """{"token":"$DEVICE_TOKEN","device_id":"regression-device","expires_at":1999999999}"""
                path == "/api/mobile/capabilities" -> """{"steer":false,"queue":true,"event_replay":true}"""
                path == "/api/brain/models" -> """{"models":[{"id":"fixture/claude","label":"$MODEL_LABEL","provider":"fixture","brand":"anthropic","efforts":["none"]}],"selected":"fixture/claude"}"""
                path == "/api/setup" -> """{"profile":{"name":"Claude Bot","language":"en","persona":"friendly","persona_custom":"Keep things clear.","greeting":"Hello"}}"""
                path == "/api/sessions" && request.method == HttpMethod.Get -> sessions().toString()
                path == "/api/sessions/$CHAT_ID/rename" && request.method == HttpMethod.Post -> {
                    val payload = body()
                    todayTitle = payload.getValue("title").jsonPrimitive.content
                    renameRequests += payload
                    """{"ok":true}"""
                }
                path == "/api/sessions/$CHAT_ID/reactions" && request.method == HttpMethod.Post -> {
                    val payload = body()
                    val key = payload.getValue("bubble").jsonPrimitive.int.toString()
                    val emoji = payload["emoji"]?.jsonPrimitive?.contentOrNull
                    savedReactions = JsonObject(if (emoji == null) savedReactions - key else savedReactions + (key to JsonPrimitive(emoji)))
                    reactionRequests += payload
                    buildJsonObject { put("ok", true); put("reactions", savedReactions) }.toString()
                }
                path == "/api/sessions/$CHAT_ID" && request.method == HttpMethod.Get -> {
                    historyReads.incrementAndGet()
                    buildJsonObject {
                        put("messages", JsonArray(history.map { value ->
                            val message = value.jsonObject
                            if (message["id"]?.jsonPrimitive?.content == REPLY_ID) JsonObject(message + ("reactions" to savedReactions)) else message
                        }))
                    }.toString()
                }
                path == "/api/chat/upload" && request.method == HttpMethod.Post -> {
                    val bytes = coroutineScope {
                        val channel = ByteChannel()
                        val writer = launch {
                            try { (request.body as OutgoingContent.WriteChannelContent).writeTo(channel) }
                            finally { channel.close() }
                        }
                        channel.readRemaining().readByteArray().also { writer.join() }
                    }
                    uploads += RegressionUpload(bytes, request.body.contentType?.toString())
                    imageManifest.toString()
                }
                path == IMAGE_PATH && request.method == HttpMethod.Get -> {
                    downloads += request.headers[HttpHeaders.Authorization]
                    return@MockEngine respond(imageBytes, headers = headersOf(HttpHeaders.ContentType, "image/png"))
                }
                path == "/api/mobile/messages" && request.method == HttpMethod.Post -> {
                    submitted += body()
                    """{"id":"regression-job","session_id":"$CHAT_ID","state":"running"}"""
                }
                path == "/api/mobile/messages" && request.method == HttpMethod.Get -> {
                    if (noteOnlyStream && submitted.isNotEmpty() && !completed) buildJsonObject {
                        put("messages", JsonArray(listOf(JsonObject(submitted.single() + mapOf(
                            "id" to JsonPrimitive("regression-job"), "state" to JsonPrimitive("running"),
                        )))))
                    }.toString() else """{"messages":[]}"""
                }
                path == "/api/mobile/messages/regression-job/events" -> {
                    val channel = ByteChannel(autoFlush = true)
                    scope.launch {
                        try {
                            var sequence = 0
                            suspend fun event(name: String, payload: JsonObject) {
                                channel.writeStringUtf8("id: ${++sequence}\nevent: $name\ndata: $payload\n\n")
                            }
                            event("mobile_state", buildJsonObject { put("state", "running") })
                            val parts: List<JsonObject>
                            val reply: String
                            if (noteOnlyStream) {
                                var notes = emptyList<String>()
                                for (rows in noteRows) {
                                    notes = listOf(growingNoteTable(rows), noteTail(rows))
                                    event("note", buildJsonObject {
                                        put("id", "growing-note"); put("bubbles", JsonArray(notes.map(::JsonPrimitive)))
                                    })
                                }
                                completeStream.await()
                                reply = NOTE_COMPLETE
                                parts = notes.map { textPart(it, note = true) } + textPart(reply)
                            } else if (holdStream) {
                                event("reply_snapshot", buildJsonObject { put("id", "answer"); put("text", LIVE_FIRST) })
                                event("note", buildJsonObject { put("id", "note1"); put("bubbles", JsonArray(listOf(JsonPrimitive(NOTE_FIRST)))) })
                                nextStreamFrame.await()
                                event("reply_snapshot", buildJsonObject { put("id", "answer"); put("text", LIVE_SECOND) })
                                event("note", buildJsonObject { put("id", "note1"); put("bubbles", JsonArray(listOf(JsonPrimitive(NOTE_SECOND)))) })
                                completeStream.await()
                                reply = LIVE_SECOND
                                parts = listOf(textPart(LIVE_SECOND), textPart(NOTE_SECOND, note = true))
                            } else {
                                reply = IMAGE_REPLY
                                parts = listOf(textPart(reply))
                                event("delta", buildJsonObject { put("chunk", reply) })
                            }
                            val message = submitted.single()
                            history = JsonArray(listOf(buildJsonObject {
                                put("id", "human1"); put("role", "user"); put("content", message.getValue("message"))
                                put("attachments", message.getValue("attachments"))
                            }, assistantMessage(reply, parts)))
                            event("done", buildJsonObject {
                                put("reply", reply); put("parts", JsonArray(parts)); put("assistant_message_id", REPLY_ID)
                            })
                            completed = true
                            event("mobile_state", buildJsonObject { put("state", "completed") })
                        } finally { channel.close() }
                    }
                    return@MockEngine respond(channel, headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
                }
                else -> {
                    unexpected += "${request.method.value} $path"
                    error("Unexpected regression fixture route: ${request.method.value} $path")
                }
            }
            respond(json, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        clients += client
        return BotApi(origin, token, client)
    }

    private fun sessions(): JsonObject {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        fun session(id: String, title: String, age: Long?) = buildJsonObject {
            put("id", id); put("title", title)
            age?.let { put("updated", today.minusDays(it).atTime(12, 0).atZone(zone).toEpochSecond()) }
        }
        // Intentionally unsorted; day groups must come from rendered host timestamps.
        return buildJsonObject { put("sessions", JsonArray(listOf(
            session("undated", "Undated conversation", null), session("older", "Older conversation", 15),
            session("yesterday", "Yesterday conversation", 1), session(CHAT_ID, todayTitle, 0),
            session("week", "This week conversation", 3),
        ))) }
    }

    fun close() { scope.cancel(); noteRows.cancel(); clients.forEach { it.close() } }
}

private class RegressionUiBridge : PlatformBridge {
    override val platformName = "android"
    override val systemLanguage = "en"
    override val reducedMotion = true
    override val foreground = MutableStateFlow(true)
    override val incomingPairing = MutableStateFlow<String?>(null)
    val preferences = mutableMapOf<String, String>()
    private val secrets = mutableMapOf<String, String>()
    private val ids = AtomicInteger()
    val pickerKinds = CopyOnWriteArrayList<String>()
    var pickedImage: PickedFile? = null
    var copied: String? = null
    override fun readPreference(key: String) = preferences[key]
    override fun writePreference(key: String, value: String?) { if (value == null) preferences.remove(key) else preferences[key] = value }
    override fun readSecret(key: String) = secrets[key]
    override fun writeSecret(key: String, value: String?) { if (value == null) secrets.remove(key) else secrets[key] = value }
    override fun scanQr(onResult: (String?) -> Unit) { onResult("claudebot://pair?server=https%3A%2F%2Fchat-regression.example&code=regression-pairing") }
    override fun pickFile(kind: String, onResult: (PickedFile?) -> Unit) { pickerKinds += kind; onResult(pickedImage) }
    override fun startRecording(onAmplitude: (Float) -> Unit, onResult: (PickedFile?) -> Unit, onPartial: (PickedFile) -> Unit) = Unit
    override fun stopRecording() = Unit
    override fun cancelRecording() = Unit
    override fun haptic() = Unit
    override fun copyText(value: String) { copied = value }
    override fun shareText(value: String) = Unit
    override fun requestNotifications(onResult: (Boolean) -> Unit) { onResult(false) }
    override fun notifyReply(title: String, body: String, conversationId: String) = Unit
    override fun nowMillis() = System.currentTimeMillis()
    override fun newId() = "regression-${ids.incrementAndGet()}"
}

private fun textPart(text: String, note: Boolean = false) = buildJsonObject {
    put("type", "text"); put("text", text)
    if (note) put("note", true)
}

private fun assistantMessage(text: String, parts: List<JsonObject> = listOf(textPart(text)), tool: Boolean = false) = buildJsonObject {
    put("id", REPLY_ID); put("role", "assistant"); put("content", text); put("model", "fixture/claude")
    put("parts", JsonArray(parts))
    if (tool) put("steps", JsonArray(listOf(buildJsonObject {
        put("id", "tool1"); put("label", "tools__web_search"); put("detail", TOOL_DETAIL); put("status", "done")
    })))
}

private fun ByteArray.containsBytes(expected: ByteArray): Boolean =
    size >= expected.size && (0..size - expected.size).any { start -> expected.indices.all { this[start + it] == expected[it] } }

private fun noteRowLabel(row: Int) = "Review step $row"
private fun noteTail(rows: Int) = "Narration has reached review step $rows."
private fun growingNoteTable(rows: Int) = buildString {
    appendLine("| Step | Observation |")
    appendLine("| --- | --- |")
    for (row in 1..rows) appendLine("| ${noteRowLabel(row)} | Reviewed fixture item $row |")
}

private const val TIMEOUT = 10_000L
private const val DEVICE_TOKEN = "synthetic-regression-token"
private const val CHAT_ID = "regression-chat"
private const val REPLY_ID = "reply1"
private const val MODEL_LABEL = "Claude regression fixture"
private const val SAVED_NOTE = "I checked the saved conversation context."
private const val FIRST_BUBBLE = "The first readable answer bubble."
private const val SECOND_BUBBLE = "The second readable answer bubble."
private const val TOOL_DETAIL = "Queried the project documentation."
private const val IMAGE_NAME = "regression.png"
private const val IMAGE_PATH = "/uploads/regression.png"
private const val IMAGE_REPLY = "The selected image was received."
private const val LIVE_FIRST = "The first answer is already arriving."
private const val LIVE_SECOND = "The corrected answer is visible before completion."
private const val NOTE_FIRST = "I am checking the first source."
private const val NOTE_SECOND = "I have checked both sources."
private const val NOTE_COMPLETE = "The narrated review is complete."
private const val LONG_CELL = "A long table cell must keep its complete explanation readable when it wraps onto several lines, including this final sentence and the distinctive end marker TABLE_CELL_END."
private const val RIGHT_CELL = "RIGHTMOST_TABLE_CELL"
private val TABLE = """
    | Explanation | Owner | Status | Date | Source | Last column |
    | --- | --- | --- | --- | --- | --- |
    | $LONG_CELL | Test author | Ready | October | Local fixture | $RIGHT_CELL |
""".trimIndent()
