@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package me.waveio.claudebot.state

import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.waveio.claudebot.platform.PickedFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Keep HTTP acknowledgements and live frames independent so polling cannot hide a race. */
class ControllerLiveStateRegressionTest {
    private val fixtures = mutableListOf<ControllerTestFixture>()
    private val channels = mutableListOf<ByteChannel>()

    private fun TestScope.fixture(): ControllerTestFixture =
        ControllerTestFixture(StandardTestDispatcher(testScheduler)).also { fixtures += it }

    private fun channel(): ByteChannel = ByteChannel(autoFlush = true).also { channels += it }

    private fun controllerTest(block: suspend TestScope.() -> Unit): TestResult = runTest(timeout = 10.seconds) {
        try { block() } finally {
            fixtures.forEach { it.close() }
            channels.forEach { it.close() }
            fixtures.clear()
            channels.clear()
            runCurrent()
        }
    }

    private fun MockRequestHandleScope.liveResponse(channel: ByteChannel) =
        respond(channel, headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))

    private suspend fun TestScope.event(channel: ByteChannel, sequence: Long, kind: String, data: String) {
        channel.writeStringUtf8("id: $sequence\nevent: $kind\ndata: $data\n\n")
        runCurrent()
    }

    private fun TestScope.streamingChat(): Pair<ControllerTestFixture, ByteChannel> {
        val fixture = fixture()
        val channel = channel()
        fixture.handler = { request -> when {
            request.url.encodedPath == "/api/mobile/messages" && request.url.parameters["session_id"] == "chat_1" ->
                jsonResponse(jobs("running"))
            request.url.encodedPath == "/api/mobile/messages/live_job/events" -> liveResponse(channel)
            else -> null
        } }
        runCurrent()
        fixture.controller.openChat("chat_1")
        runCurrent()
        assertTrue(fixture.requests.any { it.path == "/api/mobile/messages/live_job/events" })
        return fixture to channel
    }

    @Test fun staleQueuedListingCannotClearNewerSseBusyOrDisableStop() = controllerTest {
        staleListingCannotClearActiveStream(jobs("queued"))
    }

    @Test fun staleEmptyListingCannotClearNewerSseBusyOrDisableStop() = controllerTest {
        staleListingCannotClearActiveStream("""{"messages":[]}""")
    }

    private suspend fun TestScope.staleListingCannotClearActiveStream(staleResponse: String) {
        val fixture = fixture()
        val channel = channel()
        val releaseListing = CompletableDeferred<Unit>()
        var listings = 0
        fixture.handler = { request -> when {
            request.url.encodedPath == "/api/mobile/messages" && request.url.parameters["session_id"] == "chat_1" -> {
                if (++listings == 1) jsonResponse(jobs("queued")) else {
                    releaseListing.await()
                    jsonResponse(staleResponse)
                }
            }
            request.url.encodedPath == "/api/mobile/messages/live_job/events" -> liveResponse(channel)
            request.url.encodedPath == "/api/mobile/messages/live_job/stop" ->
                jsonResponse("""{"id":"live_job","session_id":"chat_1","state":"stopping"}""")
            else -> null
        } }
        runCurrent()
        fixture.controller.openChat("chat_1")
        runCurrent()
        assertFalse(fixture.controller.state.value.busy)
        fixture.controller.refresh()
        runCurrent()
        assertEquals(2, listings, "The stale listing must already be in flight before the running event")
        event(channel, 1, "mobile_state", """{"state":"running"}""")
        event(channel, 2, "delta", """{"chunk":"Live answer"}""")
        assertTrue(fixture.controller.state.value.busy)
        assertEquals("live_job", fixture.controller.state.value.activeJobId)

        releaseListing.complete(Unit)
        runCurrent()
        assertTrue(fixture.controller.state.value.busy, "Older HTTP state must not finish a newer live turn")
        assertEquals("live_job", fixture.controller.state.value.activeJobId)
        assertEquals("Live answer", fixture.controller.state.value.messages.single { it.role == "assistant" }.text)
        assertFalse(fixture.controller.state.value.pending.any { it.id == "live_job" }, "A queued snapshot must not requeue a job already observed running")
        fixture.controller.stop()
        runCurrent()
        assertEquals(1, fixture.requests.count { it.path == "/api/mobile/messages/live_job/stop" && it.method == HttpMethod.Post })
        assertTrue(fixture.controller.state.value.queuePaused)
        assertEquals(0L, testScheduler.currentTime, "No reconnect or periodic poll may repair the stale snapshot for this test")
    }

    @Test fun jobCompletingBetweenOpenChatHistoryAndListingRefreshesItsFinalHistory() = controllerTest {
        val fixture = fixture()
        val releaseListing = CompletableDeferred<Unit>()
        var histories = 0
        var finalSaved = false
        fixture.handler = { request -> when {
            request.url.encodedPath == "/api/sessions/chat_1" -> {
                histories++
                jsonResponse(if (finalSaved) finalHistory else """{"messages":[]}""")
            }
            request.url.encodedPath == "/api/mobile/messages" && request.url.parameters["session_id"] == "chat_1" -> {
                releaseListing.await()
                jsonResponse(jobs("completed"))
            }
            else -> null
        } }
        runCurrent()
        fixture.controller.openChat("chat_1")
        runCurrent()
        assertEquals(1, histories)
        assertTrue(fixture.requests.any { it.path == "/api/mobile/messages" })
        assertTrue(fixture.controller.state.value.messages.isEmpty())

        // The stored answer appears after history was read, before the listing returns.
        finalSaved = true
        releaseListing.complete(Unit)
        runCurrent()
        assertTrue(histories >= 2, "Discovering a terminal job must reconcile history missed by openChat")
        assertEquals("saved_reply", fixture.controller.state.value.messages.single().id)
        assertEquals("Final answer.", fixture.controller.state.value.messages.single().text)
        assertFalse(fixture.controller.state.value.busy)
        assertNull(fixture.controller.state.value.activeJobId)
        assertFalse(fixture.controller.state.value.loading)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun delayedTerminalHistoryCannotReplaceANewerLiveAnswer() = controllerTest {
        val fixture = fixture()
        val channel = channel()
        val releaseHistory = CompletableDeferred<Unit>()
        var histories = 0
        fixture.handler = { request -> when {
            request.url.encodedPath == "/api/sessions/chat_1" -> if (++histories == 1) {
                jsonResponse("""{"messages":[]}""")
            } else {
                releaseHistory.await()
                jsonResponse(finalHistory)
            }
            request.url.encodedPath == "/api/mobile/messages" && request.url.parameters["session_id"] == "chat_1" ->
                jsonResponse("""{"messages":[{"id":"previous_job","session_id":"chat_1","state":"completed"},{"id":"live_job","session_id":"chat_1","state":"running"}]}""")
            request.url.encodedPath == "/api/mobile/messages/live_job/events" -> liveResponse(channel)
            else -> null
        } }
        runCurrent()
        fixture.controller.openChat("chat_1")
        runCurrent()
        assertEquals(2, histories, "Terminal reconciliation must have started before the new stream")
        event(channel, 1, "mobile_state", """{"state":"running"}""")
        event(channel, 2, "reply_snapshot", """{"id":"answer","text":"New live answer"}""")
        assertEquals("New live answer", fixture.controller.state.value.messages.single().text)
        releaseHistory.complete(Unit)
        runCurrent()
        assertEquals("New live answer", fixture.controller.state.value.messages.single().text)
        assertTrue(fixture.controller.state.value.messages.single().live)
        assertTrue(fixture.controller.state.value.busy)
        assertEquals("live_job", fixture.controller.state.value.activeJobId)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun cumulativeReplySnapshotsReplaceAnswerAtItsOriginalPositionAndPreserveNotesAndTools() = controllerTest {
        val (fixture, channel) = streamingChat()
        event(channel, 1, "note", """{"id":"preamble","bubbles":["Checking the source"]}""")
        event(channel, 2, "tool_start", """{"step":{"id":"lookup","label":"Search","detail":"Finding source","status":"running"}}""")
        event(channel, 3, "reply_snapshot", """{"id":"answer","text":"First draft","bubbles":["First draft"]}""")
        assertEquals("First draft", fixture.controller.state.value.messages.single().text)
        event(channel, 4, "note", """{"id":"context","bubbles":["Verifying the result"]}""")
        event(channel, 5, "tool_start", """{"step":{"id":"verify","label":"Verify","status":"running"}}""")
        event(channel, 6, "reply_snapshot", """{"id":"answer","text":"Final answer.\n\nVerified detail.","bubbles":["Final answer.","Verified detail."]}""")
        event(channel, 7, "tool_done", """{"step":{"id":"lookup","label":"Search","detail":"Source found","status":"done"}}""")
        val row = fixture.controller.state.value.messages.single()
        assertEquals("Final answer.\n\nVerified detail.", row.text)
        assertEquals("Final answer.\n\nVerified detail.", visibleAnswer(row), "A cumulative snapshot replaces the earlier answer rather than appending it")
        assertEquals(listOf("Checking the source", "Verifying the result"), row.parts.filter { it.noteId != null }.map { it.text })
        assertEquals(listOf("lookup", "verify"), row.steps.map { it.id })
        assertEquals("done", row.steps.first().status)
        assertEquals("Source found", row.steps.first().detail)
        assertEquals(listOf("note:preamble", "tools:lookup", "answer", "note:context", "tools:verify"), row.parts.map { part ->
            when {
                part.type == "steps" -> "tools:${part.stepIds.joinToString(",")}"
                part.noteId != null -> "note:${part.noteId}"
                else -> "answer"
            }
        })
        assertTrue(row.live)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun snapshotWithoutBubblesAndFinalDoneNeverEchoTheEarlierAnswer() = controllerTest {
        val (fixture, channel) = streamingChat()
        val releaseHistory = CompletableDeferred<Unit>()
        fixture.handler = { request -> when {
            request.url.encodedPath == "/api/sessions/chat_1" -> {
                releaseHistory.await()
                jsonResponse(finalHistory)
            }
            else -> null
        } }
        event(channel, 1, "reply_snapshot", """{"id":"answer","text":"Draft answer.","bubbles":["Draft answer."]}""")
        event(channel, 2, "reply_snapshot", """{"id":"answer","text":"Final answer."}""")
        assertEquals("Final answer.", fixture.controller.state.value.messages.single().text)
        assertEquals("Final answer.", visibleAnswer(fixture.controller.state.value.messages.single()))
        event(channel, 3, "done", """{"reply":"Final answer.","bubbles":["Final answer."]}""")
        assertEquals(2, fixture.requests.count { it.path == "/api/sessions/chat_1" }, "Assert the live row before persisted history can hide an echo")
        assertEquals("Final answer.", visibleAnswer(fixture.controller.state.value.messages.single()))
        assertFalse(fixture.controller.state.value.messages.single().live)
        releaseHistory.complete(Unit)
        runCurrent()
        event(channel, 4, "mobile_state", """{"state":"completed"}""")
        assertEquals("saved_reply", fixture.controller.state.value.messages.single().id)
        assertEquals("Final answer.", visibleAnswer(fixture.controller.state.value.messages.single()))
        assertFalse(fixture.controller.state.value.busy)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun unknownMetadataEventsCannotCreateAnEmptyAssistantOrChangeItsAnswer() = controllerTest {
        val (fixture, channel) = streamingChat()
        event(channel, 1, "provider_usage", """{"tokens":42}""")
        event(channel, 2, "queue_metadata", """{"position":1}""")
        assertTrue(fixture.controller.state.value.messages.isEmpty(), "Transport metadata is not an assistant utterance")
        event(channel, 3, "reply_snapshot", """{"id":"answer","text":"Visible answer"}""")
        val before = fixture.controller.state.value.messages.single()
        event(channel, 4, "provider_diagnostics", """{"message":"Internal provider detail"}""")
        assertEquals(before, fixture.controller.state.value.messages.single())
        assertNull(fixture.controller.state.value.error)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun cumulativeAsrPartialsStayVisibleWithoutSendingAndCancelPreservesTheSavedDraft() = controllerTest {
        val fixture = fixture()
        var partials = 0
        fixture.handler = { request -> if (request.url.encodedPath == "/api/asr/partial") {
            partials++
            jsonResponse(if (partials == 1) """{"text":"Initial speech","partial":true}"""
                else """{"text":"Initial speech revised","partial":true}""")
        } else null }
        runCurrent()
        fixture.controller.openChat("chat_1")
        runCurrent()
        fixture.controller.draft("Existing draft")
        fixture.controller.startDictation()
        val partial = assertNotNull(fixture.bridge.recordingPartial)
        partial(audio)
        runCurrent()
        assertEquals("Initial speech", fixture.controller.state.value.transcript)
        partial(audio)
        runCurrent()
        assertEquals("Initial speech revised", fixture.controller.state.value.transcript, "The composer preview receives the latest cumulative recognition")
        assertTrue(fixture.controller.state.value.dictationOpen)
        assertTrue(fixture.controller.state.value.recording)
        assertEquals("Existing draft", fixture.controller.state.value.draft, "Preview text must not be persisted into the user's draft before acceptance")
        fixture.controller.cancelDictation()
        runCurrent()
        assertFalse(fixture.controller.state.value.dictationOpen)
        assertFalse(fixture.controller.state.value.recording)
        assertFalse(fixture.controller.state.value.transcribing)
        assertEquals("Existing draft", fixture.controller.state.value.draft)
        assertEquals("Existing draft", fixture.bridge.preferences["owner_old.draft.chat_1"])
        assertNoAutomaticSend(fixture)
        assertNull(fixture.controller.state.value.error)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun stoppingDictationInsertsOnlyTheFinalRecognitionIntoTheComposerWithoutSending() = controllerTest {
        val fixture = fixture()
        val releaseFinal = CompletableDeferred<Unit>()
        fixture.handler = { request -> when (request.url.encodedPath) {
            "/api/asr/partial" -> jsonResponse("""{"text":"Interim recognition","partial":true}""")
            "/api/asr" -> {
                releaseFinal.await()
                jsonResponse("""{"text":"Final recognition"}""")
            }
            else -> null
        } }
        runCurrent()
        fixture.controller.draft("Existing draft")
        fixture.controller.startDictation()
        assertNotNull(fixture.bridge.recordingPartial)(audio)
        runCurrent()
        fixture.controller.stopDictation()
        assertNotNull(fixture.bridge.recordingResult)(audio)
        runCurrent()
        assertTrue(fixture.controller.state.value.transcribing)
        assertEquals("Existing draft", fixture.controller.state.value.draft)
        releaseFinal.complete(Unit)
        runCurrent()
        assertEquals("Existing draft Final recognition", fixture.controller.state.value.draft)
        assertEquals("Existing draft Final recognition", fixture.bridge.preferences["owner_old.draft.new"])
        assertEquals("", fixture.controller.state.value.transcript)
        assertFalse(fixture.controller.state.value.dictationOpen)
        assertFalse(fixture.controller.state.value.transcribing)
        assertNoAutomaticSend(fixture)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun finalAsrSuccessAfterCancelCannotChangeTheDraft() = controllerTest {
        val fixture = fixture()
        val releaseFinal = CompletableDeferred<Unit>()
        fixture.handler = { request -> if (request.url.encodedPath == "/api/asr") {
            releaseFinal.await()
            jsonResponse("""{"text":"Cancelled recognition"}""")
        } else null }
        runCurrent()
        fixture.controller.draft("Existing draft")
        fixture.controller.startDictation()
        fixture.controller.stopDictation()
        assertNotNull(fixture.bridge.recordingResult)(audio)
        runCurrent()
        assertTrue(fixture.controller.state.value.transcribing)
        fixture.controller.cancelDictation()
        fixture.controller.draft("Draft edited after cancel")
        releaseFinal.complete(Unit)
        runCurrent()
        assertEquals("Draft edited after cancel", fixture.controller.state.value.draft)
        assertEquals("Draft edited after cancel", fixture.bridge.preferences["owner_old.draft.new"])
        assertFalse(fixture.controller.state.value.dictationOpen)
        assertFalse(fixture.controller.state.value.transcribing)
        assertNull(fixture.controller.state.value.error)
        assertNoAutomaticSend(fixture)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun staleAsrFailureAfterCancelCannotShowAnErrorOrFinishTheNewRecording() = controllerTest {
        val fixture = fixture()
        val releaseOld = CompletableDeferred<Unit>()
        val releaseCurrent = CompletableDeferred<Unit>()
        var recognitions = 0
        fixture.handler = { request -> if (request.url.encodedPath == "/api/asr") {
            if (++recognitions == 1) {
                releaseOld.await()
                jsonResponse("""{"detail":{"code":"asr_unavailable"}}""", HttpStatusCode.ServiceUnavailable)
            } else {
                releaseCurrent.await()
                jsonResponse("""{"text":"Current recognition"}""")
            }
        } else null }
        runCurrent()
        fixture.controller.draft("Existing draft")
        fixture.controller.startDictation()
        fixture.controller.stopDictation()
        assertNotNull(fixture.bridge.recordingResult)(audio)
        runCurrent()
        assertEquals(1, recognitions)
        fixture.controller.cancelDictation()
        fixture.controller.startDictation()
        fixture.controller.stopDictation()
        assertNotNull(fixture.bridge.recordingResult)(audio)
        runCurrent()
        assertEquals(2, recognitions)
        assertTrue(fixture.controller.state.value.transcribing)
        releaseOld.complete(Unit)
        runCurrent()
        assertNull(fixture.controller.state.value.error, "An obsolete recognition failure belongs to the canceled recording")
        assertTrue(fixture.controller.state.value.transcribing, "The old catch block must not dismiss the current ASR progress")
        assertTrue(fixture.controller.state.value.dictationOpen)
        assertEquals("Existing draft", fixture.controller.state.value.draft)
        releaseCurrent.complete(Unit)
        runCurrent()
        assertEquals("Existing draft Current recognition", fixture.controller.state.value.draft)
        assertNull(fixture.controller.state.value.error)
        assertFalse(fixture.controller.state.value.transcribing)
        assertNoAutomaticSend(fixture)
        assertEquals(0L, testScheduler.currentTime)
    }

    private fun visibleAnswer(row: MessageRow): String {
        val parts = row.parts.ifEmpty { row.bubbles.ifEmpty { listOf(row.text) }.map { ContentPart("text", it) } }
        return parts.filter { it.type == "text" && it.noteId == null }.map { it.text }.filter { it.isNotBlank() }.joinToString("\n\n")
    }

    private fun assertNoAutomaticSend(fixture: ControllerTestFixture) {
        assertFalse(fixture.requests.any { it.path == "/api/mobile/messages" && it.method == HttpMethod.Post }, "ASR may fill the composer but must never submit a chat turn")
    }

    private fun jobs(state: String) = """{"messages":[{"id":"live_job","session_id":"chat_1","state":"$state"}]}"""

    private companion object {
        val audio = PickedFile("recording.wav", "audio/wav", byteArrayOf(1, 2, 3))
        const val finalHistory = """{"messages":[{"id":"saved_reply","role":"assistant","content":"Final answer.","parts":[{"type":"text","text":"Final answer."}]}]}"""
    }
}
