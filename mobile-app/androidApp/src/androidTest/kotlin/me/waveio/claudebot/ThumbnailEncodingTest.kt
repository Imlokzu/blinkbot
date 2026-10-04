package me.waveio.claudebot

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.util.Random
import me.waveio.claudebot.ui.decodeImage
import me.waveio.claudebot.ui.thumbnailBytes
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThumbnailEncodingTest {
    @Test fun aLargeOriginalBecomesARealBoundedThumbnail() {
        val random = Random(41)
        val source = Bitmap.createBitmap(2048, 1536, Bitmap.Config.ARGB_8888)
        val row = IntArray(source.width)
        val original = try {
            repeat(source.height) { y ->
                for (x in row.indices) row[x] = 0xff000000.toInt() or random.nextInt(0x1000000)
                source.setPixels(row, 0, row.size, 0, y, row.size, 1)
            }
            ByteArrayOutputStream().use { output ->
                assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally { source.recycle() }
        assertTrue("The original must exceed the old thumbnail entry limit", original.size > 8 * 1024 * 1024)
        assertTrue("The original must be a supported attachment", original.size <= 20 * 1024 * 1024)
        val thumbnail = requireNotNull(thumbnailBytes(original))
        assertTrue(thumbnail.size in 1..(8 * 1024 * 1024))
        assertTrue(thumbnail.size < original.size)
        val decoded = requireNotNull(decodeImage(thumbnail))
        assertEquals(512, decoded.width)
        assertEquals(384, decoded.height)
        assertNull(thumbnailBytes(byteArrayOf(1, 2, 3)))
    }
}
