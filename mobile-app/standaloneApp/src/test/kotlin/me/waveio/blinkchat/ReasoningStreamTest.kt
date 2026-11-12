package me.waveio.blinkchat

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Visible reasoning is reported separately from the reply on every protocol. */
class ReasoningStreamTest {
    private val clients = mutableListOf<ProviderClient>()
    private val sse = headersOf(HttpHeaders.ContentType, "text/event-stream")
    private val openAi = ProviderSettings(baseUrl = "https://gw.test/v1", chatModel = "qwen3.6-flash",
        imageModel = "", imageEndpoint = "", imageProtocol = ImageLayer.NONE)

    private fun provider(engine: MockEngine) = ProviderClient(HttpClient(engine)).also { clients += it }
    private fun body(request: HttpRequestData) = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
    @After fun close() { clients.forEach { it.close() } }

    @Test fun chatCompletionsReasoningContentStaysOutOfTheReply() = runTest {
        val stream = listOf(
            """{"choices":[{"delta":{"reasoning_content":"Let me "}}]}""",
            """{"choices":[{"delta":{"reasoning_content":"multiply."}}]}""",
            """{"choices":[{"delta":{"reasoning":" Check."}}]}""",
            """{"choices":[{"delta":{"content":"391"},"finish_reason":"stop"}]}""",
        ).joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"
        val client = provider(MockEngine { respond(stream, headers = sse) })
        val events = mutableListOf<String>()
        val reply = client.chat(openAi, "k", listOf(ApiMessage("user", "17*23")),
            onOpen = { events += "open" }, onReasoning = { events += "reasoning:$it" }) { events += "text:$it" }
        assertEquals("391", reply.text)
        assertEquals(listOf("open", "reasoning:Let me ", "reasoning:Let me multiply.", "reasoning:Let me multiply. Check.", "text:391"), events)
    }

    @Test fun anthropicThinkingIsReportedAndSummariesAreRequestedOnlyWhenAsked() = runTest {
        var sent: HttpRequestData? = null
        val frames = listOf(
            """{"type":"message_start","message":{"model":"claude-opus-5-5"}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Plan the answer."}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig"}}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Done."}}""",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""",
            """{"type":"message_stop"}""",
        ).joinToString("") { "event: x\ndata: $it\n\n" }
        val client = provider(MockEngine { request -> sent = request; respond(frames, headers = sse) })
        val anthropic = openAi.copy(baseUrl = "https://api.anthropic.com/v1", chatModel = "claude-opus-5-5", api = ApiLayer.ANTHROPIC)
        var reasoning = ""
        val reply = client.chat(anthropic, "k", listOf(ApiMessage("user", "hi")), thinkingSummaries = true, onReasoning = { reasoning = it })
        assertEquals("Done.", reply.text)
        assertEquals("Plan the answer.", reasoning)
        val thinking = body(sent!!)["thinking"]!!.jsonObject
        assertEquals("adaptive", thinking["type"]!!.jsonPrimitive.content)
        assertEquals("summarized", thinking["display"]!!.jsonPrimitive.content)
        client.chat(anthropic, "k", listOf(ApiMessage("user", "hi")))
        assertNull(body(sent!!)["thinking"])
    }

    @Test fun responsesReasoningSummaryIsReported() = runTest {
        val frames = listOf(
            """{"type":"response.created","response":{"model":"gpt-5"}}""",
            """{"type":"response.reasoning_summary_part.added"}""",
            """{"type":"response.reasoning_summary_text.delta","delta":"Thinking about it."}""",
            """{"type":"response.reasoning_summary_part.added"}""",
            """{"type":"response.reasoning_summary_text.delta","delta":"Second part."}""",
            """{"type":"response.output_text.delta","delta":"Answer"}""",
            """{"type":"response.completed","response":{"model":"gpt-5"}}""",
        ).joinToString("") { "data: $it\n\n" }
        val client = provider(MockEngine { respond(frames, headers = sse) })
        var reasoning = ""
        val reply = client.chat(openAi.copy(api = ApiLayer.RESPONSES), "k", listOf(ApiMessage("user", "hi")), onReasoning = { reasoning = it })
        assertEquals("Answer", reply.text)
        assertEquals("Thinking about it.\n\nSecond part.", reasoning)
    }

    @Test fun anthropicCatalogFlagsAdaptiveThinking() {
        val item = Json.parseToJsonElement("""{"type":"model","id":"claude-opus-5-5","capabilities":{"thinking":{"supported":true,"types":{"enabled":{"supported":false},"adaptive":{"supported":true}}}}}""").jsonObject
        assertTrue(ModelCatalog.parse(item, "api.anthropic.com")!!.adaptiveThinking == true)
        assertFalse(ModelCatalog.parse(Json.parseToJsonElement("""{"id":"m"}""").jsonObject, "x")!!.adaptiveThinking == true)
    }
}
