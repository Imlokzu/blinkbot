package me.waveio.claudebot.ui

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopImageDecodeTest {
    @Test
    fun decodeHonorsRequestedMaximumDimension() {
        val decoded = decodeImage(png(1200, 600), maxDimension = 128)

        assertTrue(decoded != null)
        assertTrue(decoded.width <= 128)
        assertTrue(decoded.height <= 128)
    }

    @Test
    fun thumbnailsArePngsDownsampledTo512Pixels() {
        val thumbnail = thumbnailBytes(png(1200, 600))

        assertTrue(thumbnail != null)
        val decoded = ImageIO.read(thumbnail.inputStream())
        assertEquals(512, decoded.width)
        assertEquals(256, decoded.height)
    }

    @Test
    fun malformedOversizedAndInvalidDimensionInputsAreRejected() {
        assertNull(decodeImage(byteArrayOf(1, 2, 3)))
        assertNull(thumbnailBytes(byteArrayOf(1, 2, 3)))
        assertNull(decodeImage(png(1, 1), maxDimension = 0))
        assertNull(decodeImage(png(1, 1), maxDimension = 8193))
        assertNull(decodeImage(ByteArray(20 * 1024 * 1024 + 1)))
        assertNull(decodeImage(pngHeaderOnly(20_000, 6_000)))
    }

    private fun png(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color(30, 80, 120, 255)
            graphics.fillRect(0, 0, width, height)
        } finally { graphics.dispose() }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    /** A valid PNG header lets the decoder reject pixel bombs before allocating a raster. */
    private fun pngHeaderOnly(width: Int, height: Int): ByteArray {
        val bytes = png(1, 1).copyOfRange(0, 33)
        writeInt(bytes, 16, width)
        writeInt(bytes, 20, height)
        val crc = java.util.zip.CRC32().apply { update(bytes, 12, 17) }.value.toInt()
        writeInt(bytes, 29, crc)
        return bytes
    }

    private fun writeInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }
}
