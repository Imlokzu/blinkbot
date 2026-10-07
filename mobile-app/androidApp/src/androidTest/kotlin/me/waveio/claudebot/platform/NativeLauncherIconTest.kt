package me.waveio.claudebot.platform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
            // The spiral's open center and transparent surround must survive
            // conversion to a vector; an opaque square hides themed backgrounds.
            assertEquals(Color.TRANSPARENT, bitmap.getPixel(32, 32))
            val safeRadius = bitmap.width * 33f / 108f
            var opaquePixels = 0
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val dx = x + 0.5f - bitmap.width / 2f
                val dy = y + 0.5f - bitmap.height / 2f
                if (dx * dx + dy * dy > safeRadius * safeRadius) {
                    assertEquals("Mark leaves adaptive safe zone at ($x, $y)", 0, Color.alpha(pixel))
                }
                if (Color.alpha(pixel) >= 128) {
                    assertEquals(255, Color.red(pixel))
                    assertEquals(255, Color.green(pixel))
                    assertEquals(255, Color.blue(pixel))
                    opaquePixels++
                }
            }
            assertTrue("The launcher mark must remain visible", opaquePixels > 200)
        } finally { bitmap.recycle() }
    }
}
