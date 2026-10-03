package me.waveio.claudebot.platform

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import me.waveio.claudebot.ui.decodeImage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Use ordinary in-memory images, without providers, network access, or camera input. */
@RunWith(AndroidJUnit4::class)
class ImageDecodeTest {
    private fun encoded(width: Int, height: Int, format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(24, 80, 160))
            return ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(format, 90, output))
                output.toByteArray()
            }
        } finally { bitmap.recycle() }
    }

    @Test fun smallImageKeepsGeometryAndPixels() {
        val decoded = decodeImage(encoded(53, 31, Bitmap.CompressFormat.PNG))!!
        val bitmap = decoded.asAndroidBitmap()
        try {
            assertEquals(53, decoded.width)
            assertEquals(31, decoded.height)
            assertEquals(Color.rgb(24, 80, 160), bitmap.getPixel(20, 10))
        } finally { bitmap.recycle() }
    }

    @Test fun largeJpegAndPngDownsampleBeforeAllocatingFullResolutionPixels() {
        for (format in listOf(Bitmap.CompressFormat.JPEG, Bitmap.CompressFormat.PNG)) {
            val bytes = encoded(4097, 3073, format)
            val decoded = decodeImage(bytes)!!
            val bitmap = decoded.asAndroidBitmap()
            try {
                assertTrue(decoded.width in 1..2048)
                assertTrue(decoded.height in 1..2048)
                assertTrue(decoded.width.toLong() * decoded.height <= 4_194_304L)
                assertTrue(bitmap.allocationByteCount <= 16 * 1024 * 1024)
                assertEquals(4097.0 / 3073, decoded.width.toDouble() / decoded.height, 0.01)
            } finally { bitmap.recycle() }
        }
    }

    @Test fun panoramicImageIsBoundedEvenWhenItsPixelCountIsSmall() {
        val decoded = decodeImage(encoded(8192, 32, Bitmap.CompressFormat.PNG))!!
        try {
            assertEquals(2048, decoded.width)
            assertEquals(8, decoded.height)
        } finally { decoded.asAndroidBitmap().recycle() }
    }

    @Test fun emptyAndUnsupportedImagesReturnNull() {
        assertNull(decodeImage(byteArrayOf()))
        assertNull(decodeImage("Ordinary non-image fixture".toByteArray()))
    }

    @Test fun encodedByteLimitAcceptsTwentyMiBAndRejectsOneMoreByte() {
        val image = encoded(2, 2, Bitmap.CompressFormat.PNG)
        val padded = image.copyOf(20 * 1024 * 1024)
        val decoded = decodeImage(padded)!!
        try { assertEquals(2, decoded.width) } finally { decoded.asAndroidBitmap().recycle() }
        assertNull(decodeImage(padded.copyOf(padded.size + 1)))
    }
}
