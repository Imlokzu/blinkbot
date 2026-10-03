package me.waveio.claudebot.platform

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class BoundedFilesTest {
    @Test fun acceptsExactLimitButRejectsNextByte() {
        assertArrayEquals(byteArrayOf(1, 2, 3), BoundedFiles.read(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3))
        assertNull(BoundedFiles.read(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), 3))
    }

    @Test fun readsProvidersThatDoNotAdvertiseTheirLength() {
        val stream = object : InputStream() {
            var remaining = 10_000
            override fun available() = 0
            override fun read() = if (remaining-- > 0) 42 else -1
        }
        assertNull(BoundedFiles.read(stream, 100))
        // Oversized input is rejected after limit+1 bytes, without draining it.
        assertEquals(9899, stream.remaining)
    }

    @Test fun zeroLengthAndZeroProgressStreamsTerminate() {
        assertArrayEquals(byteArrayOf(), BoundedFiles.read(ByteArrayInputStream(byteArrayOf()), 0))
        val stream = object : InputStream() {
            private var once = true
            override fun read(b: ByteArray, off: Int, len: Int) = 0
            override fun read() = if (once) { once = false; 42 } else -1
        }
        assertArrayEquals(byteArrayOf(42), BoundedFiles.read(stream, 1))
    }

    @Test fun defaultLimitAcceptsExactlyTwentyMiB() {
        // Generate the input rather than holding another 20 MiB fixture in memory.
        assertEquals(20 * 1024 * 1024, BoundedFiles.MAX_BYTES)
        val stream = RepeatedStream(BoundedFiles.MAX_BYTES.toLong())
        val bytes = BoundedFiles.read(stream)!!
        assertEquals(BoundedFiles.MAX_BYTES, bytes.size)
        assertEquals(42.toByte(), bytes.first())
        assertEquals(42.toByte(), bytes.last())
    }

    @Test fun defaultLimitRejectsOversizedUnknownLengthWithoutDraining() {
        val stream = RepeatedStream(BoundedFiles.MAX_BYTES.toLong() * 4)
        assertNull(BoundedFiles.read(stream))
        assertEquals(BoundedFiles.MAX_BYTES.toLong() + 1, stream.bytesRead)
        assertTrue(stream.remaining > 0)
    }

    @Test fun unknownLengthWithinLimitIsAcceptedAcrossBufferBoundaries() {
        val stream = RepeatedStream(8193)
        assertEquals(0, stream.available())
        assertArrayEquals(ByteArray(8193) { 42 }, BoundedFiles.read(stream, 8193))
    }

    @Test fun zeroLimitRejectsEvenOneByte() {
        assertNull(BoundedFiles.read(ByteArrayInputStream(byteArrayOf(1)), 0))
    }

    @Test fun zeroProgressFallbackStillEnforcesLimit() {
        var reads = 0
        val stream = object : InputStream() {
            override fun read(b: ByteArray, off: Int, len: Int) = 0
            override fun read(): Int { reads++; return 42 }
        }
        assertNull(BoundedFiles.read(stream, 2))
        assertEquals(3, reads)
    }

    @Test fun cancelledBeforeReadDoesNotConsumeInput() {
        val stream = RepeatedStream(100)
        assertThrows(CancellationException::class.java) {
            BoundedFiles.read(stream) { throw CancellationException() }
        }
        assertEquals(0L, stream.bytesRead)
    }

    @Test fun cancelledDuringReadClosesProviderAndDoesNotDrainIt() {
        val stream = RepeatedStream(100_000)
        assertThrows(CancellationException::class.java) {
            stream.use {
                BoundedFiles.read(it) {
                    if (stream.bytesRead > 0) throw CancellationException()
                }
            }
        }
        assertEquals(8192L, stream.bytesRead)
        assertTrue(stream.closed)
    }

    @Test fun cancelledDuringFallbackDoesNotReturnPartialSuccess() {
        var read = false
        val stream = object : InputStream() {
            override fun read(b: ByteArray, off: Int, len: Int) = 0
            override fun read(): Int { read = true; return 42 }
        }
        assertThrows(CancellationException::class.java) {
            BoundedFiles.read(stream, 1) { if (read) throw CancellationException() }
        }
    }

    @Test fun providerFailurePropagatesAndClosesStream() {
        val failure = IOException("Interrupted provider read")
        var closed = false
        val stream = object : InputStream() {
            var reads = 0
            override fun read(): Int = if (reads++ == 0) 42 else throw failure
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (reads++ != 0) throw failure
                b[off] = 42
                return 1
            }
            override fun close() { closed = true }
        }
        assertSame(failure, assertThrows(IOException::class.java) { stream.use { BoundedFiles.read(it) } })
        assertTrue(closed)
    }

    @Test fun invalidLimitsRejectAndLargeLimitsDoNotOverflow() {
        assertThrows(IllegalArgumentException::class.java) { BoundedFiles.read(ByteArrayInputStream(byteArrayOf()), -1) }
        assertArrayEquals(byteArrayOf(42), BoundedFiles.read(ByteArrayInputStream(byteArrayOf(42)), Int.MAX_VALUE))
    }

    private class RepeatedStream(var remaining: Long) : InputStream() {
        var bytesRead = 0L
        var closed = false
        override fun available() = 0
        override fun read(): Int {
            if (remaining == 0L) return -1
            remaining--
            bytesRead++
            return 42
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining == 0L) return -1
            val count = minOf(remaining, len.toLong()).toInt()
            b.fill(42, off, off + count)
            remaining -= count
            bytesRead += count
            return count
        }
        override fun close() { closed = true }
    }
}
