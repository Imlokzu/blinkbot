package me.waveio.claudebot.platform

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NativeTemporaryFilesTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun nextProcessDeletesCapturesAndRevokesCameraGrants() {
        val cache = temporaryFolder.root
        val camera = File(cache, "native-camera").apply { mkdir() }
        val recordings = File(cache, "native-recordings").apply { mkdir() }
        val photo = File(camera, "capture-abandoned.jpg").apply { writeBytes(byteArrayOf(1)) }
        val audio = File(recordings, "dictation-abandoned.m4a").apply { writeBytes(byteArrayOf(2)) }
        val legacyAudio = File(cache, "dictation-legacy.m4a").apply { writeBytes(byteArrayOf(3)) }
        val revoked = mutableListOf<File>()
        NativeTemporaryFiles.removeAbandoned(cache) { revoked += it }
        assertEquals(listOf(photo), revoked)
        assertFalse(photo.exists())
        assertFalse(audio.exists())
        assertFalse(legacyAudio.exists())
    }

    @Test fun cleanupPreservesOtherCachesAndDirectories() {
        val cache = temporaryFolder.root
        val camera = File(cache, "native-camera").apply { mkdir() }
        val other = File(camera, "wallpaper.jpg").apply { writeBytes(byteArrayOf(1)) }
        val unrelated = File(cache, "attachment.m4a").apply { writeBytes(byteArrayOf(2)) }
        val directory = File(camera, "capture-directory.jpg").apply { mkdir() }
        NativeTemporaryFiles.removeAbandoned(cache) { fail("Unrelated files must not have grants revoked") }
        assertTrue(other.exists())
        assertTrue(unrelated.exists())
        assertTrue(directory.isDirectory)
    }

    @Test fun grantRevocationFailureStillDeletesPrivateCapture() {
        val camera = File(temporaryFolder.root, "native-camera").apply { mkdir() }
        val photo = File(camera, "capture-abandoned.jpg").apply { writeBytes(byteArrayOf(1)) }
        NativeTemporaryFiles.removeAbandoned(temporaryFolder.root) { throw SecurityException("Grant already expired") }
        assertFalse(photo.exists())
    }

    @Test fun absentCacheDirectoriesAreHarmless() {
        NativeTemporaryFiles.removeAbandoned(File(temporaryFolder.root, "absent")) { fail("No capture exists") }
        assertEquals(0, temporaryFolder.root.listFiles()!!.size)
    }
}
