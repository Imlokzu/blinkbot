package me.waveio.claudebot.data

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import io.ktor.http.takeFrom
import io.ktor.util.AttributeKey
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Typed adapters over the existing host. The cloned client owns its lifecycle;
 * close() does not close an injected client. Requests never follow redirects.
 */
class BotApi(baseUrl: String, private val token: String, client: HttpClient = platformClient()) {
    val origin: String = normalizeApiOrigin(baseUrl)
    private val originUrl = Url(origin)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val ownedPlatformClient = client.takeIf { it.attributes.contains(PlatformClientOwner) }
    private val transport = client.config {
        followRedirects = false
        expectSuccess = false
    }

    init {
        if (token.any { it.isISOControl() }) throw ApiFailure(0, "invalid_token")
        // HttpSend runs after injected DefaultRequest plugins and before the engine.
        // Compare the full origin, including scheme and port, on every send.
        transport.plugin(HttpSend).intercept { request ->
            request.headers.remove(HttpHeaders.Authorization)
            if (request.url.protocol != originUrl.protocol ||
                !request.url.host.equals(originUrl.host, ignoreCase = true) ||
                request.url.build().port != originUrl.port || request.url.user != null || request.url.password != null) {
                throw ApiFailure(0, "request_origin")
            }
            if (request.attributes.getOrNull(Authenticated) != false && token.isNotEmpty()) {
                request.headers.append(HttpHeaders.Authorization, "Bearer $token")
            }
            execute(request)
        }
    }

    suspend fun listSessions(): List<ChatSession> = get<SessionsResponse>("sessions").sessions

    suspend fun sessionHistory(sessionId: String): List<ChatMessage> =
        get<HistoryResponse>("sessions", checkedId(sessionId)).messages.map { message ->
            ChatMessage(
                id = message.id, role = message.role, text = message.content,
                bubbles = message.parts.filter { it.type == "text" }.mapNotNull { it.text }
                    .ifEmpty { listOf(message.content).filter { it.isNotEmpty() } },
                steps = message.steps, attachments = message.attachments, model = message.model,
                parts = message.parts, timestamp = message.ts,
            )
        }

    suspend fun models(refresh: Boolean = false): ModelCatalog =
        get("brain", "models", query = mapOf("refresh" to refresh.toString()))

    suspend fun selectModel(model: String): String =
        post<ModelSelection>(listOf("brain", "model"), buildJsonObject { put("model", model) }).selected

    suspend fun setThinking(level: String): String =
        post<ThinkingSelection>(listOf("brain", "thinking"), buildJsonObject { put("level", level) }).thinking

    suspend fun listWorkspace(path: String = "", sessionId: String = ""): List<WorkspaceEntry> =
        get<WorkspaceListing>("workspace", "list", query = mapOf("path" to path, "session_id" to sessionId))
            .entries.map { entry ->
                if (entry.type !in listOf("dir", "file")) throw ApiFailure(200, "invalid_response")
                WorkspaceEntry(entry.path, entry.name, entry.type == "dir", entry.size)
            }

    suspend fun readWorkspace(path: String, sessionId: String = ""): WorkspaceFile =
        get("workspace", "file", query = mapOf("path" to path, "session_id" to sessionId))

    suspend fun writeWorkspace(path: String, content: String, sessionId: String = ""): WorkspaceWrite =
        post(listOf("workspace", "file"), buildJsonObject {
            put("path", path); put("content", content); put("session_id", sessionId)
        })

    suspend fun readMobileWorkspace(path: String, sessionId: String = ""): WorkspaceFile =
        get("mobile", "workspace", "file", query = mapOf("path" to path, "session_id" to sessionId))

    suspend fun writeMobileWorkspace(path: String, content: String, revision: String, sessionId: String = ""): WorkspaceWrite =
        post(listOf("mobile", "workspace", "file"), buildJsonObject {
            put("path", path); put("content", content); put("session_id", sessionId)
            put("revision", revision); put("append", false)
        })

    suspend fun forkMessage(sessionId: String, messageId: String, action: String, clientId: String,
                            message: String? = null, model: String = "", effort: String = "none"): MobileJob =
        post(listOf("mobile", "sessions", checkedId(sessionId), "fork"), buildJsonObject {
            put("message_id", messageId); put("action", action); put("client_id", clientId)
            message?.let { put("message", it) }; put("model", model); put("reasoning_effort", effort)
        })

    suspend fun upload(name: String, bytes: ByteArray, mimeType: String): Attachment =
        multipart(listOf("chat", "upload"), "file", name, bytes, mimeType)

    suspend fun attachmentPreview(path: String): AttachmentPreview =
        get("chat", "attachment-preview", query = mapOf("url" to checkedAttachmentPath(path)))

    /** Download a stored upload from the configured origin with device authentication. */
    suspend fun downloadAttachment(path: String): ByteArray = guarded {
        val relative = checkedAttachmentPath(path)
        val response = transport.request {
            method = HttpMethod.Get
            url.takeFrom(origin + relative)
            attributes.put(Authenticated, true)
        }
        checkResponse(response)
        response.body<ByteArray>()
    }

    suspend fun transcribe(
        name: String, bytes: ByteArray, mimeType: String, partial: Boolean = false,
    ): AsrResult = multipart(if (partial) listOf("asr", "partial") else listOf("asr"),
        "audio", name, bytes, mimeType)

    suspend fun profile(): BotProfile = get<ProfileResponse>("setup").profile

    suspend fun updateProfile(profile: BotProfile): BotProfile {
        // /api/setup applies defaults to omitted core fields. Refuse an incomplete
        // snapshot instead of inadvertently resetting another device's settings.
        if (profile.name == null || profile.language == null || profile.persona == null ||
            profile.personaCustom == null || profile.greeting == null) {
            throw ApiFailure(0, "incomplete_profile")
        }
        val payload = JsonObject(json.encodeToJsonElement(BotProfile.serializer(), profile)
            .let { it as JsonObject }.filterKeys { it != "configured" })
        return post<ProfileResponse>(listOf("setup"), payload).profile
    }

    /** Real server preferences; device appearance belongs to the caller's local state. */
    suspend fun preferences(): ServerPreferences = get("openclaw", "settings")

    suspend fun updatePreference(path: String, value: JsonElement?) {
        post<JsonObject>(listOf("openclaw", "settings"), buildJsonObject {
            put("path", path); put("value", value ?: JsonNull)
        })
    }

    suspend fun capabilities(): JsonObject = get("mobile", "capabilities")

    suspend fun exchangePairing(code: String, deviceName: String, platform: String): PairingCredentials {
        val reply = post<PairingResponse>(listOf("mobile", "pair", "exchange"), buildJsonObject {
            put("code", code); put("device_name", deviceName); put("platform", platform)
        }, authenticated = false)
        val expiry = reply.expiresAt.contentOrNull ?: throw ApiFailure(200, "invalid_response")
        if (reply.token.isBlank() || reply.deviceId.isBlank() || reply.token.any(Char::isISOControl)) {
            throw ApiFailure(200, "invalid_response")
        }
        return PairingCredentials(reply.token, reply.deviceId, expiry)
    }

    /** clientId must be persisted by the caller and reused after a lost acknowledgement. */
    suspend fun submitMessage(
        clientId: String, sessionId: String?, message: String,
        attachments: List<Attachment> = emptyList(), model: String? = null,
        reasoningEffort: String? = null, delivery: String = "queue", scheduledAt: String? = null,
    ): MobileJob {
        if (clientId.isBlank()) throw ApiFailure(0, "invalid_client_id")
        if (delivery !in listOf("queue", "steer")) throw ApiFailure(0, "invalid_delivery")
        return post(listOf("mobile", "messages"), buildJsonObject {
            put("client_id", clientId); put("session_id", sessionId.orEmpty())
            put("message", message)
            put("attachments", json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(Attachment.serializer()), attachments))
            model?.let { put("model", it) }
            reasoningEffort?.let { put("reasoning_effort", it) }
            put("delivery", delivery)
            scheduledAt?.let { put("scheduled_at", it) }
        })
    }

    /** One replay/live connection. The caller reconnects using the last received id. */
    fun jobEvents(id: String, after: Long = 0): Flow<BotEvent> = flow {
        if (after < 0) throw ApiFailure(0, "invalid_cursor")
        val path = listOf("mobile", "messages", checkedId(id), "events")
        transport.prepareRequest {
            configure(HttpMethod.Get, path, mapOf("after" to after.toString()))
            headers.append(HttpHeaders.Accept, ContentType.Text.EventStream.toString())
            // Small SSE frames must not wait for a proxy's compression buffer.
            headers.append(HttpHeaders.AcceptEncoding, "identity")
        }.execute { response ->
            checkResponse(response)
            if (response.contentType()?.withoutParameters() != ContentType.Text.EventStream) {
                throw ApiFailure(response.status.value, "invalid_event_stream")
            }
            val frames = SseFrames(json)
            // Keep this inside scoped execute: request()/get() save the whole
            // response before returning, even if bodyAsChannel is used later.
            val channel = response.bodyAsChannel()
            while (true) {
                val line = channel.readUTF8Line() ?: break
                frames.line(line)?.let { emit(it) }
            }
            // An incomplete frame at EOF is not a delivered event under the SSE spec.
        }
    }.catch { failure -> throw safeFailure(failure) }

    suspend fun stopMessage(id: String): MobileJob =
        post(listOf("mobile", "messages", checkedId(id), "stop"), buildJsonObject {})

    suspend fun listMessages(sessionId: String): List<MobileJob> =
        get<JobsResponse>("mobile", "messages", query = mapOf("session_id" to sessionId)).messages

    suspend fun resumeSession(id: String): JsonObject =
        post(listOf("mobile", "sessions", checkedId(id), "resume"), buildJsonObject {})

    suspend fun deleteSession(id: String): Boolean = guarded {
        decode<OkResponse>(transport.request {
            configure(HttpMethod.Delete, listOf("sessions", checkedId(id)))
        }).ok
    }

    suspend fun pinSession(id: String, pinned: Boolean): Boolean =
        post<OkResponse>(listOf("sessions", checkedId(id), "pin"), buildJsonObject { put("pinned", pinned) }).ok

    fun close() {
        transport.close()
        ownedPlatformClient?.close()
    }

    private suspend inline fun <reified T> get(vararg path: String, query: Map<String, String> = emptyMap()): T =
        guarded { decode(transport.request { configure(HttpMethod.Get, path.toList(), query) }) }

    private suspend inline fun <reified T> post(
        path: List<String>, body: JsonObject, authenticated: Boolean = true,
    ): T = guarded {
        decode(transport.request {
            configure(HttpMethod.Post, path, authenticated = authenticated)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(body))
        })
    }

    private suspend inline fun <reified T> multipart(
        path: List<String>, field: String, name: String, bytes: ByteArray, mimeType: String,
    ): T = guarded {
        // Quoted filenames are header parameters, not URL paths.
        if (name.isBlank() || name.any(Char::isISOControl) || mimeType.any(Char::isISOControl)) {
            throw ApiFailure(0, "invalid_upload")
        }
        val quotedName = name.replace("\\", "\\\\").replace("\"", "\\\"")
        val type = try { ContentType.parse(mimeType) } catch (_: Exception) {
            throw ApiFailure(0, "invalid_upload")
        }
        decode(transport.request {
            configure(HttpMethod.Post, path)
            setBody(MultiPartFormDataContent(formData {
                append(field, bytes, Headers.build {
                    append(HttpHeaders.ContentType, type.toString())
                    append(HttpHeaders.ContentDisposition, "filename=\"$quotedName\"")
                })
            }))
        })
    }

    private fun HttpRequestBuilder.configure(
        verb: HttpMethod, path: List<String>, query: Map<String, String> = emptyMap(), authenticated: Boolean = true,
    ) {
        method = verb
        url.takeFrom(URLBuilder(origin).apply {
            appendPathSegments("api")
            path.forEach { appendPathSegments(it, encodeSlash = true) }
            query.forEach { (key, value) -> parameters.append(key, value) }
        })
        attributes.put(Authenticated, authenticated)
    }

    private suspend inline fun <reified T> decode(response: HttpResponse): T {
        checkResponse(response)
        return try { json.decodeFromString<T>(response.bodyAsText()) } catch (_: SerializationException) {
            throw ApiFailure(response.status.value, "invalid_response")
        } catch (_: IllegalArgumentException) {
            throw ApiFailure(response.status.value, "invalid_response")
        }
    }

    private suspend fun checkResponse(response: HttpResponse) {
        val status = response.status.value
        if (status in 200..299) return
        if (status in 300..399) throw ApiFailure(status, "redirect_rejected")
        val objectBody = try { json.parseToJsonElement(response.bodyAsText()) as? JsonObject }
            catch (failure: CancellationException) { throw failure }
            catch (_: Exception) { null }
        val detail = objectBody?.get("detail")
        val candidate = (objectBody?.get("code") as? JsonPrimitive)?.contentOrNull
            ?: ((detail as? JsonObject)?.get("code") as? JsonPrimitive)?.contentOrNull
            ?: (detail as? JsonPrimitive)?.contentOrNull
            ?: (objectBody?.get("error") as? JsonPrimitive)?.contentOrNull
        val code = candidate?.takeIf { MachineCode.matches(it) } ?: when (status) {
            400, 422 -> "invalid_request"
            401 -> "unauthorized"
            403 -> "forbidden"
            404 -> "not_found"
            409 -> "conflict"
            413 -> "payload_too_large"
            429 -> "rate_limited"
            in 500..599 -> "server_error"
            else -> "http_error"
        }
        throw ApiFailure(status, code)
    }

    private suspend inline fun <T> guarded(block: () -> T): T =
        try { block() } catch (failure: Exception) { throw safeFailure(failure) }

    private fun checkedId(id: String): String {
        if (!Identifier.matches(id)) throw ApiFailure(0, "invalid_id")
        return id
    }

    private fun checkedAttachmentPath(path: String): String {
        // Backend uploads have a single generated filename; absolute links are
        // never accepted as a destination for a device token.
        if (!path.startsWith("/uploads/") || path.removePrefix("/uploads/").isBlank() ||
            path.removePrefix("/uploads/").any { it == '/' || it == '\\' || it == '?' || it == '#' || it.isISOControl() } ||
            path.removePrefix("/uploads/") in listOf(".", "..")) {
            throw ApiFailure(0, "invalid_attachment")
        }
        return path
    }

    private companion object {
        val Authenticated = AttributeKey<Boolean>("BotApi.Authenticated")
        val Identifier = Regex("[A-Za-z0-9_-]{1,128}")
        val MachineCode = Regex("[a-z][a-z0-9_]{0,63}")
    }
}

internal fun safeFailure(failure: Throwable): Throwable = when (failure) {
    is CancellationException -> failure
    is ApiFailure -> failure
    else -> ApiFailure(0, "network_error")
}

@Serializable private data class SessionsResponse(val sessions: List<ChatSession>)
@Serializable private data class HistoryResponse(val messages: List<MessageResponse> = emptyList())
@Serializable private data class MessageResponse(
    val id: String? = null, val role: String, val content: String,
    val parts: List<ReplyPart> = emptyList(), val steps: List<ToolStep> = emptyList(),
    val attachments: List<Attachment> = emptyList(), val model: String? = null, val ts: Long? = null,
)
@Serializable private data class WorkspaceListing(val entries: List<EntryResponse>)
@Serializable private data class EntryResponse(val path: String, val name: String, val type: String, val size: Long? = null)
@Serializable private data class ProfileResponse(val profile: BotProfile)
@Serializable private data class ModelSelection(val selected: String)
@Serializable private data class ThinkingSelection(val thinking: String)
@Serializable private data class OkResponse(val ok: Boolean)
@Serializable private data class JobsResponse(val messages: List<MobileJob>)
@Serializable private data class PairingResponse(
    val token: String, @SerialName("device_id") val deviceId: String,
    @SerialName("expires_at") val expiresAt: JsonPrimitive,
)
