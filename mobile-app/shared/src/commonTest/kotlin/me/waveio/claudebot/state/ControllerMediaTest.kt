@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package me.waveio.claudebot.state

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.*
import me.waveio.claudebot.data.BotApi
import me.waveio.claudebot.platform.*
import kotlin.test.*
import kotlin.coroutines.CoroutineContext

/** Real shared-controller transitions with a silent OS boundary and virtual time. */
class ControllerMediaTest {
    private class Bridge(val base: FakePlatformBridge = FakePlatformBridge()) : PlatformBridge by base {
        var picked: ((List<PickedFile>) -> Unit)? = null
        val kinds = mutableListOf<String>()
        val saved = mutableListOf<PickedFile>()
        val shared = mutableListOf<PickedFile>()
        var saveResult: ((Boolean) -> Unit)? = null
        var shareResult: ((Boolean) -> Unit)? = null
        override fun pickFiles(kind: String, onResult: (List<PickedFile>) -> Unit) { kinds += kind; picked = onResult }
        override fun saveFile(file: PickedFile, onResult: (Boolean) -> Unit) { saved += file; saveResult = onResult }
        override fun shareFile(file: PickedFile, onResult: (Boolean) -> Unit) { shared += file; shareResult = onResult }
    }

    private class Harness(scope: TestScope, thumbnail: (ByteArray) -> ByteArray? = { me.waveio.claudebot.ui.thumbnailBytes(it) }) {
        val bridge = Bridge()
        var history = """{"messages":[]}"""
        val paths = mutableListOf<String>()
        var handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData? = { null }
        private val clients = mutableListOf<HttpClient>()
        val jobs = MutableStateFlow<Set<Job>>(emptySet())
        private val schedulerDispatcher = StandardTestDispatcher(scope.testScheduler)
        private val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                context[Job]?.let { job -> jobs.update { it + job } }
                schedulerDispatcher.dispatch(context, block)
            }
        }
        val controller = AppController(bridge, makeApi = { server, token ->
            val client = HttpClient(MockEngine(MockEngineConfig().apply {
                this.dispatcher = this@Harness.dispatcher
                addHandler { request ->
                    val path = request.url.encodedPath
                    paths += path
                    handler(request) ?: when (path) {
                        "/api/mobile/capabilities" -> jsonResponse("""{"steer":false}""")
                        "/api/brain/models" -> jsonResponse("""{"models":[{"id":"provider/model"}],"selected":"provider/model"}""")
                        "/api/sessions" -> jsonResponse("""{"sessions":[]}""")
                        "/api/setup" -> jsonResponse("""{"profile":{"name":"Bot"}}""")
                        "/api/mobile/messages" -> jsonResponse("""{"messages":[]}""")
                        "/api/mobile/pair/exchange" -> jsonResponse("""{"token":"new-device-token","device_id":"owner_new","expires_at":1791129600}""")
                        "/api/sessions/chat_1" -> jsonResponse(history)
                        "/api/sessions/chat_2" -> jsonResponse("""{"messages":[]}""")
                        else -> if (path.endsWith("/events")) sseResponse(": keepalive\n\n") else error("Unconfigured media fixture route: $path")
                    }
                }
            }))
            clients += client
            BotApi(server, token, client)
        }, dispatcher = dispatcher, makeThumbnail = thumbnail)
        fun close() { controller.close(); clients.forEach(HttpClient::close) }
    }

    private fun TestScope.open(h: Harness) { runCurrent(); h.controller.openChat("chat_1"); runCurrent() }

    @Test fun multiPickRetainsSuccessfulUploadsWhenOneFileFails() = runTest {
        val h = Harness(this)
        var uploads = 0
        h.handler = { request -> if (request.url.encodedPath == "/api/chat/upload") {
            uploads++
            if (uploads == 2) jsonResponse("""{"detail":"payload_too_large"}""", HttpStatusCode.PayloadTooLarge)
            else jsonResponse("""{"url":"/uploads/file$uploads.png","name":"file$uploads.png","type":"image/png","size":3}""")
        } else null }
        try {
            open(h)
            h.controller.pickFile("photo")
            assertEquals(listOf("photo"), h.bridge.kinds)
            h.bridge.picked!!.invoke((1..3).map { PickedFile("file$it.png", "image/png", byteArrayOf(1, 2, 3)) })
            runCurrent()
            assertEquals(listOf("/uploads/file1.png", "/uploads/file3.png"), h.controller.state.value.attachments.map { it.path })
            assertEquals(3, uploads)
            assertFalse(h.controller.state.value.uploading)
            assertNotNull(h.controller.state.value.error)
            assertEquals(2, h.controller.state.value.attachmentThumbnails.size)
        } finally { h.close(); runCurrent() }
    }

    @Test fun customBridgeCannotBypassSelectionLimitsOrDeliverTheCallbackTwice() = runTest {
        val h = Harness(this)
        var uploads = 0
        h.handler = { request -> if (request.url.encodedPath == "/api/chat/upload") {
            uploads++; jsonResponse("""{"url":"/uploads/file$uploads.txt","name":"file.txt","type":"text/plain","size":1}""")
        } else null }
        try {
            open(h)
            h.controller.pickFile("document")
            val files = (1..11).map { PickedFile("file$it.txt", "text/plain", byteArrayOf(1)) }
            h.bridge.picked!!.invoke(files)
            h.bridge.picked!!.invoke(files)
            runCurrent()
            assertEquals(10, uploads)
            assertEquals("attachments.selectionLimit", h.controller.state.value.error)
            assertEquals(10, h.controller.state.value.attachments.size)
        } finally { h.close(); runCurrent() }
    }

    @Test fun cancelledAndLatePickerResultsCannotAttachToAnotherChat() = runTest {
        val h = Harness(this)
        try {
            open(h)
            h.controller.pickFile("document")
            h.bridge.picked!!.invoke(emptyList()); runCurrent()
            assertFalse(h.controller.state.value.uploading)
            h.controller.pickFile("photo")
            val callback = h.bridge.picked!!
            h.controller.openChat("chat_2"); runCurrent()
            callback(listOf(PickedFile("late.png", "image/png", byteArrayOf(1))))
            runCurrent()
            assertTrue(h.controller.state.value.attachments.isEmpty())
            assertFalse(h.paths.contains("/api/chat/upload"))
        } finally { h.close(); runCurrent() }

        // Both callers suspend during native encoding. Wait for their actual
        // controller jobs, so an assertion cannot run before a stale resume.
        for (source in listOf("preview", "upload")) {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val old = Harness(this, thumbnail = {
                started.complete(Unit)
                runBlocking { release.await() }
                byteArrayOf(4, 5, 6)
            })
            old.history = generatedHistory
            val original = ByteArray(8 * 1024 * 1024 + 1)
            old.handler = { request -> when (request.url.encodedPath) {
                "/api/mobile/workspace/download" -> respond(original)
                "/api/chat/upload" -> jsonResponse("""{"url":"/uploads/old.png","name":"old.png","type":"image/png","size":8388609}""")
                else -> null
            } }
            try {
                open(old)
                val generation = old.controller.state.value.mediaGeneration
                val previousJobs = old.jobs.value
                if (source == "preview") old.controller.previewWorkFile("session/result.png")
                else {
                    old.controller.pickFile("photo")
                    old.bridge.picked!!.invoke(listOf(PickedFile("old.png", "image/png", original)))
                }
                runCurrent(); started.await()
                val encodingJobs = old.jobs.value.filter { it !in previousJobs && it.isActive }
                assertTrue(encodingJobs.isNotEmpty())
                old.controller.disconnect()
                old.controller.connect()
                old.bridge.base.qrResult!!.invoke("claudebot://pair?server=https%3A%2F%2Fnew.example&code=one-time-code")
                runCurrent()
                old.controller.openChat("chat_2"); runCurrent()
                release.complete(Unit)
                encodingJobs.forEach { it.join() }; runCurrent()
                val current = old.controller.state.value
                assertEquals("https://new.example", current.baseUrl)
                assertTrue(current.mediaGeneration > generation)
                assertTrue(current.attachmentThumbnails.isEmpty(), "$source encoding cannot cache bytes in a new account")
                assertTrue(current.attachments.isEmpty(), "A stale upload cannot append its manifest after encoding")
                assertNull(current.previewBytes, "A stale preview cannot expose original bytes after encoding")
            } finally { release.complete(Unit); old.close(); runCurrent() }
        }
    }

    @Test fun unrelatedErrorsCannotClearTheBatchGuardOrPermitSendAndRepick() = runTest {
        val h = Harness(this)
        val release = CompletableDeferred<Unit>()
        var uploads = 0
        h.handler = { request -> when (request.url.encodedPath) {
            "/api/chat/upload" -> {
                uploads++
                if (uploads == 1) release.await()
                jsonResponse("""{"url":"/uploads/file$uploads.txt","name":"file.txt","type":"text/plain","size":1}""")
            }
            "/api/workspace/list" -> jsonResponse("""{"detail":"server_error"}""", HttpStatusCode.InternalServerError)
            else -> null
        } }
        try {
            open(h)
            h.controller.pickFile("document")
            h.bridge.picked!!.invoke((1..2).map { PickedFile("file$it.txt", "text/plain", byteArrayOf(1)) })
            runCurrent()
            assertTrue(h.controller.state.value.uploading)
            h.controller.openDirectory(""); runCurrent()
            assertEquals("error.service", h.controller.state.value.error)
            assertTrue(h.controller.state.value.uploading, "An unrelated request must not unlock an active batch")
            h.controller.pickFile("photo")
            assertEquals(listOf("document"), h.bridge.kinds)
            h.controller.draft("must wait"); h.controller.send(); runCurrent()
            assertEquals("must wait", h.controller.state.value.draft)
            release.complete(Unit); runCurrent()
            assertEquals(2, uploads)
            assertEquals(2, h.controller.state.value.attachments.size)
            assertFalse(h.controller.state.value.uploading)
        } finally { h.close(); runCurrent() }
    }

    @Test fun generatedImagePreviewsAndExportsUseOriginalWorkspaceBytes() = runTest {
        val reduced = byteArrayOf(4, 5, 6)
        val encodedSizes = mutableListOf<Int>()
        val h = Harness(this, thumbnail = { encodedSizes += it.size; reduced })
        h.history = generatedHistory
        val original = ByteArray(8 * 1024 * 1024 + 1) { (it % 251).toByte() }
        h.handler = { request -> if (request.url.encodedPath == "/api/mobile/workspace/download") {
            assertEquals("session/result.png", request.url.parameters["path"])
            assertEquals("chat_1", request.url.parameters["session_id"])
            assertEquals("Bearer old-device-token", request.headers[HttpHeaders.Authorization])
            respond(original)
        } else null }
        try {
            open(h)
            assertEquals("session/result.png", h.controller.state.value.messages.single().workFiles.single().path)
            h.controller.loadWorkFileThumbnail("session/result.png"); runCurrent()
            h.controller.state.first { "workspace:session/result.png" in it.attachmentThumbnails }
            assertContentEquals(reduced, h.controller.state.value.attachmentThumbnails["workspace:session/result.png"])
            assertEquals(listOf(original.size), encodedSizes)
            h.controller.previewWorkFile("session/result.png"); runCurrent()
            h.controller.state.first { it.previewBytes != null }
            val state = h.controller.state.value
            assertEquals("workspace", state.previewSource)
            assertEquals("session/result.png", state.previewPath)
            assertEquals("image/png", state.previewMimeType)
            assertEquals(listOf(PreviewItem("session/result.png", "result.png", "image/png", "workspace")), state.previewItems)
            assertContentEquals(original, state.previewBytes)
            assertEquals(2, h.paths.count { it == "/api/mobile/workspace/download" }, "A reduced thumbnail cannot satisfy a full preview")
            h.controller.savePreview(); runCurrent()
            assertContentEquals(original, h.bridge.saved.single().bytes)
            h.bridge.saveResult!!.invoke(false); runCurrent()
            assertEquals("files.saveIncomplete", h.controller.state.value.error)
            h.controller.sharePreview(); runCurrent()
            assertEquals("result.png", h.bridge.shared.single().name)
            assertContentEquals(original, h.bridge.shared.single().bytes)
            h.bridge.shareResult!!.invoke(true); runCurrent()
            assertFalse(h.controller.state.value.previewExporting)
            assertNull(h.controller.state.value.notice, "Chooser handoff must not claim recipient delivery")

            // Evict the reduced entry with two permitted original-size cache
            // entries. Reusing its path for a smaller file must reuse full
            // bytes without a lingering "reduced" flag or another download.
            h.controller.closePreview()
            val generation = h.controller.state.value.mediaGeneration
            var uploads = 0
            h.handler = { request -> when (request.url.encodedPath) {
                "/api/chat/upload" -> {
                    uploads++
                    val name = if (uploads <= 2) "large$uploads.png" else "small.png"
                    jsonResponse("""{"url":"/uploads/$name","name":"$name","type":"image/png","size":1}""")
                }
                "/api/mobile/workspace/download" -> respond(byteArrayOf(7, 8, 9))
                else -> null
            } }
            h.controller.pickFile("photo")
            h.bridge.picked!!.invoke((1..2).map { PickedFile("large$it.png", "image/png", ByteArray(8 * 1024 * 1024)) })
            runCurrent()
            assertEquals(generation, h.controller.state.value.mediaGeneration, "Memory eviction must not reset gallery generation")
            assertNull(h.controller.state.value.attachmentThumbnails["workspace:session/result.png"])
            val beforeRead = h.paths.count { it == "/api/mobile/workspace/download" }
            h.controller.loadWorkFileThumbnail("session/result.png"); runCurrent()
            h.controller.previewWorkFile("session/result.png"); runCurrent()
            assertContentEquals(byteArrayOf(7, 8, 9), h.controller.state.value.previewBytes)
            assertEquals(beforeRead + 1, h.paths.count { it == "/api/mobile/workspace/download" }, "An evicted reduced flag must not force another full-preview download")
            h.controller.openChat("chat_2"); runCurrent()
            assertTrue(h.controller.state.value.mediaGeneration > generation)
            assertTrue(h.controller.state.value.attachmentThumbnails.isEmpty())
        } finally { h.close(); runCurrent() }
    }

    @Test fun lateExportCannotOpenAnOsPickerAfterPreviewNavigation() = runTest {
        val h = Harness(this)
        val release = CompletableDeferred<Unit>()
        h.history = generatedHistory
        h.handler = { request -> when (request.url.encodedPath) {
            "/api/mobile/workspace/download" -> { release.await(); respond(byteArrayOf(1, 2)) }
            else -> null
        } }
        try {
            open(h)
            h.controller.previewWorkFile("session/result.png"); runCurrent()
            h.controller.savePreview(); runCurrent()
            h.controller.openChat("chat_2"); runCurrent()
            release.complete(Unit); runCurrent()
            assertTrue(h.bridge.saved.isEmpty())
            assertNull(h.controller.state.value.previewPath)
            assertTrue(h.controller.state.value.attachmentThumbnails.isEmpty())
        } finally { h.close(); runCurrent() }
    }

    @Test fun laterWorkspaceWritesInvalidateThumbnailsAcrossAssistantMessages() = runTest {
        val h = Harness(this)
        h.history = generatedHistory
        h.handler = { request -> when {
            request.url.encodedPath == "/api/mobile/workspace/download" -> respond(byteArrayOf(1, 2, 3))
            request.url.encodedPath == "/api/mobile/messages" && request.method == HttpMethod.Post ->
                jsonResponse("""{"id":"rewrite_job","session_id":"chat_1","client_id":"local_1","state":"running"}""")
            request.url.encodedPath == "/api/mobile/messages/rewrite_job/events" -> sseResponse(
                "id: 1\nevent: tool_done\ndata: {\"step\":{\"id\":\"rewrite\",\"label\":\"workspace_write\",\"status\":\"done\",\"result\":{\"path\":\"session/result.png\",\"ok\":true}}}\n\nid: 2\nevent: mobile_state\ndata: {\"state\":\"completed\"}\n\n")
            else -> null
        } }
        try {
            open(h)
            h.controller.loadWorkFileThumbnail("session/result.png"); runCurrent()
            assertNotNull(h.controller.state.value.attachmentThumbnails["workspace:session/result.png"])
            h.controller.previewWorkFile("session/result.png"); runCurrent()
            h.controller.draft("revise image"); h.controller.send(); runCurrent()
            assertNull(h.controller.state.value.attachmentThumbnails["workspace:session/result.png"], "A new write must evict bytes cached by an older assistant row")
            assertNull(h.controller.state.value.previewPath, "A rewritten file must not remain visible as its older bytes")
        } finally { h.close(); runCurrent() }
    }

    @Test fun doneAndHistoryKeepPresentationIdentityButReopeningHistorySettles() = runTest {
        val h = Harness(this)
        h.handler = { request -> when {
            request.url.encodedPath == "/api/mobile/messages" && request.method == HttpMethod.Post -> {
                h.history = """{"messages":[{"id":"saved_user","role":"user","content":"hello"},{"id":"saved_assistant","role":"assistant","content":"reply"}]}"""
                jsonResponse("""{"id":"job_1","session_id":"chat_1","client_id":"local_1","state":"running"}""")
            }
            request.url.encodedPath == "/api/mobile/messages/job_1/events" -> sseResponse(
                "id: 1\nevent: done\ndata: {\"user_message_id\":\"saved_user\",\"assistant_message_id\":\"saved_assistant\",\"reply\":\"reply\"}\n\nid: 2\nevent: mobile_state\ndata: {\"state\":\"completed\"}\n\n")
            else -> null
        } }
        try {
            open(h)
            h.controller.draft("hello"); h.controller.send(); runCurrent()
            assertEquals("u-local_1", h.controller.state.value.messages.single { it.role == "user" }.presentationId)
            assertEquals("a-job_1", h.controller.state.value.messages.single { it.role == "assistant" }.presentationId)
            assertEquals(listOf("saved_user", "saved_assistant"), h.controller.state.value.messages.map { it.id })
            h.controller.openChat("chat_2"); runCurrent()
            h.controller.openChat("chat_1"); runCurrent()
            assertTrue(h.controller.state.value.messages.all { it.presentationId == null })
        } finally { h.close(); runCurrent() }
    }

    companion object {
        private const val generatedHistory = """{"messages":[{"id":"assistant_1","role":"assistant","content":"Created a file","steps":[{"id":"write_1","label":"workspace_write","status":"done","input":{"path":"session/asked.png"},"result":{"path":"session/result.png","ok":true}}]}]}"""
    }
}
