package me.waveio.claudebot

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.platform.app.InstrumentationRegistry
import me.waveio.claudebot.ui.ChatMarkdown
import me.waveio.claudebot.ui.LocalReducedMotion
import me.waveio.claudebot.ui.MobileTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Pixel comparisons use the actual Markdown glyph layout and paused Compose frames. */
class StreamingTailRenderingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val content = mutableStateOf("")
    private val streaming = mutableStateOf(true)
    private val reduced = mutableStateOf(false)
    private val mount = mutableStateOf(0)

    @After fun restoreClock() { compose.mainClock.autoAdvance = true }

    private fun launch(text: String, reduceMotion: Boolean = false) {
        content.value = text
        reduced.value = reduceMotion
        compose.setContent {
            MobileTheme(false) {
                CompositionLocalProvider(LocalReducedMotion provides reduced.value) {
                    Box(Modifier.size(340.dp, 280.dp).drawBehind { drawRect(Color.White) }
                        .testTag("tail-fixture").padding(18.dp)) {
                        key(mount.value) { ChatMarkdown(content.value, streaming.value) }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private fun rendered(text: String) = compose.onNodeWithText(text, useUnmergedTree = true)
    private fun append(markdown: String, visibleText: String) {
        compose.runOnIdle { content.value = markdown }
        awaitParsedText(visibleText)
    }
    private fun awaitParsedText(visibleText: String) {
        compose.waitUntil(5000) {
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodesWithText(visibleText, useUnmergedTree = true).fetchSemanticsNodes().size == 1 &&
                // The loading Text has the same string but no Markdown style spans.
                // Wait for parsing, never for the blur animation to settle.
                layout(visibleText).layoutInput.text.spanStyles.isNotEmpty()
        }
        // Layout and the effect observe the same freshly parsed text before capture.
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }
    private fun capture() = compose.onNodeWithTag("tail-fixture").captureToImage()
    private fun layout(text: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        rendered(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }
    private fun bounds(text: String, start: Int): Rect {
        val glyphs = layout(text).getPathForRange(start, text.length).getBounds()
        val offset = rendered(text).fetchSemanticsNode().boundsInRoot.topLeft -
            compose.onNodeWithTag("tail-fixture").fetchSemanticsNode().boundsInRoot.topLeft
        return glyphs.translate(offset)
    }
    private fun assertOnlyTailChanges(early: ImageBitmap, settled: ImageBitmap, tail: Rect) {
        assertEquals(early.width, settled.width); assertEquals(early.height, settled.height)
        val before = early.toPixelMap(); val after = settled.toPixelMap()
        var changedTail = 0; var changedOutside = 0
        for (y in 0 until before.height) for (x in 0 until before.width) {
            if (before[x,y].toArgb() == after[x,y].toArgb()) continue
            if (x >= tail.left - 1 && x <= tail.right + 1 && y >= tail.top - 1 && y <= tail.bottom + 1) changedTail++
            else changedOutside++
        }
        assertTrue("Fresh glyph pixels must actually soften and resolve", changedTail > 20)
        assertEquals("Previously rendered text and surrounding surface stay pixel-identical", 0, changedOutside)
    }
    private fun assertSamePixels(first: ImageBitmap, second: ImageBitmap) {
        val a=first.toPixelMap();val b=second.toPixelMap()
        assertEquals(a.width,b.width);assertEquals(a.height,b.height)
        var changes=0
        for(y in 0 until a.height) for(x in 0 until a.width) if(a[x,y].toArgb()!=b[x,y].toArgb()) changes++
        assertEquals("Idle/completed/reduced text must not keep pulsing",0,changes)
    }
    private fun screenshot(name: String) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val bitmap=instrumentation.uiAutomation.takeScreenshot()
        val directory=File(instrumentation.targetContext.getExternalFilesDir(null),"stream-tail-qa").apply{mkdirs()}
        File(directory,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }

    private fun visibleTexts(): List<String> = compose.onAllNodes(
        hasText("", substring = true), useUnmergedTree = true
    ).fetchSemanticsNodes().flatMap { node ->
        node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text }
    }

    @Test fun aLiveWordHasAnIntermediateSubwordFrameBeforeProviderCompletion() {
        val prefix = "Already sharp: "
        val full = prefix + "microfragment"
        launch(prefix)
        compose.runOnIdle { content.value = full }
        var partial: String? = null
        repeat(4) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            partial = partial ?: visibleTexts().firstOrNull {
                it.startsWith(prefix) && it.length > prefix.length && it.length < full.length
            }
        }
        assertNotNull("A live append must expose an actual subword frame", partial)
        assertTrue("Provider must still be running", streaming.value)
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        // The pure reveal test checks the deadline; this also waits for asynchronous parsing.
        append(full, full)
        assertEquals(full, layout(full).layoutInput.text.text)
    }

    @Test fun authoritativeCorrectionAndTruncationDoNotReplayOrRestoreQueuedText() {
        val prefix = "Stable prefix: "
        launch(prefix)
        compose.runOnIdle { content.value = prefix + "microfragment" }
        compose.mainClock.advanceTimeByFrame()
        // This corrected snapshot still extends the original visible prefix.
        val corrected = prefix + "micro"
        append(corrected, corrected)
        val correction = capture()
        compose.mainClock.advanceTimeBy(500)
        assertSamePixels(correction, capture())
        rendered(corrected).assertExists()
        append("Replacement", "Replacement")
        val replacement = capture()
        compose.mainClock.advanceTimeBy(500)
        assertSamePixels(replacement, capture())
        rendered("Replacement").assertExists()
    }

    @Test fun terminalAndReducedMotionFlushAPendingSubwordOnTheNextComposition() {
        val prefix = "Stable prefix: "
        launch(prefix)
        val full = prefix + "microfragment"
        compose.runOnIdle { content.value = full }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { streaming.value = false }
        append(full, full)
        val terminal = capture()
        compose.mainClock.advanceTimeBy(500)
        assertSamePixels(terminal, capture())
        compose.runOnIdle { streaming.value = true }
        compose.mainClock.advanceTimeByFrame()
        val more = full + " uninterrupted"
        compose.runOnIdle { content.value = more }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { reduced.value = true }
        append(more, more)
        val reducedFrame = capture()
        compose.mainClock.advanceTimeBy(500)
        assertSamePixels(reducedFrame, capture())
        rendered(more).assertExists()
    }

    @Test fun remountedLongLiveBlocksAndHistoryStartCompleteAndSharp() {
        val full = "An existing long paragraph that must not be replayed. ".repeat(8).trimEnd()
        launch(full)
        compose.runOnIdle { mount.value++ }
        compose.mainClock.advanceTimeByFrame()
        rendered(full).assertExists()
        awaitParsedText(full)
        val remounted = capture()
        compose.mainClock.advanceTimeBy(500)
        assertSamePixels(remounted, capture())
        compose.runOnIdle { streaming.value = false; mount.value++ }
        compose.mainClock.advanceTimeByFrame()
        rendered(full).assertExists()
        awaitParsedText(full)
        val history = capture()
        compose.mainClock.advanceTimeBy(500)
        assertSamePixels(history, capture())
    }

    @Test fun settledTableRetainsHorizontalScrollAcrossCompletionAndReducedMotion() {
        launch("""
            | First | Second | Third | Fourth |
            | --- | --- | --- | --- |
            | Alpha | Bravo | Charlie | Delta |
        """.trimIndent())
        compose.mainClock.autoAdvance = true
        val horizontal = SemanticsMatcher("scrollable Markdown table") { node ->
            node.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)?.maxValue()?.let { it > 0f } == true
        }
        compose.waitUntil(5000) {
            compose.onAllNodes(horizontal, useUnmergedTree = true).fetchSemanticsNodes().size == 1
        }
        val table = compose.onNode(horizontal, useUnmergedTree = true)
        table.performTouchInput {
            swipe(Offset(width - 8f, centerY), Offset(8f, centerY), durationMillis = 400)
        }
        compose.waitForIdle()
        val scrolled = table.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value()
        assertTrue("Exercise a real nonzero table scroll before changing modes", scrolled > 0f)
        fun assertScrollRetained() {
            compose.waitForIdle()
            assertEquals("Settled Markdown must keep its table viewport", scrolled,
                table.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value(), 0.5f)
        }
        compose.runOnIdle { streaming.value = false }
        assertScrollRetained()
        compose.runOnIdle { streaming.value = true }
        assertScrollRetained()
        compose.runOnIdle { reduced.value = true }
        assertScrollRetained()
        compose.runOnIdle { reduced.value = false }
        assertScrollRetained()
    }

    @Test fun intermediateUnicodeFramesNeverEndInsideAJoinedGlyph() {
        val prefix = "Unicode: "
        // Keep enough short graphemes in the bounded suffix for the asynchronous
        // parser to publish intermediate layouts, alongside the joined glyphs.
        val glyphs = listOf("e\u0301", "\uD83D\uDC69\u200D\uD83D\uDCBB", "\uD83C\uDDFA\uD83C\uDDF8", "\uD83D\uDC4D\uD83C\uDFFD") +
            "abcdefgh".map { it.toString() }
        val full = prefix + glyphs.joinToString("")
        val valid = mutableSetOf(prefix)
        var text = prefix
        for (glyph in glyphs) { text += glyph; valid += text }
        launch(prefix)
        compose.runOnIdle { content.value = full }
        var intermediate = false
        repeat(8) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            visibleTexts().filter { it.startsWith(prefix) }.forEach { visible ->
                assertTrue("Frame split a joined glyph: $visible", visible in valid)
                if (visible != prefix && visible != full) intermediate = true
            }
        }
        assertTrue("Exercise at least one intermediate Unicode frame", intermediate)
        append(full, full)
    }

    @Test fun onlyNewSuffixSoftensThenSettlesWhileProviderRemainsLive() {
        val prefix="A clear sentence already on screen. Earlier words stay sharp before "
        val suffix="the new words."
        launch(prefix)
        append(prefix+suffix,prefix+suffix)
        assertEquals(prefix+suffix,layout(prefix+suffix).layoutInput.text.text)
        val tail=bounds(prefix+suffix,prefix.length)
        val early=capture();screenshot("tail-arriving")
        compose.mainClock.advanceTimeBy(300)
        val settled=capture();screenshot("tail-settled")
        assertOnlyTailChanges(early,settled,tail)
        assertTrue(streaming.value)
        compose.mainClock.advanceTimeBy(1000)
        assertSamePixels(settled,capture())
    }

    @Test fun wrappedMarkdownTailKeepsEarlierParagraphAndFormattingCrisp() {
        val prefix="An **older** phrase is already clear.\n\nThe current line:"
        val suffix=" soft  \nnew text"
        val visible="The current line: soft\nnew text"
        launch(prefix)
        append(prefix+suffix,visible)
        val measured=layout(visible)
        assertTrue("Fixture must exercise a tail spanning multiple lines",measured.lineCount>=2)
        assertTrue(layout("An older phrase is already clear.").layoutInput.text.spanStyles.any {
            (it.item.fontWeight?.weight ?: 0)>=FontWeight.Bold.weight
        })
        val early=capture()
        val tail=bounds(visible,"The current line:".length)
        compose.mainClock.advanceTimeBy(300)
        assertOnlyTailChanges(early,capture(),tail)
    }

    @Test fun reducedMotionAndCompletionRemainSharpWithoutWaitingForProvider() {
        val prefix="An existing line. "
        launch(prefix,reduceMotion=true)
        append(prefix+"New text.",prefix+"New text.")
        val reducedFrame=capture()
        compose.mainClock.advanceTimeBy(700)
        assertSamePixels(reducedFrame,capture())
        compose.runOnIdle { reduced.value=false }
        append(prefix+"New text. More.",prefix+"New text. More.")
        compose.runOnIdle { streaming.value=false }
        compose.mainClock.advanceTimeByFrame()
        awaitParsedText(prefix+"New text. More.")
        val complete=capture()
        compose.mainClock.advanceTimeBy(700)
        assertSamePixels(complete,capture())
    }
}
