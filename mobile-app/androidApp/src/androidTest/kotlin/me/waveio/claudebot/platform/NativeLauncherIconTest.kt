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
    @Test fun manifestLoadsAnAdaptiveIconWithTheExistingMascot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(R.mipmap.ic_launcher, context.applicationInfo.icon)
        val drawable = context.applicationInfo.loadIcon(context.packageManager) as AdaptiveIconDrawable
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            drawable.foreground.setBounds(0, 0, 64, 64)
            drawable.foreground.draw(Canvas(bitmap))
            assertEquals(Color.rgb(224, 138, 103), bitmap.getPixel(32, 25))
            assertEquals(Color.rgb(19, 17, 15), bitmap.getPixel(26, 32))
            assertEquals(Color.rgb(19, 17, 15), bitmap.getPixel(38, 32))
        } finally { bitmap.recycle() }
    }
}
