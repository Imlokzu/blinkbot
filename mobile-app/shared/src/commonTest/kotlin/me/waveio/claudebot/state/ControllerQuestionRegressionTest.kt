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

    /** The exact event sequence of a gateway turn that asked a question (job c4ae90a5, 27.10.2026). */
    private val gatewayQuestionTurn = listOf(
        "mobile_state" to "{\"id\":\"job\",\"state\":\"running\"}",
        "session" to "{\"session_id\":\"chat_1\"}",
        "model" to "{\"fallback\":false,\"model\":\"gpt-6-luna\",\"provider\":\"openai\",\"type\":\"model\"}",
        "agent_status" to "{\"status\":\"connecting\",\"type\":\"agent_status\"}",
        "agent_status" to "{\"status\":\"running\",\"type\":\"agent_status\"}",
        "model" to "{\"fallback\":false,\"model\":\"gpt-6-luna\",\"provider\":\"openai\",\"type\":\"model\"}",
        "note" to "{\"bubbles\":[\"Зроблю! Який саме\"],\"id\":\"chatcmpl_5e52938e-6d7f-4fbf-9438-cd0e6c6eeb90:msg_0d682c709dbe0cf1016ae05ea4d52c819187e29ebfc71ce7f5\",\"type\":\"note\"}",
        "note" to "{\"bubbles\":[\"Зроблю! Який саме документ потрібен?\"],\"id\":\"chatcmpl_5e52938e-6d7f-4fbf-9438-cd0e6c6eeb90:msg_0d682c709dbe0cf1016ae05ea4d52c819187e29ebfc71ce7f5\",\"type\":\"note\"}",
        "tool_start" to "{\"call_id\":\"chatcmpl_5e52938e-6d7f-4fbf-9438-cd0e6c6eeb90:exec-40296bb4-e39e-4190-8267-55edda362d2b\",\"detail\":\"Який документ створити?\",\"input\":{\"options\":[\"Список справ\",\"Нотатку про щось\",\"Інше — напишу\"],\"question\":\"Який документ створити?\"},\"status\":\"active\",\"step\":{\"detail\":\"Який документ створити?\",\"id\":\"chatcmpl_5e52938e-6d7f-4fbf-9438-cd0e6c6eeb90:exec-40296bb4-e39e-4190-8267-55edda362d2b\",\"input\":{\"options\":[\"Список справ\",\"Нотатку про щось\",\"Інше — напишу\"],\"question\":\"Який документ створити?\"},\"label\":\"tools__ask_question\",\"source\":\"openclaw\",\"startedAt\":1793089189763,\"status\":\"active\"},\"tool\":\"tools__ask_question\",\"type\":\"tool_start\"}",
        "tool_done" to "{\"call_id\":\"chatcmpl_5e52938e-6d7f-4fbf-9438-cd0e6c6eeb90:exec-40296bb4-e39e-4190-8267-55edda362d2b\",\"detail\":\"Який документ створити?\",\"input\":{\"options\":[\"Список справ\",\"Нотатку про щось\",\"Інше — напишу\"],\"question\":\"Який документ створити?\"},\"result\":{\"ok\":true,\"ui\":{\"data\":{\"allow_custom\":true,\"id\":\"fb1d0ae568e2\",\"option_ids\":[\"option-0\",\"option-1\",\"option-2\"],\"options\":[\"Список справ\",\"Нотатку про щось\",\"Інше — напишу\"],\"question\":\"Який документ створити?\"},\"id\":\"fb1d0ae568e2\",\"kind\":\"question\",\"version\":1}},\"status\":\"done\",\"step\":{\"detail\":\"Який документ створити?\",\"endedAt\":1793089189806,\"id\":\"chatcmpl_5e52938e-6d7f-4fbf-9438-cd0e6c6eeb90:exec-40296bb4-e39e-4190-8267-55edda362d2b\",\"input\":{\"options\":[\"Список справ\",\"Нотатку про щось\",\"Інше — напишу\"],\"question\":\"Який документ створити?\"},\"label\":\"tools__ask_question\",\"result\":{\"ok\":true,\"ui\":{\"data\":{\"allow_custom\":true,\"id\":\"fb1d0ae568e2\",\"option_ids\":[\"option-0\",\"option-1\",\"option-2\"],\"options\":[\"Список справ\",\"Нотатку про щось\",\"Інше — напишу\"],\"question\":\"Який документ створити?\"},\"id\":\"fb1d0ae568e2\",\"kind\":\"question\",\"version\":1}},\"source\":\"openclaw\",\"startedAt\":1793089189763,\"status\":\"done\"},\"tool\":\"tools__ask_question\",\"type\":\"tool_done\"}",
        "emotion" to "{\"emotion\":\"asking\",\"type\":\"emotion\"}",
        "emotion" to "{\"emotion\":\"asking\"}",
        "done" to "{\"assistant_message_id\":\"fd745eaa9869\",\"bubbles\":[\"Який документ створити? Обери картку або напиши свій варіант.\"],\"emotion\":\"asking\",\"mode\":\"openclaw\",\"model\":\"openai/gpt-6-luna\",\"parts\":[{\"note\":true,\"text\":\"Зроблю! Який саме документ потрібен?\",\"type\":\"text\"},{\"ids\":[\"chatcmpl_5e52938e-6d7f-4fbf-9438-cd0e6c6eeb90:exec-40296bb4-e39e-4190-8267-55edda362d2b\"],\"type\":\"steps\"},{\"text\":\"Який документ створити? Обери картку або напиши свій варіант.\",\"type\":\"text\"}],\"reaction\":null,\"reply\":\"Який документ створити? Обери картку або напиши свій варіант.\",\"session_id\":\"chat_1\",\"steps\":[{\"detail\":\"Який документ створити?\",\"endedAt\":1793089189806,\"id\":\"chatcmpl_5e52938e-6d7f-4fbf-9438-cd0e6c6eeb90:exec-40296bb4-e39e-4190-8267-55edda362d2b\",\"input\":{\"options\":[\"Список справ\",\"Нотатку про щось\",\"Інше — напишу\"],\"question\":\"Який документ створити?\"},\"label\":\"tools__ask_question\",\"result\":{\"ok\":true,\"ui\":{\"data\":{\"allow_custom\":true,\"id\":\"fb1d0ae568e2\",\"option_ids\":[\"option-0\",\"option-1\",\"option-2\"],\"options\":[\"Список справ\",\"Нотатку про щось\",\"Інше — напишу\"],\"question\":\"Який документ створити?\"},\"id\":\"fb1d0ae568e2\",\"kind\":\"question\",\"version\":1}},\"source\":\"openclaw\",\"startedAt\":1793089189763,\"status\":\"done\"}],\"tool_results\":[],\"user_message_id\":\"8c6841124234\"}",
        "mobile_state" to "{\"id\":\"job\",\"state\":\"completed\"}"
    )

    @Test fun realGatewayQuestionTurnKeepsTheCard() = scenario {
        for ((kind, payload) in gatewayQuestionTurn) event(kind, payload)
        val prompt = fixture.controller.state.value.questions.single()
        assertEquals("Який документ створити?", prompt.text)
        assertEquals(listOf("Список справ", "Нотатку про щось", "Інше — напишу"), prompt.options)
    }

    @Test fun realGatewayQuestionTurnAfterSendKeepsTheCard() = scenario {
        fixture.handler = { request -> when {
            request.url.encodedPath == "/api/mobile/messages" && request.method == HttpMethod.Post ->
                jsonResponse("""{"id":"job","session_id":"chat_1","state":"running"}""")
            request.url.encodedPath == "/api/mobile/messages" && request.url.parameters["session_id"] == "chat_1" ->
                jsonResponse("""{"messages":[]}""")
            request.url.encodedPath == "/api/mobile/messages/job/events" ->
                respond(channel, headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
            else -> null
        } }
        fixture.controller.openChat("chat_1"); runCurrent()
        fixture.controller.draft("зробидокумент")
        fixture.controller.send(); runCurrent()
        for ((kind, payload) in gatewayQuestionTurn) event(kind, payload)
        assertEquals("Який документ створити?", fixture.controller.state.value.questions.single().text)
        assertEquals("chat_1", fixture.controller.state.value.sessionId)
    }

    private fun historyWith(vararg messages: String) = """{"messages":[${messages.joinToString(",")}]}"""
    private val askedInHistory = """{"id":"reply","role":"assistant","content":"Обери картку","steps":[{"id":"call:ask","label":"tools__ask_question","status":"done","input":{"question":"Який документ створити?","options":["Список справ","Нотатку"]}}]}"""

    @Test fun reopenedChatRestoresAnUnansweredQuestionFromHistory() = scenario {
        val previous = fixture.handler
        fixture.handler = { request -> when (request.url.encodedPath) {
            "/api/sessions/chat_2" -> jsonResponse(historyWith("""{"id":"human","role":"user","content":"зробидокумент"}""", askedInHistory))
            else -> previous.invoke(this, request)
        } }
        fixture.controller.openChat("chat_2"); runCurrent()
        val prompt = fixture.controller.state.value.questions.single { it.sessionId == "chat_2" }
        assertEquals("Який документ створити?", prompt.text)
        assertEquals(listOf("Список справ", "Нотатку"), prompt.options)
        // Dismissed once, it stays dismissed when the chat is reopened.
        fixture.controller.dismissQuestion(prompt.id)
        fixture.controller.openChat("chat_1"); runCurrent()
        fixture.controller.openChat("chat_2"); runCurrent()
        assertTrue(fixture.controller.state.value.questions.none { it.sessionId == "chat_2" })
    }

    @Test fun answeredOrSupersededHistoryQuestionsStayClosed() = scenario {
        val previous = fixture.handler
        fixture.handler = { request -> when (request.url.encodedPath) {
            "/api/sessions/chat_2" -> jsonResponse(historyWith(askedInHistory, """{"id":"answer","role":"user","content":"Нотатку"}"""))
            "/api/sessions/chat_3" -> jsonResponse(historyWith(askedInHistory.replace("\"done\"", "\"failed\"")))
            else -> previous.invoke(this, request)
        } }
        fixture.controller.openChat("chat_2"); runCurrent()
        fixture.controller.openChat("chat_3"); runCurrent()
        assertTrue(fixture.controller.state.value.questions.none { it.sessionId != "chat_1" })
    }
}
