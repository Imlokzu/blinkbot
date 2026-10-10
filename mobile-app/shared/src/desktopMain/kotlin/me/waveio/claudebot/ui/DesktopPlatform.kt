package me.waveio.claudebot.ui

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.jetbrains.skia.Image
import androidx.compose.ui.window.DialogProperties

actual fun mediaPreviewDialogProperties() = DialogProperties(usePlatformDefaultWidth = false)

/** Desktop Escape uses the same registered back actions as Android. */
object DesktopBackActions {
    private val handlers = linkedMapOf<Any, () -> Unit>()
    fun invoke(): Boolean = handlers.values.lastOrNull()?.let { it(); true } ?: false
    internal fun register(owner: Any, action: () -> Unit) { handlers[owner] = action }
    internal fun remove(owner: Any) { handlers.remove(owner) }
}

@Composable
actual fun NativeBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val owner = remember { Any() }
    val action by rememberUpdatedState(onBack)
    DisposableEffect(enabled) {
        if (enabled) DesktopBackActions.register(owner) { action() }
        onDispose { DesktopBackActions.remove(owner) }
    }
}

@Composable actual fun NativeDialogChrome(dark: Boolean) = Unit

private fun scaledImage(bytes: ByteArray, maxDimension: Int): java.awt.image.BufferedImage? {
    if (bytes.isEmpty() || bytes.size > 20 * 1024 * 1024 || maxDimension !in 1..8192) return null
    return runCatching {
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            val readers = ImageIO.getImageReaders(input)
            if (!readers.hasNext()) return null
            val reader = readers.next()
            try {
                reader.input = input
                val width = reader.getWidth(0); val height = reader.getHeight(0)
                if (width <= 0 || height <= 0 || width.toLong() * height > 100_000_000) return null
                val sample = (maxOf(width, height) / maxDimension).coerceAtLeast(1)
                val params = reader.defaultReadParam.apply { setSourceSubsampling(sample, sample, 0, 0) }
                val decoded = reader.read(0, params)
                val largest = maxOf(decoded.width, decoded.height)
                if (largest <= maxDimension) decoded else {
                    val scale = maxDimension.toDouble() / largest
                    val result = java.awt.image.BufferedImage((decoded.width * scale).toInt().coerceAtLeast(1),
                        (decoded.height * scale).toInt().coerceAtLeast(1), java.awt.image.BufferedImage.TYPE_INT_ARGB)
                    val graphics = result.createGraphics()
                    try {
                        graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                        graphics.drawImage(decoded, 0, 0, result.width, result.height, null)
                    } finally { graphics.dispose(); decoded.flush() }
                    result
                }
            } finally { reader.dispose() }
        }
    }.getOrNull()
}

actual fun decodeImage(bytes: ByteArray, maxDimension: Int): ImageBitmap? = runCatching {
    val image = scaledImage(bytes, maxDimension) ?: return null
    val buffer = ByteArrayOutputStream()
    ImageIO.write(image, "png", buffer)
    Image.makeFromEncoded(buffer.toByteArray()).toComposeImageBitmap()
}.getOrNull()

actual fun thumbnailBytes(bytes: ByteArray): ByteArray? = runCatching {
    val image = scaledImage(bytes, 512) ?: return null
    ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
}.getOrNull()
