package me.waveio.claudebot.desktop

import java.nio.file.Files
import java.io.ByteArrayInputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.TargetDataLine
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopBridgeTest {
    @Test
    fun preferencesPersistAndRemoveAtomically() {
        val directory = Files.createTempDirectory("blink-desktop-test-")
        try {
            val bridge = DesktopBridge(directory, MemorySecretStore())
            bridge.writePreference("server", "https://example.test")
            bridge.writePreference("theme", "dark")

            val restarted = DesktopBridge(directory, MemorySecretStore())
            assertEquals("https://example.test", restarted.readPreference("server"))
            restarted.updatePreferences(listOf("server", "theme")) { values ->
                mapOf("server" to values["server"], "theme" to null)
            }
            assertEquals("https://example.test", bridge.readPreference("server"))
            assertNull(bridge.readPreference("theme"))
            assertTrue(Files.isRegularFile(directory.resolve("preferences.properties")))
            Files.list(directory).use { paths -> assertEquals(listOf("preferences.properties"), paths.map { it.fileName.toString() }.toList()) }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun preferenceTransformCannotWriteOutsideRequestedKeys() {
        val directory = Files.createTempDirectory("blink-desktop-test-")
        try {
            val bridge = DesktopBridge(directory, MemorySecretStore())
            bridge.writePreference("server", "https://example.test")
            val failure = runCatching {
                bridge.updatePreferences(listOf("server")) { mapOf("other" to "value") }
            }.exceptionOrNull()
            assertNotNull(failure)
            assertEquals("https://example.test", bridge.readPreference("server"))
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun recordingHelpersProduceNormalizedAmplitudeAndPcmWaveHeader() {
        val pcm = byteArrayOf(0, 0, 0, 64, 0, -128, 0, -64)
        assertTrue(DesktopBridge.pcmAmplitude(pcm, pcm.size) in 0f..1f)
        assertEquals(0f, DesktopBridge.pcmAmplitude(byteArrayOf(1), 1))

        val wave = DesktopBridge.wavBytes(pcm, AudioFormat(16_000f, 16, 1, true, false))
        assertEquals("RIFF", wave.copyOfRange(0, 4).decodeToString())
        assertEquals("WAVE", wave.copyOfRange(8, 12).decodeToString())
        assertEquals(44 + pcm.size, wave.size)
        assertFalse(wave.copyOfRange(44, wave.size).contentEquals(byteArrayOf()))
    }

    @Test
    fun fileReadsStopAtLimitEvenIfTheSourceExceedsItsReportedSize() {
        assertEquals(byteArrayOf(1, 2, 3).toList(), readBoundedStream(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3)?.toList())
        assertNull(readBoundedStream(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), 3))
        assertEquals(emptyList(), readBoundedStream(ByteArrayInputStream(byteArrayOf()), 0)?.toList())
        assertNull(readBoundedStream(ByteArrayInputStream(byteArrayOf(9)), 0))
    }

    @Test
    fun stopDuringBlockedOpenReturnsPromptlyAndNeverStartsCapture() {
        assertStopDuringBlockedOpen(cancel = false)
    }

    @Test
    fun cancelDuringBlockedOpenReturnsPromptlyAndNeverStartsCapture() {
        assertStopDuringBlockedOpen(cancel = true)
    }

    private fun assertStopDuringBlockedOpen(cancel: Boolean) {
        val directory = Files.createTempDirectory("blink-desktop-recording-test-")
        val openEntered = CountDownLatch(1)
        val allowOpenToReturn = CountDownLatch(1)
        val secondClose = CountDownLatch(1)
        val startCalls = AtomicInteger()
        val readCalls = AtomicInteger()
        val closeCalls = AtomicInteger()
        val callbacks = AtomicInteger()
        val callbackDone = CountDownLatch(1)
        val recorded = AtomicReference<me.waveio.claudebot.platform.PickedFile?>(null)
        val format = AudioFormat(16_000f, 16, 1, true, false)
        val fakeLine = Proxy.newProxyInstance(
            DesktopBridgeTest::class.java.classLoader,
            arrayOf(TargetDataLine::class.java),
            InvocationHandler { _, method, _ ->
                when (method.name) {
                    "open" -> { openEntered.countDown(); allowOpenToReturn.await(3, TimeUnit.SECONDS); null }
                    "start" -> { startCalls.incrementAndGet(); null }
                    "read" -> { readCalls.incrementAndGet(); 0 }
                    "close" -> { if (closeCalls.incrementAndGet() >= 2) secondClose.countDown(); null }
                    "getFormat" -> format
                    "isOpen", "isRunning", "isActive", "isControlSupported" -> false
                    "available", "getBufferSize", "getFramePosition" -> 0
                    "getLongFramePosition", "getMicrosecondPosition" -> 0L
                    "getLevel" -> 0f
                    "getControls" -> emptyArray<javax.sound.sampled.Control>()
                    "toString" -> "blocked fake capture"
                    else -> null
                }
            },
        ) as TargetDataLine
        try {
            val bridge = DesktopBridge(directory, MemorySecretStore()) { fakeLine }
            bridge.startRecording(
                onAmplitude = {},
                onResult = { result -> recorded.set(result); callbacks.incrementAndGet(); callbackDone.countDown() },
                onPartial = {},
            )
            assertTrue(openEntered.await(2, TimeUnit.SECONDS))
            val startedAt = System.nanoTime()
            if (cancel) bridge.cancelRecording() else bridge.stopRecording()
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            assertTrue(elapsedMillis < 500, "stop/cancel blocked for ${elapsedMillis}ms")
            allowOpenToReturn.countDown()
            assertTrue(secondClose.await(2, TimeUnit.SECONDS))
            assertTrue(callbackDone.await(2, TimeUnit.SECONDS))
            assertEquals(1, callbacks.get())
            assertNull(recorded.get())
            assertEquals(0, startCalls.get())
            assertEquals(0, readCalls.get())
        } finally {
            allowOpenToReturn.countDown()
            directory.toFile().deleteRecursively()
        }
    }

    private class MemorySecretStore : DesktopSecretStore {
        private val values = mutableMapOf<String, String>()
        override fun read(key: String) = values[key]
        override fun write(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
    }
}
