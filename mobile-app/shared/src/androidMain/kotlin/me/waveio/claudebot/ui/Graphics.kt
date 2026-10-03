package me.waveio.claudebot.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

private const val MAX_ENCODED_BYTES = 20 * 1024 * 1024
private const val MAX_SOURCE_DIMENSION = 32_768
private const val MAX_SOURCE_PIXELS = 268_435_456L
private const val MAX_DECODED_DIMENSION = 2_048
private const val MAX_DECODED_PIXELS = 4_194_304L

/** Inspect geometry first: a small encoded wallpaper can contain millions of pixels. */
actual fun decodeImage(bytes: ByteArray): ImageBitmap? {
    if (bytes.isEmpty() || bytes.size > MAX_ENCODED_BYTES) return null
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width !in 1..MAX_SOURCE_DIMENSION || height !in 1..MAX_SOURCE_DIMENSION ||
            width.toLong() * height > MAX_SOURCE_PIXELS) return null

        // BitmapFactory rounds arbitrary sample sizes down; use powers of two and
        // round dimensions up so odd-sized JPEG/PNG images remain within the cap.
        var sample = 1
        while ((width.toLong() + sample - 1) / sample > MAX_DECODED_DIMENSION ||
            (height.toLong() + sample - 1) / sample > MAX_DECODED_DIMENSION) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        if (bitmap.width > MAX_DECODED_DIMENSION || bitmap.height > MAX_DECODED_DIMENSION ||
            bitmap.width.toLong() * bitmap.height > MAX_DECODED_PIXELS ||
            bitmap.allocationByteCount.toLong() > MAX_DECODED_PIXELS * 4) {
            bitmap.recycle()
            return null
        }
        bitmap.asImageBitmap()
    }.getOrNull()
}
