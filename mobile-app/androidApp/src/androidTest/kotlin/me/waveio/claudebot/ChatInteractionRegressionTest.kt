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
import androidx.compose.ui.unit.width
import androidx.compose.ui.unit.height
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
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

    private fun launch(language: String = "en", theme: String = "light", wallpaper: Boolean = false) {
        strings = runBlocking { LocaleText.load(language) }
        bridge.preferences["preferences.v1"] = buildJsonObject {
            put("language", language); put("theme", theme); put("wallpaper", wallpaper)
            put("haptics", false); put("answerHaptics", false)
        }.toString()
        compose.activityRule.scenario.onActivity {
            it.enableEdgeToEdge()
            it.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        compose.setContent {
            App(bridge, onSystemBarAppearance = compose.activity::systemBarAppearance) { platform ->
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
        // A short landscape viewport initially shows only navigation. Exercise
        // the real scrollable drawer to reach the saved conversation.
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(server.todayTitle))
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
                    appendLine("Controller: session=${state.sessionId}, loading=${state.loading}, busy=${state.busy}, error=${state.error}")
                    appendLine("Draft: ${JsonPrimitive(state.draft)}")
                    appendLine("Pending: ${state.pending.map { it.id to it.state }}")
                    appendLine("Thumbnail cache: ${state.attachmentThumbnails.mapValues { it.value.size }}")
                    state.messages.forEach { message ->
                        appendLine("Message ${message.id} role=${message.role}: ${JsonPrimitive(message.text)}")
                        appendLine("Attachments: ${message.attachments.map { Triple(it.path, it.mimeType, it.size) }}")
                        message.parts.forEach { part -> appendLine("Part ${part.type}: ${JsonPrimitive(part.text)}") }
                    }
                }
                appendLine("Fixture: submitted=${server.submitted.size}, historyReads=${server.historyReads.get()}, downloads=${server.downloads.size}, jobReads=${server.jobReads.get()}, streamRequests=${server.streamRequests.get()}, runningFrames=${server.runningFrames.get()}, completed=${server.completed}")
                appendLine("IME visible: ${imeVisible()}; Compose clock: ${compose.mainClock.currentTime}")
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

    private fun screenshot(name: String) {
        compose.waitForIdle()
        // Native Dialog window fades run outside the Compose test clock.
        SystemClock.sleep(300)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)),
            "ui-qa/bubble-edges").apply { check(isDirectory || mkdirs()) }
        val path = File(directory, "$name.png")
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            path.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        Log.i("ChatUiRegression", "Screenshot: ${path.absolutePath}")
    }

    private fun imageNode() = compose.onNode(
        hasContentDescription(IMAGE_NAME).and(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image)),
        useUnmergedTree = true,
    )

    private fun imeVisible() = compose.runOnIdle {
        ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
    }

    private fun waitForLatest() = waitForText(strings.get("chat.latest"), hasContentDescription(strings.get("chat.latest")))

    private fun historyScroll() = compose.onNodeWithTag("chat-history").fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()

    /** Start body drags inside the reading region exposed between the panels. */
    private fun exposedHistorySwipe(towardBottom: Boolean, durationMillis: Long = 600) {
        val history = compose.onNodeWithTag("chat-history")
        val bounds = history.fetchSemanticsNode().boundsInRoot
        val top = compose.onNodeWithTag("chat-header").fetchSemanticsNode().boundsInRoot.bottom - bounds.top
        val bottom = compose.onNodeWithTag("chat-composer").fetchSemanticsNode().boundsInRoot.top - bounds.top
        assertTrue("History gestures must stay between the overlaid panels", bottom > top)
        history.performTouchInput {
            val upper = Offset(centerX, top + (bottom - top) * .2f)
            val lower = Offset(centerX, top + (bottom - top) * .8f)
            swipe(if (towardBottom) lower else upper, if (towardBottom) upper else lower, durationMillis)
        }
    }

    // Table semantics retain delimiter padding; compare the complete cell content after trimming it.
    private fun tableCellText(expected: String) = SemanticsMatcher("exact table cell content: $expected") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.trim() == expected } == true
    }

    @Test fun assistantBubbleWidthFollowsContentAndBoundsRichLongReplies() {
        val greeting = "Hello!"
        val longText = "This longer reply keeps rich Markdown readable while its explanation wraps across several lines. " +
            "The bubble should grow with the answer, stay within the available chat width, and keep this final sentence visible."
        val longMarkdown = longText.replace("longer reply", "**longer reply**").replace("rich Markdown", "_rich Markdown_")
        server.history = JsonArray(listOf(assistantMessage("**$greeting**\n\n$longMarkdown", listOf(
            textPart("**$greeting**"), textPart(longMarkdown),
        ))))
        launch(theme = "dark", wallpaper = true); openFixtureChat()
        waitForText(greeting); waitForText(longText)

        // Measure the combined-clickable Surface, not its naturally narrow Text child.
        fun bubble(index: Int, text: String) = compose.onNode(
            hasTestTag("message-bubble:$REPLY_ID:$index").and(hasClickAction())
                .and(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
                .and(hasAnyDescendant(hasText(text))),
            useUnmergedTree = true,
        )
        val history = compose.onNodeWithTag("chat-history").getUnclippedBoundsInRoot()
        val maximumWidth = minOf(history.width.value - 36f, 600f)
        val shortBubble = bubble(0, greeting).performScrollTo().assertIsDisplayed().getUnclippedBoundsInRoot()
        val greetingWidth = compose.onNodeWithText(greeting, useUnmergedTree = true).getUnclippedBoundsInRoot().width.value
        assertEquals("A short reply must wrap its text and horizontal bubble padding", greetingWidth + 32f, shortBubble.width.value, 2f)
        assertTrue("A greeting must leave unused room in the message row", shortBubble.width.value < maximumWidth)
        screenshot("bubble-width-before-scroll")

        val longBubble = bubble(1, longText).performScrollTo().assertIsDisplayed().getUnclippedBoundsInRoot()
        screenshot("bubble-width-after-scroll")
        assertTrue("Long replies must grow wider than the greeting", longBubble.width.value > shortBubble.width.value + 16f)
        // Unclipped bounds catch overflow that clipped semantics could hide.
        assertTrue("Long replies must respect both the viewport and bubble width cap", longBubble.width.value <= maximumWidth + 2f)
        assertTrue("The bubble must stay inside the leading chat padding", longBubble.left.value >= history.left.value + 18f - 2f)
        assertTrue("The bubble must stay inside the trailing chat padding", longBubble.right.value <= history.right.value - 18f + 2f)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(longText, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("The parsed reply must expose its text layout", layouts.isNotEmpty())
        layouts.forEach { layout ->
            assertEquals(longText, layout.layoutInput.text.text)
            assertTrue("A long reply must wrap within its bubble", layout.lineCount > 1)
            assertFalse("The final sentence must not be truncated", layout.multiParagraph.didExceedMaxLines)
            for (line in 0 until layout.lineCount) assertFalse("Reply lines must not ellipsize", layout.isLineEllipsized(line))
        }
        assertTrue("Bold Markdown must retain its formatting", layouts.any { layout ->
            layout.layoutInput.text.spanStyles.any { (it.item.fontWeight?.weight ?: 0) >= FontWeight.SemiBold.weight }
        })
        assertTrue("Italic Markdown must retain its formatting", layouts.any { layout ->
            layout.layoutInput.text.spanStyles.any { it.item.fontStyle == FontStyle.Italic }
        })
        assertTrue("Opening fixture history must not send a message", server.submitted.isEmpty())
    }

    @Test fun historyExtendsBehindPanelsAndPadsRepliesWhenComposerGrows() {
        val replies = List(24) { "History reply $it remains readable while scrolling between the header and composer." }
        fun replyId(index: Int) = "edge-reply-$index"
        server.history = JsonArray(replies.mapIndexed { index, text ->
            JsonObject(assistantMessage(text) + ("id" to JsonPrimitive(replyId(index))))
        })
        launch(theme = "dark", wallpaper = true); openFixtureChat()
        waitForText(replies.last())
        val history = compose.onNodeWithTag("chat-history")
        val header = compose.onNodeWithTag("chat-header")
        val composer = compose.onNodeWithTag("chat-composer")
        val initialComposerHeight = composer.getUnclippedBoundsInRoot().height.value

        fun assertPanelOverlap() {
            val viewport = history.getUnclippedBoundsInRoot()
            val surface = compose.onNodeWithTag("chat-surface").getUnclippedBoundsInRoot()
            val window = compose.onRoot().getUnclippedBoundsInRoot()
            val topPanel = header.getUnclippedBoundsInRoot()
            val bottomPanel = composer.getUnclippedBoundsInRoot()
            assertEquals("History must extend to the safe viewport top", surface.top.value, viewport.top.value, 2f)
            assertEquals("History must extend to the safe viewport bottom", surface.bottom.value, viewport.bottom.value, 2f)
            assertEquals("History must not expose a clipping line below the status bar", window.top.value, viewport.top.value, 2f)
            assertEquals("History must reach the window edge behind navigation", window.bottom.value, viewport.bottom.value, 2f)
            assertEquals("The header must overlay the top of history", viewport.top.value, topPanel.top.value, 2f)
            assertEquals("The composer must overlay the bottom of history", viewport.bottom.value, bottomPanel.bottom.value, 2f)
            assertTrue("History must continue behind the header", viewport.top.value < topPanel.bottom.value)
            assertTrue("History must continue behind the composer", viewport.bottom.value > bottomPanel.top.value)
            assertTrue("The panels must leave an exposed reading region", topPanel.bottom.value < bottomPanel.top.value)
        }
        fun assertLastReplyClearOfComposer() {
            val bubble = compose.onNodeWithTag("message-bubble:${replyId(replies.lastIndex)}:0")
                .assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue("The followed reply must remain above the measured composer padding",
                bubble.bottom.value <= composer.getUnclippedBoundsInRoot().top.value - 20f + 2f)
        }
        assertPanelOverlap()
        assertLastReplyClearOfComposer()
        // Change the real draft without opening the IME, isolating measured panel growth.
        compose.runOnIdle { controller.draft(List(4) { "Draft line $it" }.joinToString("\n")) }
        compose.waitUntil(TIMEOUT) { composer.getUnclippedBoundsInRoot().height.value > initialComposerHeight + 8f }
        compose.waitForIdle()
        assertPanelOverlap()
        assertLastReplyClearOfComposer()
        compose.runOnIdle { controller.draft("") }
        compose.waitUntil(TIMEOUT) { kotlin.math.abs(composer.getUnclippedBoundsInRoot().height.value - initialComposerHeight) <= 2f }
        compose.waitForIdle()
        assertLastReplyClearOfComposer()
        screenshot("chat-edges-before-scroll")

        val viewport = history.fetchSemanticsNode().boundsInRoot
        val exposedTop = header.fetchSemanticsNode().boundsInRoot.bottom - viewport.top
        val exposedBottom = composer.fetchSemanticsNode().boundsInRoot.top - viewport.top
        val exposedHeight = exposedBottom - exposedTop
        assertTrue("The swipe must have an exposed body region", exposedHeight > 0f)
        val before = history.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        history.performTouchInput {
            swipe(Offset(centerX, exposedTop + exposedHeight * .2f),
                Offset(centerX, exposedTop + exposedHeight * .75f), durationMillis = 600)
        }
        compose.waitUntil(TIMEOUT) {
            history.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() < before - .001f
        }
        waitForText(strings.get("chat.latest"), hasContentDescription(strings.get("chat.latest")))
        assertPanelOverlap()
        assertFalse("A vertical body swipe must not open navigation", controller.state.value.menuOpen)
        compose.onNode(isPopup()).assertDoesNotExist()
        screenshot("chat-edges-after-scroll")

        // At the start of history, content padding must clear the overlaid header.
        history.performScrollToIndex(0)
        waitForText(replies.first())
        val firstBubble = compose.onNodeWithTag("message-bubble:${replyId(0)}:0")
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        assertEquals("The first reply must start below the header and body padding",
            header.getUnclippedBoundsInRoot().bottom.value + 20f, firstBubble.top.value, 2f)
        assertTrue("Layout and scrolling must not submit the local draft", server.submitted.isEmpty())
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

    @Test fun shortFastDrawerSwipeUsesDpVelocityAndMinimumTravel() {
        launch(); openFixtureChat()
        waitForText(SECOND_BUBBLE)
        val density = compose.activity.resources.displayMetrics.density
        val root = compose.onRoot()
        val bounds = root.fetchSemanticsNode().boundsInRoot
        val y = bounds.height * .42f
        val start = 80f * density
        // This travels far less than 42% of the reveal width but exceeds 420 dp/s.
        val distance = 48f * density
        assertTrue("The fixture fling must stay below the distance-only open threshold", distance < bounds.width * .65f * .42f)
        root.performTouchInput { swipe(Offset(start, y), Offset(start + distance, y), durationMillis = 80) }
        compose.waitUntil(TIMEOUT) { controller.state.value.menuOpen }
        compose.onNodeWithContentDescription(strings.get("nav.closeMenu")).assertIsDisplayed()
        compose.onNodeWithText(server.todayTitle).assertIsDisplayed()
        val closeStart = bounds.width * .9f
        root.performTouchInput { swipe(Offset(closeStart, y), Offset(closeStart - distance, y), durationMillis = 80) }
        compose.waitUntil(TIMEOUT) { !controller.state.value.menuOpen }
        compose.onNodeWithContentDescription(strings.get("nav.closeMenu")).assertDoesNotExist()

        // A quick nudge must not open the drawer, even when its velocity is high.
        root.performTouchInput { swipe(Offset(start, y), Offset(start + 18f * density, y), durationMillis = 32) }
        compose.waitForIdle()
        assertFalse("Travel below 24 dp must not reveal navigation", controller.state.value.menuOpen)
        compose.onNodeWithContentDescription(strings.get("nav.closeMenu")).assertDoesNotExist()
        compose.onNodeWithText(SECOND_BUBBLE).assertIsDisplayed()
        assertTrue(server.submitted.isEmpty())
    }

    @Test fun latestHidesAfterManualReturnAndIgnoresTrailingPadding() {
        val replies = List(18) { "Manual return history reply $it." }
        server.history = JsonArray(replies.mapIndexed { index, text ->
            JsonObject((assistantMessage(text) - "model") + ("id" to JsonPrimitive("manual-reply-$index")))
        })
        launch(); openFixtureChat()
        waitForText(replies.last())
        compose.onNodeWithTag("chat-latest").assertDoesNotExist()
        exposedHistorySwipe(towardBottom = false)
        waitForLatest()
        compose.onNodeWithTag("chat-latest").assertIsDisplayed()
            .assertContentDescriptionEquals(strings.get("chat.latest"))
        compose.onNodeWithText(strings.get("chat.latest")).assertDoesNotExist()

        var returned = false
        for (attempt in 0 until 12) {
            exposedHistorySwipe(towardBottom = true)
            if (compose.onAllNodesWithTag("chat-latest").fetchSemanticsNodes().isEmpty()) {
                returned = true
                break
            }
        }
        assertTrue("Manually returning to the exposed tail must hide Latest", returned)
        val tail = compose.onNodeWithTag("message-bubble:manual-reply-${replies.lastIndex}:0")
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        val composerTop = compose.onNodeWithTag("chat-composer").getUnclippedBoundsInRoot().top.value
        assertTrue("The final rendered bubble must clear the composer", tail.bottom.value <= composerTop + 2f)

        // Hold a tiny drag so following cannot erase the remaining padding before inspection.
        val history = compose.onNodeWithTag("chat-history")
        val bounds = history.fetchSemanticsNode().boundsInRoot
        val top = compose.onNodeWithTag("chat-header").fetchSemanticsNode().boundsInRoot.bottom - bounds.top
        val bottom = compose.onNodeWithTag("chat-composer").fetchSemanticsNode().boundsInRoot.top - bounds.top
        val density = compose.activity.resources.displayMetrics.density
        val touchSlop = android.view.ViewConfiguration.get(compose.activity).scaledTouchSlop.toFloat()
        val before = historyScroll()
        history.performTouchInput {
            down(Offset(centerX, top + (bottom - top) * .4f))
            advanceEventTime(100)
            moveBy(Offset(0f, touchSlop + 8f * density))
        }
        try {
            compose.waitForIdle()
            assertTrue("The small drag must actually leave some bottom padding unscrolled", historyScroll() < before)
            val range = history.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            assertTrue("Padding remains scrollable while the content tail is already exposed", range.value() < range.maxValue())
            val exposedTail = compose.onNodeWithTag("message-bubble:manual-reply-${replies.lastIndex}:0")
                .getUnclippedBoundsInRoot().bottom.value
            assertTrue("The tiny drag must leave the actual tail above the composer", exposedTail <= composerTop + 2f)
            compose.onNodeWithTag("chat-latest").assertDoesNotExist()
            compose.onNodeWithContentDescription(strings.get("chat.latest")).assertDoesNotExist()
        } finally {
            history.performTouchInput { advanceEventTime(500); moveBy(Offset.Zero); up() }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("chat-latest").assertDoesNotExist()
        assertTrue(server.submitted.isEmpty())
    }

    @Test fun shortUpwardReadingFlingDoesNotSnapBackToBottom() {
        val replies = List(24) { "Reading fling reply $it leaves enough older history to scroll." }
        server.history = JsonArray(replies.mapIndexed { index, text ->
            JsonObject((assistantMessage(text) - "model") + ("id" to JsonPrimitive("fling-reply-$index")))
        })
        launch(); openFixtureChat()
        waitForText(replies.last())
        compose.waitForIdle()
        val history = compose.onNodeWithTag("chat-history")
        val bounds = history.fetchSemanticsNode().boundsInRoot
        val top = compose.onNodeWithTag("chat-header").fetchSemanticsNode().boundsInRoot.bottom - bounds.top
        val bottom = compose.onNodeWithTag("chat-composer").fetchSemanticsNode().boundsInRoot.top - bounds.top
        val composerTop = compose.onNodeWithTag("chat-composer").getUnclippedBoundsInRoot().top.value
        val density = compose.activity.resources.displayMetrics.density
        val touchSlop = android.view.ViewConfiguration.get(compose.activity).scaledTouchSlop.toFloat()
        val tail = compose.onNodeWithTag("message-bubble:fling-reply-${replies.lastIndex}:0")
        assertTrue("The fling must start with the final rendered tail exposed", tail.getUnclippedBoundsInRoot().bottom.value <= composerTop + 2f)
        compose.onNodeWithTag("chat-latest").assertDoesNotExist()
        val start = historyScroll()
        compose.mainClock.autoAdvance = false
        try {
            // Reading upward moves the finger downward. Only 12 dp is consumed
            // before release, leaving the tail visible inside its 20 dp padding.
            history.performTouchInput {
                down(Offset(centerX, top + (bottom - top) * .4f))
                advanceEventTime(8)
                moveBy(Offset(0f, touchSlop + 4f * density))
                advanceEventTime(8)
                moveBy(Offset(0f, 8f * density))
                up()
            }
            compose.waitForIdle()
            assertTrue("The short drag must move toward older messages", historyScroll() < start)
            assertTrue("The tail must still be exposed at release, before fling frames run",
                tail.getUnclippedBoundsInRoot().bottom.value <= composerTop + 2f)
            compose.onNodeWithTag("chat-latest").assertDoesNotExist()
            val released = historyScroll()
            compose.mainClock.advanceTimeBy(5_000)
            compose.waitForIdle()
            assertTrue("The fling must continue into older content and stay there after settling", historyScroll() < released - .001f)
            compose.onNodeWithTag("chat-latest").assertIsDisplayed()
                .assertContentDescriptionEquals(strings.get("chat.latest"))
            assertFalse("A vertical reading fling must not open navigation", controller.state.value.menuOpen)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        assertTrue(server.submitted.isEmpty())
    }

    @Test fun latestAnimatesThroughVeryTallFinalItemAndClearsComposer() {
        val body = List(160) { "Tall final reply line $it remains readable." }.joinToString("\n")
        val tailText = "The very tall final item ends here."
        server.history = JsonArray(listOf(JsonObject(assistantMessage("$body\n\n$tailText", listOf(
            textPart(body), textPart(tailText),
        )) - "model")))
        bridge.reducedMotion = false
        launch(); openFixtureChat()
        waitForText(tailText)
        compose.waitForIdle()
        compose.onNodeWithText(tailText).assertIsDisplayed()
        val finalScroll = historyScroll()
        exposedHistorySwipe(towardBottom = false)
        waitForLatest()
        compose.onNodeWithTag("chat-history").performScrollToIndex(0)
        compose.waitForIdle()
        val bubble = compose.onNodeWithTag("message-bubble:$REPLY_ID:0").getUnclippedBoundsInRoot()
        val viewport = compose.onNodeWithTag("chat-history").getUnclippedBoundsInRoot()
        assertTrue("The final item must be much taller than its viewport", bubble.height.value > viewport.height.value * 3f)
        val start = historyScroll()
        assertTrue("The fixture must start above the previously rendered final offset", start < finalScroll)
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithContentDescription(strings.get("chat.latest")).assertIsDisplayed().performClick()
            var intermediate = false
            // A jump to the final offset cannot satisfy this intermediate-frame assertion.
            repeat(8) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                val position = historyScroll()
                if (position > start + .001f && position < finalScroll - .001f) intermediate = true
            }
            assertTrue("Latest must visibly progress through the tall item instead of jumping", intermediate)
            compose.mainClock.advanceTimeBy(5_000)
            compose.waitForIdle()
            compose.onNodeWithText(tailText).assertIsDisplayed()
            val tail = compose.onNodeWithTag("message-bubble:$REPLY_ID:1").getUnclippedBoundsInRoot()
            val composer = compose.onNodeWithTag("chat-composer").getUnclippedBoundsInRoot()
            assertEquals("Smooth scrolling must include the final measured content padding", composer.top.value - 20f, tail.bottom.value, 2f)
            compose.onNodeWithTag("chat-latest").assertDoesNotExist()
        } finally {
            compose.mainClock.autoAdvance = true
        }
        assertTrue(server.submitted.isEmpty())
    }

    @Test fun typedSendHasEntranceMotionOnComposeFrames() {
        server.history = JsonArray(listOf(assistantMessage("An existing reply keeps the chat ready.")))
        server.holdStream = true
        server.holdFirstStreamFrame = true
        bridge.reducedMotion = false
        launch(); openFixtureChat()
        val draft = "This typed message should enter smoothly."
        compose.onNode(hasSetTextAction()).performTextInput(draft)
        // Settle the native IME before examining motion driven by Compose frames.
        compose.waitUntil(TIMEOUT) { imeVisible() }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(TIMEOUT) { !imeVisible() }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithContentDescription(strings.get("chat.send")).performClick()
            var userId: String? = null
            compose.waitUntil(TIMEOUT) {
                // Network work is real; advance only one frame per rendered-state observation.
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                userId = controller.state.value.messages.lastOrNull { it.role == "user" && it.text == draft }?.id
                userId?.let { id ->
                    compose.onAllNodesWithTag("message-bubble:$id:0").fetchSemanticsNodes().isNotEmpty()
                } == true
            }
            val bubble = compose.onNodeWithTag("message-bubble:${requireNotNull(userId)}:0")
            val entrance = bubble.fetchSemanticsNode().boundsInRoot
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
            val settled = bubble.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("Typed user messages must finish their entrance scale over Compose frames (entrance=$entrance, settled=$settled)",
                settled.width > entrance.width + .1f * compose.activity.resources.displayMetrics.density)
            assertEquals("Only the typed draft must reach the host", draft, server.submitted.single().getValue("message").jsonPrimitive.content)
            assertEquals("", controller.state.value.draft)
            assertTrue(controller.state.value.busy)
            assertFalse("No provider text may race the entrance measurement", server.firstStreamFrame.isCompleted)
            assertFalse(server.completed)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun heldProviderShowsTypingAndActualTextReplacesItBeforeCompletion() {
        server.history = JsonArray(emptyList())
        server.holdStream = true
        server.holdFirstStreamFrame = true
        launch(); openFixtureChat()
        compose.onNode(hasSetTextAction()).performTextInput("Show the waiting indicator until actual text arrives")
        // Keep the real IME shown, but let its native window animation settle
        // before freezing the Compose clock and tapping the moving composer.
        compose.waitUntil(TIMEOUT) { imeVisible() }
        val imeSettledAfter = SystemClock.uptimeMillis() + 300
        compose.waitUntil(TIMEOUT) { imeVisible() && SystemClock.uptimeMillis() >= imeSettledAfter }
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithContentDescription(strings.get("chat.send")).assertIsEnabled().performClick()
            compose.waitUntil(TIMEOUT) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                server.submitted.size == 1
            }
            compose.waitUntil(TIMEOUT) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                controller.state.value.busy && controller.state.value.messages.any { it.role == "user" }
            }
            compose.mainClock.advanceTimeBy(400)
            compose.waitForIdle()
            compose.onNodeWithTag("chat-typing").assertDoesNotExist()
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
            compose.onNodeWithTag("chat-typing").assertIsDisplayed()
            assertTrue("The typing indicator must appear while the real IME stays visible", imeVisible())
        } catch (failure: Throwable) {
            captureWaitFailure("One submitted running job and delayed chat-typing while the IME stays shown", failure)
            throw failure
        } finally {
            compose.mainClock.autoAdvance = true
        }
        screenshot("media-typing-visible")
        assertTrue(controller.state.value.busy)
        assertFalse("The fixture must still withhold all provider content", server.firstStreamFrame.isCompleted)
        compose.onNodeWithText(LIVE_FIRST).assertDoesNotExist()
        server.firstStreamFrame.complete(Unit)
        waitForText(LIVE_FIRST); waitForText(NOTE_FIRST)
        compose.onNodeWithText(LIVE_FIRST).assertIsDisplayed()
        compose.onNodeWithText(NOTE_FIRST).assertIsDisplayed()
        compose.onNodeWithTag("chat-typing").assertDoesNotExist()
        assertFalse("Actual streamed text must render before provider completion", server.completeStream.isCompleted)
        assertTrue(controller.state.value.busy)
        server.nextStreamFrame.complete(Unit)
        server.completeStream.complete(Unit)
        compose.waitUntil(TIMEOUT) { server.completed && !controller.state.value.busy }
        assertEquals(1, server.submitted.size)
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
        compose.waitUntil(TIMEOUT) { server.historyReads.get() > reads }
        // Restored galleries load lazily; compose the user row before awaiting its download.
        compose.onNodeWithTag("chat-history").performScrollToIndex(0)
        try {
            compose.waitUntil(TIMEOUT) { server.downloads.isNotEmpty() }
        } catch (failure: Throwable) {
            captureWaitFailure("Authenticated restored image download after the user gallery is composed", failure)
            throw failure
        }
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

    @Test fun multiPickUploadsDistinctOriginalBytesAndSendsEveryManifest() {
        server.history = JsonArray(emptyList())
        val files = regressionMediaFiles()
        server.mediaFiles = files
        bridge.pickedImages = files.map { it.file }
        launch(); openFixtureChat()
        compose.onNodeWithContentDescription(strings.get("input.attach")).performClick()
        compose.onNodeWithText(strings.get("input.photo")).performClick()
        compose.waitUntil(TIMEOUT) { server.uploads.size == files.size && !controller.state.value.uploading }
        assertEquals(listOf("photo"), bridge.pickerKinds.toList())
        assertEquals(files.map { it.path }, controller.state.value.attachments.map { it.path })
        files.forEachIndexed { index, media ->
            val upload = server.uploads[index]
            assertTrue(upload.contentType.orEmpty().startsWith("multipart/form-data;"))
            assertTrue(upload.bytes.decodeToString().contains("filename=\"${media.file.name}\""))
            assertTrue("Each selected original must reach its own multipart request", upload.bytes.containsBytes(media.file.bytes))
            files.filterIndexed { other, _ -> other != index }.forEach { other ->
                assertFalse("Uploads must not reuse another selected image's bytes", upload.bytes.containsBytes(other.file.bytes))
            }
        }
        compose.onNodeWithContentDescription(strings.get("chat.send")).performClick()
        compose.waitUntil(TIMEOUT) { server.submitted.isNotEmpty() }
        val sent = server.submitted.single()
        assertEquals("", sent.getValue("message").jsonPrimitive.content)
        assertEquals(CHAT_ID, sent.getValue("session_id").jsonPrimitive.content)
        assertEquals(JsonArray(files.map { it.manifest() }), sent.getValue("attachments"))
        waitForText(IMAGE_REPLY)
    }

    @Test fun threeImageContactSheetPagesAndExportsSelectedOriginal() {
        val files = regressionMediaFiles()
        server.mediaFiles = files
        server.history = JsonArray(listOf(buildJsonObject {
            put("id", "gallery-human"); put("role", "user"); put("content", "Three saved images")
            put("attachments", JsonArray(files.map { it.manifest() }))
        }))
        launch(); openFixtureChat()
        compose.waitUntil(TIMEOUT) {
            files.all { controller.state.value.attachmentThumbnails[it.path]?.contentEquals(it.file.bytes) == true }
        }
        val gallery = compose.onNodeWithTag("attachment-gallery").performScrollTo()
        val sheet = gallery.getUnclippedBoundsInRoot()
        val first = compose.onNodeWithTag("gallery:upload:${files[0].path}").getUnclippedBoundsInRoot()
        val second = compose.onNodeWithTag("gallery:upload:${files[1].path}").getUnclippedBoundsInRoot()
        val third = compose.onNodeWithTag("gallery:upload:${files[2].path}").getUnclippedBoundsInRoot()
        assertTrue("Three images must share a compact contact sheet", sheet.height.value <= sheet.width.value + 4f)
        assertEquals("The first two images must share a row", first.top.value, second.top.value, 2f)
        assertEquals("The third image must share the compact three-column row", first.top.value, third.top.value, 2f)
        screenshot("media-gallery-restored-three")
        compose.onNodeWithTag("gallery:upload:${files[1].path}").performScrollTo().performClick()
        fun awaitPage(media: RegressionMedia) {
            compose.waitUntil(TIMEOUT) {
                controller.state.value.previewPath == media.path && !controller.state.value.loading &&
                    compose.onAllNodesWithTag("media-image:${media.path}").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("media-preview").assertIsDisplayed()
            compose.onNodeWithTag("media-pages").assertIsDisplayed()
            compose.onNodeWithTag("media-image:${media.path}").assertIsDisplayed()
            assertArrayEquals(media.file.bytes, controller.state.value.previewBytes)
        }
        awaitPage(files[1])
        screenshot("media-viewer-second-image")
        val window = compose.onNode(isDialog()).getUnclippedBoundsInRoot()
        val preview = compose.onNodeWithTag("media-preview").getUnclippedBoundsInRoot()
        assertTrue("The viewer must use the full available window width", preview.width.value >= window.width.value - 2f)
        compose.onNodeWithTag("media-pages").performTouchInput { swipeLeft(durationMillis = 400) }
        awaitPage(files[2])
        compose.onNodeWithText(strings.get("media.save")).assertIsEnabled().performClick()
        compose.waitUntil(TIMEOUT) { bridge.savedFiles.size == 1 && !controller.state.value.previewExporting }
        assertEquals(files[2].file.name, bridge.savedFiles.single().name)
        assertEquals(files[2].file.mimeType, bridge.savedFiles.single().mimeType)
        assertArrayEquals("Save must use the selected page's original bytes", files[2].file.bytes, bridge.savedFiles.single().bytes)
        compose.onNodeWithContentDescription(strings.get("media.previous")).performClick()
        awaitPage(files[1])
        compose.onNodeWithContentDescription(strings.get("media.share")).assertIsEnabled().performClick()
        compose.waitUntil(TIMEOUT) { bridge.sharedFiles.size == 1 && !controller.state.value.previewExporting }
        assertEquals(files[1].file.name, bridge.sharedFiles.single().name)
        assertEquals(files[1].file.mimeType, bridge.sharedFiles.single().mimeType)
        assertArrayEquals("Share must export the newly selected original, not the previously saved page", files[1].file.bytes, bridge.sharedFiles.single().bytes)
        assertTrue(server.downloads.all { it == "Bearer $DEVICE_TOKEN" })
        assertTrue(server.submitted.isEmpty())
    }

    @Test fun generatedWorkspaceFileWaitsForToolCompletionThenPreviewsAndSaves() {
        fun toolHistory(status: String): JsonArray {
            val step = buildJsonObject {
                put("id", "report-write"); put("label", "tools__workspace_write"); put("status", status)
                put("input", buildJsonObject { put("path", WORK_FILE_PATH) })
                if (status == "done") put("result", buildJsonObject { put("path", WORK_FILE_PATH); put("ok", true) })
            }
            return JsonArray(listOf(JsonObject(assistantMessage("The tool is preparing a report.") +
                ("steps" to JsonArray(listOf(step))))))
        }
        server.history = toolHistory("running")
        launch(); openFixtureChat()
        waitForText(strings.get("media.creating", "name" to WORK_FILE_PATH.substringAfterLast('/')))
        assertTrue(controller.state.value.messages.single().workFiles.single().active)
        compose.onNodeWithTag("file:workspace:$WORK_FILE_PATH").assertDoesNotExist()
        assertTrue("A running write must not trigger a workspace read", server.workspaceReads.isEmpty())
        assertTrue("A running write must not trigger an original-byte download", server.workspaceDownloads.isEmpty())
        val reads = server.historyReads.get()
        server.history = toolHistory("done")
        server.workspaceReady = true
        openFixtureChat()
        compose.waitUntil(TIMEOUT) {
            server.historyReads.get() > reads && controller.state.value.messages.single().workFiles.singleOrNull()?.active == false
        }
        compose.onNodeWithTag("file:workspace:$WORK_FILE_PATH").performScrollTo().assertIsDisplayed().performClick()
        waitForText(WORK_FILE_CONTENT)
        compose.onNodeWithTag("media-preview").assertIsDisplayed()
        assertEquals("workspace", controller.state.value.previewSource)
        assertEquals(WORK_FILE_PATH, controller.state.value.previewPath)
        screenshot("media-delivered-file-preview")
        compose.onNodeWithText(strings.get("media.save")).assertIsEnabled().performClick()
        compose.waitUntil(TIMEOUT) { bridge.savedFiles.size == 1 && !controller.state.value.previewExporting }
        val saved = bridge.savedFiles.single()
        assertEquals("generated-report.md", saved.name)
        assertEquals("text/markdown", saved.mimeType)
        assertArrayEquals("Export must preserve original line endings rather than substitute preview text", WORK_FILE_ORIGINAL.encodeToByteArray(), saved.bytes)
        assertEquals("Text preview must use the workspace content route", listOf(WORK_FILE_PATH), server.workspaceReads.toList())
        assertEquals("Export must use the authorized original-byte route", listOf(WORK_FILE_PATH), server.workspaceDownloads.toList())
        assertTrue(server.submitted.isEmpty())
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
                compose.onNodeWithContentDescription(strings.get("chat.latest")).assertDoesNotExist()
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

        // The history viewport now includes both panels; start all drags in exposed content.
        // Fetch geometry before holding a pointer across asynchronous note growth.
        val historyBounds = chat.fetchSemanticsNode().boundsInRoot
        val exposedTop = compose.onNodeWithTag("chat-header").fetchSemanticsNode().boundsInRoot.bottom - historyBounds.top
        val exposedBottom = compose.onNodeWithTag("chat-composer").fetchSemanticsNode().boundsInRoot.top - historyBounds.top
        val exposedHeight = exposedBottom - exposedTop
        assertTrue("Note-follow gestures need an exposed history region", exposedHeight > 0f)

        // Drag toward the bottom while already there, then hold the pointer across growth.
        // Following is still enabled, but the real user scroll owns the mutation.
        chat.performTouchInput {
            down(Offset(centerX, exposedTop + exposedHeight * .8f))
            advanceEventTime(100)
            moveTo(Offset(centerX, exposedTop + exposedHeight * .45f))
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
            swipe(Offset(centerX, exposedTop + exposedHeight * .2f),
                Offset(centerX, exposedTop + exposedHeight * .75f), durationMillis = 600)
        }
        waitForText(strings.get("chat.latest"), hasContentDescription(strings.get("chat.latest")))
        publishNote(48)
        compose.onNodeWithText(noteTail(48)).assertIsNotDisplayed()
        compose.onNodeWithContentDescription(strings.get("chat.latest")).assertIsDisplayed().performClick()
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

private data class RegressionMedia(val path: String, val file: PickedFile) {
    fun manifest() = buildJsonObject {
        put("url", path); put("name", file.name); put("type", file.mimeType); put("size", file.bytes.size)
    }
}

private fun regressionMediaFiles(): List<RegressionMedia> = listOf(
    android.graphics.Color.RED, android.graphics.Color.GREEN, android.graphics.Color.BLUE,
).mapIndexed { index, color ->
    val bytes = ByteArrayOutputStream().use { output ->
        val bitmap = Bitmap.createBitmap(8 + index, 8 + index, Bitmap.Config.ARGB_8888)
        try { bitmap.eraseColor(color); check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray() }
        finally { bitmap.recycle() }
    }
    val name = "gallery-${index + 1}.png"
    RegressionMedia("/uploads/$name", PickedFile(name, "image/png", bytes))
}

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
    val workspaceReads = CopyOnWriteArrayList<String>()
    val workspaceDownloads = CopyOnWriteArrayList<String>()
    @Volatile var workspaceReady = false
    @Volatile var mediaFiles = emptyList<RegressionMedia>()
    val historyReads = AtomicInteger()
    val jobReads = AtomicInteger()
    val streamRequests = AtomicInteger()
    val runningFrames = AtomicInteger()
    val firstStreamFrame = CompletableDeferred<Unit>()
    val nextStreamFrame = CompletableDeferred<Unit>()
    val completeStream = CompletableDeferred<Unit>()
    val noteRows = Channel<Int>(Channel.UNLIMITED)
    @Volatile var holdStream = false
    @Volatile var holdFirstStreamFrame = false
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
                path == "/api/brain/intelligence" && request.method == HttpMethod.Get -> """{"available":false}"""
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
                    if (mediaFiles.isEmpty()) imageManifest.toString() else {
                        val matches = mediaFiles.filter { bytes.containsBytes(it.file.bytes) }
                        check(matches.size == 1) { "Multipart upload must contain exactly one expected original" }
                        matches.single().manifest().toString()
                    }
                }
                mediaFiles.any { it.path == path } && request.method == HttpMethod.Get -> {
                    val media = mediaFiles.single { it.path == path }
                    check(request.headers[HttpHeaders.Authorization] == "Bearer $DEVICE_TOKEN")
                    downloads += request.headers[HttpHeaders.Authorization]
                    return@MockEngine respond(media.file.bytes, headers = headersOf(HttpHeaders.ContentType, media.file.mimeType))
                }
                path == "/api/workspace/file" && request.method == HttpMethod.Get -> {
                    if (!workspaceReady) {
                        unexpected += "Workspace read before tool completion"
                        error("Running generated files must not be read")
                    }
                    check(request.url.parameters["path"] == WORK_FILE_PATH)
                    check(request.url.parameters["session_id"] == CHAT_ID)
                    check(request.headers[HttpHeaders.Authorization] == "Bearer $DEVICE_TOKEN")
                    workspaceReads += WORK_FILE_PATH
                    buildJsonObject {
                        put("path", WORK_FILE_PATH); put("content", WORK_FILE_CONTENT); put("binary", false)
                        put("mime_type", "text/markdown"); put("too_large", false); put("size", WORK_FILE_CONTENT.encodeToByteArray().size)
                    }.toString()
                }
                path == "/api/mobile/workspace/download" && request.method == HttpMethod.Get -> {
                    if (!workspaceReady) {
                        unexpected += "Workspace download before tool completion"
                        error("Running generated files must not be downloaded")
                    }
                    check(request.url.parameters["path"] == WORK_FILE_PATH)
                    check(request.url.parameters["session_id"] == CHAT_ID)
                    check(request.url.parameters.names() == setOf("path", "session_id"))
                    check(request.headers[HttpHeaders.Authorization] == "Bearer $DEVICE_TOKEN")
                    workspaceDownloads += WORK_FILE_PATH
                    return@MockEngine respond(WORK_FILE_ORIGINAL.encodeToByteArray(), headers = headersOf(HttpHeaders.ContentType, "text/markdown"))
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
                    jobReads.incrementAndGet()
                    if ((noteOnlyStream || holdFirstStreamFrame) && submitted.isNotEmpty() && !completed) buildJsonObject {
                        put("messages", JsonArray(listOf(JsonObject(submitted.single() + mapOf(
                            "id" to JsonPrimitive("regression-job"), "state" to JsonPrimitive("running"),
                        )))))
                    }.toString() else """{"messages":[]}"""
                }
                path == "/api/mobile/messages/regression-job/events" -> {
                    streamRequests.incrementAndGet()
                    val channel = ByteChannel(autoFlush = true)
                    scope.launch {
                        try {
                            var sequence = 0
                            suspend fun event(name: String, payload: JsonObject) {
                                channel.writeStringUtf8("id: ${++sequence}\nevent: $name\ndata: $payload\n\n")
                            }
                            event("mobile_state", buildJsonObject { put("state", "running") })
                            runningFrames.incrementAndGet()
                            if (holdFirstStreamFrame) firstStreamFrame.await()
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
    override var reducedMotion = true
    override val foreground = MutableStateFlow(true)
    override val incomingPairing = MutableStateFlow<String?>(null)
    val preferences = mutableMapOf<String, String>()
    private val secrets = mutableMapOf<String, String>()
    private val ids = AtomicInteger()
    val pickerKinds = CopyOnWriteArrayList<String>()
    var pickedImage: PickedFile? = null
    var pickedImages: List<PickedFile>? = null
    val savedFiles = CopyOnWriteArrayList<PickedFile>()
    val sharedFiles = CopyOnWriteArrayList<PickedFile>()
    var copied: String? = null
    override fun readPreference(key: String) = preferences[key]
    override fun writePreference(key: String, value: String?) { if (value == null) preferences.remove(key) else preferences[key] = value }
    override fun readSecret(key: String) = secrets[key]
    override fun writeSecret(key: String, value: String?) { if (value == null) secrets.remove(key) else secrets[key] = value }
    override fun scanQr(onResult: (String?) -> Unit) { onResult("claudebot://pair?server=https%3A%2F%2Fchat-regression.example&code=regression-pairing") }
    override fun pickFile(kind: String, onResult: (PickedFile?) -> Unit) { pickerKinds += kind; onResult(pickedImage) }
    override fun pickFiles(kind: String, onResult: (List<PickedFile>) -> Unit) {
        if (pickedImages == null) pickFile(kind) { onResult(listOfNotNull(it)) }
        else { pickerKinds += kind; onResult(requireNotNull(pickedImages)) }
    }
    override fun saveFile(file: PickedFile, onResult: (Boolean) -> Unit) { savedFiles += file; onResult(true) }
    override fun shareFile(file: PickedFile, onResult: (Boolean) -> Unit) { sharedFiles += file; onResult(true) }
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
private const val WORK_FILE_PATH = "reports/generated-report.md"
private const val WORK_FILE_CONTENT = "Generated report keeps its complete original content, including the final export marker."
private const val WORK_FILE_ORIGINAL = WORK_FILE_CONTENT + "\r\n"
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
