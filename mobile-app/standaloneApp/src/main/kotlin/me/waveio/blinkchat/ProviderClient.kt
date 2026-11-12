package me.waveio.blinkchat

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.plugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.takeFrom
import io.ktor.util.AttributeKey
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.Closeable
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.util.Base64

/** Only localization keys travel to the UI; provider bodies and keys never do. */
private const val KEY_SETTINGS = "standalone.error.settings"
private const val KEY_AUTH = "standalone.error.auth"
private const val KEY_RATE = "standalone.error.rate"
private const val KEY_PROVIDER = "standalone.error.provider"
private const val KEY_RESPONSE = "standalone.error.response"
private const val KEY_NETWORK = "standalone.error.network"

private const val MAX_IMAGE_BYTES = 20 * 1024 * 1024
private const val MAX_BASE64_CHARS = (MAX_IMAGE_BYTES / 3 + 1) * 4
private const val MAX_IMAGE_RESPONSE_BYTES = 32 * 1024 * 1024
private const val MAX_JSON_BYTES = 8 * 1024 * 1024
private const val MAX_REPLY_CHARS = 4 * 1024 * 1024
private const val MAX_TOOL_CHARS = 2 * 1024 * 1024
private const val MAX_TOOL_CALLS = 64
private const val MAX_STREAM_CHARS = 8 * 1024 * 1024
private const val MAX_LINE_CHARS = 1024 * 1024
private const val MAX_EVENT_CHARS = 1024 * 1024
private const val MAX_MODELS = 512
private const val MAX_ID_CHARS = 256
private const val MAX_URL_CHARS = 2048
private const val DONE = "[DONE]"
private val IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/webp")
private val OPAQUE_TYPES = setOf("application/octet-stream", "binary/octet-stream")

/**
 * Direct OpenAI-compatible provider access for Blink Chat. Requests never follow
 * redirects and the API key is attached per call, so an image origin can never
 * receive it. Every failure leaves this class as a [ProviderFailure] key.
 */
class ProviderClient(
    private val client: HttpClient = HttpClient(OkHttp) {
        followRedirects = false
        install(HttpTimeout) {
            requestTimeoutMillis = 180_000
            socketTimeoutMillis = 90_000
            connectTimeoutMillis = 15_000
        }
    },
) : Closeable {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val transport = client.config { followRedirects = false; expectSuccess = false }

    init {
        // HttpSend runs after injected defaultRequest plugins, so inherited
        // Authorization headers are dropped before the key is re-added per call.
        transport.plugin(HttpSend).intercept { request ->
            request.headers.remove(HttpHeaders.Authorization)
            request.headers.remove(ANTHROPIC_KEY_HEADER)
            request.headers.remove(ANTHROPIC_VERSION_HEADER)
            request.attributes.getOrNull(Bearer)?.let { key ->
                request.headers.append(HttpHeaders.Authorization, "Bearer $key")
            }
            request.attributes.getOrNull(AnthropicKey)?.let { key ->
                request.headers.append(ANTHROPIC_KEY_HEADER, key)
                request.headers.append(ANTHROPIC_VERSION_HEADER, ANTHROPIC_VERSION)
            }
            execute(request)
        }
    }

    /** Model IDs only; kept for callers that need no metadata. */
    suspend fun models(settings: ProviderSettings, apiKey: String): List<String> = catalog(settings, apiKey).map { it.id }

    /** The provider's model list with whatever metadata it publishes, read with the layer's own auth. */
    suspend fun catalog(settings: ProviderSettings, apiKey: String): List<ModelInfo> = guarded {
        validateSettings(settings)
        val key = checkedKey(apiKey)
        listModels(settings.baseUrl, key, settings.api == ApiLayer.ANTHROPIC)
            ?: throw ProviderFailure(KEY_RESPONSE)
    }

    /**
     * Finds which API layers an endpoint serves and reads its catalog. Route probes are POSTs
     * with an empty JSON body: an existing route rejects them as invalid (400/422) before any
     * model runs, so nothing is billed, while a missing route answers 404/405. The key is only
     * sent to the typed origin, and redirects are never followed.
     */
    suspend fun discover(input: String, apiKey: String): Discovery = guarded {
        val key = checkedKey(apiKey.trim())
        if (key.isBlank()) throw ProviderFailure("standalone.error.key")
        val candidates = ModelCatalog.candidateBases(input)
        if (candidates.isEmpty()) throw ProviderFailure(KEY_SETTINGS)
        candidates.forEach(::validateEndpoint)
        var authRejected = false
        for (base in candidates) {
            val probe = probe(base, key)
            if (probe.authRejected) authRejected = true
            if (probe.apis.isEmpty() && probe.openAiModels == null && probe.anthropicModels == null) continue
            val host = Url(base).host.lowercase()
            val apis = probe.apis.toMutableList()
            if (apis.isEmpty()) {
                // Only the catalog answered; its shape still tells which protocol family this is.
                if (probe.anthropicModels != null) apis += ApiLayer.ANTHROPIC
                if (probe.openAiModels != null) apis += ApiLayer.OPENAI_CHAT
            }
            val recommended = when {
                host == "api.anthropic.com" && ApiLayer.ANTHROPIC in apis -> ApiLayer.ANTHROPIC
                host == "api.openai.com" && ApiLayer.RESPONSES in apis -> ApiLayer.RESPONSES
                ApiLayer.OPENAI_CHAT in apis -> ApiLayer.OPENAI_CHAT
                else -> apis.first()
            }
            val primary = if (recommended == ApiLayer.ANTHROPIC) probe.anthropicModels else probe.openAiModels
            val secondary = if (recommended == ApiLayer.ANTHROPIC) probe.openAiModels else probe.anthropicModels
            val models = ModelCatalog.merge(primary.orEmpty(), secondary.orEmpty())
            val qwen = host.endsWith("qwencloudapi.com") || host.startsWith("dashscope")
            val imageProtocol = when {
                qwen -> ImageLayer.QWEN
                probe.images -> ImageLayer.OPENAI
                else -> ImageLayer.NONE
            }
            val imageEndpoint = when (imageProtocol) {
                ImageLayer.QWEN -> "https://$host/api/v1/services/aigc/multimodal-generation/generation"
                ImageLayer.OPENAI -> endpoint(base, "images/generations")
                else -> ""
            }
            return@guarded Discovery(base, apis.distinct().sortedBy { ApiLayer.all.indexOf(it) }, recommended, models,
                imageProtocol, imageEndpoint, ModelCatalog.suggestName(base))
        }
        throw ProviderFailure(if (authRejected) KEY_AUTH else "standalone.setup.notFound")
    }

    private class Probe(
        val apis: List<String>,
        val openAiModels: List<ModelInfo>?,
        val anthropicModels: List<ModelInfo>?,
        val images: Boolean,
        val authRejected: Boolean,
    )

    private suspend fun probe(base: String, key: String): Probe = coroutineScope {
        val openAiModels = async { runCatching { listModels(base, key, anthropic = false) } }
        val anthropicModels = async { runCatching { listModels(base, key, anthropic = true) } }
        val chat = async { routeStatus(base, "chat/completions", key, anthropic = false) }
        val responses = async { routeStatus(base, "responses", key, anthropic = false) }
        val messages = async { routeStatus(base, "messages", key, anthropic = true) }
        val images = async { routeStatus(base, "images/generations", key, anthropic = false) }
        val statuses = listOf(chat.await(), responses.await(), messages.await(), images.await())
        val failures = listOf(openAiModels.await(), anthropicModels.await()).mapNotNull { it.exceptionOrNull() as? ProviderFailure }
        val apis = buildList {
            if (routeExists(statuses[2])) add(ApiLayer.ANTHROPIC)
            if (routeExists(statuses[0])) add(ApiLayer.OPENAI_CHAT)
            if (routeExists(statuses[1])) add(ApiLayer.RESPONSES)
        }
        Probe(apis, openAiModels.await().getOrNull(), anthropicModels.await().getOrNull(), routeExists(statuses[3]),
            statuses.any { it == 401 || it == 403 } || failures.any { it.key == KEY_AUTH })
    }

    /**
     * 400/415/422 mean "route exists, body invalid". 401/403 prove nothing: many gateways
     * authenticate before routing, and the Anthropic-style probe carries no Bearer header.
     */
    private fun routeExists(status: Int?): Boolean = status != null && status in setOf(400, 415, 422)

    private suspend fun routeStatus(base: String, path: String, key: String, anthropic: Boolean): Int? = try {
        transport.prepareRequest {
            apiCall(HttpMethod.Post, endpoint(base, path), key, anthropic)
            contentType(ContentType.Application.Json)
            setBody("{}")
        }.execute { response ->
            // Only the status matters; the body is drained within a small bound.
            runCatching { boundedBytes(response, 64 * 1024) }
            response.status.value
        }
    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }

    /**
     * Reads a model list. Null means the endpoint answered with something that is not a list in
     * this auth style's shape (for example an Anthropic listing requested as OpenAI).
     */
    private suspend fun listModels(base: String, key: String, anthropic: Boolean): List<ModelInfo>? {
        val host = Url(base).host.lowercase()
        val models = LinkedHashMap<String, ModelInfo>()
        var after: String? = null
        // Anthropic pages with after_id; other catalogs answer in one page.
        for (page in 0 until 8) {
            val address = endpoint(base, "models") + if (anthropic) "?limit=1000" + (after?.let { "&after_id=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "") else ""
            val root = transport.prepareRequest {
                method = HttpMethod.Get
                url.takeFrom(address)
                if (anthropic) attributes.put(AnthropicKey, key) else attributes.put(Bearer, key)
            }.execute { response ->
                checkStatus(response)
                if (response.contentType()?.withoutParameters() != ContentType.Application.Json &&
                    response.contentType()?.contentSubtype?.endsWith("json") != true) throw ProviderFailure(KEY_RESPONSE, response.status.value)
                parseObject(boundedBytes(response, MAX_JSON_BYTES).decodeToString(), response.status.value)
            }
            val items = (root["data"] ?: root["models"]) as? JsonArray ?: return null
            val anthropicShape = root.containsKey("has_more") || items.any { (it as? JsonObject)?.get("type").stringOrNull() == "model" }
            if (anthropic && !anthropicShape) return null
            for (item in items) {
                val info = (item as? JsonObject)?.let { ModelCatalog.parse(it, host) } ?: continue
                models.putIfAbsent(info.id, info)
                if (models.size >= MAX_MODELS) return models.values.toList()
            }
            val more = (root["has_more"] as? JsonPrimitive)?.contentOrNull == "true"
            after = root["last_id"].stringOrNull()
            if (!anthropic || !more || after == null) break
        }
        return models.values.toList()
    }

    /**
     * One streamed model round in the account's [ProviderSettings.api] protocol. [effort] is a
     * published stop for this model, or null to keep the provider's default; [maxOutput] is the
     * catalog's output cap, used where the protocol requires one.
     */
    suspend fun chat(
        settings: ProviderSettings,
        apiKey: String,
        messages: List<ApiMessage>,
        tools: List<JsonObject> = emptyList(),
        effort: String? = null,
        maxOutput: Long? = null,
        onDelta: (String) -> Unit = {},
    ): ApiReply = guarded {
        validateSettings(settings)
        if (settings.chatModel.isBlank()) throw ProviderFailure(KEY_SETTINGS)
        val key = checkedKey(apiKey)
        val level = effort?.takeIf { it in KNOWN_EFFORTS }
        val anthropic = settings.api == ApiLayer.ANTHROPIC
        val (path, payload) = when (settings.api) {
            ApiLayer.ANTHROPIC -> "messages" to anthropicPayload(settings, messages, tools, level, maxOutput)
            ApiLayer.RESPONSES -> "responses" to responsesPayload(settings, messages, tools, level)
            else -> "chat/completions" to buildJsonObject {
                put("model", settings.chatModel)
                put("stream", true)
                put("messages", buildJsonArray { messages.forEach { add(encodeMessage(it)) } })
                if (tools.isNotEmpty()) put("tools", JsonArray(tools))
                if (level != null) put("reasoning_effort", level)
            }
        }
        transport.prepareRequest {
            apiCall(HttpMethod.Post, endpoint(settings.baseUrl, path), key, anthropic)
            contentType(ContentType.Application.Json)
            headers.append(HttpHeaders.Accept, ContentType.Text.EventStream.toString())
            // Small SSE frames must not wait for a proxy's compression buffer.
            headers.append(HttpHeaders.AcceptEncoding, "identity")
            setBody(json.encodeToString(payload))
        }.execute { response ->
            checkStatus(response)
            val status = response.status.value
            val jsonBody = response.contentType()?.withoutParameters() == ContentType.Application.Json
            when (settings.api) {
                ApiLayer.ANTHROPIC -> if (jsonBody) throw ProviderFailure(KEY_RESPONSE, status) else anthropicStream(response, settings, onDelta)
                ApiLayer.RESPONSES -> if (jsonBody) throw ProviderFailure(KEY_RESPONSE, status) else responsesStream(response, settings, onDelta)
                else -> if (jsonBody) nonStreamReply(boundedBytes(response, MAX_JSON_BYTES).decodeToString(), settings, status)
                    else streamReply(response, settings, onDelta)
            }
        }
    }

    /** Tool definitions are kept in Chat Completions form and translated per protocol. */
    private fun functionOf(tool: JsonObject): JsonObject? = tool["function"] as? JsonObject

    private fun anthropicPayload(settings: ProviderSettings, messages: List<ApiMessage>, tools: List<JsonObject>, effort: String?, maxOutput: Long?) = buildJsonObject {
        put("model", settings.chatModel)
        put("max_tokens", (maxOutput ?: 16_000L).coerceIn(1_024L, 64_000L))
        put("stream", true)
        val system = messages.filter { it.role == "system" }.joinToString("\n\n") { it.text }
        if (system.isNotBlank()) put("system", system)
        put("messages", buildJsonArray {
            var pendingResults = mutableListOf<JsonObject>()
            fun flushResults() {
                if (pendingResults.isEmpty()) return
                // Every result of one tool turn goes back in a single user message.
                add(buildJsonObject { put("role", "user"); put("content", JsonArray(pendingResults)) })
                pendingResults = mutableListOf()
            }
            for (message in messages) {
                when (message.role) {
                    "system" -> Unit
                    "tool" -> pendingResults += buildJsonObject {
                        put("type", "tool_result")
                        put("tool_use_id", message.toolCallId.orEmpty())
                        put("content", message.text)
                        if (message.text.startsWith("{\"error\":")) put("is_error", true)
                    }
                    else -> {
                        val content = message.native ?: buildJsonArray {
                            message.images.forEach { image -> anthropicImage(image)?.let(::add) }
                            if (message.text.isNotEmpty()) add(buildJsonObject { put("type", "text"); put("text", message.text) })
                            message.toolCalls.forEach { call ->
                                add(buildJsonObject {
                                    put("type", "tool_use"); put("id", call.id); put("name", call.name)
                                    put("input", runCatching { json.parseToJsonElement(call.arguments) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap()))
                                })
                            }
                        }
                        // An image-only assistant turn has no text for the model; the Messages API
                        // rejects empty content, so such a turn is left out.
                        if (content.isNotEmpty()) {
                            flushResults()
                            add(buildJsonObject {
                                put("role", if (message.role == "assistant") "assistant" else "user")
                                put("content", content)
                            })
                        }
                    }
                }
            }
            flushResults()
        })
        if (tools.isNotEmpty()) put("tools", buildJsonArray {
            tools.mapNotNull(::functionOf).forEach { function ->
                add(buildJsonObject {
                    put("name", function["name"] ?: JsonNull)
                    function["description"]?.let { put("description", it) }
                    put("input_schema", function["parameters"] ?: buildJsonObject { put("type", "object") })
                })
            }
        })
        if (effort != null) putJsonObject("output_config") { put("effort", effort) }
    }

    /** `data:<mime>;base64,<data>` becomes an Anthropic base64 image block. */
    private fun anthropicImage(dataUrl: String): JsonObject? {
        val match = Regex("^data:([^;,]+);base64,(.*)$", RegexOption.DOT_MATCHES_ALL).find(dataUrl) ?: return null
        return buildJsonObject {
            put("type", "image")
            putJsonObject("source") {
                put("type", "base64"); put("media_type", match.groupValues[1]); put("data", match.groupValues[2])
            }
        }
    }

    private suspend fun anthropicStream(response: HttpResponse, settings: ProviderSettings, onDelta: (String) -> Unit): ApiReply {
        val status = response.status.value
        val reader = SseReader(response.bodyAsChannel(), status)
        val blocks = sortedMapOf<Int, AnthropicBlock>()
        val text = StringBuilder()
        var model: String? = null
        var stop: String? = null
        var done = false
        while (true) {
            val data = reader.next() ?: break
            val event = parseObject(data, status)
            when (event["type"].stringOrNull()) {
                "message_start" -> model = ((event["message"] as? JsonObject)?.get("model")).stringOrNull()
                "content_block_start" -> {
                    val index = indexOf(event, status)
                    val block = event["content_block"] as? JsonObject ?: throw ProviderFailure(KEY_RESPONSE, status)
                    if (blocks.size >= MAX_TOOL_CALLS * 2 && index !in blocks) throw ProviderFailure(KEY_RESPONSE, status)
                    val started = AnthropicBlock(block)
                    blocks[index] = started
                    // The API starts blocks empty, but compatible gateways may put content here.
                    val initial = when (started.type) { "text" -> block["text"]; "thinking" -> block["thinking"]; else -> null }.stringOrNull().orEmpty()
                    if (initial.length > MAX_TOOL_CHARS) throw ProviderFailure(KEY_RESPONSE, status)
                    started.body.append(initial)
                    if (started.type == "text" && initial.isNotEmpty()) { text.append(initial); onDelta(text.toString()) }
                }
                "content_block_delta" -> {
                    val block = blocks[indexOf(event, status)] ?: throw ProviderFailure(KEY_RESPONSE, status)
                    val delta = event["delta"] as? JsonObject ?: continue
                    val piece = when (delta["type"].stringOrNull()) {
                        "text_delta" -> delta["text"].stringOrNull()?.also { fragment ->
                            if (text.length + fragment.length > MAX_REPLY_CHARS) throw ProviderFailure(KEY_RESPONSE, status)
                            text.append(fragment); onDelta(text.toString())
                        }
                        "input_json_delta" -> delta["partial_json"].stringOrNull()
                        "thinking_delta" -> delta["thinking"].stringOrNull()
                        "signature_delta" -> { block.signature.append(delta["signature"].stringOrNull().orEmpty()); null }
                        else -> null
                    }
                    if (piece != null) {
                        if (block.body.length + piece.length > MAX_TOOL_CHARS) throw ProviderFailure(KEY_RESPONSE, status)
                        block.body.append(piece)
                    }
                }
                "message_delta" -> stop = ((event["delta"] as? JsonObject)?.get("stop_reason")).stringOrNull() ?: stop
                "message_stop" -> { done = true; break }
                "error" -> throw ProviderFailure(KEY_PROVIDER, status)
            }
        }
        if (!done) throw ProviderFailure(KEY_RESPONSE, status)
        if (stop == "refusal" && text.isEmpty()) throw ProviderFailure("standalone.error.refusal", status)
        val native = JsonArray(blocks.values.mapNotNull { it.build(json) })
        val calls = blocks.values.filter { it.type == "tool_use" }.map { block ->
            val id = block.start["id"].stringOrNull()?.takeIf { it.isNotBlank() } ?: throw ProviderFailure(KEY_RESPONSE, status)
            val name = block.start["name"].stringOrNull()?.takeIf { it.isNotBlank() } ?: throw ProviderFailure(KEY_RESPONSE, status)
            if (id.length > MAX_ID_CHARS || name.length > MAX_ID_CHARS) throw ProviderFailure(KEY_RESPONSE, status)
            ApiToolCall(id, name, block.body.toString().ifBlank { "{}" })
        }
        if (text.isEmpty() && calls.isEmpty()) throw ProviderFailure(KEY_RESPONSE, status)
        return ApiReply(text.toString(), model?.takeIf { it.isNotBlank() } ?: settings.chatModel, calls, native)
    }

    private fun indexOf(event: JsonObject, status: Int): Int =
        (event["index"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.takeIf { it in 0 until 1024 }
            ?: throw ProviderFailure(KEY_RESPONSE, status)

    private fun responsesPayload(settings: ProviderSettings, messages: List<ApiMessage>, tools: List<JsonObject>, effort: String?) = buildJsonObject {
        put("model", settings.chatModel)
        put("stream", true)
        // Nothing is kept server-side; every request carries the whole visible history.
        put("store", false)
        val system = messages.filter { it.role == "system" }.joinToString("\n\n") { it.text }
        if (system.isNotBlank()) put("instructions", system)
        put("input", buildJsonArray {
            for (message in messages) when (message.role) {
                "system" -> Unit
                "tool" -> add(buildJsonObject {
                    put("type", "function_call_output"); put("call_id", message.toolCallId.orEmpty()); put("output", message.text)
                })
                "assistant" -> {
                    if (message.text.isNotEmpty()) add(buildJsonObject { put("role", "assistant"); put("content", message.text) })
                    message.toolCalls.forEach { call ->
                        add(buildJsonObject {
                            put("type", "function_call"); put("call_id", call.id); put("name", call.name); put("arguments", call.arguments)
                        })
                    }
                }
                else -> add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        if (message.text.isNotEmpty()) add(buildJsonObject { put("type", "input_text"); put("text", message.text) })
                        message.images.forEach { image -> add(buildJsonObject { put("type", "input_image"); put("image_url", image) }) }
                    })
                })
            }
        })
        if (tools.isNotEmpty()) put("tools", buildJsonArray {
            tools.mapNotNull(::functionOf).forEach { function ->
                add(buildJsonObject {
                    put("type", "function")
                    put("name", function["name"] ?: JsonNull)
                    function["description"]?.let { put("description", it) }
                    put("parameters", function["parameters"] ?: buildJsonObject { put("type", "object") })
                })
            }
        })
        if (effort != null) putJsonObject("reasoning") { put("effort", effort) }
    }

    private suspend fun responsesStream(response: HttpResponse, settings: ProviderSettings, onDelta: (String) -> Unit): ApiReply {
        val status = response.status.value
        val reader = SseReader(response.bodyAsChannel(), status)
        val text = StringBuilder()
        val calls = mutableListOf<ApiToolCall>()
        var model: String? = null
        var done = false
        while (true) {
            val data = reader.next() ?: break
            if (data.trim() == DONE) break
            val event = parseObject(data, status)
            when (event["type"].stringOrNull()) {
                "response.created" -> model = ((event["response"] as? JsonObject)?.get("model")).stringOrNull()
                "response.output_text.delta" -> event["delta"].stringOrNull()?.let { fragment ->
                    if (text.length + fragment.length > MAX_REPLY_CHARS) throw ProviderFailure(KEY_RESPONSE, status)
                    text.append(fragment); onDelta(text.toString())
                }
                // The finished item carries the complete arguments; deltas are not needed.
                "response.output_item.done" -> (event["item"] as? JsonObject)?.takeIf { it["type"].stringOrNull() == "function_call" }?.let { item ->
                    val id = item["call_id"].stringOrNull()?.takeIf { it.isNotBlank() } ?: throw ProviderFailure(KEY_RESPONSE, status)
                    val name = item["name"].stringOrNull()?.takeIf { it.isNotBlank() } ?: throw ProviderFailure(KEY_RESPONSE, status)
                    val arguments = item["arguments"].stringOrNull().orEmpty()
                    if (calls.size >= MAX_TOOL_CALLS || id.length > MAX_ID_CHARS || name.length > MAX_ID_CHARS || arguments.length > MAX_TOOL_CHARS)
                        throw ProviderFailure(KEY_RESPONSE, status)
                    calls += ApiToolCall(id, name, arguments.ifBlank { "{}" })
                }
                "response.completed", "response.incomplete" -> {
                    model = ((event["response"] as? JsonObject)?.get("model")).stringOrNull() ?: model
                    done = true; break
                }
                "response.failed", "error" -> throw ProviderFailure(KEY_PROVIDER, status)
                "response.refusal.done" -> if (text.isEmpty()) throw ProviderFailure("standalone.error.refusal", status)
            }
        }
        if (!done) throw ProviderFailure(KEY_RESPONSE, status)
        if (text.isEmpty() && calls.isEmpty()) throw ProviderFailure(KEY_RESPONSE, status)
        return ApiReply(text.toString(), model?.takeIf { it.isNotBlank() } ?: settings.chatModel, calls)
    }

    suspend fun generateImage(settings: ProviderSettings, apiKey: String, prompt: String): GeneratedImage = guarded {
        validateSettings(settings)
        if (settings.imageProtocol == ImageLayer.NONE) throw ProviderFailure(KEY_SETTINGS)
        val key = checkedKey(apiKey)
        val qwen = settings.imageProtocol == "qwen"
        val payload = if (qwen) {
            buildJsonObject {
                put("model", settings.imageModel)
                put("input", buildJsonObject {
                    put("messages", buildJsonArray {
                        add(buildJsonObject {
                            put("role", "user")
                            put("content", buildJsonArray { add(buildJsonObject { put("text", prompt) }) })
                        })
                    })
                })
                put("parameters", buildJsonObject { put("size", "1024*1024"); put("n", 1) })
            }
        } else {
            buildJsonObject {
                put("model", settings.imageModel)
                put("prompt", prompt)
                put("n", 1)
                put("size", "1024x1024")
            }
        }
        val root = transport.prepareRequest {
            apiCall(HttpMethod.Post, settings.imageEndpoint.trimEnd('/'), key)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(payload))
        }.execute { response ->
            checkStatus(response)
            parseObject(boundedBytes(response, MAX_IMAGE_RESPONSE_BYTES).decodeToString(), response.status.value)
        }
        if (root.containsKey("error")) throw ProviderFailure(KEY_PROVIDER)
        if (qwen) downloadImage(qwenImageUrl(root)) else openAiImage(root)
    }

    override fun close() {
        transport.close()
        client.close()
    }

    private fun encodeMessage(message: ApiMessage): JsonObject = buildJsonObject {
        put("role", message.role)
        if (message.images.isEmpty()) {
            val emptyToolTurn = message.toolCalls.isNotEmpty() && message.text.isEmpty()
            put("content", if (emptyToolTurn) JsonNull else JsonPrimitive(message.text))
        } else {
            put("content", buildJsonArray {
                if (message.text.isNotEmpty()) {
                    add(buildJsonObject { put("type", "text"); put("text", message.text) })
                }
                message.images.forEach { image ->
                    add(buildJsonObject {
                        put("type", "image_url")
                        put("image_url", buildJsonObject { put("url", image) })
                    })
                }
            })
        }
        if (message.toolCalls.isNotEmpty()) {
            put("tool_calls", buildJsonArray {
                message.toolCalls.forEach { call ->
                    add(buildJsonObject {
                        put("id", call.id)
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", call.name)
                            put("arguments", call.arguments)
                        })
                    })
                }
            })
        }
        message.toolCallId?.let { put("tool_call_id", it) }
    }

    private suspend fun streamReply(
        response: HttpResponse, settings: ProviderSettings, onDelta: (String) -> Unit,
    ): ApiReply {
        val status = response.status.value
        val reader = SseReader(response.bodyAsChannel(), status)
        val text = StringBuilder()
        val calls = sortedMapOf<Int, ToolAssembly>()
        var model: String? = null
        var done = false
        var finished = false
        while (true) {
            val data = reader.next() ?: break
            if (data.trim() == DONE) { done = true; break }
            val chunk = parseObject(data, status)
            if (chunk.containsKey("error")) throw ProviderFailure(KEY_PROVIDER, status)
            if (model == null) model = chunk["model"].stringOrNull()?.takeIf { it.isNotBlank() }
            val choices = chunk["choices"] as? JsonArray ?: continue
            for (choice in choices) {
                val obj = choice as? JsonObject ?: continue
                if (!obj["finish_reason"].stringOrNull().isNullOrBlank()) finished = true
                val delta = obj["delta"] as? JsonObject ?: continue
                // reasoning_content is deliberately not part of the visible reply.
                val content = delta["content"].stringOrNull()
                if (!content.isNullOrEmpty()) {
                    if (text.length + content.length > MAX_REPLY_CHARS) throw ProviderFailure(KEY_RESPONSE, status)
                    text.append(content)
                    onDelta(text.toString())
                }
                (delta["tool_calls"] as? JsonArray)?.forEach { element ->
                    assembleToolCall(element, calls, status)
                }
            }
        }
        // EOF without [DONE] or a finish_reason is a truncated stream, not an answer.
        if (!done && !finished) throw ProviderFailure(KEY_RESPONSE, status)
        val toolCalls = calls.values.map { it.build(status) }
        if (text.isEmpty() && toolCalls.isEmpty()) throw ProviderFailure(KEY_RESPONSE, status)
        return ApiReply(text.toString(), model ?: settings.chatModel, toolCalls)
    }

    private fun assembleToolCall(element: JsonElement, calls: MutableMap<Int, ToolAssembly>, status: Int) {
        val obj = element as? JsonObject ?: throw ProviderFailure(KEY_RESPONSE, status)
        // Providers send index as a JSON number; a missing index means a single call.
        val index = (obj["index"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
        if (index < 0 || index >= MAX_TOOL_CALLS) throw ProviderFailure(KEY_RESPONSE, status)
        val assembly = calls.getOrPut(index) {
            if (calls.size >= MAX_TOOL_CALLS) throw ProviderFailure(KEY_RESPONSE, status)
            ToolAssembly()
        }
        if (assembly.id == null) assembly.id = obj["id"].stringOrNull()?.takeIf { it.isNotBlank() }
        val function = obj["function"] as? JsonObject ?: return
        function["name"].stringOrNull()?.let { fragment ->
            if (assembly.name.length + fragment.length > MAX_ID_CHARS) throw ProviderFailure(KEY_RESPONSE, status)
            assembly.name.append(fragment)
        }
        function["arguments"].stringOrNull()?.let { fragment ->
            if (assembly.arguments.length + fragment.length > MAX_TOOL_CHARS) throw ProviderFailure(KEY_RESPONSE, status)
            assembly.arguments.append(fragment)
        }
    }

    private fun nonStreamReply(body: String, settings: ProviderSettings, status: Int): ApiReply {
        val root = parseObject(body, status)
        if (root.containsKey("error")) throw ProviderFailure(KEY_PROVIDER, status)
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: throw ProviderFailure(KEY_RESPONSE, status)
        val message = choice["message"] as? JsonObject ?: throw ProviderFailure(KEY_RESPONSE, status)
        val text = contentText(message["content"])
        if (text.length > MAX_REPLY_CHARS) throw ProviderFailure(KEY_RESPONSE, status)
        val callData = message["tool_calls"] as? JsonArray
        if (callData != null && callData.size > MAX_TOOL_CALLS) throw ProviderFailure(KEY_RESPONSE, status)
        val toolCalls = callData?.map { completeToolCall(it, status) }.orEmpty()
        if (text.isEmpty() && toolCalls.isEmpty()) throw ProviderFailure(KEY_RESPONSE, status)
        val model = root["model"].stringOrNull()?.takeIf { it.isNotBlank() } ?: settings.chatModel
        return ApiReply(text, model, toolCalls)
    }

    private fun completeToolCall(element: JsonElement, status: Int): ApiToolCall {
        val obj = element as? JsonObject ?: throw ProviderFailure(KEY_RESPONSE, status)
        val id = obj["id"].stringOrNull()?.takeIf { it.isNotBlank() } ?: throw ProviderFailure(KEY_RESPONSE, status)
        val function = obj["function"] as? JsonObject ?: throw ProviderFailure(KEY_RESPONSE, status)
        val name = function["name"].stringOrNull()?.takeIf { it.isNotBlank() } ?: throw ProviderFailure(KEY_RESPONSE, status)
        val arguments = function["arguments"].stringOrNull().orEmpty()
        if (arguments.length > MAX_TOOL_CHARS || id.length > MAX_ID_CHARS || name.length > MAX_ID_CHARS || id.any(Char::isISOControl) || name.any(Char::isISOControl)) throw ProviderFailure(KEY_RESPONSE, status)
        return ApiToolCall(id, name, arguments.ifBlank { "{}" })
    }

    private fun contentText(content: JsonElement?): String = when (content) {
        is JsonPrimitive -> content.contentOrNull.orEmpty()
        is JsonArray -> content.mapNotNull { part ->
            val obj = part as? JsonObject ?: return@mapNotNull null
            if (obj["type"].stringOrNull() == "text") obj["text"].stringOrNull() else null
        }.joinToString("")
        else -> ""
    }

    private fun qwenImageUrl(root: JsonObject): String {
        val choices = (root["output"] as? JsonObject)?.get("choices") as? JsonArray
            ?: throw ProviderFailure(KEY_RESPONSE)
        for (choice in choices) {
            val content = ((choice as? JsonObject)?.get("message") as? JsonObject)?.get("content") as? JsonArray
                ?: continue
            for (part in content) {
                val url = (part as? JsonObject)?.get("image").stringOrNull()
                if (!url.isNullOrBlank()) return url
            }
        }
        throw ProviderFailure(KEY_RESPONSE)
    }

    private suspend fun openAiImage(root: JsonObject): GeneratedImage {
        val first = (root["data"] as? JsonArray)?.firstOrNull() as? JsonObject ?: throw ProviderFailure(KEY_RESPONSE)
        val encoded = first["b64_json"].stringOrNull()
        if (!encoded.isNullOrBlank()) return decodeImage(encoded)
        val url = first["url"].stringOrNull()?.takeIf { it.isNotBlank() } ?: throw ProviderFailure(KEY_RESPONSE)
        return downloadImage(url)
    }

    private fun decodeImage(encoded: String): GeneratedImage {
        if (encoded.length > MAX_BASE64_CHARS) throw ProviderFailure(KEY_RESPONSE)
        val bytes = try {
            Base64.getMimeDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            throw ProviderFailure(KEY_RESPONSE)
        }
        if (bytes.size > MAX_IMAGE_BYTES) throw ProviderFailure(KEY_RESPONSE)
        return GeneratedImage(bytes, sniffImage(bytes) ?: throw ProviderFailure(KEY_RESPONSE))
    }

    /** Signed CDN links keep their query; credentials and plain HTTP are refused. */
    private suspend fun downloadImage(imageUrl: String): GeneratedImage {
        val parsed = runCatching { Url(imageUrl) }.getOrNull() ?: throw ProviderFailure(KEY_RESPONSE)
        if (parsed.protocol != URLProtocol.HTTPS || parsed.user != null || parsed.password != null ||
            imageUrl.length > MAX_URL_CHARS || imageUrl.any(Char::isISOControl)
        ) {
            throw ProviderFailure(KEY_RESPONSE)
        }
        return transport.prepareRequest {
            method = HttpMethod.Get
            url.takeFrom(parsed)
        }.execute { response ->
            checkStatus(response)
            val status = response.status.value
            val bytes = boundedBytes(response, MAX_IMAGE_BYTES)
            val mime = sniffImage(bytes) ?: throw ProviderFailure(KEY_RESPONSE, status)
            val declared = response.contentType()?.withoutParameters()?.toString()?.lowercase().orEmpty()
            val normalized = if (declared == "image/jpg") "image/jpeg" else declared
            if (normalized.isNotEmpty() && normalized !in IMAGE_TYPES && normalized !in OPAQUE_TYPES) {
                throw ProviderFailure(KEY_RESPONSE, status)
            }
            if (normalized in IMAGE_TYPES && normalized != mime) throw ProviderFailure(KEY_RESPONSE, status)
            GeneratedImage(bytes, mime)
        }
    }

    private fun sniffImage(bytes: ByteArray): String? = when {
        bytes.matches(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "image/png"
        bytes.matches(0, 0xFF, 0xD8, 0xFF) -> "image/jpeg"
        bytes.matches(0, 0x52, 0x49, 0x46, 0x46) && bytes.matches(8, 0x57, 0x45, 0x42, 0x50) -> "image/webp"
        else -> null
    }

    private fun HttpRequestBuilder.apiCall(verb: HttpMethod, endpoint: String, apiKey: String, anthropic: Boolean = false) {
        method = verb
        url.takeFrom(endpoint)
        if (anthropic) attributes.put(AnthropicKey, apiKey) else attributes.put(Bearer, apiKey)
    }

    private fun checkStatus(response: HttpResponse) {
        val status = response.status.value
        if (status in 200..299) return
        // 3xx included: a redirect must never carry the key to another origin.
        throw when (status) {
            401, 403 -> ProviderFailure(KEY_AUTH, status)
            429 -> ProviderFailure(KEY_RATE, status)
            else -> ProviderFailure(KEY_PROVIDER, status)
        }
    }

    private suspend fun boundedBytes(response: HttpResponse, limit: Int): ByteArray {
        val status = response.status.value
        if ((response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0L) > limit) {
            throw ProviderFailure(KEY_RESPONSE, status)
        }
        val channel = response.bodyAsChannel()
        val chunks = mutableListOf<ByteArray>()
        val buffer = ByteArray(8192)
        var count = 0
        while (true) {
            val read = channel.readAvailable(buffer, 0, buffer.size)
            if (read < 0) break
            if (read == 0) continue
            count += read
            if (count > limit) throw ProviderFailure(KEY_RESPONSE, status)
            chunks += buffer.copyOf(read)
        }
        val bytes = ByteArray(count)
        var offset = 0
        for (chunk in chunks) { chunk.copyInto(bytes, offset); offset += chunk.size }
        return bytes
    }

    private fun parseObject(body: String, status: Int): JsonObject = try {
        validateJsonNesting(body)
        json.parseToJsonElement(body) as? JsonObject ?: throw ProviderFailure(KEY_RESPONSE, status)
    } catch (_: SerializationException) {
        throw ProviderFailure(KEY_RESPONSE, status)
    }

    private fun checkedKey(apiKey: String): String {
        if (apiKey.any(Char::isISOControl)) throw ProviderFailure(KEY_AUTH)
        return apiKey
    }

    private suspend inline fun <T> guarded(block: () -> T): T =
        try { block() } catch (failure: Exception) { throw safeFailure(failure) }

    private fun safeFailure(failure: Throwable): Throwable = when (failure) {
        is CancellationException -> failure
        is ProviderFailure -> failure
        is IOException -> ProviderFailure(KEY_NETWORK)
        is SerializationException, is IllegalArgumentException -> ProviderFailure(KEY_RESPONSE)
        else -> ProviderFailure(KEY_NETWORK)
    }

    companion object {
        private val Bearer = AttributeKey<String>("ProviderClient.Bearer")
        private val AnthropicKey = AttributeKey<String>("ProviderClient.AnthropicKey")
        private const val ANTHROPIC_KEY_HEADER = "x-api-key"
        private const val ANTHROPIC_VERSION_HEADER = "anthropic-version"
        private const val ANTHROPIC_VERSION = "2023-06-01"

        /** Called before settings are saved and before every request. */
        fun validateSettings(settings: ProviderSettings) {
            if (settings.api !in ApiLayer.all || settings.imageProtocol !in ImageLayer.all) throw ProviderFailure(KEY_SETTINGS)
            if (settings.chatModel.isNotBlank()) validateModel(settings.chatModel)
            validateEndpoint(settings.baseUrl)
            // A text-only provider carries neither an image model nor an image endpoint.
            if (settings.imageProtocol == ImageLayer.NONE) {
                if (settings.imageModel.isNotBlank()) throw ProviderFailure(KEY_SETTINGS)
            } else {
                validateModel(settings.imageModel)
                validateEndpoint(settings.imageEndpoint)
            }
        }

        private fun validateModel(model: String) {
            if (model.isBlank() || model.length > MAX_ID_CHARS || model.any(Char::isISOControl)) {
                throw ProviderFailure(KEY_SETTINGS)
            }
        }

        internal fun validateEndpoint(endpoint: String) {
            if (endpoint.isBlank() || endpoint.length > MAX_URL_CHARS || endpoint.any(Char::isISOControl) ||
                '?' in endpoint || '#' in endpoint
            ) {
                throw ProviderFailure(KEY_SETTINGS)
            }
            val url = runCatching { Url(endpoint) }.getOrNull() ?: throw ProviderFailure(KEY_SETTINGS)
            if (url.protocol != URLProtocol.HTTPS || url.host.isBlank() || url.user != null || url.password != null) {
                throw ProviderFailure(KEY_SETTINGS)
            }
        }

        private fun endpoint(base: String, path: String): String = base.trimEnd('/') + "/" + path

        private fun JsonElement?.stringOrNull(): String? =
            (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        private fun ByteArray.matches(offset: Int, vararg signature: Int): Boolean {
            if (size < offset + signature.size) return false
            return signature.indices.all { this[offset + it] == signature[it].toByte() }
        }
    }
}

/** One Anthropic content block: its start object plus the streamed text, JSON or signature. */
private class AnthropicBlock(val start: JsonObject) {
    val type: String? = (start["type"] as? JsonPrimitive)?.contentOrNull
    val body = StringBuilder()
    val signature = StringBuilder()

    /** The block exactly as the API must receive it back in the next tool round. */
    fun build(json: Json): JsonObject? = when (type) {
        "text" -> buildJsonObject { put("type", "text"); put("text", body.toString()) }
        "tool_use" -> buildJsonObject {
            put("type", "tool_use"); put("id", start["id"] ?: JsonNull); put("name", start["name"] ?: JsonNull)
            put("input", runCatching { json.parseToJsonElement(body.toString().ifBlank { "{}" }) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap()))
        }
        "thinking" -> buildJsonObject {
            put("type", "thinking"); put("thinking", body.toString()); put("signature", signature.toString())
        }
        "redacted_thinking" -> start
        else -> null
    }
}

/** One assembled tool call; providers fragment both the name and the arguments. */
private class ToolAssembly {
    var id: String? = null
    val name = StringBuilder()
    val arguments = StringBuilder()

    fun build(status: Int): ApiToolCall {
        val identifier = id
        if (identifier.isNullOrBlank() || name.isEmpty()) throw ProviderFailure(KEY_RESPONSE, status)
        return ApiToolCall(identifier, name.toString(), arguments.toString().ifBlank { "{}" })
    }
}

/**
 * SSE framing independent of transport chunks: CRLF/LF, multiline data fields,
 * comments, and a final frame that ends at EOF without a blank line.
 */
private class SseReader(private val channel: ByteReadChannel, private val status: Int) {
    private val lines = StreamLines(channel, status)
    private val data = StringBuilder()
    private var firstLine = true
    private var eof = false

    suspend fun next(): String? {
        while (!eof) {
            val line = lines.next()
            if (line == null) { eof = true; break }
            val text = if (firstLine) line.removePrefix("\uFEFF") else line
            firstLine = false
            if (text.isEmpty()) {
                if (data.isNotEmpty()) {
                    val payload = take()
                    if (payload.isNotBlank()) return payload
                }
                continue
            }
            if (text.startsWith(":")) continue
            val colon = text.indexOf(':')
            val field = if (colon < 0) text else text.substring(0, colon)
            if (field != "data") continue
            val value = if (colon < 0) "" else text.substring(colon + 1).removePrefix(" ")
            if (data.length + value.length + 1 > MAX_EVENT_CHARS) throw ProviderFailure(KEY_RESPONSE, status)
            data.append(value).append('\n')
        }
        return if (data.isEmpty()) null else take().takeIf { it.isNotBlank() }
    }

    private fun take(): String {
        val payload = data.toString().removeSuffix("\n")
        data.clear()
        return payload
    }
}

/** Bounds each line while reading bytes and accepts LF, CRLF and bare CR. */
private class StreamLines(private val channel: ByteReadChannel, private val status: Int) {
    private val buffer = ByteArray(8192)
    private var position = 0
    private var available = 0
    private var total = 0
    private var skipLf = false

    private suspend fun nextByte(): Int {
        while (position >= available) {
            available = channel.readAvailable(buffer, 0, buffer.size)
            position = 0
            if (available < 0) return -1
        }
        if (++total > MAX_STREAM_CHARS) throw ProviderFailure(KEY_RESPONSE, status)
        return buffer[position++].toInt() and 255
    }

    suspend fun next(): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val value = nextByte()
            if (value < 0) return if (bytes.size() == 0) null else decode(bytes)
            if (skipLf) { skipLf = false; if (value == 10) continue }
            if (value == 13) { skipLf = true; return decode(bytes) }
            if (value == 10) return decode(bytes)
            if (bytes.size() >= MAX_LINE_CHARS) throw ProviderFailure(KEY_RESPONSE, status)
            bytes.write(value)
        }
    }

    private fun decode(bytes: ByteArrayOutputStream): String = try {
        bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
    } catch (_: Exception) { throw ProviderFailure(KEY_RESPONSE, status) }
}
