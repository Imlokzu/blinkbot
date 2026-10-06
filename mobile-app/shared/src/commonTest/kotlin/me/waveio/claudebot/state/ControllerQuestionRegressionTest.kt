@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package me.waveio.claudebot.state

import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import me.waveio.claudebot.data.BotEvent
import me.waveio.claudebot.data.MobileJob
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/** A real open SSE channel exposes questions before the provider finishes. */
class ControllerQuestionRegressionTest {
    private lateinit var fixture: ControllerTestFixture
    private lateinit var channel: ByteChannel
    private var sequence = 0

    private fun scenario(block: suspend TestScope.() -> Unit): TestResult = runTest(timeout = 10.seconds) {
        fixture = ControllerTestFixture(StandardTestDispatcher(testScheduler))
        channel = ByteChannel(autoFlush = true)
        configure()
        try {
            runCurrent(); fixture.controller.openChat("chat_1"); runCurrent()
            block()
        } finally { fixture.close(); channel.close(); runCurrent() }
    }

    private fun configure() {
        fixture.handler = { request -> when {
            request.url.encodedPath == "/api/mobile/messages" && request.method == HttpMethod.Post ->
                jsonResponse("""{"id":"answer_job","session_id":"chat_1","state":"queued"}""")
            request.url.encodedPath == "/api/mobile/messages" && request.url.parameters["session_id"] == "chat_1" ->
                jsonResponse("""{"messages":[{"id":"job","session_id":"chat_1","state":"running"}]}""")
            request.url.encodedPath == "/api/mobile/messages/job/events" ->
                respond(channel, headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
            else -> null
        } }
    }

    private suspend fun TestScope.event(kind: String, payload: String) {
        channel.writeStringUtf8("id: ${++sequence}\nevent: $kind\ndata: $payload\n\n")
        runCurrent()
    }
    private suspend fun TestScope.question(call: String = "run:ask", custom: Boolean = true) {
        event("tool_start", """{"step":{"id":"$call","label":"tools__ask_question","status":"active","input":{"question":"Which format?","options":["PDF","Markdown"],"allow_custom":$custom}}}""")
    }

    @Test fun opensBeforeTheToolOrProviderFinishes() = scenario {
        question()
        val prompt = fixture.controller.state.value.questions.single()
        assertEquals("Which format?", prompt.text)
        assertEquals(listOf("PDF", "Markdown"), prompt.options)
        assertEquals("chat_1", prompt.sessionId)
        assertTrue(fixture.controller.state.value.busy)
        assertEquals(0L, testScheduler.currentTime)
        assertFalse(channel.isClosedForRead)
    }

    @Test fun choiceQueuesOneAnswerWithoutConsumingTheComposerDraft() = scenario {
        fixture.controller.draft("Keep my unsent draft")
        question(custom = false)
        val id = fixture.controller.state.value.questions.single().id
        fixture.controller.answerQuestion(id, "PDF")
        fixture.controller.answerQuestion(id, "PDF")
        runCurrent()
        val sent = fixture.requests.single { it.method == HttpMethod.Post && it.path == "/api/mobile/messages" }.body!!
        assertEquals("PDF", sent["message"]?.jsonPrimitive?.content)
        assertEquals("chat_1", sent["session_id"]?.jsonPrimitive?.content)
        assertEquals("queue", sent["delivery"]?.jsonPrimitive?.content)
        assertEquals(JsonArray(emptyList()), sent["attachments"])
        assertEquals("Keep my unsent draft", fixture.controller.state.value.draft)
        assertTrue(fixture.controller.state.value.questions.isEmpty())
    }

    @Test fun customAnswerIsTrimmedAndForbiddenAnswersDoNotSend() = scenario {
        question(custom = false)
        val id = fixture.controller.state.value.questions.single().id
        fixture.controller.answerQuestion(id, "  ")
        fixture.controller.answerQuestion(id, "Other")
        runCurrent()
        assertEquals(1, fixture.controller.state.value.questions.size)
        assertTrue(fixture.requests.none { it.method == HttpMethod.Post })
        fixture.controller.dismissQuestion(id)
        question("custom")
        fixture.controller.answerQuestion(fixture.controller.state.value.questions.single().id, "  Plain text please  ")
        runCurrent()
        assertEquals("Plain text please", fixture.requests.single { it.method == HttpMethod.Post }.body!!["message"]?.jsonPrimitive?.content)
    }

    @Test fun repeatedStartAndCompletionDoNotReopenDismissedQuestions() = scenario {
        question()
        fixture.controller.dismissQuestion(fixture.controller.state.value.questions.single().id)
        question()
        event("tool_done", """{"step":{"id":"run:ask","label":"tools__ask_question","status":"done","input":{"question":"Which format?"}}}""")
        assertTrue(fixture.controller.state.value.questions.isEmpty())
        assertTrue(fixture.requests.none { it.method == HttpMethod.Post })
    }

    @Test fun multipleQuestionsRemainOrdered() = scenario {
        question("first"); question("second")
        val before = fixture.controller.state.value.questions
        assertEquals(2, before.size)
        fixture.controller.dismissQuestion(before.first().id)
        assertEquals(listOf(before.last()), fixture.controller.state.value.questions)
    }

    @Test fun lateQuestionCannotBeAnsweredFromAnotherChat() = scenario {
        fixture.controller.openChat("chat_2"); runCurrent()
        question()
        val prompt = fixture.controller.state.value.questions.single()
        assertEquals("chat_1", prompt.sessionId)
        assertEquals("chat_2", fixture.controller.state.value.sessionId)
        fixture.controller.answerQuestion(prompt.id, "PDF"); runCurrent()
        assertTrue(fixture.requests.none { it.method == HttpMethod.Post })
        fixture.controller.openChat("chat_1"); runCurrent()
        assertEquals(prompt, fixture.controller.state.value.questions.single())
        fixture.controller.answerQuestion(prompt.id, "PDF"); runCurrent()
        assertEquals(1, fixture.requests.count { it.method == HttpMethod.Post })
    }

    @Test fun accountSwitchClearsQuestionsAndRejectsOldCallbacks() = scenario {
        question()
        val id = fixture.controller.state.value.questions.single().id
        fixture.pairNewOwner(); runCurrent()
        assertTrue(fixture.controller.state.value.questions.isEmpty())
        fixture.controller.answerQuestion(id, "PDF"); runCurrent()
        assertTrue(fixture.requests.none { it.path == "/api/mobile/messages" && it.method == HttpMethod.Post })
    }

    @Test fun finishedTurnKeepsQuestionButFailedToolRemovesIt() = scenario {
        question()
        event("done", """{"reply":"","bubbles":[],"steps":[]}""")
        assertEquals(1, fixture.controller.state.value.questions.size)
        event("tool_error", """{"call_id":"run:ask","tool":"tools__ask_question","is_error":true}""")
        assertTrue(fixture.controller.state.value.questions.isEmpty())
    }

    @Test fun stoppedTurnRemovesUnansweredQuestions() = scenario {
        question()
        event("mobile_state", """{"state":"stopped"}""")
        assertTrue(fixture.controller.state.value.questions.isEmpty())
    }

    @Test fun storageFailureRetainsPromptForRetryWithoutSending() = scenario {
        question()
        val id = fixture.controller.state.value.questions.single().id
        fixture.bridge.beforePreferenceWrite = { key, _ -> if (key.endsWith("outbox.v1")) error("Storage unavailable") }
        fixture.controller.answerQuestion(id, "PDF"); runCurrent()
        assertEquals(id, fixture.controller.state.value.questions.single().id)
        assertEquals("error.storage", fixture.controller.state.value.error)
        assertTrue(fixture.requests.none { it.method == HttpMethod.Post })
        fixture.bridge.beforePreferenceWrite = null
        fixture.controller.answerQuestion(id, "PDF"); runCurrent()
        assertEquals(1, fixture.requests.count { it.method == HttpMethod.Post })
    }

    @Test fun answeredAndDismissedReceiptsSurviveControllerRecreation() = scenario {
        question("answered")
        fixture.controller.answerQuestion(fixture.controller.state.value.questions.single().id, "PDF")
        runCurrent()
        question("dismissed")
        fixture.controller.dismissQuestion(fixture.controller.state.value.questions.single().id)
        val bridge = fixture.bridge
        fixture.close(); channel.close(); runCurrent()
        fixture = ControllerTestFixture(StandardTestDispatcher(testScheduler), bridge)
        channel = ByteChannel(autoFlush = true)
        configure()
        runCurrent(); fixture.controller.openChat("chat_1"); runCurrent()
        question("answered"); question("dismissed")
        assertTrue(fixture.controller.state.value.questions.isEmpty())
        assertTrue(fixture.requests.none { it.method == HttpMethod.Post })
    }

    @Test fun rejectedAnswerDoesNotFillAnEmptyEditDraftOrSubmitAFork() = scenario {
        val previous = fixture.handler
        fixture.handler = { request -> when {
            request.url.encodedPath == "/api/sessions/chat_1" ->
                jsonResponse("""{"messages":[{"id":"human","role":"user","content":"Original"}]}""")
            request.method == HttpMethod.Post && request.url.encodedPath == "/api/mobile/messages" ->
                jsonResponse("""{"detail":"invalid_message"}""", HttpStatusCode.BadRequest)
            else -> previous.invoke(this, request)
        } }
        fixture.controller.openChat("chat_1"); runCurrent()
        fixture.controller.draft("Before editing")
        fixture.controller.editMessage("human")
        fixture.controller.draft("")
        assertEquals("human", fixture.controller.state.value.editingMessageId)
        question()
        fixture.controller.answerQuestion(fixture.controller.state.value.questions.single().id, "PDF"); runCurrent()
        assertEquals("", fixture.controller.state.value.draft)
        assertEquals("human", fixture.controller.state.value.editingMessageId)
        assertTrue(fixture.requests.none { it.path.endsWith("/fork") })
        fixture.controller.cancelEdit()
        assertEquals("Before editing", fixture.controller.state.value.draft)
    }

    @Test fun decliningOfflineAnswerPreservesAnAttachmentsOnlyDraft() = scenario {
        val previous = fixture.handler
        fixture.handler = { request -> when {
            request.url.encodedPath == "/api/chat/upload" ->
                jsonResponse("""{"url":"/uploads/draft.txt","name":"draft.txt","type":"text/plain","size":3}""")
            request.method == HttpMethod.Post && request.url.encodedPath == "/api/mobile/messages" ->
                jsonResponse("""{"detail":"unavailable"}""", HttpStatusCode.ServiceUnavailable)
            else -> previous.invoke(this, request)
        } }
        fixture.controller.pickFile("file")
        fixture.bridge.pickedResult?.invoke(me.waveio.claudebot.platform.PickedFile("draft.txt", "text/plain", byteArrayOf(1, 2, 3)))
        runCurrent()
        val attachments = fixture.controller.state.value.attachments
        assertEquals(1, attachments.size)
        question()
        fixture.controller.answerQuestion(fixture.controller.state.value.questions.single().id, "PDF"); runCurrent()
        assertTrue(fixture.controller.state.value.offlineQuestion)
        fixture.controller.offlineDelivery(false)
        assertEquals(attachments, fixture.controller.state.value.attachments)
        assertEquals("", fixture.controller.state.value.draft)
        assertEquals(1, fixture.requests.count { it.method == HttpMethod.Post && it.path == "/api/mobile/messages" })
    }

    @Test fun pendingAnswerSurvivesAReceiptWriteFailureAndRestartWithoutDuplication() = scenario {
        question()
        val questionId = fixture.controller.state.value.questions.single().id
        fixture.bridge.beforePreferenceWrite = { key, _ -> if (key.endsWith("questions.handled.v1")) error("Receipt unavailable") }
        fixture.controller.answerQuestion(questionId, "PDF"); runCurrent()
        assertTrue(fixture.controller.state.value.questions.isEmpty())
        assertTrue(fixture.requests.none { it.method == HttpMethod.Post })
        val queued = Json.decodeFromString<List<OutboxItem>>(fixture.bridge.preferences.getValue("owner_old.outbox.v1")).single()
        assertEquals(questionId, queued.questionId)
        val bridge = fixture.bridge
        bridge.beforePreferenceWrite = null
        fixture.close(); channel.close(); runCurrent()
        fixture = ControllerTestFixture(StandardTestDispatcher(testScheduler), bridge)
        channel = ByteChannel(autoFlush = true); configure()
        runCurrent(); fixture.controller.openChat("chat_1"); runCurrent()
        question()
        assertTrue(fixture.controller.state.value.questions.isEmpty())
        fixture.controller.retryPending("local:${queued.clientId}"); runCurrent()
        val sent = fixture.requests.single { it.method == HttpMethod.Post }.body!!
        assertEquals(queued.clientId, sent["client_id"]?.jsonPrimitive?.content)
        assertTrue(questionId in Json.decodeFromString<Set<String>>(bridge.preferences.getValue("owner_old.questions.handled.v1")))
    }

    @Test fun nativeAndNormalizedEventsDecodeWithoutGuessingFromDisplayText() {
        val job = MobileJob("j", "s", "running")
        fun decode(body: String) = botQuestion(job, BotEvent(null, "tool_start", Json.parseToJsonElement(body).jsonObject))
        val raw = decode("""{"tool":"tools--ask-question","call_id":"r:c","input":{"question":"Pick?","options":["A",null,4,"",{},"A","B"],"allow_custom":false}}""")!!
        assertEquals(listOf("A", "B"), raw.options)
        assertFalse(raw.allowCustom)
        assertNotNull(decode("""{"tool":"ask_question","call_id":"local","input":"{\"question\":\"Text?\"}"}"""))
        for (body in listOf(
            """{"tool":"search","call_id":"c","input":{"question":"Not a prompt"}}""",
            """{"tool":"ask_question","call_id":"c","input":"broken JSON"}""",
            """{"tool":"ask_question","call_id":"c","input":{"question":{}}}""",
            """{"tool":"ask_question","call_id":"c","detail":"No structured arguments"}""",
            """{"tool":"ask_question","input":{"question":"No call identity"}}""",
            """{"step":{"id":"c","label":"ask_question","status":"failed","input":{"question":"Failed"}}}"""
        )) assertNull(decode(body), body)
    }
}
