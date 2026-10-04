package me.waveio.claudebot.platform

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class NativeFileTransfersTest {
    @Test fun boundsAttemptedSelectionsEvenWhenEveryProviderFails() {
        var reads = 0
        val files = NativeFileTransfers.readSelection(0..1000) { _, _ -> reads++; null }
        assertTrue(files.isEmpty())
        assertEquals(PickedFileLimits.MAX_SELECTION, reads)
    }

    @Test fun aggregateBudgetIsPassedToEachReaderAndOversizeIsSkipped() {
        val limits = mutableListOf<Int>()
        val files = NativeFileTransfers.readSelection(listOf(0, 1, 2)) { index, limit ->
            limits += limit
            when (index) {
                0 -> PickedFile("first", "image/png", ByteArray(PickedFileLimits.MAX_BYTES - 1))
                1 -> PickedFile("too-large", "image/png", byteArrayOf(1, 2))
                else -> PickedFile("last", "text/plain", byteArrayOf(42))
            }
        }
        assertEquals(listOf(PickedFileLimits.MAX_BYTES, 1, 1), limits)
        assertEquals(listOf("first", "last"), files.map { it.name })
        assertEquals(PickedFileLimits.MAX_BYTES, files.sumOf { it.bytes.size })
    }

    @Test fun exactBudgetStopsBeforeOpeningAnotherProvider() {
        var reads = 0
        val files = NativeFileTransfers.readSelection(0..9) { _, _ ->
            reads++
            PickedFile("exact", "application/octet-stream", ByteArray(PickedFileLimits.MAX_BYTES))
        }
        assertEquals(1, reads)
        assertEquals(1, files.size)
    }

    @Test fun boundedStreamUsesRemainingBatchBudget() {
        val files = NativeFileTransfers.readSelection(listOf(1, 2, 3)) { value, limit ->
            val bytes = BoundedFiles.read(ByteArrayInputStream(byteArrayOf(value.toByte())), limit)!!
            PickedFile("$value.txt", "text/plain", bytes)
        }
        assertEquals(listOf(1.toByte(), 2.toByte(), 3.toByte()), files.map { it.bytes.single() })
    }

    @Test fun cancellationDiscardsPartialBatchRatherThanReturningSuccess() {
        var reads = 0
        assertThrows(CancellationException::class.java) {
            NativeFileTransfers.readSelection(0..9, { if (reads > 0) throw CancellationException() }) { _, _ ->
                reads++
                PickedFile("first.txt", "text/plain", byteArrayOf(1))
            }
        }
        assertEquals(1, reads)
    }

    @Test fun safeNamesCannotEscapeExportDirectoryAndKeepNormalNames() {
        assertEquals("report.pdf", NativeFileTransfers.safeName("../../report.pdf"))
        assertEquals("report.pdf", NativeFileTransfers.safeName("C:\\tmp\\report.pdf"))
        assertEquals("report.pdf", NativeFileTransfers.safeName("report\u0000.pdf"))
        for (name in listOf("", " ", ".", "..", "../")) assertEquals("attachment", NativeFileTransfers.safeName(name))
        assertEquals("résumé 2026.pdf", NativeFileTransfers.safeName("résumé 2026.pdf"))
        assertEquals(180, NativeFileTransfers.safeName("a".repeat(1000)).length)
    }

    @Test fun mimeCannotInjectParametersOrWildcards() {
        assertEquals("application/pdf", NativeFileTransfers.mimeType("application/pdf"))
        for (mime in listOf("", "*/*", "text/plain\r\nother", "text/plain; charset=utf-8")) {
            assertEquals("application/octet-stream", NativeFileTransfers.mimeType(mime))
        }
    }

    @Test fun sharingPreservesFilenameAndExactBytesInUniqueDirectories() = withCache { cache ->
        val file = PickedFile("../../report.pdf", "application/pdf", byteArrayOf(0, 1, 2, -1))
        val first = NativeFileTransfers.prepareShare(cache, file)!!
        val second = NativeFileTransfers.prepareShare(cache, file)!!
        assertEquals("report.pdf", first.name)
        assertNotEquals(first.parentFile, second.parentFile)
        assertTrue(first.canonicalPath.startsWith(cache.canonicalPath + "/native-share/"))
        assertArrayEquals(file.bytes, first.readBytes())
        assertArrayEquals(file.bytes, second.readBytes())
    }

    @Test fun freshShareGrantsAreRetainedButStorageIsBounded() = withCache { cache ->
        val file = PickedFile("report.pdf", "application/pdf", byteArrayOf(42))
        val shares = (1..64).map { NativeFileTransfers.prepareShare(cache, file)!! }
        assertNull(NativeFileTransfers.prepareShare(cache, file))
        assertTrue(shares.all { it.exists() })
        assertEquals(64, java.io.File(cache, "native-share").listFiles()!!.size)
    }

    @Test fun fiveSmallSharesAllRemainReadable() = withCache { cache ->
        val input = PickedFile("photo.jpg", "image/jpeg", byteArrayOf(1, 2, 3))
        val shares = (1..5).map { NativeFileTransfers.prepareShare(cache, input)!! }
        shares.forEach { assertArrayEquals(input.bytes, it.readBytes()) }
    }

    @Test fun aggregateByteLimitAcceptsExactEightyMiBButRejectsNextByte() = withCache { cache ->
        val full = PickedFile("large.bin", "application/octet-stream", ByteArray(PickedFileLimits.MAX_BYTES))
        val shares = (1..4).map { NativeFileTransfers.prepareShare(cache, full)!! }
        assertEquals(80L * 1024 * 1024, shares.sumOf { it.length() })
        assertNull(NativeFileTransfers.prepareShare(cache, PickedFile("next", "text/plain", byteArrayOf(42))))
        // Zero bytes still fit the byte boundary, subject to the independent count cap.
        val empty = NativeFileTransfers.prepareShare(cache, PickedFile("empty.txt", "text/plain", byteArrayOf()))!!
        assertEquals(0L, empty.length())
        assertTrue(shares.all { it.exists() && it.length() == PickedFileLimits.MAX_BYTES.toLong() })
        assertEquals(5, java.io.File(cache, "native-share").listFiles()!!.size)
    }

    @Test fun expirationReleasesCapacityExactlyAtTwentyFourHours() = withCache { cache ->
        val input = PickedFile("photo.jpg", "image/jpeg", byteArrayOf(42))
        val shares = (1..64).map { NativeFileTransfers.prepareShare(cache, input)!! }
        val now = System.currentTimeMillis()
        val lifetime = 24 * 60 * 60 * 1000L
        assertTrue(requireNotNull(shares[0].parentFile).setLastModified(now - lifetime))
        assertTrue(requireNotNull(shares[1].parentFile).setLastModified(now - lifetime + 1))
        assertNotNull(NativeFileTransfers.prepareShare(cache, input, now))
        assertFalse(shares[0].exists())
        assertTrue(shares.drop(1).all { it.exists() })
        assertEquals(64, java.io.File(cache, "native-share").listFiles()!!.size)
    }

    @Test fun expiredShareCleanupOnlyRemovesOwnedDirectories() = withCache { cache ->
        val file = NativeFileTransfers.prepareShare(cache, PickedFile("report.pdf", "application/pdf", byteArrayOf(42)))!!
        val unrelated = java.io.File(cache, "native-share/unrelated").apply { mkdirs() }
        assertTrue(requireNotNull(file.parentFile).setLastModified(1L))
        assertTrue(unrelated.setLastModified(1L))
        NativeFileTransfers.removeExpiredShares(cache)
        assertFalse(file.exists())
        assertTrue(unrelated.exists())
    }

    @Test fun emptyExportIsValidButEmptySelectionsAreSkipped() = withCache { cache ->
        val input = PickedFile("empty.txt", "text/plain", byteArrayOf())
        assertTrue(NativeFileTransfers.valid(input))
        val output = NativeFileTransfers.prepareShare(cache, input)!!
        assertEquals("empty.txt", output.name)
        assertEquals(0L, output.length())
        assertTrue(NativeFileTransfers.readSelection(listOf(input)) { file, _ -> file }.isEmpty())
    }

    @Test fun oversizedExportsNeverCreateTemporaryFiles() = withCache { cache ->
        val input = PickedFile("large", "text/plain", ByteArray(PickedFileLimits.MAX_BYTES + 1))
        assertFalse(NativeFileTransfers.valid(input))
        assertNull(NativeFileTransfers.prepareShare(cache, input))
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test fun additiveDefaultsKeepSinglePickerAndReportUnsupportedExports() {
        val bridge = SinglePickerBridge()
        var result: List<PickedFile>? = null
        bridge.pickFiles("wallpaper") { result = it }
        assertEquals("wallpaper", bridge.kind)
        assertEquals(listOf(bridge.file), result)
        bridge.file = null
        bridge.pickFiles("camera") { result = it }
        assertEquals(emptyList<PickedFile>(), result)
        val file = PickedFile("report.pdf", "application/pdf", byteArrayOf(42))
        bridge.saveFile(file) { assertFalse(it) }
        bridge.shareFile(file) { assertFalse(it) }
    }

    private fun withCache(action: (java.io.File) -> Unit) {
        val directory = Files.createTempDirectory("native-file-transfers-test-").toFile()
        try { action(directory) } finally { directory.deleteRecursively() }
    }

    private class SinglePickerBridge : PlatformBridge {
        var kind: String? = null
        var file: PickedFile? = PickedFile("photo.png", "image/png", byteArrayOf(42))
        override val platformName = "test"
        override val systemLanguage = "en"
        override val foreground = MutableStateFlow(true)
        override val incomingPairing = MutableStateFlow<String?>(null)
        override val reducedMotion = true
        override fun readPreference(key: String): String? = null
        override fun writePreference(key: String, value: String?) {}
        override fun readSecret(key: String): String? = null
        override fun writeSecret(key: String, value: String?) {}
        override fun scanQr(onResult: (String?) -> Unit) = onResult(null)
        override fun pickFile(kind: String, onResult: (PickedFile?) -> Unit) { this.kind = kind; onResult(file) }
        override fun startRecording(onAmplitude: (Float) -> Unit, onResult: (PickedFile?) -> Unit,
                                    onPartial: (PickedFile) -> Unit) = onResult(null)
        override fun stopRecording() {}
        override fun cancelRecording() {}
        override fun haptic() {}
        override fun copyText(value: String) {}
        override fun shareText(value: String) {}
        override fun requestNotifications(onResult: (Boolean) -> Unit) = onResult(false)
        override fun notifyReply(title: String, body: String, conversationId: String) {}
        override fun nowMillis() = 0L
        override fun newId() = "test"
    }
}
