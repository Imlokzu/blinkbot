package me.waveio.claudebot

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import me.waveio.claudebot.ui.chatEdges
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/** Empty space and opaque media must soften too; foreground controls stay sharp. */
class ChatEdgesRenderingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun wholeBackdropSoftensOnlyUnderPanels() {
        val enabled = mutableStateOf(false)
        val media = mutableStateOf(false)
        compose.setContent {
            val wallpaper = rememberGraphicsLayer()
            Box(Modifier.size(340.dp, 420.dp).testTag("edge-fixture")) {
                Box(Modifier.matchParentSize().drawWithContent {
                    wallpaper.record {
                        // High-frequency wallpaper makes blur measurable even
                        // where no message occupies the strip.
                        for (x in 0 until size.width.toInt() step 4) {
                            drawRect(if (x % 8 == 0) Color.White else Color.Black,
                                Offset(x.toFloat(), 0f), Size(4f, size.height))
                        }
                    }
                    drawLayer(wallpaper)
                })
                Box(Modifier.matchParentSize().then(if (enabled.value)
                    Modifier.chatEdges(96.dp, 96.dp, wallpaper) { Offset.Zero } else Modifier)) {
                    if (media.value) Box(Modifier.align(Alignment.Center).width(100.dp).fillMaxHeight().drawBehind {
                        for (y in 0 until size.height.toInt() step 4) {
                            drawRect(if (y % 8 == 0) Color.Red else Color.Blue,
                                Offset(0f, y.toFloat()), Size(size.width, 4f))
                        }
                    })
                }
                Box(Modifier.align(Alignment.TopCenter).padding(top = 8.dp).size(28.dp)
                    .drawBehind { drawRect(Color.Green) }.testTag("sharp-control"))
            }
        }
        fun capture() = compose.onNodeWithTag("edge-fixture").captureToImage()
        fun changes(before: ImageBitmap, after: ImageBitmap, x0: Float, x1: Float, y0: Float, y1: Float): Int {
            val a = before.toPixelMap(); val b = after.toPixelMap()
            var changed = 0
            for (y in (a.height * y0).toInt() until (a.height * y1).toInt()) {
                for (x in (a.width * x0).toInt() until (a.width * x1).toInt()) {
                    if (a[x, y] != b[x, y]) changed++
                }
            }
            return changed
        }
        fun contrast(image: ImageBitmap, x0: Float, x1: Float, y0: Float, y1: Float, vertical: Boolean = false): Float {
            val pixels = image.toPixelMap()
            var total = 0f
            var samples = 0
            for (y in (pixels.height * y0).toInt() until (pixels.height * y1).toInt()) {
                for (x in (pixels.width * x0).toInt() until (pixels.width * x1).toInt()) {
                    val a = pixels[x, y]
                    val b = pixels[x + if (vertical) 0 else 4, y + if (vertical) 4 else 0]
                    total += abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue)
                    samples++
                }
            }
            return total / samples
        }
        for (hasMedia in listOf(false, true)) {
            compose.runOnIdle { media.value = hasMedia; enabled.value = false }
            val sharp = capture()
            val control = compose.onNodeWithTag("sharp-control").captureToImage()
            compose.runOnIdle { enabled.value = true }
            val softened = capture()
            // Probe both outer sides, beyond any narrow message column.
            for ((left, right) in listOf(.02f to .12f, .88f to .98f)) {
                assertTrue("Wallpaper must soften across the top strip", changes(sharp, softened, left, right, .03f, .13f) > 100)
                assertTrue("Wallpaper must soften across the bottom strip", changes(sharp, softened, left, right, .93f, .98f) > 100)
                // A tint alone changes pixels but retains stripe contrast. These
                // checks require actual spatial smoothing of the wallpaper.
                assertTrue("Top wallpaper detail must blur, not merely darken",
                    contrast(softened, left, right, .03f, .13f) < contrast(sharp, left, right, .03f, .13f) * .55f)
                assertTrue("Bottom wallpaper detail must blur, not merely darken",
                    contrast(softened, left, right, .93f, .98f) < contrast(sharp, left, right, .93f, .98f) * .55f)
            }
            assertEquals("The middle reading region must be unchanged", 0, changes(sharp, softened, .02f, .98f, .4f, .6f))
            if (hasMedia) {
                assertTrue("Opaque non-text content must soften under the header", changes(sharp, softened, .4f, .6f, .13f, .18f) > 100)
                assertTrue("Opaque non-text content must soften under the composer", changes(sharp, softened, .4f, .6f, .93f, .98f) > 100)
                assertTrue("Media detail under the header must blur, not merely darken",
                    contrast(softened, .4f, .6f, .13f, .15f, true) < contrast(sharp, .4f, .6f, .13f, .15f, true) * .55f)
                assertTrue("Media detail under the composer must blur, not merely darken",
                    contrast(softened, .4f, .6f, .93f, .98f, true) < contrast(sharp, .4f, .6f, .93f, .98f, true) * .55f)
            }
            assertEquals("Foreground control pixels must remain unchanged", 0,
                changes(control, compose.onNodeWithTag("sharp-control").captureToImage(), 0f, 1f, 0f, 1f))
        }
    }
}
