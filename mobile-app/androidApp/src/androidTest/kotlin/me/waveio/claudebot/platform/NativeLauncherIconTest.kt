package me.waveio.claudebot.platform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.abs
import me.waveio.claudebot.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeLauncherIconTest {
    @Test fun manifestLoadsTheBlinkMarkInsideTheAdaptiveSafeZone() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(R.mipmap.ic_launcher, context.applicationInfo.icon)
        val drawable = context.applicationInfo.loadIcon(context.packageManager) as AdaptiveIconDrawable
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            drawable.foreground.setBounds(0, 0, 64, 64)
            drawable.foreground.draw(Canvas(bitmap))
            // The cube's open center and transparent surround must survive
            // conversion to a vector; an opaque square hides themed backgrounds.
            assertEquals(Color.TRANSPARENT, bitmap.getPixel(32, 32))
            val safeRadius = bitmap.width * 33f / 108f
            val orange = Color.rgb(230, 83, 47)
            var orangePixels = 0
            var whitePixels = 0
            // Antialiased edges are premultiplied when rasterized; allow only
            // the rounding error from converting them back to RGB.
            fun matches(pixel: Int, color: Int) =
                abs(Color.red(pixel) - Color.red(color)) <= 2 &&
                    abs(Color.green(pixel) - Color.green(color)) <= 2 &&
                    abs(Color.blue(pixel) - Color.blue(color)) <= 2
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val dx = x + 0.5f - bitmap.width / 2f
                val dy = y + 0.5f - bitmap.height / 2f
                if (dx * dx + dy * dy > safeRadius * safeRadius) {
                    assertEquals("Mark leaves adaptive safe zone at ($x, $y)", 0, Color.alpha(pixel))
                }
                if (Color.alpha(pixel) >= 128) {
                    when {
                        matches(pixel, orange) -> orangePixels++
                        matches(pixel, Color.WHITE) -> whitePixels++
                        else -> fail("Unexpected launcher color at ($x, $y): ${Integer.toHexString(pixel)}")
                    }
                }
            }
            assertTrue("The cube retains its orange #E6532F face", orangePixels > 20)
            assertTrue("The cube retains its white faces", whitePixels > 100)
            assertTrue("The launcher mark must remain visible", orangePixels + whitePixels > 200)
        } finally { bitmap.recycle() }
    }
}
