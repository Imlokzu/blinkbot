package me.waveio.claudebot.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BotApiTest {
    private val clients = mutableListOf<HttpClient>()
    private val apis = mutableListOf<BotApi>()

    private fun api(engine: MockEngine, base: String = "https://bot.example/api/"): BotApi {
        val client = HttpClient(engine)
        clients += client
        return BotApi(base, "test-device-token", client).also { apis += it }
    }

    @AfterTest fun closeClients() {
        apis.forEach { it.close() }
        clients.forEach { it.close() }
    }

    @Test fun sessionsAndHistoryPreserveActualStoredContent() = runTest {
        val api = api(MockEngine { request ->
            val body = when (request.url.encodedPath) {
                "/api/sessions" -> """{"sessions":[{"id":"chat_1","title":"Plan","updated":1791020400,"pinned":true,"new_field":9},{"id":"old"}],"kind":"chat"}"""
                "/api/sessions/chat_1" -> """{
                    "id":"chat_1","messages":[
                    {"id":"msg1","role":"assistant","content":"Final answer","ts":1791020400,
                     "parts":[{"type":"text","text":"Checking","note":true},{"type":"steps","ids":["tool1"]},{"type":"text","text":"Final answer"}],
                     "steps":[{"id":"tool1","label":"Read file","detail":"notes.md","status":"interrupted","input":{"path":"notes.md"}}],
                     "attachments":[{"url":"/uploads/doc.pdf","name":"doc.pdf","type":"application/pdf","size":2048,"truncated":false}],"model":"regolo/actual"},
                    {"role":"user","content":"Older message"}]}"""
                else -> error("Unexpected request")
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val sessions = api.listSessions()
        assertEquals(1791020400L, sessions.first().updatedAt)
        assertNull(sessions.last().updatedAt)
        assertNull(sessions.last().title)
        val messages = api.sessionHistory("chat_1")
        assertEquals(listOf("Checking", "Final answer"), messages.first().bubbles)
        assertEquals("steps", messages.first().parts[1].type)
        assertEquals("interrupted", messages.first().steps.single().status)
        assertEquals("/uploads/doc.pdf", messages.first().attachments.single().path)
        assertEquals("regolo/actual", messages.first().model)
        assertNull(messages.last().id)
        assertNull(messages.last().model)
        assertEquals(listOf("Older message"), messages.last().bubbles)
    }

    @Test fun catalogDoesNotInventBrandAvailabilityOrModelEfforts() = runTest {
        val api = api(MockEngine {
            respond("""{"models":[{"id":"regolo/model","label":"Model","provider":"regolo"}],"selected":"regolo/model","thinking":"low","thinking_levels":["off","low"],"available":true}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val catalog = api.models()
        assertEquals(listOf("off", "low"), catalog.efforts)
        assertEquals("low", catalog.thinking)
        assertNull(catalog.models.single().brand)
        assertNull(catalog.models.single().available)
        assertTrue(catalog.models.single().efforts.isEmpty())
    }

    @Test fun configuredOriginAndBearerSurviveInjectedClientDefaults() = runTest {
        var received: HttpRequestData? = null
        val client = HttpClient(MockEngine { request ->
            received = request
            respond("""{"entries":[{"path":"notes","name":"notes","type":"dir","size":0}]}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) {
            defaultRequest {
                url("https://default.example/wrong/")
                headers.append(HttpHeaders.Authorization, "Bearer unrelated-default")
            }
        }
        clients += client
        val api = BotApi("https://bot.example:8443/api", "device-token", client).also { apis += it }
        assertTrue(api.listWorkspace("notes/hello world & more.md", "chat_1").single().isDirectory)
        val request = received!!
        assertEquals("bot.example", request.url.host)
        assertEquals(8443, request.url.port)
        assertEquals("https", request.url.protocol.name)
        assertEquals("/api/workspace/list", request.url.encodedPath)
        assertEquals("notes/hello world & more.md", request.url.parameters["path"])
        assertEquals("chat_1", request.url.parameters["session_id"])
        assertEquals(listOf("Bearer device-token"), request.headers.getAll(HttpHeaders.Authorization))
    }

    @Test fun redirectIsReturnedAsFailureWithoutMakingAnotherRequest() = runTest {
        var requests = 0
        val api = api(MockEngine {
            requests++
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://new.example/api/sessions"))
        })
        val failure = assertFailsWith<ApiFailure> { api.listSessions() }
        assertEquals(302, failure.status)
        assertEquals("redirect_rejected", failure.code)
        assertEquals(1, requests)
    }

    @Test fun serverDetailsAreConvertedToCodesAndCancellationPropagates() = runTest {
        val api = api(MockEngine {
            respond("""{"detail":"Internal traceback: database file is unavailable"}""", HttpStatusCode.InternalServerError)
        })
        val failure = assertFailsWith<ApiFailure> { api.listSessions() }
        assertEquals(500, failure.status)
        assertEquals("server_error", failure.code)
        assertFalse(failure.toString().contains("database"))
        val coded = api(MockEngine {
            respond("""{"detail":{"code":"pair_expired","message":"Private server details"}}""", HttpStatusCode.BadRequest)
        })
        assertEquals("pair_expired", assertFailsWith<ApiFailure> { coded.capabilities() }.code)
        val cancelled = api(MockEngine { throw CancellationException("cancelled") })
        assertFailsWith<CancellationException> { cancelled.listSessions() }
    }

    @Test fun requiredPayloadFieldsFailInsteadOfBecomingInventedData() = runTest {
        val api = api(MockEngine { respond("""{"sessions":[{"title":"Missing id"}]}""") })
        val failure = assertFailsWith<ApiFailure> { api.listSessions() }
        assertEquals(200, failure.status)
        assertEquals("invalid_response", failure.code)
    }

    @Test fun durableSubmissionCarriesTheCallerIdentityAndOriginalAttachments() = runTest {
        var payload: JsonObject? = null
        val api = api(MockEngine { request ->
            assertEquals("/api/mobile/messages", request.url.encodedPath)
            assertEquals(HttpMethod.Post, request.method)
            payload = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            respond("""{"id":"job_1","session_id":"chat_1","state":"scheduled","extra":true}""")
        })
        val job = api.submitMessage("persisted-client-id", "chat_1", "Hello",
            attachments = listOf(Attachment("/uploads/paper.pdf", "paper.pdf", "application/pdf", 12)),
            model = "regolo/model", reasoningEffort = "low", delivery = "queue", scheduledAt = "2026-10-04T10:00:00Z")
        assertEquals("scheduled", job.state)
        val body = payload!!
        assertEquals("persisted-client-id", body["client_id"]!!.jsonPrimitive.content)
        assertEquals("chat_1", body["session_id"]!!.jsonPrimitive.content)
        assertEquals("2026-10-04T10:00:00Z", body["scheduled_at"]!!.jsonPrimitive.content)
        assertEquals("/uploads/paper.pdf", body["attachments"]!!.jsonArray.single().jsonObject["url"]!!.jsonPrimitive.content)
    }

    @Test fun pairingExchangeDoesNotSendBearerAndRedactsCredentials() = runTest {
        val api = api(MockEngine { request ->
            assertEquals("/api/mobile/pair/exchange", request.url.encodedPath)
            assertNull(request.headers[HttpHeaders.Authorization])
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals("phone", body["device_name"]!!.jsonPrimitive.content)
            assertEquals("android", body["platform"]!!.jsonPrimitive.content)
            respond("""{"token":"paired-secret","device_id":"device_1","expires_at":1791020400}""")
        })
        val result = api.exchangePairing("one-time-code", "phone", "android")
        assertEquals("paired-secret", result.token)
        assertEquals("1791020400", result.expiresAt)
        assertFalse(result.toString().contains(result.token))
    }

    @Test fun ssePreservesEventNamesCursorAndMultilineJson() = runTest {
        val frames = "\uFEFF: connected\r\nid: 7\r\nevent: tool_start\r\ndata: {\r\ndata: \"id\": \"tool_1\", \"label\": \"Read\"}\r\n\r\n" +
            ": heartbeat\n\n" +
            "id: 8\nevent: text\ndata: {\"text\":\"Hello 🌊\"}\n\n" +
            "id: 9\nevent: done\ndata: {\"model\":\"regolo/actual\"}\n\n" +
            "id: 10\nevent: text\ndata: {\"text\":\"Incomplete\"}"
        val api = api(MockEngine { request ->
            assertEquals("/api/mobile/messages/job_1/events", request.url.encodedPath)
            assertEquals("6", request.url.parameters["after"])
            assertEquals("text/event-stream", request.headers[HttpHeaders.Accept])
            respond(frames, headers = headersOf(HttpHeaders.ContentType, "text/event-stream; charset=utf-8"))
        })
        val events = api.jobEvents("job_1", after = 6).toList()
        assertEquals(listOf(7L, 8L, 9L), events.map { it.id })
        assertEquals(listOf("tool_start", "text", "done"), events.map { it.event })
        assertEquals("Hello 🌊", events[1].data["text"]!!.jsonPrimitive.content)
    }

    @Test fun sseFailureRemainsAFailureAndNeverFallsBackToLegacyChat() = runTest {
        var requests = 0
        val api = api(MockEngine {
            requests++
            respond("""{"detail":"mobile_unavailable"}""", HttpStatusCode.ServiceUnavailable)
        })
        assertEquals("mobile_unavailable", assertFailsWith<ApiFailure> { api.jobEvents("job_1").toList() }.code)
        assertEquals(1, requests)
    }

    @Test fun malformedSseDataIsNotEmittedAsAnEmptySuccess() = runTest {
        val api = api(MockEngine {
            respond("id: 1\nevent: text\ndata: not json\n\n", headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
        })
        assertEquals("invalid_event", assertFailsWith<ApiFailure> { api.jobEvents("job_1").toList() }.code)
    }

    @Test fun profileWritesUseTheRealSetupContractAndRequireACompleteSnapshot() = runTest {
        var requests = 0
        val api = api(MockEngine { request ->
            requests++
            assertEquals("/api/setup", request.url.encodedPath)
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertFalse("configured" in body)
            assertEquals("calm", body["persona"]!!.jsonPrimitive.content)
            respond("""{"profile":{"name":"Bot","language":"en","persona":"calm","persona_custom":"","greeting":"","use_emoji":false}}""")
        })
        assertEquals("incomplete_profile", assertFailsWith<ApiFailure> { api.updateProfile(BotProfile(name = "New")) }.code)
        assertEquals(0, requests)
        val result = api.updateProfile(BotProfile("Bot", "en", "calm", "", "", useEmoji = false))
        assertEquals(false, result.useEmoji)
        assertEquals(1, requests)
    }

    @Test fun newConversationSubmissionUsesServerDefaultsWithoutNullStrings() = runTest {
        val api = api(MockEngine { request ->
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals("", body["session_id"]!!.jsonPrimitive.content)
            assertFalse("model" in body)
            assertFalse("reasoning_effort" in body)
            assertFalse("scheduled_at" in body)
            respond("""{"id":"job_new","session_id":"server_allocated","state":"queued"}""")
        })
        assertEquals("server_allocated", api.submitMessage("new-client-id", null, "Hello").sessionId)
    }

    @Test fun listStopAndResumeUseDurableEndpointsAndRetainOptionalJobMetadata() = runTest {
        val visited = mutableListOf<String>()
        val api = api(MockEngine { request ->
            visited += request.url.encodedPath
            when (request.url.encodedPath) {
                "/api/mobile/messages" -> {
                    assertEquals("chat_1", request.url.parameters["session_id"])
                    respond("""{"messages":[{"id":"job_1","session_id":"chat_1","state":"scheduled","message":"Later","scheduled_at":1791108000.5,"model":"regolo/model","reasoning_effort":"low","conversation_paused":true}]}""")
                }
                "/api/mobile/messages/job_1/stop" -> {
                    assertEquals(HttpMethod.Post, request.method)
                    respond("""{"id":"job_1","session_id":"chat_1","state":"stopped"}""")
                }
                "/api/mobile/sessions/chat_1/resume" -> respond("""{"ok":true,"session_id":"chat_1","paused":false}""")
                else -> error("Unexpected request")
            }
        })
        val job = api.listMessages("chat_1").single()
        assertEquals("Later", job.message)
        assertEquals(1791108000.5, job.scheduledAt)
        assertEquals("low", job.reasoningEffort)
        assertEquals(true, job.conversationPaused)
        val stopped = api.stopMessage("job_1")
        assertEquals("stopped", stopped.state)
        assertNull(stopped.message)
        assertEquals(2, visited.size)
        assertEquals("false", api.resumeSession("chat_1")["paused"]!!.jsonPrimitive.content)
        assertEquals(3, visited.size)
    }

    @Test fun storedUploadsDownloadBytesAndPreviewUsingTheSameOrigin() = runTest {
        val expected = byteArrayOf(1, 2, 3, 4)
        val api = api(MockEngine { request ->
            assertEquals("bot.example", request.url.host)
            assertEquals("Bearer test-device-token", request.headers[HttpHeaders.Authorization])
            when (request.url.encodedPath) {
                "/uploads/document.pdf" -> respond(expected, headers = headersOf(HttpHeaders.ContentType, "application/pdf"))
                "/api/chat/attachment-preview" -> {
                    assertEquals("/uploads/document.pdf", request.url.parameters["url"])
                    respond("""{"text":"Actual extract","truncated":true,"type":"application/pdf","size":4}""")
                }
                else -> error("Unexpected request")
            }
        })
        assertTrue(expected.contentEquals(api.downloadAttachment("/uploads/document.pdf")))
        val preview = api.attachmentPreview("/uploads/document.pdf")
        assertEquals("Actual extract", preview.text)
        assertEquals(true, preview.truncated)
        assertEquals("application/pdf", preview.mimeType)
    }

    @Test fun ownedNativeClientsCloseWhileInjectedClientsRemainReusable() = runTest {
        val owned = HttpClient(MockEngine { respond("{}") }).ownedByBotApi()
        clients += owned
        val ownedApi = BotApi("https://bot.example", "", owned).also { apis += it }
        ownedApi.close()
        owned.coroutineContext[Job]!!.join()
        assertTrue(owned.coroutineContext[Job]!!.isCompleted)

        val injected = HttpClient(MockEngine { respond("""{"sessions":[]}""") })
        clients += injected
        val first = BotApi("https://bot.example", "", injected).also { apis += it }
        first.close()
        val second = BotApi("https://bot.example", "", injected).also { apis += it }
        assertTrue(second.listSessions().isEmpty())
    }

    @Test fun existingSessionMutationsUseOnlyTheBackendDeleteAndPinOperations() = runTest {
        val visited = mutableListOf<Pair<HttpMethod, String>>()
        val api = api(MockEngine { request ->
            visited += request.method to request.url.encodedPath
            if (request.method == HttpMethod.Post) {
                val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                assertEquals("true", body["pinned"]!!.jsonPrimitive.content)
            }
            respond("""{"ok":true}""")
        })
        assertTrue(api.pinSession("chat_1", true))
        assertTrue(api.deleteSession("chat_1"))
        assertEquals(listOf(HttpMethod.Post to "/api/sessions/chat_1/pin", HttpMethod.Delete to "/api/sessions/chat_1"), visited)
    }

    @Test fun uploadAndBothAsrOperationsEncodeRealMultipartFields() = runTest {
        val api = api(MockEngine { request ->
            val body = coroutineScope {
                val channel = ByteChannel()
                val writer = launch {
                    (request.body as OutgoingContent.WriteChannelContent).writeTo(channel)
                    channel.close()
                }
                val text = channel.readRemaining().readByteArray().decodeToString()
                writer.join()
                text
            }
            assertTrue(body.contains("actual-bytes"))
            assertTrue(body.contains("filename=\"record.webm\""))
            assertTrue(body.contains("Content-Type: audio/webm"))
            when (request.url.encodedPath) {
                "/api/chat/upload" -> {
                    assertTrue(body.contains("name=file"))
                    respond("""{"url":"/uploads/record.webm","name":"record.webm","type":"audio/webm","size":12}""")
                }
                "/api/asr" -> {
                    assertTrue(body.contains("name=audio"))
                    respond("""{"text":"Actual final"}""")
                }
                "/api/asr/partial" -> {
                    assertTrue(body.contains("name=audio"))
                    respond("""{"text":"Actual partial","partial":true}""")
                }
                else -> error("Unexpected request")
            }
        })
        val bytes = "actual-bytes".encodeToByteArray()
        assertEquals("/uploads/record.webm", api.upload("record.webm", bytes, "audio/webm").path)
        assertNull(api.transcribe("record.webm", bytes, "audio/webm").partial)
        assertEquals(true, api.transcribe("record.webm", bytes, "audio/webm", partial = true).partial)
    }

    @Test fun workspaceWritesAndServerPreferencesRetainActualBackendFields() = runTest {
        val api = api(MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/workspace/file" -> if (request.method == HttpMethod.Get) {
                    assertEquals("session/report.md", request.url.parameters["path"])
                    assertEquals("chat_1", request.url.parameters["session_id"])
                    respond("""{"path":"sessions/chat_1/report.md","content":"","binary":false,"too_large":true,"size":999999}""")
                } else {
                    val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    assertEquals("chat_1", body["session_id"]!!.jsonPrimitive.content)
                    assertEquals("Saved text", body["content"]!!.jsonPrimitive.content)
                    respond("""{"ok":true,"path":"sessions/chat_1/report.md","size":10}""")
                }
                "/api/openclaw/settings" -> if (request.method == HttpMethod.Get) {
                    respond("""{"available":true,"fields":[{"path":"agents.defaults.timeoutSeconds","section":"style","group":"thinking","kind":"int","value":15,"options":[],"unset":false}]}""")
                } else {
                    val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    assertEquals("agents.defaults.timeoutSeconds", body["path"]!!.jsonPrimitive.content)
                    assertEquals("null", body["value"].toString())
                    respond("""{"ok":true}""")
                }
                else -> error("Unexpected request")
            }
        })
        val file = api.readWorkspace("session/report.md", "chat_1")
        assertTrue(file.tooLarge)
        assertNull(file.mimeType)
        assertEquals("sessions/chat_1/report.md", api.writeWorkspace("session/report.md", "Saved text", "chat_1").path)
        val preference = api.preferences().fields.single()
        assertEquals("15", preference.value!!.jsonPrimitive.content)
        assertEquals(false, preference.unset)
        api.updatePreference(preference.path, null)
    }
}
