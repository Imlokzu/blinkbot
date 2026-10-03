package me.waveio.claudebot.platform

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** No network or silence detector: samples remain bounded until Stop or the cap. */
internal class AndroidPcmRecorder(
    private val scope: CoroutineScope,
    private val onAmplitude: (Float) -> Unit,
    private val onPartial: (PickedFile) -> Unit,
    private val onResult: (PickedFile?) -> Unit,
    private val audioInput: (Int) -> PcmAudioInput = ::androidAudioInput,
    private val elapsedMillis: () -> Long = SystemClock::elapsedRealtime,
) {
    private val finished = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val device = AtomicReference<PcmAudioInput?>(null)
    // Stop/release must not race native start/read; reads are explicitly non-blocking.
    private val deviceLock = Any()
    private val latestAmplitude = AtomicReference(0f)
    // A stalled UI can retain at most one pending snapshot, never minutes of audio.
    private val partials = Channel<ByteArray>(Channel.CONFLATED)
    private var captureJob: Job? = null
    private var amplitudeJob: Job? = null
    private var partialJob: Job? = null
    private val name = "dictation-${UUID.randomUUID()}.wav"

    fun start() {
        check(captureJob == null)
        amplitudeJob = scope.launch(start = CoroutineStart.LAZY) {
            while (isActive && !finished.get() && !stopRequested.get()) {
                try { onAmplitude(latestAmplitude.get()) } catch (_: Exception) { cancel(); break }
                delay(80)
            }
        }
        partialJob = scope.launch(start = CoroutineStart.LAZY) {
            for (bytes in partials) {
                if (finished.get() || stopRequested.get()) break
                try { onPartial(PickedFile(name, "audio/wav", bytes)) } catch (_: Exception) { cancel(); break }
            }
        }
        captureJob = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) { capture() }
        captureJob?.start()
        partialJob?.start()
        amplitudeJob?.start()
    }

    private suspend fun capture() {
        val pcm = PcmWavBuffer()
        var native: PcmAudioInput? = null
        var result: PickedFile? = null
        try {
            if (stopRequested.get() || finished.get()) return
            val input = audioInput(pcm.sampleRate)
            native = input
            synchronized(deviceLock) {
                device.set(input)
                // A cancellation during device construction still releases this input in finally.
                if (stopRequested.get() || finished.get()) return
                input.start()
            }
            val began = elapsedMillis()
            val samples = ShortArray(1024)
            while (!stopRequested.get() && !finished.get() && !pcm.isFull && elapsedMillis() - began < MAX_MILLIS) {
                currentCoroutineContext().ensureActive()
                val count = synchronized(deviceLock) {
                    if (stopRequested.get() || finished.get()) 0 else input.read(samples)
                }
                if (count < 0) {
                    if (stopRequested.get() || finished.get()) break
                    error("Audio capture failed")
                }
                if (count == 0) { delay(10); continue }
                val accepted = pcm.append(samples, count)
                latestAmplitude.set(PcmWavBuffer.amplitude(samples, accepted))
                if (!stopRequested.get() && !finished.get()) pcm.partialIfDue()?.let { partials.trySend(it) }
            }
            if (!finished.get()) pcm.snapshot()?.let { result = PickedFile(name, "audio/wav", it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            result = null
        } finally {
            synchronized(deviceLock) {
                if (native != null && device.compareAndSet(native, null)) release(native)
            }
            pcm.clear()
            val captured = result
            scope.launch { complete(captured) }
        }
    }

    fun stop() {
        stopRequested.set(true)
        // Non-blocking reads plus explicit release end capture promptly, even if the device stalls.
        synchronized(deviceLock) { device.getAndSet(null)?.let(::release) }
    }

    fun cancel() {
        stopRequested.set(true)
        synchronized(deviceLock) { device.getAndSet(null)?.let(::release) }
        captureJob?.cancel()
        complete(null)
    }

    private fun complete(result: PickedFile?) {
        if (!finished.compareAndSet(false, true)) return
        partials.cancel()
        amplitudeJob?.cancel()
        partialJob?.cancel()
        try { onAmplitude(0f) } finally { onResult(result) }
    }

    private fun release(native: PcmAudioInput) { runCatching { native.release() } }

    companion object { private const val MAX_MILLIS = 600_000L }
}

/** A narrow hardware boundary allows lifecycle races to be exercised with harmless PCM fixtures. */
internal interface PcmAudioInput {
    fun start()
    fun read(samples: ShortArray): Int
    fun release()
}

@SuppressLint("MissingPermission") // The bridge gates construction through RECORD_AUDIO.
private fun androidAudioInput(sampleRate: Int): PcmAudioInput {
    val minimum = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    check(minimum in 1..1_048_576)
    val native = AudioRecord.Builder()
        .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
        .setBufferSizeInBytes(maxOf(minimum * 2, 4096)).build()
    return object : PcmAudioInput {
        override fun start() {
            check(native.state == AudioRecord.STATE_INITIALIZED)
            native.startRecording()
            check(native.recordingState == AudioRecord.RECORDSTATE_RECORDING)
        }
        override fun read(samples: ShortArray) = native.read(samples, 0, samples.size, AudioRecord.READ_NON_BLOCKING)
        override fun release() {
            try { runCatching { native.stop() } } finally { native.release() }
        }
    }
}
