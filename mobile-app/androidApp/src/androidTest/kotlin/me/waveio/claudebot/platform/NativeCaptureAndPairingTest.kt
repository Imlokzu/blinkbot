package me.waveio.claudebot.platform

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import me.waveio.claudebot.MainActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Harmless route fixtures cannot redeem a code: no server parameter is supplied. */
@RunWith(AndroidJUnit4::class)
class NativeCaptureAndPairingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun coldAndWarmPairingIntentsReachTheSameBridge() {
        val cold = "claudebot://pair?code=cold-fixture"
        val warm = "claudebot://pair?code=warm-fixture"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(cold), context, MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            var original: AndroidBridge? = null
            var host: MainActivity? = null
            var launchIntent: Intent? = null
            try {
                scenario.onActivity { activity ->
                    host = activity
                    launchIntent = Intent(activity.intent)
                    original = bridge(activity)
                    assertEquals(cold, original.incomingPairing.value)
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(warm), context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
                }
                val delivered = CountDownLatch(1)
                repeat(40) {
                    if (delivered.count != 0L) {
                        scenario.onActivity { activity ->
                            assertSame(original, bridge(activity))
                            if (bridge(activity).incomingPairing.value == warm) delivered.countDown()
                        }
                        if (delivered.count != 0L) Thread.sleep(25)
                    }
                }
                assertEquals(0L, delivered.count)
            } finally {
                // ActivityScenario matches its original Intent on lifecycle events.
                // A real onNewIntent correctly changes that Intent; restore only the test identity for teardown.
                instrumentation.runOnMainSync { launchIntent?.let { host?.setIntent(it) } }
            }
        }
    }

    @Test fun unrelatedWarmIntentDoesNotReplacePendingPairing() {
        val pair = "claudebot://pair?code=route-fixture"
        val unrelated = "claudebot://conversation?id=fixture"
        ActivityScenario.launch<MainActivity>(Intent(Intent.ACTION_VIEW, Uri.parse(pair), context, MainActivity::class.java)).use { scenario ->
            var host: MainActivity? = null
            var launchIntent: Intent? = null
            try {
                scenario.onActivity { activity ->
                    host = activity
                    launchIntent = Intent(activity.intent)
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(unrelated), context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
                }
                var delivered = false
                repeat(40) {
                    if (!delivered) {
                        scenario.onActivity { activity ->
                            delivered = activity.intent.dataString == unrelated
                            assertEquals(pair, bridge(activity).incomingPairing.value)
                        }
                        if (!delivered) Thread.sleep(25)
                    }
                }
                assertTrue(delivered)
            } finally { instrumentation.runOnMainSync { launchIntent?.let { host?.setIntent(it) } } }
        }
    }

    @Test fun silentEmulatorProducesRollingWavsUntilManualStop() {
        // Run this capture check on an emulator with host audio disabled, never a user's microphone.
        assumeTrue(Build.HARDWARE in listOf("ranchu", "goldfish"))
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        ActivityScenario.launch(MainActivity::class.java).use {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val snapshots = CountDownLatch(2)
            val result = CountDownLatch(1)
            val resultCount = AtomicInteger()
            val firstSize = AtomicInteger()
            val lastSize = AtomicInteger()
            val finalFile = AtomicReference<PickedFile?>()
            val capture = AndroidPcmRecorder(scope, onAmplitude = { value -> assertTrue(value in 0f..1f) },
                onPartial = { file ->
                    assertWav(file)
                    firstSize.compareAndSet(0, file.bytes.size)
                    assertTrue(file.bytes.size >= lastSize.get())
                    lastSize.set(file.bytes.size)
                    snapshots.countDown()
                }, onResult = { file -> finalFile.set(file); resultCount.incrementAndGet(); result.countDown() })
            try {
                instrumentation.runOnMainSync { capture.start() }
                assertTrue("Two rolling snapshots must arrive during capture", snapshots.await(8, TimeUnit.SECONDS))
                assertEquals("Silence must not stop the recording", 1L, result.count)
                assertTrue(lastSize.get() > firstSize.get())
                instrumentation.runOnMainSync { capture.stop(); capture.stop() }
                assertTrue(result.await(3, TimeUnit.SECONDS))
                val file = finalFile.get()!!
                assertWav(file)
                assertTrue(file.bytes.size >= lastSize.get())
                assertEquals(1, resultCount.get())
            } finally { instrumentation.runOnMainSync { capture.cancel(); scope.cancel() } }
        }
    }

    @Test fun immediateCancelReturnsNullOnce() {
        assumeTrue(Build.HARDWARE in listOf("ranchu", "goldfish"))
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        ActivityScenario.launch(MainActivity::class.java).use {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val count = AtomicInteger()
            val capture = AndroidPcmRecorder(scope, {}, { fail("Cancelled capture must not emit partial audio") },
                { assertNull(it); count.incrementAndGet() })
            try {
                instrumentation.runOnMainSync { capture.start(); capture.cancel(); capture.cancel() }
                instrumentation.waitForIdleSync()
                assertEquals(1, count.get())
            } finally { instrumentation.runOnMainSync { capture.cancel(); scope.cancel() } }
        }
    }

    private fun bridge(activity: MainActivity): AndroidBridge = MainActivity::class.java
        .getDeclaredField("bridge").apply { isAccessible = true }.get(activity) as AndroidBridge

    private fun assertWav(file: PickedFile) {
        assertEquals("audio/wav", file.mimeType)
        assertTrue(file.bytes.size > 44 && file.bytes.size <= BoundedFiles.MAX_BYTES)
        val header = ByteBuffer.wrap(file.bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", file.bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals(file.bytes.size - 8, header.getInt(4))
        assertEquals(16_000, header.getInt(24))
        assertEquals(file.bytes.size - 44, header.getInt(40))
        assertEquals(0, header.getInt(40) % 2)
    }
}
