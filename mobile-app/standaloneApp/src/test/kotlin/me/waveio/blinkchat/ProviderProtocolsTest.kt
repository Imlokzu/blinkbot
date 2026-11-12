package me.waveio.blinkchat

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderProtocolsTest {
    private val clients = mutableListOf<ProviderClient>()
    private val apiKey = "test-provider-key"
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val sseHeaders = headersOf(HttpHeaders.ContentType, "text/event-stream")

    private fun provider(engine: MockEngine): ProviderClient =
        ProviderClient(HttpClient(engine)).also { clients += it }

    @After fun closeClients() {
        clients.forEach { it.close() }
    }

    private fun bodyOf(request: HttpRequestData): JsonObject =
        Json.parseToJsonElement((request.body as TextContent).text).jsonObject

    private suspend fun expectFailure(block: suspend () -> Unit): ProviderFailure =
        try {
            block()
            throw AssertionError("Expected ProviderFailure")
        } catch (failure: ProviderFailure) {
            failure
        }

    @Test fun parseAnthropicModelItemWithEffortsAndCapabilities() {
        val item = buildJsonObject {
            put("type", "model")
            put("id", "claude-opus-5-5")
            put("display_name", "Claude Opus 5.5")
            put("max_input_tokens", 1000000)
            put("max_tokens", 128000)
            putJsonObject("capabilities") {
                putJsonObject("image_input") { put("supported", true) }
                putJsonObject("effort") {
                    put("supported", true)
                    putJsonObject("low") { put("supported", true) }
                    putJsonObject("medium") { put("supported", true) }
                    putJsonObject("high") { put("supported", true) }
                    putJsonObject("xhigh") { put("supported", true) }
                    putJsonObject("max") { put("supported", true) }
                }
            }
        }
        val info = ModelCatalog.parse(item, "api.anthropic.com")
        assertNotNull(info)
        assertEquals("Claude Opus 5.5", info!!.label)
        assertEquals(1000000L, info.context)
        assertEquals(128000L, info.maxOutput)
        assertEquals(true, info.vision)
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), info.efforts)
        assertEquals("medium", info.effortDefault)

        // Also a claude-sonnet id -> effortDefault "high"
        val sonnetItem = buildJsonObject {
            put("type", "model")
            put("id", "claude-3-7-sonnet")
            putJsonObject("capabilities") {
                putJsonObject("effort") {
                    put("supported", true)
                    putJsonObject("low") { put("supported", true) }
                    putJsonObject("medium") { put("supported", true) }
                    putJsonObject("high") { put("supported", true) }
                }
            }
        }
        val sonnetInfo = ModelCatalog.parse(sonnetItem, "api.anthropic.com")
        assertNotNull(sonnetInfo)
        assertEquals("high", sonnetInfo!!.effortDefault)

        // Also effort {"supported":false} -> empty efforts
        val unsupportedEffortItem = buildJsonObject {
            put("type", "model")
            put("id", "claude-3-5-haiku")
            putJsonObject("capabilities") {
                putJsonObject("effort") {
                    put("supported", false)
                }
            }
        }
        val unsupportedInfo = ModelCatalog.parse(unsupportedEffortItem, "api.anthropic.com")
        assertNotNull(unsupportedInfo)
        assertEquals(emptyList<String>(), unsupportedInfo!!.efforts)
        assertEquals("", unsupportedInfo.effortDefault)
    }

    @Test fun parseOpenRouterItemWithReasoningParameters() {
        val item = buildJsonObject {
            put("id", "deepseek/deepseek-r1")
            put("context_length", 131072)
            putJsonObject("architecture") {
                put("input_modalities", buildJsonArray {
                    add(JsonPrimitive("text"))
                    add(JsonPrimitive("image"))
                })
                put("output_modalities", buildJsonArray {
                    add(JsonPrimitive("text"))
                })
            }
            put("supported_parameters", buildJsonArray {
                add(JsonPrimitive("reasoning"))
                add(JsonPrimitive("tools"))
            })
        }
        val info = ModelCatalog.parse(item, "openrouter.ai")
        assertNotNull(info)
        assertEquals(131072L, info!!.context)
        assertEquals(true, info.vision)
        assertEquals(true, info.text)
        assertEquals(false, info.image)
        assertEquals(listOf("low", "medium", "high"), info.efforts)
    }

    @Test fun parseImageAndEmbeddingAndSpecialFamilyModels() {
        // parse of an image model (architecture.output_modalities ["image"]) -> image=true, text=false
        val imageItem = buildJsonObject {
            put("id", "custom-gen-model")
            putJsonObject("architecture") {
                put("output_modalities", buildJsonArray { add(JsonPrimitive("image")) })
            }
        }
        val imageInfo = ModelCatalog.parse(imageItem, "example.com")
        assertNotNull(imageInfo)
        assertEquals(true, imageInfo!!.image)
        assertEquals(false, imageInfo.text)

        // parse of "gpt-image-2" with no metadata -> image=true, text=false
        val gptImageItem = buildJsonObject {
            put("id", "gpt-image-2")
        }
        val gptImageInfo = ModelCatalog.parse(gptImageItem, "api.openai.com")
        assertNotNull(gptImageInfo)
        assertEquals(true, gptImageInfo!!.image)
        assertEquals(false, gptImageInfo.text)

        // "text-embedding-3-small" -> text=false
        val embedItem = buildJsonObject {
            put("id", "text-embedding-3-small")
        }
        val embedInfo = ModelCatalog.parse(embedItem, "api.openai.com")
        assertNotNull(embedInfo)
        assertEquals(false, embedInfo!!.text)

        // api.openai.com host with id "o4-mini" -> efforts [low, medium, high]
        val o4Item = buildJsonObject {
            put("id", "o4-mini")
        }
        val o4Info = ModelCatalog.parse(o4Item, "api.openai.com")
        assertNotNull(o4Info)
        assertEquals(listOf("low", "medium", "high"), o4Info!!.efforts)

        // unknown host plain "llama-4" -> efforts empty, vision null
        val llamaItem = buildJsonObject {
            put("id", "llama-4")
        }
        val llamaInfo = ModelCatalog.parse(llamaItem, "llm.unknown-provider.com")
        assertNotNull(llamaInfo)
        assertEquals(emptyList<String>(), llamaInfo!!.efforts)
        assertNull(llamaInfo.vision)
    }

    @Test fun candidateBasesAndSuggestName() {
        assertEquals(
            listOf("https://api.anthropic.com/v1", "https://api.anthropic.com"),
            ModelCatalog.candidateBases("https://api.anthropic.com"),
        )
        assertEquals(
            listOf("https://api.openai.com/v1"),
            ModelCatalog.candidateBases("api.openai.com/v1/"),
        )
        assertEquals(
            listOf("https://x.test/v1"),
            ModelCatalog.candidateBases("https://x.test/v1/chat/completions"),
        )
        assertEquals("Anthropic", ModelCatalog.suggestName("https://api.anthropic.com/v1"))
        assertEquals("OpenRouter", ModelCatalog.suggestName("https://openrouter.ai/api/v1"))
    }

    @Test fun chatAnthropicProtocolSendsMessagesAndParsesThinkingTextToolCallsStream() = runTest {
        var received: HttpRequestData? = null
        val anthropicStreamResponse = listOf(
            "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"model\":\"claude-3-7-sonnet\"}}",
            "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}",
            "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"thinking\"}}",
            "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"sig_test\"}}",
            "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}",
            "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
            "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hel\"}}",
            "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"lo\"}}",
            "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":2,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"current_time\",\"input\":{}}}",
            "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{}\"}}",
            "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"}}",
            "event: message_stop\ndata: {\"type\":\"message_stop\"}",
        ).joinToString("\n\n", postfix = "\n\n")

        val client = provider(MockEngine { request ->
            received = request
            respond(anthropicStreamResponse, headers = sseHeaders)
        })

        val anthropicSettings = ProviderSettings(
            baseUrl = "https://api.anthropic.com/v1",
            chatModel = "claude-3-7-sonnet",
            api = ApiLayer.ANTHROPIC,
        )
        val tool = buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", "current_time")
                put("description", "Get current time")
                put("parameters", buildJsonObject { put("type", "object") })
            })
        }
        val messages = listOf(
            ApiMessage("system", "You are an assistant."),
            ApiMessage("user", "What time is it?"),
        )
        val deltas = mutableListOf<String>()
        val reply = client.chat(
            settings = anthropicSettings,
            apiKey = apiKey,
            messages = messages,
            tools = listOf(tool),
            effort = "high",
            onDelta = { deltas += it },
        )

        val request = received!!
        assertTrue(request.url.encodedPath.endsWith("/v1/messages"))
        assertEquals(apiKey, request.headers["x-api-key"])
        assertNotNull(request.headers["anthropic-version"])
        assertNull(request.headers[HttpHeaders.Authorization])

        val body = bodyOf(request)
        assertEquals("You are an assistant.", body["system"]!!.jsonPrimitive.content)
        assertNotNull(body["max_tokens"])
        assertEquals("high", body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)

        val sentTool = body["tools"]!!.jsonArray.single().jsonObject
        assertEquals("current_time", sentTool["name"]!!.jsonPrimitive.content)
        assertEquals("Get current time", sentTool["description"]!!.jsonPrimitive.content)
        assertNotNull(sentTool["input_schema"]!!.jsonObject)

        assertEquals("Hello", reply.text)
        assertEquals(listOf(ApiToolCall("toolu_1", "current_time", "{}")), reply.toolCalls)

        assertNotNull(reply.native)
        val nativeBlocks = reply.native!!
        assertEquals(3, nativeBlocks.size)
        assertEquals("thinking", nativeBlocks[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("sig_test", nativeBlocks[0].jsonObject["signature"]!!.jsonPrimitive.content)
        assertEquals("text", nativeBlocks[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("tool_use", nativeBlocks[2].jsonObject["type"]!!.jsonPrimitive.content)

        assertEquals(listOf("Hel", "Hello"), deltas)
    }

    @Test fun anthropicToolRoundTripEncoding() = runTest {
        var received: HttpRequestData? = null
        val dummyStream = listOf(
            "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"model\":\"claude-3-7-sonnet\"}}",
            "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"Done\"}}",
            "event: message_stop\ndata: {\"type\":\"message_stop\"}",
        ).joinToString("\n\n", postfix = "\n\n")

        val client = provider(MockEngine { request ->
            received = request
            respond(dummyStream, headers = sseHeaders)
        })

        val anthropicSettings = ProviderSettings(
            baseUrl = "https://api.anthropic.com/v1",
            chatModel = "claude-3-7-sonnet",
            api = ApiLayer.ANTHROPIC,
        )

        val nativeThinking = buildJsonObject {
            put("type", "thinking")
            put("thinking", "checking current time")
            put("signature", "sig_roundtrip")
        }
        val nativeToolUse = buildJsonObject {
            put("type", "tool_use")
            put("id", "toolu_1")
            put("name", "current_time")
            putJsonObject("input") {}
        }
        val nativeArray = buildJsonArray {
            add(nativeThinking)
            add(nativeToolUse)
        }

        val messages = listOf(
            ApiMessage(role = "user", text = "hi"),
            ApiMessage(
                role = "assistant",
                toolCalls = listOf(ApiToolCall("toolu_1", "current_time", "{}")),
                native = nativeArray,
            ),
            ApiMessage(role = "tool", toolCallId = "toolu_1", text = "ok"),
            ApiMessage(role = "tool", toolCallId = "toolu_2", text = "result_2"),
        )

        client.chat(anthropicSettings, apiKey, messages)

        val request = received!!
        val body = bodyOf(request)
        val sentMessages = body["messages"]!!.jsonArray
        assertEquals(3, sentMessages.size)

        // Assistant content must equal the native array exactly
        val assistantMsg = sentMessages[1].jsonObject
        assertEquals("assistant", assistantMsg["role"]!!.jsonPrimitive.content)
        assertEquals(nativeArray, assistantMsg["content"])

        // Both tool results must be in ONE following user message as tool_result blocks
        val userToolResultsMsg = sentMessages[2].jsonObject
        assertEquals("user", userToolResultsMsg["role"]!!.jsonPrimitive.content)
        val toolResults = userToolResultsMsg["content"]!!.jsonArray
        assertEquals(2, toolResults.size)

        val r1 = toolResults[0].jsonObject
        assertEquals("tool_result", r1["type"]!!.jsonPrimitive.content)
        assertEquals("toolu_1", r1["tool_use_id"]!!.jsonPrimitive.content)
        assertEquals("ok", r1["content"]!!.jsonPrimitive.content)

        val r2 = toolResults[1].jsonObject
        assertEquals("tool_result", r2["type"]!!.jsonPrimitive.content)
        assertEquals("toolu_2", r2["tool_use_id"]!!.jsonPrimitive.content)
        assertEquals("result_2", r2["content"]!!.jsonPrimitive.content)
    }

    @Test fun anthropicStreamErrorsWithoutMessageStopAndRefusalWithoutText() = runTest {
        val anthropicSettings = ProviderSettings(
            baseUrl = "https://api.anthropic.com/v1",
            chatModel = "claude-3-7-sonnet",
            api = ApiLayer.ANTHROPIC,
        )

        // Anthropic stream without message_stop -> ProviderFailure key "standalone.error.response"
        val streamNoStop = listOf(
            "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"model\":\"claude-3-7-sonnet\"}}",
            "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
            "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Incomplete\"}}",
        ).joinToString("\n\n", postfix = "\n\n")

        val clientNoStop = provider(MockEngine { respond(streamNoStop, headers = sseHeaders) })
        val failureNoStop = expectFailure {
            clientNoStop.chat(anthropicSettings, apiKey, listOf(ApiMessage("user", "hi")))
        }
        assertEquals("standalone.error.response", failureNoStop.key)

        // Anthropic stop_reason "refusal" with no text -> key "standalone.error.refusal"
        val streamRefusal = listOf(
            "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"model\":\"claude-3-7-sonnet\"}}",
            "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"refusal\"}}",
            "event: message_stop\ndata: {\"type\":\"message_stop\"}",
        ).joinToString("\n\n", postfix = "\n\n")

        val clientRefusal = provider(MockEngine { respond(streamRefusal, headers = sseHeaders) })
        val failureRefusal = expectFailure {
            clientRefusal.chat(anthropicSettings, apiKey, listOf(ApiMessage("user", "hi")))
        }
        assertEquals("standalone.error.refusal", failureRefusal.key)
    }

    @Test fun chatResponsesProtocolSendsInstructionsStoreAndParsesDeltaAndToolCalls() = runTest {
        var received: HttpRequestData? = null
        val responsesStream = listOf(
            """data: {"type":"response.created","response":{"model":"o3-mini"}}""",
            """data: {"type":"response.output_text.delta","delta":"Here are "}""",
            """data: {"type":"response.output_text.delta","delta":"the files:"}""",
            """data: {"type":"response.output_item.done","item":{"type":"function_call","call_id":"call_1","name":"list_files","arguments":"{}"}}""",
            """data: {"type":"response.completed"}""",
        ).joinToString("\n\n", postfix = "\n\n")

        val client = provider(MockEngine { request ->
            received = request
            respond(responsesStream, headers = sseHeaders)
        })

        val responsesSettings = ProviderSettings(
            baseUrl = "https://api.openai.com/v1",
            chatModel = "o3-mini",
            api = ApiLayer.RESPONSES,
        )
        val tool = buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", "list_files")
                put("description", "Lists files in directory")
                put("parameters", buildJsonObject { put("type", "object") })
            })
        }
        val messages = listOf(
            ApiMessage(role = "system", text = "Instructions for assistant."),
            ApiMessage(role = "user", text = "list files"),
        )

        val reply = client.chat(
            settings = responsesSettings,
            apiKey = apiKey,
            messages = messages,
            tools = listOf(tool),
            effort = "low",
        )

        val request = received!!
        assertTrue(request.url.encodedPath.endsWith("/responses"))
        assertEquals(listOf("Bearer $apiKey"), request.headers.getAll(HttpHeaders.Authorization))

        val body = bodyOf(request)
        assertEquals("Instructions for assistant.", body["instructions"]!!.jsonPrimitive.content)
        assertEquals(false, body["store"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("low", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)

        val inputList = body["input"]!!.jsonArray
        val userItem = inputList.single { it.jsonObject["role"]?.jsonPrimitive?.content == "user" }.jsonObject
        val contentArray = userItem["content"]!!.jsonArray
        assertEquals("input_text", contentArray[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("list files", contentArray[0].jsonObject["text"]!!.jsonPrimitive.content)

        val toolsArray = body["tools"]!!.jsonArray
        val toolObj = toolsArray.single().jsonObject
        assertEquals("function", toolObj["type"]!!.jsonPrimitive.content)
        assertEquals("list_files", toolObj["name"]!!.jsonPrimitive.content)

        assertEquals("Here are the files:", reply.text)
        assertEquals(listOf(ApiToolCall("call_1", "list_files", "{}")), reply.toolCalls)
    }

    @Test fun chatCompletionsReasoningEffortHandling() = runTest {
        val openAiSettings = ProviderSettings(
            baseUrl = "https://api.openai.com/v1",
            chatModel = "o3-mini",
            api = ApiLayer.OPENAI_CHAT,
        )

        // Chat Completions with effort="medium" sends "reasoning_effort":"medium"
        var mediumBody: JsonObject? = null
        val clientMedium = provider(MockEngine { request ->
            mediumBody = bodyOf(request)
            respond("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n", headers = sseHeaders)
        })
        clientMedium.chat(openAiSettings, apiKey, listOf(ApiMessage("user", "hi")), effort = "medium")
        assertEquals("medium", mediumBody!!["reasoning_effort"]?.jsonPrimitive?.content)

        // with effort=null the key is absent
        var nullBody: JsonObject? = null
        val clientNull = provider(MockEngine { request ->
            nullBody = bodyOf(request)
            respond("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n", headers = sseHeaders)
        })
        clientNull.chat(openAiSettings, apiKey, listOf(ApiMessage("user", "hi")), effort = null)
        assertNull(nullBody!!["reasoning_effort"])

        // an effort not in KNOWN_EFFORTS (e.g. "turbo") is not sent
        var turboBody: JsonObject? = null
        val clientTurbo = provider(MockEngine { request ->
            turboBody = bodyOf(request)
            respond("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n", headers = sseHeaders)
        })
        clientTurbo.chat(openAiSettings, apiKey, listOf(ApiMessage("user", "hi")), effort = "turbo")
        assertNull(turboBody!!["reasoning_effort"])
    }

    @Test fun discoverRouteProbingAndCatalogParsing() = runTest {
        val requestHosts = mutableListOf<String>()
        val client = provider(MockEngine { request ->
            requestHosts += request.url.host
            val path = request.url.encodedPath
            val method = request.method
            when {
                // GET https://gw.test/v1/models with Bearer -> OpenAI list
                method == HttpMethod.Get && path == "/v1/models" && request.headers[HttpHeaders.Authorization] != null -> {
                    respond(
                        """{"object":"list","data":[{"id":"m-chat","object":"model","context_length":128000},{"id":"m-image","object":"model","architecture":{"output_modalities":["image"]}}]}""",
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }
                // GET models with x-api-key (no Authorization) -> 401
                method == HttpMethod.Get && path == "/v1/models" && request.headers["x-api-key"] != null -> {
                    respond("""{"error":"unauthorized"}""", HttpStatusCode.Unauthorized, jsonHeaders)
                }
                // POST /v1/chat/completions -> 400
                method == HttpMethod.Post && path == "/v1/chat/completions" -> {
                    respond("""{"error":"bad request"}""", HttpStatusCode.BadRequest, jsonHeaders)
                }
                // POST /v1/responses -> 404
                method == HttpMethod.Post && path == "/v1/responses" -> {
                    respond("""{"error":"not found"}""", HttpStatusCode.NotFound, jsonHeaders)
                }
                // POST /v1/messages -> 404
                method == HttpMethod.Post && path == "/v1/messages" -> {
                    respond("""{"error":"not found"}""", HttpStatusCode.NotFound, jsonHeaders)
                }
                // POST /v1/images/generations -> 400
                method == HttpMethod.Post && path == "/v1/images/generations" -> {
                    respond("""{"error":"bad request"}""", HttpStatusCode.BadRequest, jsonHeaders)
                }
                else -> respond("""{"error":"not found"}""", HttpStatusCode.NotFound, jsonHeaders)
            }
        })

        val discovery = client.discover("https://gw.test", apiKey)
        assertEquals("https://gw.test/v1", discovery.baseUrl)
        assertEquals(listOf(ApiLayer.OPENAI_CHAT), discovery.apis)
        assertEquals(ApiLayer.OPENAI_CHAT, discovery.recommended)
        assertEquals(ImageLayer.OPENAI, discovery.imageProtocol)
        assertEquals("https://gw.test/v1/images/generations", discovery.imageEndpoint)

        val modelIds = discovery.models.map { it.id }
        assertTrue(modelIds.contains("m-chat"))
        assertTrue(modelIds.contains("m-image"))
        val mImage = discovery.models.first { it.id == "m-image" }
        assertEquals(true, mImage.image)

        // Verify key is never sent to any host other than gw.test
        assertTrue(requestHosts.isNotEmpty())
        assertTrue(requestHosts.all { it == "gw.test" })
        assertEquals(setOf("gw.test"), requestHosts.toSet())

        // Also a discover where every request returns 401 -> ProviderFailure key "standalone.error.auth"
        val authFailClient = provider(MockEngine {
            respond("""{"error":"unauthorized"}""", HttpStatusCode.Unauthorized, jsonHeaders)
        })
        val authFailure = expectFailure {
            authFailClient.discover("https://gw.test", apiKey)
        }
        assertEquals("standalone.error.auth", authFailure.key)
    }

    @Test fun validateSettingsTextOnlyAndGenerateImageRejection() = runTest {
        val textOnly = ProviderSettings(
            baseUrl = "https://a.test/v1",
            chatModel = "x",
            imageModel = "",
            imageEndpoint = "",
            imageProtocol = ImageLayer.NONE,
        )
        // Accepts text-only settings
        ProviderClient.validateSettings(textOnly)

        // Rejects imageProtocol NONE with a non-blank imageModel
        val invalidNone = textOnly.copy(imageModel = "dall-e-3")
        val failureSettings = assertThrows(ProviderFailure::class.java) {
            ProviderClient.validateSettings(invalidNone)
        }
        assertEquals("standalone.error.settings", failureSettings.key)

        // generateImage on NONE settings fails with "standalone.error.settings"
        val client = provider(MockEngine { respond("{}", headers = jsonHeaders) })
        val failureImage = expectFailure {
            client.generateImage(textOnly, apiKey, "draw a picture")
        }
        assertEquals("standalone.error.settings", failureImage.key)
    }
}
