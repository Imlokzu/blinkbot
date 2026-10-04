package me.waveio.claudebot.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
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

class BotApiTransportContractTest {
    private val clients = mutableListOf<HttpClient>()
    private val apis = mutableListOf<BotApi>()

    private fun api(engine: MockEngine): BotApi {
        val client = HttpClient(engine)
        clients += client
        return BotApi("https://phone.example", "synthetic-device-token", client).also { apis += it }
    }

    @AfterTest fun closeClients() {
        apis.forEach { it.close() }
        clients.forEach { it.close() }
    }

    @Test fun historyPreservesBotReactionAndUserReactionBubbleIndices() = runTest {
        val api = api(MockEngine {
            respond("""{"messages":[
                {"id":"user1","role":"user","content":"Question","reaction":"👍"},
                {"id":"assistant1","role":"assistant","content":"Answer","ts":1791100000,
                 "parts":[{"type":"text","text":"Checking","note":true},{"type":"steps","ids":["tool1"]},{"type":"text","text":"Answer"}],
                 "reactions":{"1":"❤️"}},
                {"role":"assistant","content":"Older message"}]}""")
        })
        val messages = api.sessionHistory("chat1")
        assertEquals("👍", messages[0].reaction)
        assertTrue(messages[0].reactions.isEmpty())
        assertNull(messages[1].reaction)
        assertEquals(mapOf("1" to "❤️"), messages[1].reactions)
        assertEquals(listOf("Checking", "Answer"), messages[1].bubbles)
        assertEquals(1791100000L, messages[1].timestamp)
        assertNull(messages[2].reaction)
        assertTrue(messages[2].reactions.isEmpty())
    }

    @Test fun reactionsUseStoredMessageIdAndExplicitNullForRemoval() = runTest {
        var writes = 0
        val api = api(MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/api/sessions/chat1/reactions", request.url.encodedPath)
            assertEquals("Bearer synthetic-device-token", request.headers[HttpHeaders.Authorization])
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals("assistant1", body["message_id"]!!.jsonPrimitive.content)
            assertEquals("1", body["bubble"]!!.jsonPrimitive.content)
            writes++
            if (writes == 1) {
                assertEquals("❤️", body["emoji"]!!.jsonPrimitive.content)
                respond("""{"ok":true,"reactions":{"1":"❤️"}}""")
            } else {
                assertTrue("emoji" in body)
                assertEquals(JsonNull, body["emoji"])
                respond("""{"ok":true,"reactions":{}}""")
            }
        })
        assertEquals(mapOf("1" to "❤️"), api.setReaction("chat1", "assistant1", 1, "❤️"))
        assertEquals(emptyMap(), api.setReaction("chat1", "assistant1", 1, null))
        assertEquals(2, writes)
    }

    @Test fun renameAndDeleteUseOwnerAuthenticatedServerOperations() = runTest {
        val verbs = mutableListOf<HttpMethod>()
        val api = api(MockEngine { request ->
            assertEquals("Bearer synthetic-device-token", request.headers[HttpHeaders.Authorization])
            verbs += request.method
            if (request.method == HttpMethod.Post) {
                assertEquals("/api/sessions/chat1/rename", request.url.encodedPath)
                val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                assertEquals("My conversation", body["title"]!!.jsonPrimitive.content)
                respond("""{"ok":true,"title":"My conversation"}""")
            } else {
                assertEquals("/api/sessions/chat1", request.url.encodedPath)
                respond("""{"ok":false}""")
            }
        })
        assertTrue(api.renameSession("chat1", "My conversation"))
        assertFalse(api.deleteSession("chat1"))
        assertEquals(listOf(HttpMethod.Post, HttpMethod.Delete), verbs)
    }

    @Test fun missingVisionMetadataStaysUnknownAndConfiguredImageModelIsDecoded() = runTest {
        val api = api(MockEngine {
            respond("""{"models":[
                {"id":"text/default","provider":"text"},
                {"id":"image/vision","provider":"image","vision":true},
                {"id":"image/text","provider":"image","vision":false}],
                "default":"text/default","image_model":"image/vision"}""")
        })
        val catalog = api.models()
        assertEquals("text/default", catalog.defaultModel)
        assertEquals("image/vision", catalog.imageModel)
        assertNull(catalog.models[0].vision)
        assertEquals(true, catalog.models[1].vision)
        assertEquals(false, catalog.models[2].vision)
    }

    @Test fun imageUploadManifestRoundTripsToQueuedSendWithNumericSize() = runTest {
        val bytes = byteArrayOf(-1, -40, -1) + "synthetic-image-bytes".encodeToByteArray()
        var uploaded = false
        val api = api(MockEngine { request ->
            assertEquals("Bearer synthetic-device-token", request.headers[HttpHeaders.Authorization])
            assertEquals(HttpMethod.Post, request.method)
            when (request.url.encodedPath) {
                "/api/chat/upload" -> {
                    val multipart = request.body as OutgoingContent.WriteChannelContent
                    assertTrue(multipart.contentType!!.match(ContentType.MultiPart.FormData))
                    val wire = coroutineScope {
                        val channel = ByteChannel()
                        val writer = launch {
                            try { multipart.writeTo(channel) } finally { channel.close() }
                        }
                        channel.readRemaining().readByteArray().also { writer.join() }
                    }
                    assertTrue(wire.asList().windowed(bytes.size).any { it == bytes.toList() })
                    val envelope = wire.decodeToString()
                    assertTrue(envelope.contains("name=file"))
                    assertTrue(envelope.contains("filename=\"phone.jpg\""))
                    assertTrue(envelope.contains("Content-Type: image/jpeg"))
                    uploaded = true
                    respond("""{"url":"/uploads/local-fixture.jpg","name":"phone.jpg","type":"image/jpeg","size":${bytes.size},"truncated":false}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
                "/api/mobile/messages" -> {
                    assertTrue(uploaded)
                    val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    assertEquals("", body["message"]!!.jsonPrimitive.content)
                    assertEquals("", body["model"]!!.jsonPrimitive.content)
                    val manifest = body["attachments"]!!.jsonArray.single().jsonObject
                    assertEquals("/uploads/local-fixture.jpg", manifest["url"]!!.jsonPrimitive.content)
                    assertEquals("image/jpeg", manifest["type"]!!.jsonPrimitive.content)
                    assertEquals(bytes.size.toString(), manifest["size"]!!.jsonPrimitive.content)
                    assertFalse(manifest["size"]!!.jsonPrimitive.isString)
                    assertFalse("path" in manifest || "mimeType" in manifest)
                    respond("""{"id":"job1","session_id":"chat1","state":"queued"}""")
                }
                else -> error("Unexpected request")
            }
        })
        val attachment = api.upload("phone.jpg", bytes, "image/jpeg")
        assertEquals(bytes.size.toLong(), attachment.size)
        assertEquals("queued", api.submitMessage("client1", "chat1", "", listOf(attachment), model = "").state)
    }

    @Test fun imageEligibilityErrorKeepsItsMachineCodeAndFailedJobMetadata() = runTest {
        val api = api(MockEngine { request ->
            if (request.url.encodedPath == "/api/mobile/messages" && request.method == HttpMethod.Get) {
                respond("""{"messages":[{"id":"job1","session_id":"chat1","state":"failed","error":"mobile_image_model_unavailable"}]}""")
            } else {
                respond("""{"detail":{"code":"mobile_image_model_unavailable"}}""", HttpStatusCode.UnprocessableEntity)
            }
        })
        val job = api.listMessages("chat1").single()
        assertEquals("mobile_image_model_unavailable", job.error)
        val failure = assertFailsWith<ApiFailure> { api.submitMessage("client1", "chat1", "Describe.") }
        assertEquals("mobile_image_model_unavailable", failure.code)
        assertEquals(422, failure.status)
    }
}
