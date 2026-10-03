package me.waveio.claudebot.platform

import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test

/** All inputs are synthetic; these tests never construct AudioRecord or request a microphone. */
class AndroidPcmRecorderTest {
    private class ManualUI : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { synchronized(queue) { queue.add(block) } }
        val pending: Int get() = synchronized(queue) { queue.size }
        fun drain() {
            while (true) {
                val next = synchronized(queue) { queue.poll() } ?: return
                next.run()
            }
        }
    }

    private class FixtureInput(
        private val startAction: () -> Unit = {},
        private val readAction: (ShortArray) -> Int = { 0 },
    ) : PcmAudioInput {
        val starts = AtomicInteger()
        val releases = AtomicInteger()
        val released = CountDownLatch(1)
        val inRead = AtomicBoolean(false)
        val releasedDuringRead = AtomicBoolean(false)
        override fun start() { starts.incrementAndGet(); startAction() }
        override fun read(samples: ShortArray): Int {
            check(releases.get() == 0) { "A released input must never be read" }
            inRead.set(true)
            return try { readAction(samples) } finally { inRead.set(false) }
        }
        override fun release() {
            if (inRead.get()) releasedDuringRead.set(true)
            releases.incrementAndGet()
            released.countDown()
        }
    }

    private fun await(latch: CountDownLatch) = assertTrue("Synthetic capture timed out", latch.await(3, TimeUnit.SECONDS))
    private fun pumpUntil(ui: ManualUI, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) { ui.drain(); Thread.sleep(1) }
        assertTrue("Queued completion timed out", condition())
    }

    @Test fun cancellationDuringInitializationReleasesTheLateInputWithoutStarting() {
        val ui = ManualUI()
        val scope = CoroutineScope(SupervisorJob() + ui)
        val constructing = CountDownLatch(1)
        val allowConstruction = CountDownLatch(1)
        val input = FixtureInput()
        val results = mutableListOf<PickedFile?>()
        val recorder = AndroidPcmRecorder(scope, {}, {}, { results.add(it) }, audioInput = {
            constructing.countDown()
            await(allowConstruction)
            input
        }, elapsedMillis = { 0 })
        try {
            recorder.start()
            await(constructing)
            recorder.cancel()
            allowConstruction.countDown()
            await(input.released)
            ui.drain()
            assertEquals(0, input.starts.get())
            assertEquals(1, input.releases.get())
            assertEquals(1, results.size)
            assertNull(results.single())
        } finally { allowConstruction.countDown(); recorder.cancel(); scope.cancel() }
    }

    @Test fun failedInitializationReleasesOnceAndCompletesOnTheUIQueue() {
        val ui = ManualUI()
        val scope = CoroutineScope(SupervisorJob() + ui)
        val input = FixtureInput(startAction = { error("Synthetic initialization failure") })
        val results = mutableListOf<PickedFile?>()
        val recorder = AndroidPcmRecorder(scope, {}, {}, { results.add(it) }, { input }, { 0 })
        try {
            recorder.start()
            await(input.released)
            assertTrue("IO cleanup must marshal completion to the UI queue", results.isEmpty())
            pumpUntil(ui) { results.isNotEmpty() }
            assertEquals(1, input.releases.get())
            assertNull(results.single())
        } finally { recorder.cancel(); scope.cancel() }
    }

    @Test fun stopSerializesReleaseWithReadAndPreservesTheLastActualSamples() {
        val ui = ManualUI()
        val scope = CoroutineScope(SupervisorJob() + ui)
        val reading = CountDownLatch(1)
        val allowRead = CountDownLatch(1)
        val stopEntered = CountDownLatch(1)
        val samples = shortArrayOf(1, 2, -1, Short.MIN_VALUE)
        val input = FixtureInput(readAction = { buffer ->
            reading.countDown()
            await(allowRead)
            samples.copyInto(buffer)
            samples.size
        })
        val results = mutableListOf<PickedFile?>()
        val recorder = AndroidPcmRecorder(scope, {}, {}, { results.add(it) }, { input }, { 0 })
        val stopper = Thread { stopEntered.countDown(); recorder.stop() }
        try {
            recorder.start()
            await(reading)
            stopper.start()
            await(stopEntered)
            // Wait for Stop to contend on the in-flight native read, not an arbitrary timing delay.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (stopper.state != Thread.State.BLOCKED && stopper.isAlive && System.nanoTime() < deadline) Thread.yield()
            assertEquals(Thread.State.BLOCKED, stopper.state)
            assertEquals(0, input.releases.get())
            allowRead.countDown()
            stopper.join(3000)
            assertFalse(stopper.isAlive)
            pumpUntil(ui) { results.isNotEmpty() }
            val expected = PcmWavBuffer().apply { append(samples, samples.size) }.snapshot()
            assertArrayEquals(expected, results.single()!!.bytes)
            assertFalse(input.releasedDuringRead.get())
            assertEquals(1, input.releases.get())
            recorder.stop()
            ui.drain()
            assertEquals(1, results.size)
        } finally { allowRead.countDown(); stopper.join(3000); recorder.cancel(); scope.cancel() }
    }

    @Test fun blockedUIReceivesOnlyTheNewestAccumulatedPartialAndCancellationDropsQueuedWork() {
        val ui = ManualUI()
        val scope = CoroutineScope(SupervisorJob() + ui)
        val sampleCount = AtomicInteger()
        val buffered = CountDownLatch(1)
        val input = FixtureInput(readAction = { buffer ->
            val remaining = 96_000 - sampleCount.get()
            if (remaining == 0) { buffered.countDown(); 0 } else {
                val count = minOf(buffer.size, remaining)
                buffer.fill(123, 0, count)
                sampleCount.addAndGet(count)
                count
            }
        })
        val partials = mutableListOf<PickedFile>()
        val results = mutableListOf<PickedFile?>()
        val recorder = AndroidPcmRecorder(scope, {}, { partials.add(it) }, { results.add(it) }, { input }, { 0 })
        try {
            recorder.start()
            await(buffered)
            assertTrue("Three emitted WAVs must not create three queued UI deliveries", ui.pending <= 2)
            ui.drain()
            assertEquals(1, partials.size)
            assertEquals(44 + 96_000 * 2, partials.single().bytes.size)
            recorder.cancel()
            await(input.released)
            ui.drain()
            assertEquals(1, results.size)
            assertNull(results.single())
            assertEquals(1, partials.size)
            assertEquals(1, input.releases.get())
        } finally { recorder.cancel(); scope.cancel() }
    }

    @Test fun cancellationDiscardsAPartialAndFinalAlreadyQueuedByAnOlderCapture() {
        val ui = ManualUI()
        val scope = CoroutineScope(SupervisorJob() + ui)
        val time = java.util.concurrent.atomic.AtomicLong()
        val captured = AtomicInteger()
        val input = FixtureInput(readAction = { buffer ->
            val count = minOf(buffer.size, 32_000 - captured.get())
            buffer.fill(0, 0, count)
            if (captured.addAndGet(count) == 32_000) time.set(600_000)
            count
        })
        val results = mutableListOf<PickedFile?>()
        val partials = mutableListOf<PickedFile>()
        val recorder = AndroidPcmRecorder(scope, {}, { partials.add(it) }, { results.add(it) }, { input }, time::get)
        try {
            recorder.start()
            await(input.released)
            recorder.cancel()
            ui.drain()
            assertTrue(partials.isEmpty())
            assertEquals(1, results.size)
            assertNull(results.single())
            assertEquals(1, input.releases.get())
        } finally { recorder.cancel(); scope.cancel() }
    }
}
