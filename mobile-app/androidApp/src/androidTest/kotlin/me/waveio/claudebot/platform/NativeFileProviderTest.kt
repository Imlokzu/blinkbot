package me.waveio.claudebot.platform

import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise the real manifest/path mapping, not a simulated content URI. */
@RunWith(AndroidJUnit4::class)
class NativeFileProviderTest {
    @Test fun sharedDocumentRetainsBytesFilenameAndMime() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = PickedFile("report.pdf", "application/pdf", byteArrayOf(0, 1, 2, -1))
        val output = NativeFileTransfers.prepareShare(context.cacheDir, input)!!
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.native-files", output, input.name)
            assertEquals("content", uri.scheme)
            assertEquals(input.mimeType, context.contentResolver.getType(uri))
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)!!.use {
                assertTrue(it.moveToFirst())
                assertEquals(input.name, it.getString(0))
                assertEquals(input.bytes.size.toLong(), it.getLong(1))
            }
            context.contentResolver.openInputStream(uri)!!.use { assertArrayEquals(input.bytes, it.readBytes()) }
        } finally { output.parentFile?.deleteRecursively() }
    }
}
