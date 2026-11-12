package me.waveio.blinkchat

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Base64

class ProviderClientTest {
    /** Build synthetic user-info fixtures without storing credential-looking URIs in source. */
    private fun credentialedUrl(hostAndPath: String): String = URLBuilder("https://$hostAndPath").apply {
        user = "user"
        password = "pass"
    }.buildString()
    private val clients = mutableListOf<ProviderClient>()
    private val apiKey = "test-provider-key"
    private val settings = ProviderSettings(
        baseUrl = "https://api.example.com/v1",
        chatModel = "qwen4-max-preview",
        imageModel = "qwen-image-3.0-pro",
        imageEndpoint = "https://api.example.com/api/v1/image",
        imageProtocol = "qwen",
    )
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val sseHeaders = headersOf(HttpHeaders.ContentType, "text/event-stream")
    private val png = byteArrayOf(
        0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
        0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte(),
    ) + "png-payload".toByteArray()
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) +
        "jpeg-payload".toByteArray()
    private val webp = "RIFF".toByteArray() + ByteArray(4) + "WEBP".toByteArray() + "VP8 ".toByteArray()
    private val gif = "GIF89a".toByteArray() + ByteArray(8)

    private fun provider(engine: MockEngine): ProviderClient =
        ProviderClient(HttpClient(engine)).also { clients += it }

    @After fun closeClients() {
        clients.forEach { it.close() }
    }

    private fun bodyOf(request: HttpRequestData) =
        Json.parseToJsonElement((request.body as TextContent).text).jsonObject

    private suspend fun expectFailure(block: suspend () -> Unit): ProviderFailure =
        try {
            block()
            throw AssertionError("Expected ProviderFailure")
        } catch (failure: ProviderFailure) {
            failure
        }

    @Test fun chatSendsOpenAiContentArrayWithImagesAndToolRoundTrip() = runTest {
        var received: HttpRequestData? = null
        val client = provider(MockEngine { request ->
            received = request
            respond("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n", headers = sseHeaders)
        })
        val tool = buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject { put("name", "get_weather") })
        }
        val messages = listOf(
            ApiMessage("system", "You are Blink."),
            ApiMessage(
                "user", "What is on this photo?",
                images = listOf("data:image/png;base64,AAAA", "https://cdn.example.com/a.png"),
            ),
            ApiMessage("assistant", toolCalls = listOf(ApiToolCall("call_1", "get_weather", "{\"city\":\"Kyiv\"}"))),
            ApiMessage("tool", "12C", toolCallId = "call_1"),
        )
        val reply = client.chat(settings, apiKey, messages, tools = listOf(tool))
        assertEquals("ok", reply.text)
        val request = received!!
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/v1/chat/completions", request.url.encodedPath)
        assertEquals(listOf("Bearer $apiKey"), request.headers.getAll(HttpHeaders.Authorization))
        val body = bodyOf(request)
        assertEquals("qwen4-max-preview", body["model"]!!.jsonPrimitive.content)
        assertEquals(true, body["stream"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(tool, body["tools"]!!.jsonArray.single())
        val sent = body["messages"]!!.jsonArray
        assertEquals("You are Blink.", sent[0].jsonObject["content"]!!.jsonPrimitive.content)
        val content = sent[1].jsonObject["content"]!!.jsonArray
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("What is on this photo?", content[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("image_url", content[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(
            "data:image/png;base64,AAAA",
            content[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "https://cdn.example.com/a.png",
            content[2].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content,
        )
        val call = sent[2].jsonObject["tool_calls"]!!.jsonArray.single().jsonObject
        assertEquals("call_1", call["id"]!!.jsonPrimitive.content)
        assertEquals("function", call["type"]!!.jsonPrimitive.content)
        assertEquals("get_weather", call["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("{\"city\":\"Kyiv\"}", call["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content)
        assertEquals("call_1", sent[3].jsonObject["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("12C", sent[3].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test fun streamAssemblesFragmentedToolCallsAndEmitsCumulativeText() = runTest {
        // CRLF framing, a comment, a multiline data event, fragmented names and
        // arguments, two indices, and [DONE] at EOF without a trailing blank line.
        val body = listOf(
            ": keep-alive",
            "",
            """data: {"model":"qwen4-max-actual","choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"private reasoning","content":"Hel"}}]}""",
            "",
            """data: {"choices":[{"index":0,"delta":{"content":"lo","tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_","arguments":"{\"ci"}}]}}]}""",
            "",
            "data: {",
            """data: "choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"name":"weather","arguments":"ty\":\"Kyiv\"}"}},{"index":1,"id":"call_2","function":{"name":"clock","arguments":"{}"}}]}}]""",
            "data: }",
            "",
            """data: {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""",
            "",
            "data: [DONE]",
        ).joinToString("\r\n") + "\r\n"
        val client = provider(MockEngine { respond(body, headers = sseHeaders) })
        val deltas = mutableListOf<String>()
        val reply = client.chat(settings, apiKey, listOf(ApiMessage("user", "hi"))) { deltas += it }
        assertEquals("Hello", reply.text)
        assertEquals("qwen4-max-actual", reply.model)
        assertEquals(
            listOf(
                ApiToolCall("call_1", "get_weather", "{\"city\":\"Kyiv\"}"),
                ApiToolCall("call_2", "clock", "{}"),
            ),
            reply.toolCalls,
        )
        assertEquals(listOf("Hel", "Hello"), deltas)
    }

    @Test fun streamWithoutTerminatorIsTruncatedNotAnAnswer() = runTest {
        val body = """data: {"choices":[{"index":0,"delta":{"content":"Half a sentence"}}]}""" + "\n\n"
        val client = provider(MockEngine { respond(body, headers = sseHeaders) })
        val failure = expectFailure { client.chat(settings, apiKey, listOf(ApiMessage("user", "hi"))) }
        assertEquals("standalone.error.response", failure.key)
    }

    @Test fun emptyAndContentlessStreamsFail() = runTest {
        val empty = provider(MockEngine { respond("", headers = sseHeaders) })
        assertEquals(
            "standalone.error.response",
            expectFailure { empty.chat(settings, apiKey, listOf(ApiMessage("user", "hi"))) }.key,
        )
        val doneOnly = provider(MockEngine { respond("data: [DONE]\n\n", headers = sseHeaders) })
        assertEquals(
            "standalone.error.response",
            expectFailure { doneOnly.chat(settings, apiKey, listOf(ApiMessage("user", "hi"))) }.key,
        )
        val reasoningOnly = provider(MockEngine {
            respond(
                """data: {"choices":[{"delta":{"reasoning_content":"only thinking"},"finish_reason":"stop"}]}""" +
                    "\n\ndata: [DONE]\n\n",
                headers = sseHeaders,
            )
        })
        assertEquals(
            "standalone.error.response",
            expectFailure { reasoningOnly.chat(settings, apiKey, listOf(ApiMessage("user", "hi"))) }.key,
        )
    }

    @Test fun streamErrorFailsWithoutLeakingProviderTextOrKey() = runTest {
        val client = provider(MockEngine {
            respond(
                """data: {"error":{"message":"upstream exploded for $apiKey","code":"internal"}}""" +
                    "\n\ndata: [DONE]\n\n",
                headers = sseHeaders,
            )
        })
        val failure = expectFailure { client.chat(settings, apiKey, listOf(ApiMessage("user", "hi"))) }
        assertEquals("standalone.error.provider", failure.key)
        assertEquals("standalone.error.provider", failure.message)
        assertTrue(!failure.message!!.contains(apiKey))
        assertTrue(!failure.message!!.contains("upstream"))
    }

    @Test fun malformedStreamPayloadFails() = runTest {
        val client = provider(MockEngine {
            respond("data: {not json}\n\ndata: [DONE]\n\n", headers = sseHeaders)
        })
        assertEquals(
            "standalone.error.response",
            expectFailure { client.chat(settings, apiKey, listOf(ApiMessage("user", "hi"))) }.key,
        )
    }

    @Test fun oversizedStreamLineIsRefused() = runTest {
        val client = provider(MockEngine {
            respond(": " + "x".repeat(2 * 1024 * 1024) + "\n\ndata: [DONE]\n\n", headers = sseHeaders)
        })
        assertEquals(
            "standalone.error.response",
            expectFailure { client.chat(settings, apiKey, listOf(ApiMessage("user", "hi"))) }.key,
        )
    }

    @Test fun httpErrorsMapToLocalizedKeys() = runTest {
        val expected = listOf(
            401 to "standalone.error.auth",
            403 to "standalone.error.auth",
            429 to "standalone.error.rate",
            400 to "standalone.error.provider",
            500 to "standalone.error.provider",
        )
        for ((status, key) in expected) {
            val client = provider(MockEngine {
                respond(
                    """{"error":{"message":"provider detail for $apiKey"}}""",
                    HttpStatusCode.fromValue(status),
                    jsonHeaders,
                )
            })
            val failure = expectFailure { client.chat(settings, apiKey, listOf(ApiMessage("user", "hi"))) }
            assertEquals(key, failure.key)
            assertEquals(status, failure.status)
            assertEquals(key, failure.message)
        }
    }

    @Test fun redirectIsRejectedWithoutForwardingTheKey() = runTest {
        var requests = 0
        val client = provider(MockEngine {
            requests++
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://evil.example/v1/chat/completions"))
        })
        val failure = expectFailure { client.chat(settings, apiKey, listOf(ApiMessage("user", "hi"))) }
        assertEquals("standalone.error.provider", failure.key)
        assertEquals(302, failure.status)
        assertEquals(1, requests)
    }

    @Test fun networkFailureAndCancellationAreNotConfused() = runTest {
        val broken = provider(MockEngine { throw IOException("connection reset") })
        assertEquals(
            "standalone.error.network",
            expectFailure { broken.chat(settings, apiKey, listOf(ApiMessage("user", "hi"))) }.key,
        )
        val cancelled = provider(MockEngine { throw CancellationException("cancelled") })
        val thrown = try {
            cancelled.chat(settings, apiKey, listOf(ApiMessage("user", "hi")))
            null
        } catch (failure: Throwable) {
            failure
        }
        assertTrue("Cancellation must propagate, not become a provider error", thrown is CancellationException)
    }

    @Test fun nonStreamJsonResponseIsParsed() = runTest {
        val client = provider(MockEngine {
            respond(
                """{"model":"qwen4-max-actual","choices":[{"message":{"role":"assistant","reasoning_content":"hidden","content":"Done","tool_calls":[{"id":"call_9","type":"function","function":{"name":"clock","arguments":"{}"}}]},"finish_reason":"stop"}]}""",
                headers = jsonHeaders,
            )
        })
        val reply = client.chat(settings, apiKey, listOf(ApiMessage("user", "hi")))
        assertEquals("Done", reply.text)
        assertEquals("qwen4-max-actual", reply.model)
        assertEquals(listOf(ApiToolCall("call_9", "clock", "{}")), reply.toolCalls)
    }

    @Test fun nonStreamContentPartsAreJoined() = runTest {
        val client = provider(MockEngine {
            respond(
                """{"choices":[{"message":{"content":[{"type":"text","text":"one "},{"type":"image_url","image_url":{"url":"https://cdn.example.com/i.png"}},{"type":"text","text":"two"}]}}]}""",
                headers = jsonHeaders,
            )
        })
        val reply = client.chat(settings, apiKey, listOf(ApiMessage("user", "hi")))
        assertEquals("one two", reply.text)
        assertEquals(settings.chatModel, reply.model)
    }

    @Test fun modelsIgnoreBadNullAndDuplicateIds() = runTest {
        var received: HttpRequestData? = null
        val client = provider(MockEngine { request ->
            // The catalog also asks for LiteLLM's /model/info afterwards; keep the listing request.
            if (received == null) received = request
            respond(
                """{"data":[{"id":"qwen4-max"},{"id":"qwen4-max"},{"id":null},{"id":""},{"id":42},"junk",{},{"id":"qwen-image"},{"id":"bad\nid"}]}""",
                headers = jsonHeaders,
            )
        })
        assertEquals(listOf("qwen4-max", "qwen-image"), client.models(settings, apiKey))
        val request = received!!
        assertEquals(HttpMethod.Get, request.method)
        assertEquals("/v1/models", request.url.encodedPath)
        assertEquals(listOf("Bearer $apiKey"), request.headers.getAll(HttpHeaders.Authorization))
    }

    @Test fun qwenImagePayloadAndDownloadWithoutBearerForwarding() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val client = provider(MockEngine { request ->
            seen += request
            if (request.url.host == "api.example.com") {
                respond(
                    """{"output":{"choices":[{"message":{"content":[{"text":"ignored"},{"image":"https://cdn.example.com/generated.png?sig=abc"}]}}]},"request_id":"r1"}""",
                    headers = jsonHeaders,
                )
            } else {
                respond(png, headers = headersOf(HttpHeaders.ContentType, "image/png"))
            }
        })
        val image = client.generateImage(settings, apiKey, "a red panda")
        assertEquals("image/png", image.mimeType)
        assertArrayEquals(png, image.bytes)
        assertEquals(2, seen.size)
        val api = seen[0]
        val download = seen[1]
        assertEquals("/api/v1/image", api.url.encodedPath)
        assertEquals(listOf("Bearer $apiKey"), api.headers.getAll(HttpHeaders.Authorization))
        assertNull("The image origin must never see the provider key", download.headers[HttpHeaders.Authorization])
        assertEquals("cdn.example.com", download.url.host)
        assertEquals("https", download.url.protocol.name)
        assertEquals("abc", download.url.parameters["sig"])
        val body = bodyOf(api)
        assertEquals("qwen-image-3.0-pro", body["model"]!!.jsonPrimitive.content)
        val message = body["input"]!!.jsonObject["messages"]!!.jsonArray.single().jsonObject
        assertEquals("user", message["role"]!!.jsonPrimitive.content)
        assertEquals(
            "a red panda",
            message["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content,
        )
        assertEquals("1024*1024", body["parameters"]!!.jsonObject["size"]!!.jsonPrimitive.content)
        assertEquals(1, body["parameters"]!!.jsonObject["n"]!!.jsonPrimitive.int)
    }

    @Test fun openAiImageProtocolDecodesBoundedBase64() = runTest {
        var received: HttpRequestData? = null
        val client = provider(MockEngine { request ->
            received = request
            respond("""{"data":[{"b64_json":"${Base64.getEncoder().encodeToString(png)}"}]}""", headers = jsonHeaders)
        })
        val openAi = settings.copy(imageProtocol = "openai", imageEndpoint = "https://api.example.com/v1/images/generations")
        val image = client.generateImage(openAi, apiKey, "a red panda")
        assertEquals("image/png", image.mimeType)
        assertArrayEquals(png, image.bytes)
        val request = received!!
        assertEquals("/v1/images/generations", request.url.encodedPath)
        val body = bodyOf(request)
        assertEquals("qwen-image-3.0-pro", body["model"]!!.jsonPrimitive.content)
        assertEquals("a red panda", body["prompt"]!!.jsonPrimitive.content)
        assertEquals(1, body["n"]!!.jsonPrimitive.int)
        assertEquals("1024x1024", body["size"]!!.jsonPrimitive.content)
    }

    @Test fun openAiImageProtocolDownloadsUrlWithoutBearer() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val client = provider(MockEngine { request ->
            seen += request
            if (request.url.host == "api.example.com") {
                respond("""{"data":[{"url":"https://cdn.example.com/generated.webp"}]}""", headers = jsonHeaders)
            } else {
                respond(webp, headers = headersOf(HttpHeaders.ContentType, "application/octet-stream"))
            }
        })
        val openAi = settings.copy(imageProtocol = "openai", imageEndpoint = "https://api.example.com/v1/images/generations")
        val image = client.generateImage(openAi, apiKey, "a red panda")
        assertEquals("image/webp", image.mimeType)
        assertArrayEquals(webp, image.bytes)
        assertEquals(2, seen.size)
        assertNull(seen[1].headers[HttpHeaders.Authorization])
    }

    @Test fun invalidBase64ImageFails() = runTest {
        val client = provider(MockEngine {
            respond("""{"data":[{"b64_json":"!!!not base64!!!"}]}""", headers = jsonHeaders)
        })
        val openAi = settings.copy(imageProtocol = "openai", imageEndpoint = "https://api.example.com/v1/images/generations")
        assertEquals(
            "standalone.error.response",
            expectFailure { client.generateImage(openAi, apiKey, "a red panda") }.key,
        )
    }

    @Test fun oversizedImageIsRefusedByDeclaredLengthAndByBytes() = runTest {
        val declared = provider(MockEngine {
            if (it.url.host == "api.example.com") {
                respond("""{"data":[{"url":"https://cdn.example.com/big.png"}]}""", headers = jsonHeaders)
            } else {
                respond(
                    png,
                    headers = headersOf(
                        HttpHeaders.ContentType to listOf("image/png"),
                        HttpHeaders.ContentLength to listOf((20L * 1024 * 1024 + 1).toString()),
                    ),
                )
            }
        })
        val openAi = settings.copy(imageProtocol = "openai", imageEndpoint = "https://api.example.com/v1/images/generations")
        assertEquals(
            "standalone.error.response",
            expectFailure { declared.generateImage(openAi, apiKey, "big") }.key,
        )
        val streamed = provider(MockEngine {
            if (it.url.host == "api.example.com") {
                respond("""{"data":[{"url":"https://cdn.example.com/big.png"}]}""", headers = jsonHeaders)
            } else {
                respond(png + ByteArray(20 * 1024 * 1024), headers = headersOf(HttpHeaders.ContentType, "image/png"))
            }
        })
        assertEquals(
            "standalone.error.response",
            expectFailure { streamed.generateImage(openAi, apiKey, "big") }.key,
        )
    }

    @Test fun imageBytesMustMatchAnAllowedMagicAndType() = runTest {
        val openAi = settings.copy(imageProtocol = "openai", imageEndpoint = "https://api.example.com/v1/images/generations")
        val cases = listOf(
            "this is not an image".toByteArray() to "image/png",
            gif to "image/gif",
            jpeg to "image/png",
            png to "text/html",
        )
        for ((bytes, type) in cases) {
            val client = provider(MockEngine { request ->
                if (request.url.host == "api.example.com") {
                    respond("""{"data":[{"url":"https://cdn.example.com/i"}]}""", headers = jsonHeaders)
                } else {
                    respond(bytes, headers = headersOf(HttpHeaders.ContentType, type))
                }
            })
            assertEquals(
                "standalone.error.response",
                expectFailure { client.generateImage(openAi, apiKey, "x") }.key,
            )
        }
        val jpegClient = provider(MockEngine { request ->
            if (request.url.host == "api.example.com") {
                respond("""{"data":[{"url":"https://cdn.example.com/i"}]}""", headers = jsonHeaders)
            } else {
                respond(jpeg, headers = headersOf(HttpHeaders.ContentType, "image/jpg"))
            }
        })
        assertEquals("image/jpeg", jpegClient.generateImage(openAi, apiKey, "x").mimeType)
    }

    @Test fun imageDownloadRefusesInsecureAndCredentialedUrls() = runTest {
        val urls = listOf(
            "http://cdn.example.com/i.png",
            credentialedUrl("cdn.example.com/i.png"),
        )
        for (url in urls) {
            var downloads = 0
            val client = provider(MockEngine { request ->
                if (request.url.host == "api.example.com") {
                    respond("""{"output":{"choices":[{"message":{"content":[{"image":"$url"}]}}]}}""", headers = jsonHeaders)
                } else {
                    downloads++
                    respond(png, headers = headersOf(HttpHeaders.ContentType, "image/png"))
                }
            })
            assertEquals("standalone.error.response", expectFailure { client.generateImage(settings, apiKey, "x") }.key)
            assertEquals(0, downloads)
        }
    }

    @Test fun imageDownloadRedirectIsRejected() = runTest {
        var requests = 0
        val client = provider(MockEngine {
            requests++
            if (it.url.host == "api.example.com") {
                respond("""{"data":[{"url":"https://cdn.example.com/i.png"}]}""", headers = jsonHeaders)
            } else {
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://evil.example/i.png"))
            }
        })
        val openAi = settings.copy(imageProtocol = "openai", imageEndpoint = "https://api.example.com/v1/images/generations")
        assertEquals("standalone.error.provider", expectFailure { client.generateImage(openAi, apiKey, "x") }.key)
        assertEquals(2, requests)
    }

    @Test fun settingsValidationRejectsInsecureAndMalformedValues() {
        val invalid = listOf(
            settings.copy(baseUrl = "http://api.example.com/v1"),
            settings.copy(baseUrl = credentialedUrl("api.example.com/v1")),
            settings.copy(baseUrl = "https://api.example.com/v1?key=1"),
            settings.copy(baseUrl = "https://api.example.com/v1#fragment"),
            settings.copy(baseUrl = "   "),
            settings.copy(baseUrl = "https://api.example.com/v1\n"),
            settings.copy(imageEndpoint = "http://api.example.com/image"),
            settings.copy(imageEndpoint = "https://api.example.com/image?sig=1"),
            settings.copy(chatModel = "qwen\n4"),
            settings.copy(imageModel = " "),
            settings.copy(imageProtocol = "anthropic"),
        )
        for (candidate in invalid) {
            val failure = assertThrows(ProviderFailure::class.java) { ProviderClient.validateSettings(candidate) }
            assertEquals("standalone.error.settings", failure.key)
        }
        ProviderClient.validateSettings(settings)
        ProviderClient.validateSettings(
            settings.copy(baseUrl = "https://api.example.com/v1/", imageEndpoint = "https://api.example.com/image/"),
        )
    }

    @Test fun invalidSettingsFailBeforeAnyRequest() = runTest {
        var requests = 0
        val client = provider(MockEngine { requests++; respond("{}", headers = jsonHeaders) })
        val failure = expectFailure {
            client.chat(settings.copy(baseUrl = "http://api.example.com/v1"), apiKey, listOf(ApiMessage("user", "hi")))
        }
        assertEquals("standalone.error.settings", failure.key)
        // An image-only provider has no chat model; it is valid to save but can never chat.
        val imageOnly = expectFailure { client.chat(settings.copy(chatModel = ""), apiKey, listOf(ApiMessage("user", "hi"))) }
        assertEquals("standalone.error.settings", imageOnly.key)
        assertEquals(0, requests)
    }

    @Test fun trailingSlashIsNormalizedWithoutChangingThePath() = runTest {
        var path = ""
        val client = provider(MockEngine { request ->
            if (path.isEmpty()) path = request.url.encodedPath
            respond("""{"data":[]}""", headers = jsonHeaders)
        })
        assertEquals(emptyList<String>(), client.models(settings.copy(baseUrl = "https://api.example.com/v1/"), apiKey))
        assertEquals("/v1/models", path)
    }
}
