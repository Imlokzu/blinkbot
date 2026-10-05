@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package me.waveio.claudebot.state

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Real open SSE channels expose each update before final history is available. */
class ControllerStreamContractRegressionTest {
    private val fixtures = mutableListOf<ControllerTestFixture>()
    private val channels = mutableListOf<ByteChannel>()

    private fun TestScope.fixture() = ControllerTestFixture(StandardTestDispatcher(testScheduler)).also { fixtures += it }

    private fun controllerTest(block: suspend TestScope.() -> Unit): TestResult = runTest(timeout = 10.seconds) {
        try { block() } finally {
            fixtures.forEach { it.close() }; channels.forEach { it.close() }
            fixtures.clear(); channels.clear(); runCurrent()
        }
    }

    private fun TestScope.streamingChat(): Pair<ControllerTestFixture, ByteChannel> {
        val fixture = fixture()
        val channel = ByteChannel(autoFlush = true).also { channels += it }
        fixture.handler = { request -> when {
            request.url.encodedPath == "/api/sessions/chat_1" -> jsonResponse(humanHistory)
            request.url.encodedPath == "/api/mobile/messages" && request.url.parameters["session_id"] == "chat_1" -> jsonResponse(runningJob)
            request.url.encodedPath == "/api/mobile/messages/live_job/events" ->
                respond(channel, headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
            else -> null
        } }
        runCurrent(); fixture.controller.openChat("chat_1"); runCurrent()
        return fixture to channel
    }

    private suspend fun TestScope.event(channel: ByteChannel, id: Long, kind: String, data: JsonObject) {
        channel.writeStringUtf8("id: $id\nevent: $kind\ndata: $data\n\n"); runCurrent()
    }

    private suspend fun TestScope.event(channel: ByteChannel, id: Long, kind: String, data: String) =
        event(channel, id, kind, Json.parseToJsonElement(data).jsonObject)

    @Test fun aLongMarkdownTableDeltaIsImmediatelyVisibleWithoutInventedSplits() = controllerTest {
        val (fixture, channel) = streamingChat()
        val table = "| Name | Value |\n| --- | --- |\n" + (1..80).joinToString("\n") { "| Item $it | ${"Value ".repeat(6)}|" }
        event(channel, 1, "delta", buildJsonObject { put("chunk", table) })
        val answer = fixture.controller.state.value.messages.single { it.role == "assistant" }
        assertEquals(table, answer.text)
        assertEquals(listOf(table), answer.parts.map { it.text }, "Transport chunk length must not create presentation bubbles")
        assertTrue(answer.live)
        assertTrue(fixture.controller.state.value.busy)
        assertEquals(0L, testScheduler.currentTime, "Live text does not need a typing timer or final response")
    }

    @Test fun snapshotsPreserveExplicitTableBubblesAndTheSurroundingNotesAndTools() = controllerTest {
        val (fixture, channel) = streamingChat()
        event(channel, 1, "note", """{"id":"preamble","bubbles":["Checking", "Reading"]}""")
        event(channel, 2, "tool_start", """{"step":{"id":"lookup","label":"Lookup","status":"running"}}""")
        val table = "| Metric | Result |\n| --- | --- |\n| Success | 100% |"
        event(channel, 3, "reply_snapshot", buildJsonObject {
            put("text", "Result\n\n$table")
            put("bubbles", JsonArray(listOf(JsonPrimitive("Result"), JsonPrimitive(table))))
        })
        val answer = fixture.controller.state.value.messages.single { it.role == "assistant" }
        assertEquals(listOf("Checking", "Reading", "", "Result", table), answer.parts.map { it.text })
        assertEquals(listOf("text", "text", "steps", "text", "text"), answer.parts.map { it.type })
        assertTrue(answer.parts.take(2).all { it.note && it.noteId == "preamble" })
        assertEquals(listOf("lookup"), answer.parts[2].stepIds)
        assertEquals(listOf("Result", table), answer.bubbles)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun trimmedSnapshotLabelsDoNotMergeLiveBubblesOrDropTheNextDeltaSpace() = controllerTest {
        val (fixture, channel) = streamingChat()
        event(channel, 1, "reply_snapshot", """{"text":"First bubble.  \n\nSecond par ","bubbles":["First bubble.","Second par"]}""")
        var answer = fixture.controller.state.value.messages.single { it.role == "assistant" }
        assertTrue(answer.live)
        assertEquals(listOf("First bubble.  ", "Second par "), answer.parts.map { it.text })
        assertEquals(answer.text, answer.parts.joinToString("\n\n") { it.text })
        event(channel, 2, "delta", """{"chunk":"t."}""")
        answer = fixture.controller.state.value.messages.single { it.role == "assistant" }
        assertEquals(listOf("First bubble.  ", "Second par t."), answer.parts.map { it.text })
        assertTrue(answer.live)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun inconsistentSnapshotBubblesCannotRewriteTheAuthoritativeText() = controllerTest {
        val (fixture, channel) = streamingChat()
        event(channel, 1, "reply_snapshot", """{"text":"Correct table text","bubbles":["Incorrect split","Other text"]}""")
        val answer = fixture.controller.state.value.messages.single { it.role == "assistant" }
        assertEquals("Correct table text", answer.text)
        assertEquals(listOf("Correct table text"), answer.parts.map { it.text })
    }

    @Test fun botReactionLandsOnThisJobsHumanMessageAndDoneAdoptsStableIds() = controllerTest {
        val (fixture, channel) = streamingChat()
        val history = CompletableDeferred<Unit>()
        fixture.handler = { request -> if (request.url.encodedPath == "/api/sessions/chat_1") {
            history.await(); jsonResponse("""{"messages":[]}""")
        } else null }
        event(channel, 1, "reaction", """{"emoji":"💛"}""")
        val humans = fixture.controller.state.value.messages
        assertNull(humans.first { it.id == "earlier_user" }.reaction)
        assertEquals("💛", humans.single { it.id == "u-client_1" }.reaction)
        assertTrue(humans.none { it.role == "assistant" }, "A reaction is not an empty assistant bubble")
        event(channel, 2, "done", """{"reply":"Reply","bubbles":["Reply"],"parts":[{"type":"text","text":"Reply"}],"reaction":"💛","user_message_id":"saved_user","assistant_message_id":"saved_answer"}""")
        val rows = fixture.controller.state.value.messages
        assertEquals("💛", rows.single { it.id == "saved_user" }.reaction)
        assertEquals("Reply", rows.single { it.id == "saved_answer" }.text)
        assertTrue(rows.none { it.id == "u-client_1" || it.id == "a-live_job" })
        assertFalse(rows.single { it.id == "saved_answer" }.live)
    }

    @Test fun terminalSseDoesNotWaitForSlowFinalHistoryToClearBusy() = controllerTest {
        val (fixture, channel) = streamingChat()
        val history = CompletableDeferred<Unit>()
        fixture.handler = { request -> if (request.url.encodedPath == "/api/sessions/chat_1") {
            history.await(); jsonResponse("""{"messages":[]}""")
        } else null }
        event(channel, 1, "delta", """{"chunk":"Live answer"}""")
        event(channel, 2, "done", """{"reply":"Final answer","bubbles":["Final answer"]}""")
        assertTrue(fixture.controller.state.value.busy)
        event(channel, 3, "mobile_state", """{"state":"completed"}""")
        assertFalse(fixture.controller.state.value.busy, "HTTP history work must not stall the SSE collector")
        assertNull(fixture.controller.state.value.activeJobId)
        assertEquals("Final answer", fixture.controller.state.value.messages.single { it.role == "assistant" }.text)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun delayedDoneHistoryCannotReplaceAReopenedChatWithTheSameId() = controllerTest {
        val (fixture, channel) = streamingChat()
        val oldHistory = CompletableDeferred<Unit>()
        var histories = 0
        fixture.handler = { request -> if (request.url.encodedPath == "/api/sessions/chat_1") {
            if (++histories == 1) { oldHistory.await(); jsonResponse("""{"messages":[{"id":"old_answer","role":"assistant","content":"Obsolete final"}]}""") }
            else jsonResponse("""{"messages":[{"id":"new_answer","role":"assistant","content":"Current reopened history"}]}""")
        } else null }
        event(channel, 1, "done", """{"reply":"Final answer"}""")
        assertEquals(1, histories)
        fixture.controller.openChat("chat_2"); runCurrent()
        fixture.controller.openChat("chat_1"); runCurrent()
        oldHistory.complete(Unit); runCurrent()
        assertEquals("Current reopened history", fixture.controller.state.value.messages.single().text)
        assertEquals("new_answer", fixture.controller.state.value.messages.single().id)
    }

    @Test fun imageModelFailureShowsActionableFeedbackAndKeepsTheStreamedContent() = controllerTest {
        val (fixture, channel) = streamingChat()
        event(channel, 1, "delta", """{"chunk":"Partial content"}""")
        event(channel, 2, "error", """{"error":"mobile_image_model_unavailable"}""")
        event(channel, 3, "mobile_state", """{"state":"failed","error":"turn_failed"}""")
        val state = fixture.controller.state.value
        assertEquals("error.imageModel", state.error)
        assertTrue(state.modelPickerOpen)
        assertEquals("Partial content", state.messages.single { it.role == "assistant" }.text)
        assertTrue(state.queuePaused)
        assertFalse(state.busy)
    }

    @Test fun genericServerStreamFailureRemainsAServiceFailure() = controllerTest {
        val (fixture, channel) = streamingChat()
        event(channel, 1, "error", """{"error":"mobile_turn_failed"}""")
        assertEquals("error.service", fixture.controller.state.value.error)
        assertFalse(fixture.controller.state.value.modelPickerOpen)
    }

    private companion object {
        const val runningJob = """{"messages":[{"id":"live_job","session_id":"chat_1","client_id":"client_1","message":"Human message","state":"running"}]}"""
        const val humanHistory = """{"messages":[{"id":"earlier_user","role":"user","content":"Earlier message"},{"id":"u-client_1","role":"user","content":"Human message"}]}"""
    }
}
