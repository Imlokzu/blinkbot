package me.waveio.claudebot.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.cinterop.*
import org.jetbrains.skia.Image
import platform.CoreFoundation.*
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.ImageIO.*
import platform.UIKit.UIImage
import platform.UIKit.UIImagePNGRepresentation
import platform.posix.memcpy

private const val MAX_ENCODED_BYTES = 20 * 1024 * 1024
private const val MAX_SOURCE_DIMENSION = 32_768L
private const val MAX_SOURCE_PIXELS = 268_435_456L
private const val MAX_DECODED_DIMENSION = 2_048L

/** ImageIO downsamples before Skia sees pixels, preserving first-frame orientation. */
@OptIn(ExperimentalForeignApi::class)
private fun downsampledPng(bytes: ByteArray, maxDimension: Int): ByteArray? {
    val limit = maxDimension.toLong().coerceIn(1L, MAX_DECODED_DIMENSION)
    if (bytes.isEmpty() || bytes.size > MAX_ENCODED_BYTES) return null
    return runCatching {
        val data = bytes.usePinned {
            CFDataCreate(null, it.addressOf(0).reinterpret(), bytes.size.toLong())
        } ?: return null
        data.useCF {
            // Retain typed CF values; avoid ObjC collection casts and ambiguous
            // ownership of bridged constants.
            val options = CFDictionaryCreateMutable(null, 0,
                kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr) ?: return null
            options.useCF {
                CFDictionarySetValue(options, kCGImageSourceShouldCache, kCFBooleanFalse)
                val source = CGImageSourceCreateWithData(data, options) ?: return null
                source.useCF {
                    val properties = CGImageSourceCopyPropertiesAtIndex(source, 0UL, null) ?: return null
                    val dimensions = properties.useCF {
                        val width = dimension(properties, kCGImagePropertyPixelWidth) ?: return null
                        val height = dimension(properties, kCGImagePropertyPixelHeight) ?: return null
                        width to height
                    }
                    if (dimensions.first !in 1..MAX_SOURCE_DIMENSION || dimensions.second !in 1..MAX_SOURCE_DIMENSION ||
                        dimensions.first * dimensions.second > MAX_SOURCE_PIXELS) return null
                    val maximum = memScoped {
                        val value = alloc<LongVar> { this.value = limit }
                        CFNumberCreate(null, kCFNumberSInt64Type, value.ptr)
                    } ?: return null
                    maximum.useCF {
                        CFDictionarySetValue(options, kCGImageSourceThumbnailMaxPixelSize, maximum)
                        CFDictionarySetValue(options, kCGImageSourceCreateThumbnailFromImageAlways, kCFBooleanTrue)
                        CFDictionarySetValue(options, kCGImageSourceCreateThumbnailWithTransform, kCFBooleanTrue)
                        val thumbnail = CGImageSourceCreateThumbnailAtIndex(source, 0UL, options) ?: return null
                        thumbnail.useCF {
                            val width = CGImageGetWidth(thumbnail)
                            val height = CGImageGetHeight(thumbnail)
                            if (width !in 1UL..MAX_DECODED_DIMENSION.toULong() ||
                                height !in 1UL..MAX_DECODED_DIMENSION.toULong()) return null
                            // Encode only the bounded thumbnail. This keeps UIKit
                            // color/orientation conversion away from the full image.
                            val png = UIImagePNGRepresentation(UIImage.imageWithCGImage(thumbnail)) ?: return null
                            if (png.length == 0UL || png.length > MAX_ENCODED_BYTES.toULong()) return null
                            val thumbnailBytes = ByteArray(png.length.toInt())
                            thumbnailBytes.usePinned { memcpy(it.addressOf(0), png.bytes, png.length) }
                            thumbnailBytes
                        }
                    }
                }
            }
        }
    }.getOrNull()
}

actual fun decodeImage(bytes: ByteArray, maxDimension: Int): ImageBitmap? = runCatching {
    val encoded = downsampledPng(bytes, maxDimension) ?: return null
    Image.makeFromEncoded(encoded).toComposeImageBitmap()
}.getOrNull()

actual fun thumbnailBytes(bytes: ByteArray): ByteArray? = downsampledPng(bytes, 512)
    ?.takeIf { it.isNotEmpty() && it.size <= 8 * 1024 * 1024 }

@OptIn(ExperimentalForeignApi::class)
private fun dimension(properties: CFDictionaryRef, key: CFStringRef?): Long? = memScoped {
    val number = CFDictionaryGetValue(properties, key) ?: return null
    if (CFGetTypeID(number) != CFNumberGetTypeID()) return null
    val result = alloc<LongVar>()
    if (!CFNumberGetValue(number.reinterpret(), kCFNumberSInt64Type, result.ptr)) return null
    result.value
}

@OptIn(ExperimentalForeignApi::class)
private inline fun <T : CPointed, R> CPointer<T>.useCF(block: () -> R): R =
    try { block() } finally { CFRelease(this) }
