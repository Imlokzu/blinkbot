package me.waveio.claudebot.platform

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class PcmWavBufferTest {
    @Test fun signedSamplesHaveExactLittleEndianPcmHeaderAndPayload() {
        val pcm = PcmWavBuffer()
        val samples = shortArrayOf(0, 1, -1, Short.MIN_VALUE, Short.MAX_VALUE)
        pcm.append(samples, samples.size)
        val wav = pcm.snapshot()!!
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", wav.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals(wav.size - 8, header.getInt(4))
        assertEquals("WAVE", wav.copyOfRange(8, 12).toString(Charsets.US_ASCII))
        assertEquals(16, header.getInt(16))
        assertEquals(1, header.getShort(20).toInt())
        assertEquals(1, header.getShort(22).toInt())
        assertEquals(16_000, header.getInt(24))
        assertEquals(32_000, header.getInt(28))
        assertEquals(2, header.getShort(32).toInt())
        assertEquals(16, header.getShort(34).toInt())
        assertEquals("data", wav.copyOfRange(36, 40).toString(Charsets.US_ASCII))
        assertEquals(samples.size * 2, header.getInt(40))
        samples.forEachIndexed { index, sample -> assertEquals(sample, header.getShort(44 + index * 2)) }
    }

    @Test fun silentAudioContinuesAndPartialsAreIndependentAccumulatedWavs() {
        val pcm = PcmWavBuffer(sampleRate = 10, maximumSeconds = 10)
        pcm.append(ShortArray(19), 19)
        assertNull(pcm.partialIfDue())
        assertFalse(pcm.isFull)
        pcm.append(shortArrayOf(0), 1)
        val first = pcm.partialIfDue()!!
        assertNull(pcm.partialIfDue())
        pcm.append(ShortArray(20), 20)
        val second = pcm.partialIfDue()!!
        assertEquals(44 + 20 * 2, first.size)
        assertEquals(44 + 40 * 2, second.size)
        assertFalse(pcm.isFull)
        assertArrayEquals(first.copyOfRange(44, first.size), second.copyOfRange(44, first.size))
        assertEquals(second.size, pcm.snapshot()!!.size)
        second[44] = 99
        assertEquals(0.toByte(), pcm.snapshot()!![44])
    }

    @Test fun sizeAndDurationLimitsTruncateOnlyAtWholeSamples() {
        val bytes = PcmWavBuffer(maximumBytes = 51)
        assertEquals(3, bytes.append(ShortArray(20) { 42 }, 20))
        assertTrue(bytes.isFull)
        assertEquals(50, bytes.snapshot()!!.size)
        assertEquals(0, bytes.append(shortArrayOf(1), 1))
        val duration = PcmWavBuffer(sampleRate = 10, maximumSeconds = 1)
        assertEquals(10, duration.append(ShortArray(20), 20))
        assertTrue(duration.isFull)
    }

    @Test fun emptyCaptureAndClearedCaptureHaveNoFalseSuccessfulAudio() {
        val pcm = PcmWavBuffer()
        assertNull(pcm.snapshot())
        pcm.append(shortArrayOf(1), 1)
        val previous = pcm.snapshot()!!
        pcm.clear()
        assertNull(pcm.snapshot())
        assertEquals(0, pcm.samples)
        assertEquals(1.toByte(), previous[44])
    }

    @Test fun rmsAmplitudeUsesRealSamplesWithoutAStopThreshold() {
        assertEquals(0f, PcmWavBuffer.amplitude(ShortArray(10), 10), 0f)
        assertEquals(1f, PcmWavBuffer.amplitude(shortArrayOf(Short.MIN_VALUE), 1), 0f)
        assertEquals(.5f, PcmWavBuffer.amplitude(shortArrayOf(16384, -16384), 2), .00001f)
        assertEquals(0f, PcmWavBuffer.amplitude(shortArrayOf(), 0), 0f)
    }

    @Test fun defaultTenMinuteWavIncludesItsHeaderInsideTheTwentyMiBLimit() {
        val pcm = PcmWavBuffer()
        val second = ShortArray(16_000) { (it % 32768).toShort() }
        repeat(600) { assertEquals(second.size, pcm.append(second, second.size)) }
        assertTrue(pcm.isFull)
        assertEquals(0, pcm.append(shortArrayOf(42), 1))
        val wav = pcm.snapshot()!!
        assertEquals(19_200_044, wav.size)
        assertTrue(wav.size <= BoundedFiles.MAX_BYTES)
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(wav.size - 8, header.getInt(4))
        assertEquals(19_200_000, header.getInt(40))
        assertEquals(second.last(), header.getShort(wav.size - 2))
    }
}
