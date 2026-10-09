@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlin.io.encoding.ExperimentalEncodingApi::class)

package me.waveio.claudebot.state

import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.waveio.claudebot.data.WorkspaceEditorMaxContentBytes
import kotlin.io.encoding.Base64
import kotlin.test.*

class ControllerDrawingExportTest {
    private fun TestScope.fixture() = ControllerTestFixture(StandardTestDispatcher(testScheduler))
    private val png = Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/l1cAAAAASUVORK5CYII=")
    private fun data(bytes: ByteArray = png) = "data:image/png;base64," + Base64.encode(bytes)
    private fun content(path: String) = buildJsonObject {
        put("path", path); put("content", "{}"); put("binary", false); put("revision", "one")
    }.toString()

    @Test fun validCanvasPngGoesToTheOsWithoutAnyWorkspaceWriteAndOnlyOnePicker() = runTest {
        val f = fixture()
        try {
            f.bridge.deferFileSave = true
            f.handler = { request -> if (request.url.encodedPath == "/api/mobile/workspace/file")
                jsonResponse(content(request.url.parameters["path"]!!)) else null }
            runCurrent(); f.controller.openFile("notes/Architecture.excalidraw.json"); runCurrent()
            val id = f.controller.state.value.fileEditorGeneration.toString()
            f.controller.saveEditorDrawing(id, data())
            f.controller.saveEditorDrawing(id, data())
            assertTrue(f.controller.state.value.editorExporting)
            val saved = f.bridge.savedFiles.single()
            assertEquals("Architecture.png", saved.name)
            assertEquals("image/png", saved.mimeType)
            assertContentEquals(png, saved.bytes)
            assertTrue(f.requests.none { it.method == HttpMethod.Post })
            assertEquals("{}", f.controller.state.value.fileText)
            f.bridge.saveFileResult!!.invoke(true); runCurrent()
            assertFalse(f.controller.state.value.editorExporting)
            assertEquals("files.saved", f.controller.state.value.notice)
        } finally { f.close() }
    }

    @Test fun readOnlyMermaidPreviewExportsWithoutOpeningAnEditor() = runTest {
        val f = fixture()
        try {
            f.bridge.deferFileSave = true
            f.handler = { request -> if (request.url.encodedPath == "/api/workspace/file")
                jsonResponse(content("notes/flow.mmd")) else null }
            runCurrent(); f.controller.browseFile("notes/flow.mmd"); runCurrent()
            f.controller.saveEditorDrawing("preview:${f.controller.state.value.previewRevision}", data())
            assertEquals("flow.png", f.bridge.savedFiles.single().name)
            assertContentEquals(png, f.bridge.savedFiles.single().bytes)
            assertNull(f.controller.state.value.openFile)
            assertTrue(f.requests.none { it.method == HttpMethod.Post })
        } finally { f.close() }
    }

    @Test fun staleIdsOtherFormatsNonPngAndInvalidDimensionsNeverReachTheOs() = runTest {
        val f = fixture()
        try {
            f.handler = { request -> if (request.url.encodedPath == "/api/mobile/workspace/file")
                jsonResponse(content(request.url.parameters["path"]!!)) else null }
            runCurrent(); f.controller.openFile("drawing.excalidraw"); runCurrent()
            val id = f.controller.state.value.fileEditorGeneration.toString()
            val wide = png.copyOf().also { it[16] = 0; it[17] = 0; it[18] = 16; it[19] = 1 }
            val zero = png.copyOf().also { it[20] = 0; it[21] = 0; it[22] = 0; it[23] = 0 }
            val wrongChunk = png.copyOf().also { it[12] = 'I'.code.toByte(); it[13] = 'D'.code.toByte() }
            for (invalid in listOf(data(byteArrayOf(1, 2, 3)), data(wide), data(zero), data(wrongChunk),
                data().replace("image/png", "image/jpeg"), data() + "\n", data().dropLast(1),
                "data:image/png;base64," + "A".repeat(WorkspaceEditorMaxContentBytes))) {
                f.controller.saveEditorDrawing(id, invalid)
            }
            f.controller.openFile("note.md"); runCurrent()
            f.controller.saveEditorDrawing(id, data())
            f.controller.saveEditorDrawing(f.controller.state.value.fileEditorGeneration.toString(), data())
            assertTrue(f.bridge.savedFiles.isEmpty())
            assertFalse(f.controller.state.value.editorExporting)
            assertTrue(f.requests.none { it.method == HttpMethod.Post })
        } finally { f.close() }
    }

    @Test fun exportCompletionAfterNavigationOrAccountSwitchCannotShowAnotherDocumentsNotice() = runTest {
        val f = fixture()
        try {
            f.bridge.deferFileSave = true
            f.handler = { request -> if (request.url.encodedPath == "/api/mobile/workspace/file")
                jsonResponse(content(request.url.parameters["path"]!!)) else null }
            runCurrent(); f.controller.openFile("drawing.excalidraw"); runCurrent()
            f.controller.saveEditorDrawing(f.controller.state.value.fileEditorGeneration.toString(), data())
            val oldCallback = f.bridge.saveFileResult!!
            f.controller.openFile("second.excalidraw"); runCurrent()
            oldCallback(true); runCurrent()
            assertFalse(f.controller.state.value.editorExporting)
            assertNull(f.controller.state.value.notice)
            f.controller.saveEditorDrawing(f.controller.state.value.fileEditorGeneration.toString(), data())
            val accountCallback = f.bridge.saveFileResult!!
            f.controller.disconnect(); f.pairNewOwner(); runCurrent()
            accountCallback(true); runCurrent()
            assertNull(f.controller.state.value.notice)
            assertFalse(f.controller.state.value.editorExporting)
        } finally { f.close() }
    }
}
