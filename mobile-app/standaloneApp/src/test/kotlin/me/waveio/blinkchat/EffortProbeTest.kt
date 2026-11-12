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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Effort levels for endpoints whose catalog does not publish them (QwenCloud, LiteLLM, ...). */
class EffortProbeTest {
    private val clients = mutableListOf<ProviderClient>()
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val sse = headersOf(HttpHeaders.ContentType, "text/event-stream")
    private val settings = ProviderSettings(baseUrl = "https://gw.test/v1", chatModel = "qwen3.6-flash",
        imageModel = "", imageEndpoint = "", imageProtocol = ImageLayer.NONE)

    private fun provider(engine: MockEngine) = ProviderClient(HttpClient(engine)).also { clients += it }
    private fun body(request: HttpRequestData) = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
    @After fun close() { clients.forEach { it.close() } }

    // Recorded from QwenCloud on 2026-11-12 (qwen3.6-flash and glm-5.2).
    private val qwenError = """{"error":{"code":"invalid_parameter_error","param":null,"message":"'reasoning_effort' must be one of: 'none', 'minimal', 'low', 'medium', 'high', 'xhigh'","type":"invalid_request_error"}}"""
    private val glmError = """{"error":{"message":"'reasoning_effort' must be one of: 'none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max'","type":"invalid_request_error"}}"""

    @Test fun readsAllowedLevelsFromProviderErrors() {
        assertEquals(listOf("off", "minimal", "low", "medium", "high", "xhigh"), ModelCatalog.effortsFromError(qwenError, "blink_probe"))
        assertEquals(listOf("off", "minimal", "low", "medium", "high", "xhigh", "max"), ModelCatalog.effortsFromError(glmError, "blink_probe"))
        val openAi = """{"error":{"message":"Invalid value: 'blink_probe'. Supported values are: 'low', 'medium', and 'high'.","type":"invalid_request_error","param":"reasoning_effort","code":"invalid_value"}}"""
        assertEquals(listOf("low", "medium", "high"), ModelCatalog.effortsFromError(openAi, "blink_probe"))
        val anthropic = """{"type":"error","error":{"type":"invalid_request_error","message":"output_config.effort: Input should be 'low', 'medium', 'high', 'xhigh' or 'max'"}}"""
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), ModelCatalog.effortsFromError(anthropic, "blink_probe"))
        // The model takes no effort at all: the error names the parameter but lists nothing.
        assertEquals(emptyList<String>(), ModelCatalog.effortsFromError("""{"error":{"message":"reasoning_effort is not supported with this model"}}""", "blink_probe"))
        // Unrelated validation errors say nothing about effort.
        assertNull(ModelCatalog.effortsFromError("""{"error":{"message":"max_tokens must be at least 'high'"}}""".replace("'high'", "16"), "blink_probe"))
    }

    @Test fun probeSendsAnInvalidLevelAndParsesTheRejection() = runTest {
        var sent: HttpRequestData? = null
        val client = provider(MockEngine { request -> sent = request; respond(qwenError, HttpStatusCode.BadRequest, json) })
        assertEquals(listOf("off", "minimal", "low", "medium", "high", "xhigh"), client.probeEfforts(settings, "k", "qwen3.6-flash"))
        assertEquals("/v1/chat/completions", sent!!.url.encodedPath)
        assertEquals(ProviderClient.EFFORT_PROBE, body(sent!!)["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("1", body(sent!!)["max_tokens"]!!.jsonPrimitive.content)
    }

    @Test fun anEndpointThatIgnoresUnknownLevelsGetsTheCommonSet() = runTest {
        val client = provider(MockEngine { respond("""{"choices":[{"message":{"content":"x"}}]}""", headers = json) })
        assertEquals(listOf("low", "medium", "high"), client.probeEfforts(settings, "k", "m"))
    }

    @Test fun offIsSentAsNone() = runTest {
        var sent: HttpRequestData? = null
        val client = provider(MockEngine { request ->
            sent = request
            respond("data: {\"choices\":[{\"delta\":{\"content\":\"4\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n", headers = sse)
        })
        client.chat(settings, "k", listOf(ApiMessage("user", "2+2")), effort = "off")
        assertEquals("none", body(sent!!)["reasoning_effort"]!!.jsonPrimitive.content)
    }

    @Test fun discoveryProbesEffortsAndReadsLiteLlmModelInfo() = runTest {
        val probed = mutableListOf<String>()
        val client = provider(MockEngine { request ->
            val path = request.url.encodedPath
            when {
                request.method == HttpMethod.Get && path == "/v1/models" && request.headers[HttpHeaders.Authorization] != null ->
                    respond("""{"object":"list","data":[{"id":"qwen3.6-flash","object":"model"},{"id":"glm-5.2","object":"model"},{"id":"plain-chat","object":"model"}]}""", headers = json)
                request.method == HttpMethod.Get && path == "/model/info" ->
                    respond("""{"data":[{"model_name":"qwen3.6-flash","model_info":{"mode":"chat","max_input_tokens":262144,"max_output_tokens":65536,"supports_vision":true,"supports_reasoning":true}}]}""", headers = json)
                request.method == HttpMethod.Post && path == "/v1/chat/completions" -> {
                    val payload = body(request)
                    if (payload["reasoning_effort"]?.jsonPrimitive?.content == ProviderClient.EFFORT_PROBE) {
                        val model = payload["model"]!!.jsonPrimitive.content
                        probed += model
                        when (model) {
                            "glm-5.2" -> respond(glmError, HttpStatusCode.BadRequest, json)
                            "plain-chat" -> respond("""{"error":{"message":"reasoning_effort is not supported for this model"}}""", HttpStatusCode.BadRequest, json)
                            else -> respond(qwenError, HttpStatusCode.BadRequest, json)
                        }
                    } else respond("""{"error":{"message":"model is required"}}""", HttpStatusCode.BadRequest, json)
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        })
        val found = client.discover("https://gw.test/v1", "k")
        assertEquals(setOf("qwen3.6-flash", "glm-5.2", "plain-chat"), probed.toSet())
        val qwen = found.models.single { it.id == "qwen3.6-flash" }
        assertEquals(listOf("off", "minimal", "low", "medium", "high", "xhigh"), qwen.efforts)
        assertEquals("medium", qwen.effortDefault)
        assertEquals(EffortSource.PROBE, qwen.effortSource)
        assertEquals(262_144L, qwen.context)
        assertEquals(65_536L, qwen.maxOutput)
        assertEquals(true, qwen.vision)
        assertEquals("max", found.models.single { it.id == "glm-5.2" }.efforts.last())
        assertEquals(emptyList<String>(), found.models.single { it.id == "plain-chat" }.efforts)
        assertEquals("high", ModelCatalog.defaultEffort("glm-5.3", listOf("low", "high", "max")))
        assertEquals(false, ModelCatalog.parse(Json.parseToJsonElement("""{"id":"wan3.0-video"}""").jsonObject, "gw.test")!!.text)
    }
}
