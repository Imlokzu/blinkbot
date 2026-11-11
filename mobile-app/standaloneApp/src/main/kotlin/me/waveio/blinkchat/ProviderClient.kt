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
            request.attributes.getOrNull(Bearer)?.let { key ->
                request.headers.append(HttpHeaders.Authorization, "Bearer $key")
            }
            execute(request)
        }
    }

    suspend fun models(settings: ProviderSettings, apiKey: String): List<String> = guarded {
        validateSettings(settings)
        val key = checkedKey(apiKey)
        transport.prepareRequest {
            apiCall(HttpMethod.Get, endpoint(settings.baseUrl, "models"), key)
        }.execute { response ->
            checkStatus(response)
            val status = response.status.value
            val root = parseObject(boundedBytes(response, MAX_JSON_BYTES).decodeToString(), status)
            val items = root["data"] as? JsonArray ?: throw ProviderFailure(KEY_RESPONSE, status)
            val ids = LinkedHashSet<String>()
            for (item in items) {
                val id = (item as? JsonObject)?.get("id").stringOrNull() ?: continue
                if (id.isBlank() || id.length > MAX_ID_CHARS || id.any(Char::isISOControl)) continue
                ids += id
                if (ids.size >= MAX_MODELS) break
            }
            ids.toList()
        }
    }

    suspend fun chat(
        settings: ProviderSettings,
        apiKey: String,
        messages: List<ApiMessage>,
        tools: List<JsonObject> = emptyList(),
        onDelta: (String) -> Unit = {},
    ): ApiReply = guarded {
        validateSettings(settings)
        val key = checkedKey(apiKey)
        val payload = buildJsonObject {
            put("model", settings.chatModel)
            put("stream", true)
            put("messages", buildJsonArray { messages.forEach { add(encodeMessage(it)) } })
            if (tools.isNotEmpty()) put("tools", JsonArray(tools))
        }
        transport.prepareRequest {
            apiCall(HttpMethod.Post, endpoint(settings.baseUrl, "chat/completions"), key)
            contentType(ContentType.Application.Json)
            headers.append(HttpHeaders.Accept, ContentType.Text.EventStream.toString())
            // Small SSE frames must not wait for a proxy's compression buffer.
            headers.append(HttpHeaders.AcceptEncoding, "identity")
            setBody(json.encodeToString(payload))
        }.execute { response ->
            checkStatus(response)
            val status = response.status.value
            if (response.contentType()?.withoutParameters() == ContentType.Application.Json) {
                nonStreamReply(boundedBytes(response, MAX_JSON_BYTES).decodeToString(), settings, status)
            } else {
                streamReply(response, settings, onDelta)
            }
        }
    }

    suspend fun generateImage(settings: ProviderSettings, apiKey: String, prompt: String): GeneratedImage = guarded {
        validateSettings(settings)
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

    private fun HttpRequestBuilder.apiCall(verb: HttpMethod, endpoint: String, apiKey: String) {
        method = verb
        url.takeFrom(endpoint)
        attributes.put(Bearer, apiKey)
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

        /** Called before settings are saved and before every request. */
        fun validateSettings(settings: ProviderSettings) {
            validateModel(settings.chatModel)
            validateModel(settings.imageModel)
            if (settings.imageProtocol != "qwen" && settings.imageProtocol != "openai") {
                throw ProviderFailure(KEY_SETTINGS)
            }
            validateEndpoint(settings.baseUrl)
            validateEndpoint(settings.imageEndpoint)
        }

        private fun validateModel(model: String) {
            if (model.isBlank() || model.length > MAX_ID_CHARS || model.any(Char::isISOControl)) {
                throw ProviderFailure(KEY_SETTINGS)
            }
        }

        private fun validateEndpoint(endpoint: String) {
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
