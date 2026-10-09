@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package me.waveio.claudebot.state

import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.*

/** A visible reader follows completed AI writes without exposing partial scenes. */
class ControllerLiveWorkspacePreviewTest {
    private fun TestScope.fixture() = ControllerTestFixture(StandardTestDispatcher(testScheduler))
    private fun file(path: String, content: String) = buildJsonObject {
        put("path", path); put("content", content); put("binary", false); put("revision", "r1")
    }.toString()
    private fun MockRequestHandleScope.liveResponse(request: HttpRequestData, channel: ByteChannel): HttpResponseData? = when {
        request.url.encodedPath == "/api/mobile/messages" && request.url.parameters["session_id"] == "chat_1" ->
            jsonResponse("""{"messages":[{"id":"live_workspace","session_id":"chat_1","message":"Update files","state":"running"}]}""")
        request.url.encodedPath == "/api/mobile/messages/live_workspace/events" ->
            respond(channel, headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
        else -> null
    }
    private suspend fun TestScope.tool(channel: ByteChannel, sequence: Int, status: String, path: String, id: String = "write") {
        val kind = when (status) { "running" -> "tool_start"; "done" -> "tool_done"; else -> "tool_error" }
        val data = buildJsonObject {
            put("step", buildJsonObject {
                put("id", id); put("label", "workspace_write"); put("status", status)
                put("input", buildJsonObject { put("path", path) })
                if (status == "done") put("result", buildJsonObject { put("path", path); put("ok", true) })
            })
        }
        channel.writeStringUtf8("id: $sequence\nevent: $kind\ndata: $data\n\n")
        runCurrent()
    }

    @Test fun completedDrawingWriteRefreshesTheReaderAndPreservesTheDirtyEditorBehindIt() = runTest {
        val f = fixture(); val channel = ByteChannel(autoFlush = true)
        val old = """{"type":"excalidraw","elements":[{"id":"before"}]}"""
        val updated = """{"type":"excalidraw","elements":[{"id":"after"}]}"""
        var remote = old; val reads = mutableListOf<Pair<String, String?>>()
        try {
            f.handler = { request -> when (request.url.encodedPath) {
                "/api/workspace/file" -> {
                    reads += request.url.parameters["path"]!! to request.url.parameters["session_id"]
                    jsonResponse(file("sessions/chat_1/diagram.excalidraw", remote))
                }
                "/api/mobile/workspace/file" -> jsonResponse(file("notes/local.md", "Original note"))
                else -> liveResponse(request, channel)
            } }
            runCurrent(); f.controller.openChat("chat_1"); runCurrent()
            f.controller.openFile("notes/local.md"); runCurrent(); f.controller.fileText("Unsaved local note")
            f.controller.openLink("/file/session/diagram.excalidraw?session_id=chat_1"); runCurrent()
            val revision = f.controller.state.value.previewRevision
            tool(channel, 1, "running", "session/diagram.excalidraw")
            assertTrue(f.controller.state.value.previewWriteActive)
            assertEquals(old, f.controller.state.value.previewText)
            assertEquals(revision, f.controller.state.value.previewRevision)
            assertEquals(1, reads.size)
            remote = updated
            tool(channel, 2, "done", "sessions/chat_1/diagram.excalidraw")
            assertEquals(updated, f.controller.state.value.previewText)
            assertFalse(f.controller.state.value.previewWriteActive)
            assertTrue(f.controller.state.value.previewRevision > revision)
            assertEquals("chat_1", f.controller.state.value.previewSessionId)
            assertEquals("session/diagram.excalidraw", f.controller.state.value.previewPath)
            assertEquals("Unsaved local note", f.controller.state.value.fileText)
            assertEquals("Unsaved local note", f.bridge.preferences["owner_old.file.notes/local.md"])
            assertEquals(listOf("session/diagram.excalidraw" to "chat_1", "sessions/chat_1/diagram.excalidraw" to "chat_1"), reads.map { it.first to it.second.orEmpty() })
        } finally { f.close(); channel.close() }
    }

    @Test fun failedWritesKeepTheLastCompleteMarkdownAndDoNotFetchPartialText() = runTest {
        val f = fixture(); val channel = ByteChannel(autoFlush = true); var reads = 0
        try {
            f.handler = { request -> when (request.url.encodedPath) {
                "/api/workspace/file" -> { reads++; jsonResponse(file("sessions/chat_1/note.md", "# Last complete note")) }
                else -> liveResponse(request, channel)
            } }
            runCurrent(); f.controller.openChat("chat_1"); runCurrent()
            f.controller.openLink("/file/session/note.md?session_id=chat_1"); runCurrent()
            val revision = f.controller.state.value.previewRevision
            tool(channel, 1, "running", "session/note.md")
            f.controller.reloadPreview(); runCurrent()
            assertEquals(1, reads)
            tool(channel, 2, "failed", "session/note.md")
            assertFalse(f.controller.state.value.previewWriteActive)
            assertEquals("# Last complete note", f.controller.state.value.previewText)
            assertEquals(revision, f.controller.state.value.previewRevision)
            assertEquals(1, reads)
        } finally { f.close(); channel.close() }
    }

    @Test fun sessionAliasUpdatesNeverRefreshTheSameFilenameInAnotherSession() = runTest {
        val f = fixture(); val channel = ByteChannel(autoFlush = true); var reads = 0
        try {
            f.handler = { request -> when (request.url.encodedPath) {
                "/api/workspace/file" -> { reads++; jsonResponse(file("sessions/chat_2/note.md", "Second session")) }
                else -> liveResponse(request, channel)
            } }
            runCurrent(); f.controller.openChat("chat_1"); runCurrent()
            f.controller.openLink("/file/session/note.md?session_id=chat_2"); runCurrent()
            val revision = f.controller.state.value.previewRevision
            tool(channel, 1, "running", "session/note.md")
            assertFalse(f.controller.state.value.previewWriteActive)
            tool(channel, 2, "done", "session/note.md")
            assertEquals("Second session", f.controller.state.value.previewText)
            assertEquals(revision, f.controller.state.value.previewRevision)
            assertEquals("chat_2", f.controller.state.value.previewSessionId)
            assertEquals(1, reads)
        } finally { f.close(); channel.close() }
    }

    @Test fun lateCompletedWriteRefreshCannotSwitchTheReaderBackToAnOldDocument() = runTest {
        val f = fixture(); val channel = ByteChannel(autoFlush = true); val gate = CompletableDeferred<Unit>(); var reads = 0
        try {
            f.handler = { request -> when (request.url.encodedPath) {
                "/api/workspace/file" -> {
                    val path = request.url.parameters["path"]!!
                    if (path == "other.md") jsonResponse(file(path, "The newer reader"))
                    else {
                        reads++
                        if (reads > 1) gate.await()
                        jsonResponse(file("sessions/chat_1/note.md", if (reads > 1) "Updated old note" else "Original note"))
                    }
                }
                else -> liveResponse(request, channel)
            } }
            runCurrent(); f.controller.openChat("chat_1"); runCurrent()
            f.controller.openLink("/file/session/note.md?session_id=chat_1"); runCurrent()
            tool(channel, 1, "done", "session/note.md")
            assertEquals(2, reads)
            f.controller.openLink("/file/other.md?session_id=chat_3"); runCurrent()
            val revision = f.controller.state.value.previewRevision
            gate.complete(Unit); runCurrent()
            assertEquals("other.md", f.controller.state.value.previewPath)
            assertEquals("The newer reader", f.controller.state.value.previewText)
            assertEquals("chat_3", f.controller.state.value.previewSessionId)
            assertEquals(revision, f.controller.state.value.previewRevision)
        } finally { gate.complete(Unit); f.close(); channel.close() }
    }

    @Test fun deferredChatDeletionNeverClosesAnEditorThatMayHaveUnflushedKeystrokes() = runTest {
        val f = fixture(); val gate = CompletableDeferred<Unit>()
        try {
            f.handler = { request -> when {
                request.url.encodedPath == "/api/sessions/chat_1" && request.method == HttpMethod.Delete -> {
                    gate.await(); jsonResponse("""{"ok":true}""")
                }
                request.url.encodedPath == "/api/mobile/workspace/file" -> jsonResponse(file("note.md", "Original"))
                else -> null
            } }
            runCurrent(); f.controller.openChat("chat_1"); runCurrent()
            f.controller.openFile("note.md"); runCurrent()
            val id = f.controller.state.value.fileEditorGeneration
            f.controller.deleteChat("chat_1"); runCurrent()
            f.controller.editorChanged(id.toString(), "Typed while deletion was pending")
            gate.complete(Unit); runCurrent()
            assertEquals("note.md", f.controller.state.value.openFile)
            assertEquals(id, f.controller.state.value.fileEditorGeneration)
            assertEquals("Typed while deletion was pending", f.controller.state.value.fileText)
            assertTrue(f.controller.state.value.conversations.none { it.id == "chat_1" })
        } finally { gate.complete(Unit); f.close() }
    }

    @Test fun aQueuedEditSurvivesTheAiLockCloseAndReopenThenConflictsWithTheCompletedWrite() = runTest {
        val f = fixture(); val channel = ByteChannel(autoFlush = true); var remote = "Original"; var reads = 0
        val path = "sessions/chat_1/note.md"
        try {
            f.handler = { request -> when (request.url.encodedPath) {
                "/api/mobile/workspace/file" -> { reads++; jsonResponse(file(path, remote)) }
                else -> liveResponse(request, channel)
            } }
            runCurrent(); f.controller.openChat("chat_1"); runCurrent()
            f.controller.openFile(path); runCurrent()
            val id = f.controller.state.value.fileEditorGeneration.toString()
            tool(channel, 1, "running", "session/note.md")
            // The WebView made this change before its writing lock arrived,
            // but native receives its queued callback after the AI event.
            f.controller.editorChanged(id, "The last buffered keystroke")
            advanceTimeBy(651); runCurrent()
            assertEquals("The last buffered keystroke", f.bridge.preferences["owner_old.file.$path"])
            assertTrue(f.requests.none { it.path == "/api/mobile/workspace/file" && it.method == HttpMethod.Post })
            f.controller.closeFile(); runCurrent()
            f.controller.openFile(path); runCurrent()
            assertEquals("The last buffered keystroke", f.controller.state.value.fileText)
            assertTrue(f.controller.state.value.fileWriteActive)
            assertEquals(1, reads, "Recovery must not read partially written remote text")
            f.controller.editorChanged(id, "Stale disposed view callback")
            assertEquals("The last buffered keystroke", f.controller.state.value.fileText)
            remote = "AI final text"
            tool(channel, 2, "done", path)
            assertFalse(f.controller.state.value.fileWriteActive)
            assertEquals(FileConflict("The last buffered keystroke", "AI final text", "r1"), f.controller.state.value.fileConflict)
            assertEquals("AI final text", remote)
            assertTrue(f.requests.none { it.path == "/api/mobile/workspace/file" && it.method == HttpMethod.Post })
        } finally { f.close(); channel.close() }
    }

    @Test fun aQueuedEditResumesItsCasSaveWhenTheAiWriteFailsWithoutChangingTheFile() = runTest {
        val f = fixture(); val channel = ByteChannel(autoFlush = true); var remote = "Original"
        val path = "sessions/chat_1/note.md"
        try {
            f.handler = { request -> when (request.url.encodedPath) {
                "/api/mobile/workspace/file" -> {
                    if (request.method == HttpMethod.Get) jsonResponse(file(path, remote))
                    else {
                        val body = f.requests.last().body!!
                        assertEquals("r1", body["revision"]?.jsonPrimitive?.content)
                        remote = body.getValue("content").jsonPrimitive.content
                        jsonResponse("""{"ok":true,"path":"sessions/chat_1/note.md","revision":"saved"}""")
                    }
                }
                else -> liveResponse(request, channel)
            } }
            runCurrent(); f.controller.openChat("chat_1"); runCurrent()
            f.controller.openFile(path); runCurrent()
            tool(channel, 1, "running", "session/note.md")
            f.controller.editorChanged(f.controller.state.value.fileEditorGeneration.toString(), "Pending mobile edit")
            advanceTimeBy(651); runCurrent()
            assertEquals("Original", remote)
            assertTrue(f.requests.none { it.path == "/api/mobile/workspace/file" && it.method == HttpMethod.Post })
            tool(channel, 2, "failed", "session/note.md")
            assertEquals("Pending mobile edit", remote)
            assertEquals("saved", f.controller.state.value.fileSaveState)
            assertFalse(f.controller.state.value.fileWriteActive)
            assertNull(f.controller.state.value.fileConflict)
            assertNull(f.bridge.preferences["owner_old.file.$path"])
            assertEquals(1, f.requests.count { it.path == "/api/mobile/workspace/file" && it.method == HttpMethod.Post })
        } finally { f.close(); channel.close() }
    }
}
