@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package me.waveio.claudebot.state

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.*

/** Exercise user-visible persistence and trust boundaries through the real controller. */
class ControllerWorkspaceEditorTest {
    private fun TestScope.fixture() = ControllerTestFixture(StandardTestDispatcher(testScheduler))
    private fun file(path: String, content: String, revision: String = "r1", binary: Boolean = false) = buildJsonObject {
        put("path", path); put("content", content); put("revision", revision); put("binary", binary)
    }.toString()
    private val scene = """{"type":"excalidraw","version":2,"elements":[],"appState":{},"files":{}}"""

    @Test fun browsingFormatsOpensReadersInTheGlobalWorkspace() = runTest {
        val f = fixture()
        val reads = mutableListOf<Pair<String, String?>>()
        try {
            f.handler = { request -> when (request.url.encodedPath) {
                "/api/workspace/file" -> {
                    val path = request.url.parameters["path"]!!
                    reads += path to request.url.parameters["session_id"]
                    jsonResponse(file(path, if (path.endsWith("excalidraw")) scene else "Document"))
                }
                "/api/mobile/workspace/web-preview" -> {
                    reads += request.url.parameters["path"]!! to request.url.parameters["session_id"]
                    jsonResponse("""{"ready":true,"kind":"web","root":"site","entry":"index.html","project_path":"site"}""")
                }
                else -> null
            } }
            runCurrent(); f.controller.openChat("chat_1"); runCurrent()
            for (path in listOf("notes.md", "diagram.excalidraw", "flow.mmd", "script.py", "site/index.html")) {
                f.controller.browseFile(path); runCurrent()
                assertEquals(path, f.controller.state.value.previewPath)
                assertEquals("", f.controller.state.value.previewSessionId)
                assertNull(f.controller.state.value.openFile)
                f.controller.closePreview()
            }
            assertEquals(5, reads.size)
            assertTrue(reads.all { it.second.isNullOrEmpty() })
            assertTrue(f.requests.none { it.path == "/api/mobile/workspace/file" })
        } finally { f.close() }
    }

    @Test fun offlineRecoveryIsVisibleBeforeTheRemoteReadCompletes() = runTest {
        val f = fixture(); val gate = CompletableDeferred<Unit>()
        try {
            f.bridge.preferences["owner_old.file.notes.md"] = "Unsent mobile draft"
            f.bridge.preferences["owner_old.fileBaseline.notes.md"] = "Original"
            f.handler = { request -> if (request.url.encodedPath == "/api/mobile/workspace/file") {
                gate.await(); jsonResponse("""{"detail":"offline"}""", HttpStatusCode.ServiceUnavailable)
            } else null }
            runCurrent(); f.controller.openFile("notes.md"); runCurrent()
            assertEquals("Unsent mobile draft", f.controller.state.value.fileText)
            assertTrue(f.controller.state.value.fileRecovery)
            assertTrue(f.controller.state.value.fileEditable)
            assertFalse(f.controller.state.value.loading)
            f.controller.editorChanged(f.controller.state.value.fileEditorGeneration.toString(), "Still editable offline")
            assertEquals("Still editable offline", f.bridge.preferences["owner_old.file.notes.md"])
            gate.complete(Unit); runCurrent()
            assertEquals("Still editable offline", f.controller.state.value.fileText)
            assertNotNull(f.controller.state.value.fileLoadError)
        } finally { gate.complete(Unit); f.close() }
    }

    @Test fun aliasRecoveryIsScopedAndAvailableAfterAColdOfflineReopen() = runTest {
        val f = fixture()
        try {
            f.bridge.preferences["owner_old.fileAlias.session.v1:chat_2:session/note.md"] = "sessions/chat_2/note.md"
            f.bridge.preferences["owner_old.file.sessions/chat_2/note.md"] = "Recovered canonical draft"
            f.bridge.preferences["owner_old.fileBaseline.sessions/chat_2/note.md"] = "Original"
            f.handler = { request -> when (request.url.encodedPath) {
                "/api/workspace/file" -> jsonResponse(file("sessions/chat_2/note.md", "Original"))
                "/api/mobile/workspace/file" -> jsonResponse("""{"detail":"offline"}""", HttpStatusCode.ServiceUnavailable)
                else -> null
            } }
            runCurrent(); f.controller.openLink("/file/session/note.md?session_id=chat_2"); runCurrent()
            f.controller.editPreview(); runCurrent()
            assertEquals("sessions/chat_2/note.md", f.controller.state.value.openFile)
            assertEquals("Recovered canonical draft", f.controller.state.value.fileText)
            assertEquals("chat_2", f.controller.state.value.fileSessionId)
            f.controller.closeFile(); f.controller.openFile("session/note.md"); runCurrent()
            assertEquals("", f.controller.state.value.fileText, "An unresolved alias must not recover another session's draft")
        } finally { f.close() }
    }

    @Test fun staleAndReadOnlyEditorCallbacksCannotChangeTheCurrentDocument() = runTest {
        val f = fixture()
        try {
            f.handler = { request -> if (request.url.encodedPath == "/api/mobile/workspace/file") {
                val path = request.url.parameters["path"]!!
                jsonResponse(file(path, if (path.endsWith("bin")) "" else path, binary = path.endsWith("bin")))
            } else null }
            runCurrent(); f.controller.openFile("first.md"); runCurrent()
            val oldId = f.controller.state.value.fileEditorGeneration.toString()
            f.controller.openFile("second.md"); runCurrent()
            f.controller.editorChanged(oldId, "Late first document callback")
            assertEquals("second.md", f.controller.state.value.fileText)
            f.controller.openFile("binary.bin"); runCurrent()
            f.controller.editorChanged(f.controller.state.value.fileEditorGeneration.toString(), "Decoded binary replacement")
            assertEquals("", f.controller.state.value.fileText)
            assertFalse(f.controller.state.value.fileEditable)
            assertTrue(f.requests.none { it.method == HttpMethod.Post && it.path == "/api/mobile/workspace/file" })
        } finally { f.close() }
    }

    @Test fun conflictPreservesBothVersionsAndKeepLocalReadsAFreshRevision() = runTest {
        val f = fixture(); var remote = "Original"; var revision = "r1"
        try {
            f.handler = { request -> if (request.url.encodedPath == "/api/mobile/workspace/file") {
                if (request.method == HttpMethod.Get) jsonResponse(file("notes.md", remote, revision))
                else {
                    val body = f.requests.last().body!!
                    assertEquals(revision, body["revision"]?.jsonPrimitive?.content)
                    remote = body.getValue("content").jsonPrimitive.content
                    revision = "saved"
                    jsonResponse("""{"ok":true,"path":"notes.md","revision":"saved"}""")
                }
            } else null }
            runCurrent(); f.controller.openFile("notes.md"); runCurrent()
            f.controller.fileText("Phone draft")
            remote = "PC update"; revision = "r2"
            advanceTimeBy(651); runCurrent()
            assertEquals(FileConflict("Phone draft", "PC update", "r2"), f.controller.state.value.fileConflict)
            assertTrue(f.requests.none { it.method == HttpMethod.Post })
            f.controller.fileText("Latest phone draft")
            f.controller.retryFileSave(); runCurrent()
            assertTrue(f.requests.none { it.method == HttpMethod.Post }, "Retry must not silently resolve a known conflict")
            remote = "Newer PC update"; revision = "r3"
            f.controller.keepLocalFile(); runCurrent()
            assertEquals("Latest phone draft", remote)
            assertEquals("r3", f.requests.single { it.method == HttpMethod.Post }.body?.get("revision")?.jsonPrimitive?.content)
            assertNull(f.controller.state.value.fileConflict)
            assertEquals("saved", f.controller.state.value.fileSaveState)
        } finally { f.close() }
    }

    @Test fun aSecondPcWriteDuringKeepLocalRetainsTheConflict() = runTest {
        val f = fixture(); var remote = "Original"; var revision = "r1"
        try {
            f.handler = { request -> if (request.url.encodedPath == "/api/mobile/workspace/file") {
                if (request.method == HttpMethod.Get) jsonResponse(file("notes.md", remote, revision))
                else {
                    remote = "Racing PC edit"; revision = "r3"
                    jsonResponse("""{"detail":{"code":"workspace_revision_conflict","revision":"r3"}}""", HttpStatusCode.Conflict)
                }
            } else null }
            runCurrent(); f.controller.openFile("notes.md"); runCurrent()
            f.controller.fileText("Phone draft"); remote = "PC edit"; revision = "r2"
            advanceTimeBy(651); runCurrent()
            f.controller.keepLocalFile(); runCurrent()
            assertEquals("Phone draft", f.controller.state.value.fileText)
            assertEquals(FileConflict("Phone draft", "Racing PC edit", "r3"), f.controller.state.value.fileConflict)
            assertEquals("Phone draft", f.bridge.preferences["owner_old.file.notes.md"])
        } finally { f.close() }
    }

    @Test fun ordinaryReloadPreservesDraftAndOnlyExplicitRemoteReloadDiscardsIt() = runTest {
        val f = fixture(); var remote = "Original"
        try {
            f.handler = { request -> if (request.url.encodedPath == "/api/mobile/workspace/file") jsonResponse(file("notes.md", remote)) else null }
            runCurrent(); f.controller.openFile("notes.md"); runCurrent()
            f.controller.fileText("Phone draft"); remote = "PC edit"
            f.controller.reloadFile(); runCurrent()
            assertEquals("Phone draft", f.controller.state.value.fileText)
            assertNotNull(f.controller.state.value.fileConflict)
            val id = f.controller.state.value.fileEditorGeneration.toString()
            f.controller.reloadRemoteFile(); runCurrent()
            assertEquals("PC edit", f.controller.state.value.fileText)
            assertNull(f.controller.state.value.fileConflict)
            assertNull(f.bridge.preferences["owner_old.file.notes.md"])
            f.controller.editorChanged(id, "Discarded old WebView callback")
            assertEquals("PC edit", f.controller.state.value.fileText)
        } finally { f.close() }
    }

    @Test fun storageWriteFailuresKeepTheLatestDraftInMemory() = runTest {
        val f = fixture(); var remoteFailed = false
        try {
            f.handler = { request -> if (request.url.encodedPath == "/api/mobile/workspace/file") {
                if (remoteFailed) jsonResponse("""{"detail":"offline"}""", HttpStatusCode.ServiceUnavailable)
                else jsonResponse(file("notes.md", "Original"))
            } else null }
            runCurrent(); f.controller.openFile("notes.md"); runCurrent()
            f.bridge.beforePreferenceWrite = { key, _ -> if (key.startsWith("owner_old.file")) error("Disk full") }
            f.controller.fileText("Never lose this edit")
            assertEquals("Never lose this edit", f.controller.state.value.fileText)
            assertEquals("error.storage", f.controller.state.value.error)
            assertTrue(f.controller.state.value.fileStorageError)
            f.controller.dismissNotice()
            assertNull(f.controller.state.value.error)
            assertTrue(f.controller.state.value.fileStorageError, "Dismissing a toast does not make a draft durable")
            remoteFailed = true
            f.controller.retryFileSave(); runCurrent()
            assertNotNull(f.controller.state.value.fileLoadError)
            assertTrue(f.controller.state.value.fileStorageError)
            f.controller.reloadFile(); runCurrent()
            assertEquals("Never lose this edit", f.controller.state.value.fileText)
            assertTrue(f.controller.state.value.fileRecovery)
            assertTrue(f.controller.state.value.fileStorageError, "A failed reopen must preserve the storage warning")
            f.bridge.beforePreferenceWrite = null
            f.controller.reloadFile(); runCurrent()
            assertEquals("Never lose this edit", f.bridge.preferences["owner_old.file.notes.md"])
            assertFalse(f.controller.state.value.fileStorageError, "Reopening retries the in-memory draft before its network read")
            assertNotNull(f.controller.state.value.fileLoadError, "Network availability is independent of local durability")
        } finally { f.close() }
    }

    @Test fun typingWhileRemoteReloadIsPendingPreservesTheNewerDraft() = runTest {
        val f = fixture(); val gate = CompletableDeferred<Unit>(); var defer = false; var remote = "Original"
        try {
            f.handler = { request -> if (request.url.encodedPath == "/api/mobile/workspace/file") {
                if (defer) gate.await()
                jsonResponse(file("notes.md", remote))
            } else null }
            runCurrent(); f.controller.openFile("notes.md"); runCurrent()
            f.controller.fileText("First phone draft"); remote = "PC update"
            advanceTimeBy(651); runCurrent()
            assertNotNull(f.controller.state.value.fileConflict)
            defer = true
            f.controller.reloadRemoteFile(); runCurrent()
            f.controller.editorChanged(f.controller.state.value.fileEditorGeneration.toString(), "Typed after the reload tap")
            gate.complete(Unit); runCurrent()
            assertEquals("Typed after the reload tap", f.controller.state.value.fileText)
            assertEquals("Typed after the reload tap", f.bridge.preferences["owner_old.file.notes.md"])
            assertEquals(FileConflict("Typed after the reload tap", "PC update", "r1"), f.controller.state.value.fileConflict)
        } finally { gate.complete(Unit); f.close() }
    }

    @Test fun newFilesAndCopiesUseCreateOnlyRevisionAndPreserveExistingContentOnCollision() = runTest {
        val f = fixture()
        try {
            f.handler = { request -> if (request.url.encodedPath == "/api/mobile/workspace/file") {
                if (request.method == HttpMethod.Get) jsonResponse(file("docs/original.md", "Original"))
                else jsonResponse("""{"detail":{"code":"workspace_revision_conflict","revision":"existing"}}""", HttpStatusCode.Conflict)
            } else null }
            runCurrent(); f.controller.openDirectory("docs"); runCurrent()
            f.controller.createFile("existing", "markdown"); runCurrent()
            assertEquals("files.alreadyExists", f.controller.state.value.error)
            assertNull(f.controller.state.value.openFile)
            f.controller.openFile("docs/original.md"); runCurrent()
            f.controller.fileText("Local copy contents")
            f.controller.saveFileCopy("existing.md"); runCurrent()
            assertEquals("docs/original.md", f.controller.state.value.openFile)
            assertEquals("Local copy contents", f.controller.state.value.fileText)
            val posts = f.requests.filter { it.method == HttpMethod.Post }
            assertEquals(2, posts.size)
            assertTrue(posts.all { it.body?.get("revision")?.jsonPrimitive?.content == "missing" })
            assertTrue(posts.all { it.body?.get("path")?.jsonPrimitive?.content == "docs/existing.md" })
        } finally { f.close() }
    }

    @Test fun creationCapturesItsFolderAndDoesNotOpenInALaterFolder() = runTest {
        val f = fixture(); val gate = CompletableDeferred<Unit>()
        try {
            f.handler = { request -> if (request.url.encodedPath == "/api/mobile/workspace/file") {
                gate.await(); jsonResponse("""{"ok":true,"path":"first/note.md","revision":"created"}""")
            } else null }
            runCurrent(); f.controller.openDirectory("first"); runCurrent()
            f.controller.createFile("note", "markdown"); runCurrent()
            f.controller.openDirectory("second"); runCurrent()
            gate.complete(Unit); runCurrent()
            assertEquals("first/note.md", f.requests.single { it.method == HttpMethod.Post }.body?.get("path")?.jsonPrimitive?.content)
            assertEquals("second", f.controller.state.value.directory)
            assertNull(f.controller.state.value.openFile)
            assertFalse(f.controller.state.value.fileCreating)
        } finally { gate.complete(Unit); f.close() }
    }

    @Test fun editorResourcesRejectUnownedKindsAndStaleIdsBeforeNetworking() = runTest {
        val f = fixture(); val resourcePaths = mutableListOf<String>()
        try {
            f.handler = { request -> when (request.url.encodedPath) {
                "/api/mobile/workspace/file" -> {
                    val path = request.url.parameters["path"]!!
                    resourcePaths += path
                    jsonResponse(file(path, if (path.endsWith("excalidraw")) scene else "Note"))
                }
                else -> null
            } }
            runCurrent(); f.controller.openFile("nested/note.md"); runCurrent()
            val id = f.controller.state.value.fileEditorGeneration.toString()
            for (path in listOf("../other.excalidraw", "/absolute.png", "https://remote/image.png", "page.html", "code.js", "encoded%2Fimage.png"))
                assertNull(f.controller.loadEditorResource(id, path))
            assertNull(f.controller.loadEditorResource("stale", "nested/diagram.excalidraw"))
            val result = async { f.controller.loadEditorResource(id, "nested/diagram.excalidraw") }; runCurrent()
            assertEquals("application/json", result.await()?.mimeType)
            assertEquals(scene, result.await()?.bytes?.decodeToString())
            assertEquals(listOf("nested/note.md", "nested/diagram.excalidraw"), resourcePaths,
                "A folder drawing must never fall back to a root file with the same name")
        } finally { f.close() }
    }

    @Test fun lateResourceDownloadsAreDroppedAfterTheDocumentChanges() = runTest {
        val f = fixture(); val gate = CompletableDeferred<Unit>()
        try {
            f.handler = { request -> when (request.url.encodedPath) {
                "/api/mobile/workspace/file" -> jsonResponse(file(request.url.parameters["path"]!!, "Note"))
                "/api/mobile/workspace/download" -> { gate.await(); respond(byteArrayOf(1, 2), headers = headersOf(HttpHeaders.ContentType, "image/png")) }
                else -> null
            } }
            runCurrent(); f.controller.openFile("note.md"); runCurrent()
            val id = f.controller.state.value.fileEditorGeneration.toString()
            val resource = async { f.controller.loadEditorResource(id, "image.png") }; runCurrent()
            f.controller.openFile("different.md"); runCurrent()
            gate.complete(Unit); runCurrent()
            assertNull(resource.await())
        } finally { gate.complete(Unit); f.close() }
    }

    @Test fun embeddedDrawingCreationReturnsCanonicalPathWithoutReplacingTheNote() = runTest {
        val f = fixture(); var created: String? = null
        try {
            f.handler = { request -> if (request.url.encodedPath == "/api/mobile/workspace/file") {
                if (request.method == HttpMethod.Get) jsonResponse(file("notes/note.md", "# Note"))
                else {
                    val body = f.requests.last().body!!
                    val path = body.getValue("path").jsonPrimitive.content
                    assertEquals("missing", body["revision"]?.jsonPrimitive?.content)
                    assertTrue(path.startsWith("notes/note.drawings/"))
                    jsonResponse(buildJsonObject { put("ok", true); put("path", path); put("revision", "created") }.toString())
                }
            } else null }
            runCurrent(); f.controller.openFile("notes/note.md"); runCurrent()
            f.controller.createEditorDrawing(f.controller.state.value.fileEditorGeneration.toString()) { created = it }; runCurrent()
            assertNotNull(created)
            assertTrue(created!!.endsWith(".excalidraw"))
            assertEquals("# Note", f.controller.state.value.fileText, "The editor inserts the result at its own current cursor")
            assertEquals("notes/note.md", f.controller.state.value.openFile)
        } finally { f.close() }
    }

    @Test fun openingAnEmbeddedDrawingUsesTheExactPathAndBackReturnsToTheNote() = runTest {
        val f = fixture(); val reads = mutableListOf<String>()
        try {
            f.handler = { request -> if (request.url.encodedPath == "/api/mobile/workspace/file") {
                val path = request.url.parameters["path"]!!; reads += path
                jsonResponse(file(path, if (path.endsWith("excalidraw")) scene else "Note"))
            } else null }
            runCurrent(); f.controller.openFile("nested/note.md"); runCurrent()
            f.controller.openEditorLink(f.controller.state.value.fileEditorGeneration.toString(), "nested/diagram.excalidraw"); runCurrent()
            assertEquals("nested/diagram.excalidraw", f.controller.state.value.openFile)
            f.controller.closeFile(); runCurrent()
            assertEquals("nested/note.md", f.controller.state.value.openFile)
            assertEquals(listOf("nested/note.md", "nested/diagram.excalidraw", "nested/note.md"), reads)
        } finally { f.close() }
    }

    @Test fun activeAiOutputCannotBeOpenedAsAnEditablePartialFile() = runTest {
        val f = fixture()
        try {
            f.handler = { request -> if (request.url.encodedPath == "/api/sessions/chat_1") jsonResponse("""{"messages":[{"id":"assistant_1","role":"assistant","content":"Drawing","steps":[{"id":"write","label":"workspace_write","status":"running","input":{"path":"diagram.excalidraw"}}]}]}""") else null }
            runCurrent(); f.controller.openChat("chat_1"); runCurrent()
            assertTrue(f.controller.state.value.messages.single().workFiles.single().active)
            f.controller.openFile("diagram.excalidraw"); runCurrent()
            assertNull(f.controller.state.value.openFile)
            assertEquals("files.stillWriting", f.controller.state.value.error)
            f.controller.browseFile("diagram.excalidraw"); runCurrent()
            assertNull(f.controller.state.value.previewTitle)
            assertTrue(f.requests.none { it.path == "/api/mobile/workspace/file" || it.path == "/api/workspace/file" })
        } finally { f.close() }
    }

    @Test fun activeSessionAliasWritesLockAnAlreadyCanonicalEditor() = runTest {
        val f = fixture()
        try {
            f.handler = { request -> when {
                request.url.encodedPath == "/api/mobile/workspace/file" ->
                    jsonResponse(file("sessions/chat_1/diagram.excalidraw", scene))
                request.url.encodedPath == "/api/mobile/messages" && request.method == HttpMethod.Post ->
                    jsonResponse("""{"id":"drawing_job","session_id":"chat_1","state":"running"}""")
                request.url.encodedPath == "/api/mobile/messages/drawing_job/events" ->
                    sseResponse("id: 1\nevent: tool_start\ndata: {\"step\":{\"id\":\"draw\",\"label\":\"workspace_write\",\"status\":\"running\",\"input\":{\"path\":\"session/diagram.excalidraw\"}}}\n\n")
                else -> null
            } }
            runCurrent(); f.controller.openChat("chat_1"); runCurrent()
            f.controller.openFile("sessions/chat_1/diagram.excalidraw"); runCurrent()
            f.controller.fileText("Before the AI write")
            val generation = f.controller.state.value.fileEditorGeneration
            f.controller.draft("Update the diagram"); f.controller.send(); runCurrent()
            assertTrue(f.controller.state.value.fileWriteActive)
            assertTrue(f.controller.state.value.fileExternallyChanged)
            f.controller.editorChanged(generation.toString(), "Queued edit from before the AI lock")
            f.controller.retryFileSave(); runCurrent()
            assertEquals("Queued edit from before the AI lock", f.controller.state.value.fileText)
            assertTrue(f.requests.none { it.path == "/api/mobile/workspace/file" && it.method == HttpMethod.Post })
            f.controller.openFile("sessions/chat_1/diagram.excalidraw"); runCurrent()
            assertTrue(f.controller.state.value.fileEditorGeneration > generation)
            assertTrue(f.controller.state.value.fileWriteActive)
            assertEquals("Queued edit from before the AI lock", f.controller.state.value.fileText)
        } finally { f.close() }
    }

    @Test fun editedHtmlPreviewWaitsForItsSaveAndKeepsTheCapturedSession() = runTest {
        val f = fixture(); val gate = CompletableDeferred<Unit>(); var remote = "Original"; val previewSessions = mutableListOf<String?>()
        try {
            f.handler = { request -> when (request.url.encodedPath) {
                "/api/mobile/workspace/web-preview" -> {
                    previewSessions += request.url.parameters["session_id"]
                    jsonResponse("""{"ready":true,"kind":"web","root":"site","entry":"page.html","project_path":"site"}""")
                }
                "/api/mobile/workspace/file" -> {
                    if (request.method == HttpMethod.Get) jsonResponse(file("site/page.html", remote))
                    else {
                        val body = f.requests.last().body!!
                        assertEquals("chat_2", body["session_id"]?.jsonPrimitive?.content)
                        gate.await(); remote = body.getValue("content").jsonPrimitive.content
                        jsonResponse("""{"ok":true,"path":"site/page.html","revision":"saved"}""")
                    }
                }
                else -> null
            } }
            runCurrent(); f.controller.openLink("/file/site/page.html?session_id=chat_2"); runCurrent()
            f.controller.editPreview(); runCurrent()
            f.controller.fileText("<h1>Latest</h1>")
            f.controller.previewEditedFile(f.controller.state.value.fileEditorGeneration.toString()); runCurrent()
            assertNull(f.controller.state.value.previewTitle)
            gate.complete(Unit); runCurrent()
            assertEquals("<h1>Latest</h1>", remote)
            assertEquals("site/page.html", f.controller.state.value.previewPath)
            assertEquals(listOf<String?>("chat_2", "chat_2"), previewSessions)
            assertEquals("site/page.html", f.controller.state.value.openFile)
            assertFalse(f.controller.state.value.fileLoading)
        } finally { gate.complete(Unit); f.close() }
    }

    @Test fun editedPreviewNeverHidesAnUnsavedConflictBehindStaleServerContent() = runTest {
        val f = fixture(); var remote = "Original"
        try {
            f.handler = { request -> if (request.url.encodedPath == "/api/mobile/workspace/file") jsonResponse(file("note.md", remote)) else null }
            runCurrent(); f.controller.openFile("note.md"); runCurrent()
            f.controller.fileText("Phone draft"); remote = "PC update"
            f.controller.previewEditedFile(f.controller.state.value.fileEditorGeneration.toString()); runCurrent()
            assertNull(f.controller.state.value.previewTitle)
            assertEquals("Phone draft", f.controller.state.value.fileText)
            assertNotNull(f.controller.state.value.fileConflict)
            assertEquals("note.md", f.controller.state.value.openFile)
        } finally { f.close() }
    }

    @Test fun copyAfterFlushUsesFreshControllerTextAndRejectsOldDocumentIds() = runTest {
        val f = fixture()
        try {
            f.handler = { request -> if (request.url.encodedPath == "/api/mobile/workspace/file") {
                if (request.method == HttpMethod.Post) jsonResponse("""{"ok":true,"path":"note.md","revision":"saved"}""")
                else jsonResponse(file(request.url.parameters["path"]!!, "Original"))
            } else null }
            runCurrent(); f.controller.openFile("note.md"); runCurrent()
            val id = f.controller.state.value.fileEditorGeneration.toString()
            // Model change + flush acknowledgement in one native callback turn,
            // before Compose has had a chance to capture another state snapshot.
            f.controller.editorChanged(id, "The final keystroke")
            f.controller.copyEditorContent(id)
            assertEquals(listOf("The final keystroke"), f.bridge.copiedTexts)
            f.controller.openFile("different.md"); runCurrent()
            f.controller.copyEditorContent(id)
            assertEquals(listOf("The final keystroke"), f.bridge.copiedTexts)
        } finally { f.close() }
    }

    @Test fun immediateNewChatAndDisconnectRetainTheSynchronousRecoveryDraft() = runTest {
        val f = fixture()
        try {
            f.handler = { request -> if (request.url.encodedPath == "/api/mobile/workspace/file")
                jsonResponse(file("note.md", "Original")) else null }
            runCurrent(); f.controller.openFile("note.md"); runCurrent()
            val id = f.controller.state.value.fileEditorGeneration.toString()
            f.controller.editorChanged(id, "Typed immediately before leaving")
            f.controller.newChat()
            f.controller.disconnect()
            f.controller.editorChanged(id, "Late disposed editor event")
            runCurrent()
            assertEquals("Typed immediately before leaving", f.bridge.preferences["owner_old.file.note.md"])
            assertEquals("Original", f.bridge.preferences["owner_old.fileBaseline.note.md"])
            assertNull(f.controller.state.value.openFile)
            assertTrue(f.requests.none { it.method == HttpMethod.Post && it.path == "/api/mobile/workspace/file" })
        } finally { f.close() }
    }

    @Test fun clipboardFailureKeepsTheOnlyInMemoryRecoveryAndNeverReportsCopied() = runTest {
        val f = fixture()
        try {
            f.handler = { request -> if (request.url.encodedPath == "/api/mobile/workspace/file")
                jsonResponse(file("note.md", "Original")) else null }
            runCurrent(); f.controller.openFile("note.md"); runCurrent()
            val id = f.controller.state.value.fileEditorGeneration.toString()
            f.bridge.beforePreferenceWrite = { key, _ -> if (key.startsWith("owner_old.file")) error("Disk full") }
            f.controller.editorChanged(id, "The only surviving draft")
            assertNull(f.bridge.preferences["owner_old.file.note.md"])
            f.bridge.failCopy = true
            f.controller.copyEditorContent(id)
            assertEquals("The only surviving draft", f.controller.state.value.fileText)
            assertTrue(f.controller.state.value.fileStorageError)
            assertTrue(f.controller.state.value.fileRecovery)
            assertEquals("error.copyFailed", f.controller.state.value.error)
            assertNull(f.controller.state.value.notice)
            assertTrue(f.bridge.copiedTexts.isEmpty())
            f.bridge.failCopy = false
            f.controller.copyEditorContent(id)
            assertEquals(listOf("The only surviving draft"), f.bridge.copiedTexts)
            assertEquals("chat.copied", f.controller.state.value.notice)
            assertNull(f.controller.state.value.error)
            assertTrue(f.controller.state.value.fileStorageError)
            f.bridge.failCopy = true
            f.controller.copyContent("Conflict version")
            assertEquals("error.copyFailed", f.controller.state.value.error)
            assertNull(f.controller.state.value.notice, "An earlier successful copy must not mask a later failure")
            assertEquals("The only surviving draft", f.controller.state.value.fileText)
        } finally { f.close() }
    }
}
