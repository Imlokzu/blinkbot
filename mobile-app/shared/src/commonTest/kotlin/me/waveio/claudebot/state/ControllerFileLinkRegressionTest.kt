@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package me.waveio.claudebot.state

import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import me.waveio.claudebot.ui.LocaleText
import kotlin.test.*

/** Links use the paired transport and retain the captured workspace/session through editing. */
class ControllerFileLinkRegressionTest {
    private fun TestScope.fixture() = ControllerTestFixture(StandardTestDispatcher(testScheduler))

    @Test fun anOwnedUrlPreviewsWithoutOpeningTheBrowserAndExportsTheSameSession() = runTest {
        val f=fixture();val queries=mutableListOf<Pair<String,String?>>()
        try {
            f.handler={ request -> when(request.url.encodedPath) {
                "/api/workspace/file" -> {
                    queries += request.url.encodedPath to request.url.parameters["session_id"]
                    jsonResponse("""{"path":"session/notes.md","content":"# Linked report","binary":false}""")
                }
                "/api/mobile/workspace/download" -> {
                    queries += request.url.encodedPath to request.url.parameters["session_id"]
                    assertEquals("Bearer old-device-token",request.headers[HttpHeaders.Authorization])
                    respond("# Linked report\r\n",headers=headersOf(HttpHeaders.ContentType,"text/markdown"))
                }
                else -> null
            } }
            runCurrent();f.controller.openChat("chat_1");runCurrent()
            f.controller.openLink("https://old.example/file/session/notes.md?session_id=chat_2&r=9")
            runCurrent()
            assertEquals("# Linked report",f.controller.state.value.previewText)
            assertTrue(f.controller.state.value.previewEditable)
            assertEquals("chat_2",f.controller.state.value.previewSessionId)
            assertEquals("chat_1",f.controller.state.value.sessionId)
            f.controller.savePreview();runCurrent()
            assertEquals(listOf<Pair<String,String?>>("/api/workspace/file" to "chat_2","/api/mobile/workspace/download" to "chat_2"),queries)
            assertTrue(f.bridge.openedLinks.isEmpty())
        } finally { f.close() }
    }

    @Test fun editingUsesTheLinkedSessionAndReturnsToTheChatWithItsDraft() = runTest {
        val f=fixture();var remote="Original";val writes=mutableListOf<JsonObject>()
        try {
            f.handler={ request -> when(request.url.encodedPath) {
                "/api/workspace/file" -> jsonResponse("""{"path":"session/notes.md","content":"$remote","binary":false}""")
                "/api/mobile/workspace/file" -> {
                    assertEquals("chat_2",request.url.parameters["session_id"] ?: Json.parseToJsonElement((request.body as TextContent).text).jsonObject["session_id"]?.jsonPrimitive?.content)
                    if(request.method==HttpMethod.Post) {
                        val body=Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        writes+=body;remote=body.getValue("content").jsonPrimitive.content
                        jsonResponse("""{"ok":true,"path":"session/notes.md","revision":"saved"}""")
                    } else jsonResponse("""{"path":"session/notes.md","content":"$remote","binary":false,"revision":"before"}""")
                }
                else -> null
            } }
            runCurrent();f.controller.openChat("chat_1");runCurrent();f.controller.draft("Unsent chat draft")
            f.controller.openLink("/file/session/notes.md?session_id=chat_2");runCurrent()
            f.controller.editPreview();runCurrent()
            assertEquals(Screen.Files,f.controller.state.value.screen)
            assertEquals("chat_2",f.controller.state.value.fileSessionId)
            assertNull(f.controller.state.value.previewTitle)
            f.controller.fileText("Edited on the phone");advanceTimeBy(651);runCurrent()
            assertEquals("Edited on the phone",remote)
            assertEquals("chat_2",writes.single()["session_id"]?.jsonPrimitive?.content)
            assertEquals("before",writes.single()["revision"]?.jsonPrimitive?.content)
            f.controller.closeFile();runCurrent()
            assertEquals(Screen.Chat,f.controller.state.value.screen)
            assertEquals("Unsent chat draft",f.controller.state.value.draft)
            assertEquals("chat_1",f.controller.state.value.sessionId)
        } finally { f.close() }
    }

    @Test fun savedRecoveryDraftsDoNotCrossSessionPrefixes() = runTest {
        val f=fixture()
        try {
            f.bridge.preferences["owner_old.file.session.v1:chat_2:session/notes.md"]="Recovered second chat"
            f.bridge.preferences["owner_old.fileBaseline.session.v1:chat_2:session/notes.md"]="Original"
            f.handler={ request -> when(request.url.encodedPath) {
                "/api/workspace/file","/api/mobile/workspace/file" -> jsonResponse("""{"path":"session/notes.md","content":"Original","binary":false,"revision":"before"}""")
                else -> null
            } }
            runCurrent()
            f.controller.openLink("/file/session/notes.md?session_id=chat_3");runCurrent();f.controller.editPreview();runCurrent()
            assertEquals("Original",f.controller.state.value.fileText)
            f.controller.closeFile()
            f.controller.openLink("/file/session/notes.md?session_id=chat_2");runCurrent();f.controller.editPreview();runCurrent()
            assertEquals("Recovered second chat",f.controller.state.value.fileText)
            assertEquals("failed",f.controller.state.value.fileSaveState)
        } finally { f.close() }
    }

    @Test fun webResourcesUseFixedRootSessionAndRevisionWithoutEnteringThePageCredentials() = runTest {
        val f=fixture();val calls=mutableListOf<Map<String,String?>>()
        try {
            f.handler={ request -> when(request.url.encodedPath) {
                "/api/mobile/workspace/web-preview" -> jsonResponse("""{"ready":true,"kind":"web","root":"sessions/chat_2/site/dist","entry":"index.html","project_path":"sessions/chat_2/site","buildable":true}""")
                "/api/mobile/workspace/web-resource" -> {
                    assertEquals("Bearer old-device-token",request.headers[HttpHeaders.Authorization])
                    calls += request.url.parameters.names().associateWith { request.url.parameters[it] }
                    respond("export const title = 'Working app';",headers=headersOf(HttpHeaders.ContentType,"text/javascript"))
                }
                else -> null
            } }
            runCurrent();f.controller.openLink("/preview/session/site?session_id=chat_2");runCurrent()
            val revision=f.controller.state.value.previewRevision
            val resource=async { f.controller.loadWebPreviewResource(revision,"/assets/main.js") }
            runCurrent();assertEquals("text/javascript",resource.await()?.mimeType)
            assertEquals(mapOf("root" to "sessions/chat_2/site/dist","path" to "assets/main.js","entry" to "index.html","session_id" to "chat_2"),calls.single())
            f.controller.closePreview()
            val stale=async { f.controller.loadWebPreviewResource(revision,"/assets/main.js") }
            runCurrent();assertNull(stale.await());assertEquals(1,calls.size)
        } finally { f.close() }
    }

    @Test fun lateWebResourcesAreDiscardedAfterAccountSwitch() = runTest {
        val f=fixture();val gate=CompletableDeferred<Unit>()
        try {
            f.handler={ request -> when(request.url.encodedPath) {
                "/api/mobile/workspace/web-preview" -> jsonResponse("""{"ready":true,"root":"site","entry":"index.html","project_path":"site"}""")
                "/api/mobile/workspace/web-resource" -> { gate.await();respond("old page",headers=headersOf(HttpHeaders.ContentType,"text/html")) }
                else -> null
            } }
            runCurrent();f.controller.openLink("/preview/site/index.html");runCurrent()
            val revision=f.controller.state.value.previewRevision
            val result=async { runCatching { f.controller.loadWebPreviewResource(revision,"/index.html") }.getOrNull() }
            runCurrent();f.controller.disconnect();f.pairNewOwner();runCurrent()
            gate.complete(Unit);runCurrent()
            assertNull(result.await())
            assertEquals("https://new.example",f.controller.state.value.baseUrl)
            assertNull(f.controller.state.value.previewTitle)
            assertTrue(f.requests.filter { it.path.endsWith("web-resource") }.all { it.host=="old.example" })
        } finally { f.close() }
    }

    @Test fun buildActionQueuesTheLinkedProjectWithoutOverwritingEitherChatDraft() = runTest {
        val f=fixture()
        try {
            f.bridge.preferences["owner_old.draft.chat_2"]="Second unsent draft"
            f.controller.strings(LocaleText(mapOf("files.buildPrompt" to "Build project {path}")))
            f.handler={ request -> when {
                request.url.encodedPath=="/api/mobile/workspace/web-preview" -> jsonResponse("""{"ready":false,"reason":"build_required","root":"sessions/chat_2/app","project_path":"sessions/chat_2/app","buildable":true}""")
                request.url.encodedPath=="/api/mobile/messages" && request.method==HttpMethod.Post -> jsonResponse("""{"id":"build_job","session_id":"chat_2","state":"queued"}""")
                else -> null
            } }
            runCurrent();f.controller.openChat("chat_1");runCurrent();f.controller.draft("First unsent draft")
            f.controller.openLink("/preview/session/app/?session_id=chat_2");runCurrent()
            assertFalse(f.controller.state.value.previewWeb!!.ready)
            f.controller.buildPreviewProject();f.controller.buildPreviewProject();runCurrent()
            val sent=f.requests.filter { it.path=="/api/mobile/messages" && it.method==HttpMethod.Post }.single().body!!
            assertEquals("chat_2",sent["session_id"]?.jsonPrimitive?.content)
            assertEquals("Build project sessions/chat_2/app",sent["message"]?.jsonPrimitive?.content)
            assertEquals("First unsent draft",f.bridge.preferences["owner_old.draft.chat_1"])
            assertEquals("Second unsent draft",f.controller.state.value.draft)
            assertEquals("chat_2",f.controller.state.value.sessionId)
        } finally { f.close() }
    }

    @Test fun closingAnEditorDuringItsReadDoesNotLeaveTheChatLoading() = runTest {
        val f=fixture();val gate=CompletableDeferred<Unit>()
        try {
            f.handler={ request -> when(request.url.encodedPath) {
                "/api/mobile/workspace/file" -> { gate.await();jsonResponse("""{"path":"notes.md","content":"Late","binary":false,"revision":"one"}""") }
                else -> null
            } }
            runCurrent();f.controller.openFile("notes.md");runCurrent()
            assertTrue(f.controller.state.value.loading)
            f.controller.closeFile()
            assertFalse(f.controller.state.value.loading)
            gate.complete(Unit);runCurrent()
            assertNull(f.controller.state.value.openFile)
            assertFalse(f.controller.state.value.loading)
            assertEquals(Screen.Chat,f.controller.state.value.screen)
        } finally { f.close() }
    }

    @Test fun aPreviousFilesLateSaveFailureCannotInterruptTheNewEditor() = runTest {
        val f=fixture();val saveGate=CompletableDeferred<Unit>();val readGate=CompletableDeferred<Unit>()
        try {
            f.handler={ request -> when(request.url.encodedPath) {
                "/api/mobile/workspace/file" -> {
                    if(request.method==HttpMethod.Post) { saveGate.await();jsonResponse("""{"detail":"file_conflict"}""",HttpStatusCode.Conflict) }
                    else {
                        val path=request.url.parameters["path"]!!
                        if(path=="new.md") readGate.await()
                        jsonResponse("""{"path":"$path","content":"Original","binary":false,"revision":"one"}""")
                    }
                }
                else -> null
            } }
            runCurrent();f.controller.openFile("old.md");runCurrent()
            f.controller.fileText("Pending edit");advanceTimeBy(651);runCurrent()
            f.controller.openFile("new.md");runCurrent()
            assertTrue(f.controller.state.value.loading)
            saveGate.complete(Unit);runCurrent()
            assertTrue(f.controller.state.value.loading)
            assertNull(f.controller.state.value.error)
            assertEquals("new.md",f.controller.state.value.openFile)
            assertEquals("Pending edit",f.bridge.preferences["owner_old.file.old.md"])
            readGate.complete(Unit);runCurrent()
            assertEquals("Original",f.controller.state.value.fileText)
            assertFalse(f.controller.state.value.loading)
        } finally { f.close() }
    }

    @Test fun aliasAndCanonicalLinksRecoverTheSameFileDraft() = runTest {
        val f=fixture()
        try {
            f.bridge.preferences["owner_old.file.session.v1:chat_2:session/note.md"]="Recovered alias edit"
            f.bridge.preferences["owner_old.fileBaseline.session.v1:chat_2:session/note.md"]="Original"
            f.handler={ request -> when(request.url.encodedPath) {
                "/api/workspace/file","/api/mobile/workspace/file" -> jsonResponse("""{"path":"sessions/chat_2/note.md","content":"Original","binary":false,"revision":"one"}""")
                else -> null
            } }
            runCurrent();f.controller.openLink("/file/session/note.md?session_id=chat_2");runCurrent()
            f.controller.editPreview();runCurrent()
            assertEquals("sessions/chat_2/note.md",f.controller.state.value.openFile)
            assertEquals("Recovered alias edit",f.controller.state.value.fileText)
            assertNull(f.bridge.preferences["owner_old.file.session.v1:chat_2:session/note.md"])
            assertEquals("Recovered alias edit",f.bridge.preferences["owner_old.file.sessions/chat_2/note.md"])
            f.controller.openFile("sessions/chat_2/note.md");runCurrent()
            assertEquals("Recovered alias edit",f.controller.state.value.fileText)
        } finally { f.close() }
    }

    @Test fun anAliasSaveAcknowledgementCannotEraseANewerCanonicalDraft() = runTest {
        val f=fixture();val firstSave=CompletableDeferred<Unit>();val nextRead=CompletableDeferred<Unit>()
        var remote="Original";var writing=false
        try {
            f.handler={ request -> when(request.url.encodedPath) {
                "/api/workspace/file" -> jsonResponse("""{"path":"sessions/chat_2/note.md","content":"Original","binary":false}""")
                "/api/mobile/workspace/file" -> {
                    if(request.method==HttpMethod.Post) {
                        val content=Json.parseToJsonElement((request.body as TextContent).text).jsonObject.getValue("content").jsonPrimitive.content
                        if(content=="First edit") { writing=true;firstSave.await() }
                        remote=content
                        jsonResponse("""{"ok":true,"path":"sessions/chat_2/note.md","revision":"saved"}""")
                    } else {
                        if(remote=="First edit") nextRead.await()
                        jsonResponse("""{"path":"sessions/chat_2/note.md","content":"$remote","binary":false,"revision":"one"}""")
                    }
                }
                else -> null
            } }
            runCurrent();f.controller.openLink("/file/session/note.md?session_id=chat_2");runCurrent();f.controller.editPreview();runCurrent()
            f.controller.fileText("First edit");f.controller.retryFileSave();runCurrent();assertTrue(writing)
            f.controller.openFile("sessions/chat_2/note.md");runCurrent()
            f.controller.fileText("Newer draft")
            firstSave.complete(Unit);runCurrent()
            assertEquals("Newer draft",f.bridge.preferences["owner_old.file.sessions/chat_2/note.md"])
            assertEquals("Newer draft",f.controller.state.value.fileText)
            nextRead.complete(Unit);runCurrent()
            assertEquals("Newer draft",remote)
            assertNull(f.bridge.preferences["owner_old.file.sessions/chat_2/note.md"])
        } finally { f.close() }
    }

    @Test fun relativeLinksResolveAgainstTheServersCanonicalPreviewPath() = runTest {
        val f=fixture();val paths=mutableListOf<String>()
        try {
            f.handler={ request -> when(request.url.encodedPath) {
                "/api/workspace/file" -> {
                    paths+=request.url.parameters["path"]!!
                    jsonResponse("""{"path":"sessions/chat_2/note.md","content":"Report","binary":false}""")
                }
                else -> null
            } }
            runCurrent();f.controller.openLink("/file/session/note.md?session_id=chat_2");runCurrent()
            f.controller.openLink("../summary.md");runCurrent()
            assertEquals(listOf("session/note.md","sessions/summary.md"),paths)
            assertEquals("chat_2",f.controller.state.value.previewSessionId)
        } finally { f.close() }
    }

    @Test fun buildFromAnEmptyChatUsesTheLinkedChatsSavedModel() = runTest {
        val f=fixture()
        try {
            f.controller.strings(LocaleText(mapOf("files.buildPrompt" to "Build {path}")))
            f.handler={ request -> when {
                request.url.encodedPath=="/api/mobile/workspace/web-preview" -> jsonResponse("""{"ready":false,"root":"app","project_path":"app","buildable":true}""")
                request.url.encodedPath=="/api/mobile/messages" && request.method==HttpMethod.Post -> jsonResponse("""{"id":"build_job","session_id":"chat_2","state":"queued"}""")
                else -> null
            } }
            runCurrent()
            f.controller.preferences(f.controller.state.value.preferences.copy(chatModels=mapOf("chat_2" to "regolo/target")))
            assertTrue(f.controller.state.value.sessionId.isBlank())
            f.controller.openLink("/preview/app?session_id=chat_2");runCurrent()
            f.controller.buildPreviewProject();runCurrent()
            val sent=f.requests.single { it.path=="/api/mobile/messages" && it.method==HttpMethod.Post }.body!!
            assertEquals("regolo/target",sent["model"]?.jsonPrimitive?.content)
        } finally { f.close() }
    }

    @Test fun unsafeOwnedLinksNeverReachNetworkingOrExternalBrowser() = runTest {
        val f=fixture()
        try {
            runCurrent();val initial=f.requests.size
            for(url in listOf("/file/../secret.txt","/preview/%252e%252e/secret.html","/file/a.md?session_id=a&session_id=b")) {
                f.controller.openLink(url);runCurrent()
                assertEquals("files.invalidPath",f.controller.state.value.error)
                assertEquals(initial,f.requests.size)
                assertTrue(f.bridge.openedLinks.isEmpty())
            }
            f.controller.openLink("https://external.example/file/public.md")
            assertEquals(listOf("https://external.example/file/public.md"),f.bridge.openedLinks)
        } finally { f.close() }
    }
}
