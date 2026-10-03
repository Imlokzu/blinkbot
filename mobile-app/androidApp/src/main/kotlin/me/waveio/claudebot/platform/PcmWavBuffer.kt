package me.waveio.claudebot.platform

import kotlin.math.sqrt

/** Capture-thread confined. Every snapshot is a complete, accumulated PCM WAV. */
internal class PcmWavBuffer(
    val sampleRate: Int = 16_000,
    maximumBytes: Int = BoundedFiles.MAX_BYTES,
    maximumSeconds: Int = 600,
    partialSeconds: Int = 2,
) {
    private val maximumSamples: Int
    private val partialSamples: Int
    private var nextPartial: Long
    private var pcm = ByteArray(0)
    var samples: Int = 0
        private set
    val isFull get() = samples >= maximumSamples

    init {
        require(sampleRate in 1..192_000 && maximumBytes >= HEADER_BYTES + 2 && maximumSeconds > 0 && partialSeconds > 0)
        maximumSamples = minOf((maximumBytes - HEADER_BYTES).toLong() / 2, sampleRate.toLong() * maximumSeconds).toInt()
        partialSamples = (sampleRate.toLong() * partialSeconds).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        nextPartial = partialSamples.toLong()
    }

    fun append(input: ShortArray, count: Int): Int {
        require(count in 0..input.size)
        val accepted = minOf(count, maximumSamples - samples)
        val required = (samples + accepted) * 2
        if (required > pcm.size) pcm = pcm.copyOf(minOf(maximumSamples.toLong() * 2,
            maxOf(required.toLong(), maxOf(4096L, pcm.size.toLong() * 2))).toInt())
        for (index in 0 until accepted) {
            val value = input[index].toInt()
            val offset = (samples + index) * 2
            pcm[offset] = value.toByte()
            pcm[offset + 1] = (value shr 8).toByte()
        }
        samples += accepted
        return accepted
    }

    fun partialIfDue(): ByteArray? {
        if (samples.toLong() < nextPartial || samples == 0) return null
        nextPartial = (samples.toLong() / partialSamples + 1) * partialSamples
        return snapshot()
    }

    fun snapshot(): ByteArray? {
        if (samples == 0) return null
        val size = samples * 2
        return ByteArray(HEADER_BYTES + size).apply {
            fun ascii(offset: Int, value: String) { value.forEachIndexed { index, character -> this[offset + index] = character.code.toByte() } }
            fun u16(offset: Int, value: Int) { this[offset] = value.toByte(); this[offset + 1] = (value ushr 8).toByte() }
            fun u32(offset: Int, value: Int) { repeat(4) { this[offset + it] = (value ushr (it * 8)).toByte() } }
            ascii(0, "RIFF"); u32(4, size + 36); ascii(8, "WAVE"); ascii(12, "fmt ")
            u32(16, 16); u16(20, 1); u16(22, 1); u32(24, sampleRate)
            u32(28, sampleRate * 2); u16(32, 2); u16(34, 16)
            ascii(36, "data"); u32(40, size)
            pcm.copyInto(this, HEADER_BYTES, 0, size)
        }
    }

    fun clear() { pcm.fill(0); pcm = ByteArray(0); samples = 0; nextPartial = partialSamples.toLong() }

    companion object {
        const val HEADER_BYTES = 44

        fun amplitude(input: ShortArray, count: Int): Float {
            require(count in 0..input.size)
            if (count == 0) return 0f
            var squares = 0.0
            for (index in 0 until count) { val value = input[index].toDouble(); squares += value * value }
            return (sqrt(squares / count) / 32768.0).toFloat().coerceIn(0f, 1f)
        }
    }
}
