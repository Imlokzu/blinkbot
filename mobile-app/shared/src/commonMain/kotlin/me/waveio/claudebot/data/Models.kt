package me.waveio.claudebot.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Timestamps from chat_store are Unix seconds, not milliseconds. */
@Serializable
data class ChatSession(
    val id: String,
    val title: String? = null,
    @SerialName("updated") val updatedAt: Long? = null,
    val pinned: Boolean? = null,
    val project: String? = null,
    val count: Int? = null,
)

data class ChatMessage(
    val id: String?,
    val role: String,
    val text: String,
    val bubbles: List<String>,
    val steps: List<ToolStep>,
    val attachments: List<Attachment>,
    val model: String?,
    val parts: List<ReplyPart> = emptyList(),
    val timestamp: Long? = null,
    val reaction: String? = null,
    val reactions: Map<String, String> = emptyMap(),
)

/** Retain the interleaving of narration, tool activity, and the answer. */
@Serializable
data class ReplyPart(
    val type: String,
    val text: String? = null,
    val ids: List<String> = emptyList(),
    val note: Boolean? = null,
)

@Serializable
data class ToolStep(
    val id: String,
    val label: String? = null,
    val detail: String? = null,
    val status: String? = null,
    val input: JsonElement? = null,
    val result: JsonElement? = null,
    val startedAt: Double? = null,
    val endedAt: Double? = null,
    val source: String? = null,
)

@Serializable
data class ModelOption(
    val id: String,
    val label: String? = null,
    val provider: String? = null,
    val brand: String? = null,
    val available: Boolean? = null,
    val efforts: List<String> = emptyList(),
    /** Missing capability metadata stays unknown, rather than becoming false. */
    val vision: Boolean? = null,
)

@Serializable
data class ModelCatalog(
    val models: List<ModelOption>,
    val selected: String? = null,
    @SerialName("default") val defaultModel: String? = null,
    @SerialName("thinking_levels") val efforts: List<String> = emptyList(),
    val thinking: String? = null,
    val available: Boolean? = null,
    @SerialName("image_model") val imageModel: String? = null,
)

data class WorkspaceEntry(val path: String, val name: String, val isDirectory: Boolean, val size: Long?)

@Serializable
data class WorkspaceFile(
    val path: String,
    val content: String,
    val binary: Boolean,
    @SerialName("mime_type") val mimeType: String? = null,
    @SerialName("too_large") val tooLarge: Boolean = false,
    val size: Long? = null,
    val revision: String? = null,
)

@Serializable
data class WorkspaceWrite(val ok: Boolean, val path: String, val size: Long? = null, val revision: String? = null)

/** The backend attachment manifest uses url/name/type/size. */
@Serializable
data class Attachment(
    @SerialName("url") val path: String,
    val name: String? = null,
    @SerialName("type") val mimeType: String? = null,
    val size: Long? = null,
)

@Serializable
data class AsrResult(val text: String, val partial: Boolean? = null)

@Serializable
data class AttachmentPreview(
    val text: String,
    val truncated: Boolean,
    @SerialName("type") val mimeType: String,
    val size: Long,
)

@Serializable
data class BotProfile(
    val name: String? = null,
    val language: String? = null,
    val persona: String? = null,
    @SerialName("persona_custom") val personaCustom: String? = null,
    val greeting: String? = null,
    @SerialName("reply_length") val replyLength: String? = null,
    @SerialName("use_emoji") val useEmoji: Boolean? = null,
    val spontaneous: Boolean? = null,
    val configured: Boolean? = null,
)

@Serializable
data class ServerPreferences(val available: Boolean? = null, val fields: List<PreferenceField>)

@Serializable
data class PreferenceField(
    val path: String,
    val section: String? = null,
    val group: String? = null,
    val kind: String? = null,
    val options: List<String> = emptyList(),
    val value: JsonElement? = null,
    val unset: Boolean? = null,
)

@Serializable
data class MobileJob(
    val id: String,
    @SerialName("session_id") val sessionId: String,
    val state: String,
    val message: String? = null,
    /** The list endpoint returns a Unix timestamp in seconds, including fractions. */
    @SerialName("scheduled_at") val scheduledAt: Double? = null,
    val model: String? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    @SerialName("client_id") val clientId: String? = null,
    val attachments: List<Attachment> = emptyList(),
    val delivery: String? = null,
    @SerialName("conversation_paused") val conversationPaused: Boolean? = null,
    val error: String? = null,
)

/** An SSE id is the server's sequence cursor. Missing ids remain absent. */
data class BotEvent(val id: Long?, val event: String, val data: JsonObject)

data class PairingCredentials(val token: String, val deviceId: String, val expiresAt: String) {
    override fun toString(): String = "PairingCredentials(redacted)"
}

/** A stable machine code for localization; server detail/tracebacks never reach the UI. */
class ApiFailure(val status: Int, val code: String) : Exception(code)
