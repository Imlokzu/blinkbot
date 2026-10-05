package me.waveio.claudebot

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
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

    @After fun restoreClock() { compose.mainClock.autoAdvance = true }

    private fun launch(text: String, reduceMotion: Boolean = false) {
        content.value = text
        reduced.value = reduceMotion
        compose.setContent {
            MobileTheme(false) {
                CompositionLocalProvider(LocalReducedMotion provides reduced.value) {
                    Box(Modifier.size(340.dp, 280.dp).drawBehind { drawRect(Color.White) }
                        .testTag("tail-fixture").padding(18.dp)) {
                        ChatMarkdown(content.value, streaming.value)
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
        compose.waitUntil(5000) {
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodesWithText(visibleText, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
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
        val complete=capture()
        compose.mainClock.advanceTimeBy(700)
        assertSamePixels(complete,capture())
    }
}
