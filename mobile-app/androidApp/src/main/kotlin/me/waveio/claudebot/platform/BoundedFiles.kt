package me.waveio.claudebot.platform

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Never trust a document provider's advertised size before allocating bytes. */
internal object BoundedFiles {
    const val MAX_BYTES = 20 * 1024 * 1024

    fun read(input: InputStream, limit: Int = MAX_BYTES, checkCancelled: () -> Unit = {}): ByteArray? {
        require(limit >= 0)
        val output = ByteArrayOutputStream(minOf(limit, 8192))
        val buffer = ByteArray(8192)
        while (true) {
            checkCancelled()
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), limit.toLong() - output.size() + 1).toInt())
            checkCancelled()
            if (count < 0) return output.toByteArray()
            if (count == 0) {
                val next = input.read()
                checkCancelled()
                if (next < 0) return output.toByteArray()
                if (output.size() == limit) return null
                output.write(next)
            } else {
                if (count > limit - output.size()) return null
                output.write(buffer, 0, count)
            }
        }
    }
}
