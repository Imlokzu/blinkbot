@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package me.waveio.claudebot.state

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import me.waveio.claudebot.platform.PickedFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class AppControllerTest {
    private val fixtures = mutableListOf<ControllerTestFixture>()
    private fun fixture(dispatcher: CoroutineDispatcher, bridge: FakePlatformBridge = FakePlatformBridge()): ControllerTestFixture =
        ControllerTestFixture(dispatcher, bridge).also { fixtures += it }

    @AfterTest fun closeControllers() { fixtures.forEach { it.close() } }

    private fun controllerTest(block: suspend TestScope.() -> Unit): TestResult = runTest(timeout = 10.seconds) {
        try { block() } finally {
            closeControllers()
            fixtures.clear()
            runCurrent()
        }
    }

    @Test fun profileUpdateCheckRefreshesTheInstallDialogFromTheCurrentConnection() = controllerTest {
        val fixture = fixture(StandardTestDispatcher(testScheduler))
        fixture.handler = { request ->
            if (request.url.encodedPath == "/api/mobile/capabilities") jsonResponse(
                """{"update":{"available":true,"version_name":"0.4.9","version_code":16,"changelog":["Smoother live text"],"url":"https://old.example/api/mobile/update/download","sha256":"${"a".repeat(64)}"}}"""
            ) else null
        }
        runCurrent()
        fixture.controller.dismissUpdate()
        fixture.controller.checkForUpdate()
        runCurrent()
        assertEquals("0.4.9", fixture.controller.state.value.update?.versionName)
        assertEquals(listOf("Smoother live text"), fixture.controller.state.value.update?.changelog)
        assertFalse(fixture.controller.state.value.updateChecking)
    }

    @Test fun initialNetworkFailureIsReportedWithoutAnUncaughtCoroutineException() = controllerTest {
        val fixture = fixture(StandardTestDispatcher(testScheduler))
        fixture.handler = { request ->
            if (request.url.encodedPath == "/api/mobile/capabilities") throw IllegalStateException("local simulated disconnection")
            null
        }
        runCurrent()
        assertFalse(fixture.controller.state.value.connected)
        assertFalse(fixture.controller.state.value.connecting)
        assertEquals("error.network", fixture.controller.state.value.error)
    }

    @Test fun queuedJobStateCannotHideAnotherJobsActiveStopControl() = controllerTest {
        val fixture = fixture(StandardTestDispatcher(testScheduler))
        val queuedEvent = CompletableDeferred<Unit>()
        fixture.handler = { request -> when {
            request.url.encodedPath == "/api/mobile/messages" -> jsonResponse("""{"messages":[{"id":"active","session_id":"chat_1","state":"running"},{"id":"queued","session_id":"chat_1","state":"queued","message":"Next"}]}""")
            request.url.encodedPath == "/api/mobile/messages/active/events" -> sseResponse("id: 1\nevent: mobile_state\ndata: {\"state\":\"running\"}\n\n")
            request.url.encodedPath == "/api/mobile/messages/queued/events" -> {
                queuedEvent.await()
                sseResponse("id: 1\nevent: mobile_state\ndata: {\"state\":\"queued\"}\n\nid: 2\nevent: mobile_state\ndata: {\"state\":\"stopped\"}\n\n")
            }
            else -> null
        } }
        runCurrent()
        fixture.controller.openChat("chat_1")
        runCurrent()
        assertTrue(fixture.controller.state.value.busy)
        assertEquals("active", fixture.controller.state.value.activeJobId)
        queuedEvent.complete(Unit)
        runCurrent()
        assertTrue(fixture.controller.state.value.busy, "A queued job must not hide the active run's Stop control")
        assertEquals("active", fixture.controller.state.value.activeJobId)
    }

    @Test fun stopPreservesTheQueueAndRequiresExplicitResume() = controllerTest {
        val fixture = fixture(StandardTestDispatcher(testScheduler))
        var paused = false
        fixture.handler = { request -> when {
            request.url.encodedPath == "/api/mobile/messages" -> jsonResponse(if (paused)
                """{"messages":[{"id":"active","session_id":"chat_1","state":"stopped","conversation_paused":true},{"id":"queued","session_id":"chat_1","state":"queued","message":"Next","conversation_paused":true}]}"""
                else """{"messages":[{"id":"active","session_id":"chat_1","state":"running"},{"id":"queued","session_id":"chat_1","state":"queued","message":"Next"}]}""")
            request.url.encodedPath == "/api/mobile/messages/active/events" -> sseResponse("id: 1\nevent: mobile_state\ndata: {\"state\":\"running\"}\n\n")
            request.url.encodedPath == "/api/mobile/messages/active/stop" -> {
                paused = true
                jsonResponse("""{"id":"active","session_id":"chat_1","state":"stopped"}""")
            }
            request.url.encodedPath == "/api/mobile/sessions/chat_1/resume" -> {
                paused = false
                jsonResponse("""{"ok":true,"paused":false,"session_id":"chat_1"}""")
            }
            else -> null
        } }
        runCurrent()
        fixture.controller.openChat("chat_1")
        runCurrent()
        fixture.controller.stop()
        runCurrent()
        assertTrue(fixture.controller.state.value.queuePaused)
        assertTrue(fixture.controller.state.value.pending.any { it.id == "queued" })
        assertFalse(fixture.requests.any { it.path.endsWith("/resume") })
        assertFalse(fixture.requests.any { it.path == "/api/mobile/messages" && it.method == HttpMethod.Post })
        fixture.controller.resumeQueue()
        runCurrent()
        assertEquals(1, fixture.requests.count { it.path.endsWith("/resume") })
    }

    @Test fun permanentOutboxFailurePreservesTheRejectedMessageAlongsideANewerDraft() = controllerTest {
        val fixture = fixture(StandardTestDispatcher(testScheduler))
        val reject = CompletableDeferred<Unit>()
        fixture.handler = { request -> if (request.url.encodedPath == "/api/mobile/messages" && request.method == HttpMethod.Post) {
            reject.await()
            jsonResponse("""{"detail":{"code":"invalid_attachment"}}""", HttpStatusCode.BadRequest)
        } else null }
        runCurrent()
        fixture.controller.draft("Submitted message that must survive")
        fixture.controller.send()
        runCurrent()
        fixture.controller.draft("Newer unsent draft")
        reject.complete(Unit)
        runCurrent()
        assertEquals("Newer unsent draft", fixture.controller.state.value.draft)
        assertNotNull(fixture.controller.state.value.error)
        val persisted = fixture.bridge.preferences.filterKeys { it.startsWith("owner_old.") }.values.joinToString("\n")
        assertTrue(persisted.contains("Submitted message that must survive"), "A permanent failure must retain recovery data, even when another draft is open")
        val failed = Json.decodeFromString<List<OutboxItem>>(fixture.bridge.preferences.getValue("owner_old.outbox.v1")).single()
        assertEquals("invalid_attachment", failed.lastError)
        val allowed = Json.decodeFromString<List<String>>(fixture.bridge.preferences.getValue("owner_old.outbox.allowed"))
        assertFalse(failed.clientId in allowed, "Permanent failures require an explicit retry rather than background delivery")
        fixture.controller.navigate(Screen.Queue)
        runCurrent()
        val visible = fixture.controller.state.value.allPending.single { it.id == "local:${failed.clientId}" }
        assertEquals("failed", visible.state)
        assertEquals(failed.message, visible.text)
    }

    @Test fun queueScreenIncludesOtherConversationsAndRecoverableLocalFailures() = controllerTest {
        val bridge = FakePlatformBridge()
        val failed = OutboxItem("local_failed", "old_chat", "Unsent recovery", "regolo/model", "low", lastError = "invalid_attachment")
        bridge.preferences["owner_old.outbox.v1"] = Json.encodeToString(listOf(failed))
        val fixture = fixture(StandardTestDispatcher(testScheduler), bridge)
        fixture.handler = { request -> if (request.url.encodedPath == "/api/mobile/messages" && request.method == HttpMethod.Get)
            jsonResponse("""{"messages":[{"id":"scheduled","session_id":"other_chat","state":"scheduled","message":"Later","scheduled_at":1791129600},{"id":"server_failed","session_id":"third_chat","state":"failed","message":"Retry me"}]}""") else null }
        runCurrent()
        fixture.controller.navigate(Screen.Queue)
        runCurrent()
        assertTrue(fixture.requests.any { it.path == "/api/mobile/messages" && it.method == HttpMethod.Get })
        val rows = fixture.controller.state.value.allPending.associateBy { it.id }
        assertEquals("other_chat", rows.getValue("scheduled").sessionId)
        assertNotNull(rows.getValue("scheduled").scheduledAt)
        assertEquals("third_chat", rows.getValue("server_failed").sessionId)
        assertEquals("failed", rows.getValue("local:local_failed").state)
        assertFalse(fixture.requests.any { it.path == "/api/mobile/messages" && it.method == HttpMethod.Post })
    }

    @Test fun idempotentSubmissionReturningACompletedJobReloadsHistoryWithoutWatching() = controllerTest {
        val fixture = fixture(StandardTestDispatcher(testScheduler))
        fixture.handler = { request -> when {
            request.url.encodedPath == "/api/mobile/messages" && request.method == HttpMethod.Post ->
                jsonResponse("""{"id":"already_complete","session_id":"chat_1","state":"completed"}""")
            request.url.encodedPath == "/api/mobile/messages" ->
                jsonResponse("""{"messages":[{"id":"already_complete","session_id":"chat_1","state":"completed"}]}""")
            request.url.encodedPath == "/api/sessions/chat_1" ->
                jsonResponse("""{"messages":[{"id":"canonical_reply","role":"assistant","content":"Already saved","parts":[{"type":"text","text":"Already saved"}]}]}""")
            else -> null
        } }
        runCurrent()
        fixture.controller.openChat("chat_1")
        runCurrent()
        fixture.controller.draft("Original idempotent request")
        fixture.controller.send()
        runCurrent()
        assertEquals("canonical_reply", fixture.controller.state.value.messages.single().id)
        assertFalse(fixture.requests.any { it.path.endsWith("/events") })
        assertFalse(fixture.controller.state.value.busy)
    }

    @Test fun lateFilePickerResultCannotUploadOldOwnersBytesAfterPairing() = controllerTest {
        val fixture = fixture(StandardTestDispatcher(testScheduler))
        fixture.handler = { request -> if (request.url.encodedPath == "/api/chat/upload")
            jsonResponse("""{"url":"/uploads/file.txt","name":"file.txt","type":"text/plain","size":4}""") else null }
        runCurrent()
        fixture.controller.pickFile("document")
        val oldCallback = fixture.bridge.pickedResult!!
        fixture.controller.disconnect()
        fixture.pairNewOwner()
        runCurrent()
        assertTrue(fixture.controller.state.value.connected)
        oldCallback(PickedFile("file.txt", "text/plain", "old owner's private file".encodeToByteArray()))
        runCurrent()
        assertFalse(fixture.requests.any { it.host == "new.example" && it.path == "/api/chat/upload" }, "An OS result captured for the previous owner must be discarded before upload")
        assertTrue(fixture.controller.state.value.attachments.isEmpty())
    }

    @Test fun lateDictationCallbackCannotSubmitOldAudioToANewOwner() = controllerTest {
        val fixture = fixture(StandardTestDispatcher(testScheduler))
        fixture.handler = { request -> if (request.url.encodedPath.startsWith("/api/asr")) jsonResponse("""{"text":"Old transcript"}""") else null }
        runCurrent()
        fixture.controller.startDictation()
        val oldFinal = fixture.bridge.recordingResult!!
        val oldPartial = fixture.bridge.recordingPartial!!
        fixture.controller.disconnect()
        fixture.pairNewOwner()
        runCurrent()
        val audio = PickedFile("record.webm", "audio/webm", byteArrayOf(1, 2, 3))
        oldPartial(audio)
        oldFinal(audio)
        runCurrent()
        assertFalse(fixture.requests.any { it.host == "new.example" && it.path.startsWith("/api/asr") })
        assertEquals("", fixture.controller.state.value.draft)
        assertFalse(fixture.controller.state.value.dictationOpen)
    }

    @Test fun stalePairingExchangeCannotReplaceTheNewOwnersCredentials() = controllerTest {
        val fixture = fixture(StandardTestDispatcher(testScheduler))
        val oldExchange = CompletableDeferred<Unit>()
        fixture.handler = { request -> if (request.url.host == "old.example" && request.url.encodedPath == "/api/mobile/pair/exchange") {
            oldExchange.await()
            jsonResponse("""{"token":"late-old-device-token","device_id":"late_old_owner","expires_at":1791129600}""")
        } else null }
        runCurrent()
        fixture.controller.disconnect()
        fixture.controller.connect()
        fixture.bridge.qrResult!!("claudebot://pair?server=https%3A%2F%2Fold.example&code=old-one-time-code")
        runCurrent()
        assertTrue(fixture.controller.state.value.connecting)
        fixture.controller.disconnect()
        fixture.pairNewOwner()
        runCurrent()
        assertEquals("https://new.example", fixture.controller.state.value.baseUrl)
        assertEquals("owner_new", fixture.bridge.preferences["device_id"])
        oldExchange.complete(Unit)
        runCurrent()
        assertEquals("owner_new", fixture.bridge.preferences["device_id"], "A superseded exchange must not replace device identity")
        assertEquals("new-device-token", fixture.bridge.secrets["device_token"])
        assertEquals("https://new.example", fixture.controller.state.value.baseUrl)
    }

    @Test fun anOldRetrySnapshotCannotSendItsRemainingItemsThroughANewOwnersApi() = controllerTest {
        val bridge = FakePlatformBridge()
        val items = listOf(OutboxItem("old_one", "chat_1", "First old message", "regolo/model", "none"),
            OutboxItem("old_two", "chat_1", "Second old message", "regolo/model", "none"))
        bridge.preferences["owner_old.outbox.v1"] = Json.encodeToString(items)
        bridge.preferences["owner_old.outbox.allowed"] = Json.encodeToString(items.map { it.clientId })
        val fixture = fixture(StandardTestDispatcher(testScheduler), bridge)
        val firstReply = CompletableDeferred<Unit>()
        fixture.handler = { request -> if (request.url.encodedPath == "/api/mobile/messages" && request.method == HttpMethod.Post) {
            if (request.url.host == "old.example") firstReply.await()
            jsonResponse("""{"id":"old_job","session_id":"chat_1","state":"queued"}""")
        } else null }
        runCurrent()
        assertEquals(1, fixture.requests.count { it.host == "old.example" && it.method == HttpMethod.Post })
        fixture.controller.disconnect()
        fixture.pairNewOwner()
        runCurrent()
        firstReply.complete(Unit)
        runCurrent()
        assertFalse(fixture.requests.any { it.host == "new.example" && it.path == "/api/mobile/messages" && it.method == HttpMethod.Post }, "A retry batch must retain its captured identity across every item")
    }

    @Test fun staleFileReadCannotReplaceANewOwnersOpenFile() = controllerTest {
        val fixture = fixture(StandardTestDispatcher(testScheduler))
        val oldRead = CompletableDeferred<Unit>()
        fixture.handler = { request -> if (request.url.encodedPath == "/api/mobile/workspace/file") {
            if (request.url.host == "old.example") {
                oldRead.await()
                jsonResponse("""{"path":"notes.md","content":"Old owner's file","binary":false,"revision":"old_rev"}""")
            } else jsonResponse("""{"path":"notes.md","content":"New owner's file","binary":false,"revision":"new_rev"}""")
        } else null }
        runCurrent()
        fixture.controller.openFile("notes.md")
        runCurrent()
        fixture.controller.disconnect()
        fixture.pairNewOwner()
        runCurrent()
        fixture.controller.openFile("notes.md")
        runCurrent()
        assertEquals("New owner's file", fixture.controller.state.value.fileText)
        oldRead.complete(Unit)
        runCurrent()
        assertEquals("New owner's file", fixture.controller.state.value.fileText, "Late reads must retain their connection identity")
    }

    @Test fun replayedSequenceDoesNotAppendItsBubbleTwiceAndTerminalStopsReconnect() = controllerTest {
        val fixture = fixture(StandardTestDispatcher(testScheduler))
        var streams = 0
        fixture.handler = { request -> when {
            request.url.encodedPath == "/api/mobile/messages" -> jsonResponse("""{"messages":[{"id":"active","session_id":"chat_1","state":"running"}]}""")
            request.url.encodedPath == "/api/mobile/messages/active/events" -> {
                streams++
                if (streams == 1) sseResponse("id: 1\nevent: mobile_state\ndata: {\"state\":\"running\"}\n\nid: 2\nevent: delta\ndata: {\"chunk\":\"Hello\"}\n\n")
                else {
                    assertEquals("2", request.url.parameters["after"])
                    sseResponse("id: 2\nevent: delta\ndata: {\"chunk\":\"Hello\"}\n\nid: 3\nevent: mobile_state\ndata: {\"state\":\"completed\"}\n\n")
                }
            }
            else -> null
        } }
        runCurrent()
        fixture.controller.openChat("chat_1")
        runCurrent()
        advanceTimeBy(1001)
        runCurrent()
        assertEquals("Hello", fixture.controller.state.value.messages.single { it.role == "assistant" }.text)
        assertFalse(fixture.controller.state.value.busy)
        assertNull(fixture.controller.state.value.activeJobId)
        advanceTimeBy(2500)
        runCurrent()
        assertEquals(2, streams, "A terminal stream must not reconnect forever")
    }

    @Test fun completedReplayReconcilesSavedHistoryAndDoesNotReconnect() = controllerTest {
        val fixture = fixture(StandardTestDispatcher(testScheduler))
        var streams = 0
        var saved = false
        fixture.handler = { request -> when {
            request.url.encodedPath == "/api/sessions/chat_1" -> jsonResponse(if (saved)
                """{"messages":[{"id":"server_reply","role":"assistant","content":"Saved answer","parts":[{"type":"text","text":"Saved answer"}]}]}"""
                else """{"messages":[]}""")
            request.url.encodedPath == "/api/mobile/messages" -> jsonResponse("""{"messages":[{"id":"active","session_id":"chat_1","state":"running"}]}""")
            request.url.encodedPath == "/api/mobile/messages/active/events" -> {
                streams++
                saved = true
                sseResponse("id: 1\nevent: mobile_state\ndata: {\"state\":\"running\"}\n\nid: 2\nevent: done\ndata: {\"reply\":\"Saved answer\",\"bubbles\":[\"Saved answer\"]}\n\nid: 3\nevent: mobile_state\ndata: {\"state\":\"completed\"}\n\n")
            }
            else -> null
        } }
        runCurrent()
        fixture.controller.openChat("chat_1")
        runCurrent()
        assertEquals("server_reply", fixture.controller.state.value.messages.single().id)
        assertEquals(listOf("Saved answer"), fixture.controller.state.value.messages.single().bubbles)
        assertFalse(fixture.controller.state.value.busy)
        advanceTimeBy(3500)
        runCurrent()
        assertEquals(1, streams)
    }

    @Test fun autosaveKeepsTheNewestEditWhenAnOlderWriteIsInFlight() = controllerTest {
        val fixture = fixture(StandardTestDispatcher(testScheduler))
        var remote = "Original"
        var revision = "r1"
        val firstWrite = CompletableDeferred<Unit>()
        val written = mutableListOf<String>()
        fixture.handler = { request -> if (request.url.encodedPath == "/api/mobile/workspace/file") {
            if (request.method == HttpMethod.Get) jsonResponse("""{"path":"notes.md","content":"$remote","binary":false,"revision":"$revision"}""")
            else {
                val payload = fixture.requests.last().body!!
                val content = payload["content"]!!.jsonPrimitive.content
                assertEquals(revision, payload["revision"]!!.jsonPrimitive.content)
                written += content
                if (written.size == 1) firstWrite.await()
                remote = content
                revision = "r${written.size + 1}"
                jsonResponse("""{"ok":true,"path":"notes.md","revision":"$revision","size":${remote.length}}""")
            }
        } else null }
        runCurrent()
        fixture.controller.openFile("notes.md")
        runCurrent()
        fixture.controller.fileText("First edit")
        // Start an explicit save so a later debounce cancellation cannot cancel
        // the acknowledgement whose interaction with newer edits is under test.
        fixture.controller.retryFileSave()
        runCurrent()
        assertEquals(listOf("First edit"), written)
        fixture.controller.fileText("Newest edit")
        assertEquals("Newest edit", fixture.bridge.preferences["owner_old.file.notes.md"])
        firstWrite.complete(Unit)
        runCurrent()
        advanceTimeBy(651)
        runCurrent()
        assertEquals("Newest edit", remote)
        assertEquals("Newest edit", fixture.controller.state.value.fileText)
        assertEquals("saved", fixture.controller.state.value.fileSaveState)
        assertNull(fixture.bridge.preferences["owner_old.file.notes.md"])
    }

    @Test fun cancelledAutosaveRetainsRecoveryAndExplicitRetrySavesTheLatestText() = controllerTest {
        val fixture = fixture(StandardTestDispatcher(testScheduler))
        var attempts = 0
        var remote = "Original"
        fixture.handler = { request -> if (request.url.encodedPath == "/api/mobile/workspace/file") {
            if (request.method == HttpMethod.Get) jsonResponse("""{"path":"notes.md","content":"$remote","binary":false,"revision":"r1"}""")
            else {
                attempts++
                if (attempts == 1) throw CancellationException("local fake cancellation before commit")
                remote = fixture.requests.last().body!!.getValue("content").jsonPrimitive.content
                jsonResponse("""{"ok":true,"path":"notes.md","revision":"r2","size":${remote.length}}""")
            }
        } else null }
        runCurrent()
        fixture.controller.openFile("notes.md")
        runCurrent()
        fixture.controller.fileText("First edit")
        advanceTimeBy(651)
        runCurrent()
        assertEquals(1, attempts)
        assertEquals("Original", remote)
        assertEquals("First edit", fixture.bridge.preferences["owner_old.file.notes.md"])
        fixture.controller.fileText("Latest recovery")
        fixture.controller.retryFileSave()
        runCurrent()
        assertEquals("Latest recovery", remote)
        assertEquals("Latest recovery", fixture.controller.state.value.fileText)
        assertEquals("saved", fixture.controller.state.value.fileSaveState)
        assertNull(fixture.bridge.preferences["owner_old.file.notes.md"])
    }

    @Test fun closingDuringAutosaveKeepsRecoveryForTheNextControllerAndRetry() = controllerTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val bridge = FakePlatformBridge()
        val first = fixture(dispatcher, bridge)
        val writeStarted = CompletableDeferred<Unit>()
        val withheldReply = CompletableDeferred<Unit>()
        first.handler = { request -> if (request.url.encodedPath == "/api/mobile/workspace/file") {
            if (request.method == HttpMethod.Get) jsonResponse("""{"path":"notes.md","content":"Original","binary":false,"revision":"r1"}""")
            else {
                writeStarted.complete(Unit)
                withheldReply.await()
                jsonResponse("""{"ok":true,"path":"notes.md","revision":"r2"}""")
            }
        } else null }
        runCurrent()
        first.controller.openFile("notes.md")
        runCurrent()
        first.controller.fileText("Recovery after close")
        advanceTimeBy(651)
        runCurrent()
        assertTrue(writeStarted.isCompleted)
        first.close()
        runCurrent()
        assertEquals("Recovery after close", bridge.preferences["owner_old.file.notes.md"])

        val restored = fixture(dispatcher, bridge)
        var written: String? = null
        restored.handler = { request -> if (request.url.encodedPath == "/api/mobile/workspace/file") {
            if (request.method == HttpMethod.Get) jsonResponse("""{"path":"notes.md","content":"Original","binary":false,"revision":"r1"}""")
            else {
                written = restored.requests.last().body!!.getValue("content").jsonPrimitive.content
                jsonResponse("""{"ok":true,"path":"notes.md","revision":"r2"}""")
            }
        } else null }
        runCurrent()
        restored.controller.openFile("notes.md")
        runCurrent()
        assertEquals("Recovery after close", restored.controller.state.value.fileText)
        restored.controller.retryFileSave()
        runCurrent()
        assertEquals("Recovery after close", written)
        assertEquals("saved", restored.controller.state.value.fileSaveState)
        assertNull(bridge.preferences["owner_old.file.notes.md"])
    }
}
