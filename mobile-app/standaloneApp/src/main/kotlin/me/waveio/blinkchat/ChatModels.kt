package me.waveio.blinkchat

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray

@Serializable
data class ProviderSettings(
    val baseUrl: String = "https://token-plan.maas.qwencloudapi.com/compatible-mode/v1",
    val chatModel: String = "qwen4-max-preview",
    val imageModel: String = "qwen-image-3.0-pro",
    val imageEndpoint: String = "https://token-plan.maas.qwencloudapi.com/api/v1/services/aigc/multimodal-generation/generation",
    /** [ImageLayer]; `none` makes a text-only provider with blank image model and endpoint. */
    val imageProtocol: String = "qwen",
    val systemPrompt: String = "",
    val toolsEnabled: Boolean = true,
    /** [ApiLayer]. Settings saved before 0.3.0 have no field and were Chat Completions. */
    val api: String = ApiLayer.OPENAI_CHAT,
)

/** Provider messages are independent of the host protocol and carry tool round trips. */
data class ApiMessage(
    val role: String,
    val text: String = "",
    val images: List<String> = emptyList(),
    val toolCalls: List<ApiToolCall> = emptyList(),
    val toolCallId: String? = null,
    /**
     * The provider's own content blocks for an assistant tool turn. Anthropic requires the
     * thinking blocks (with signatures) of a tool turn to be sent back unchanged.
     */
    val native: JsonArray? = null,
)
data class ApiToolCall(val id: String, val name: String, val arguments: String)
data class ApiReply(val text: String, val model: String, val toolCalls: List<ApiToolCall> = emptyList(), val native: JsonArray? = null)
data class GeneratedImage(val bytes: ByteArray, val mimeType: String)

/** Only a localization key and HTTP status can reach the UI; raw provider errors stay private. */
class ProviderFailure(val key: String, val status: Int? = null) : Exception(key)

@Serializable
data class StoredMessage(
    val id: String,
    val role: String,
    val text: String,
    val model: String = "",
    val attachments: List<StoredMedia> = emptyList(),
    val timestamp: Long = 0,
    val reactions: Map<String, String> = emptyMap(),
    val steps: List<StoredStep> = emptyList(),
    val kind: String = "text",
    val providerId: String = LEGACY_PROVIDER_ID,
    /** Visible reasoning the provider streamed, kept with the reply but never resent. */
    val reasoning: String = "",
    /** Time spent thinking before the answer started, summed over tool rounds. */
    val reasoningMillis: Long? = null,
)
@Serializable
data class StoredStep(val id: String, val name: String, val status: String)
/** One durable fact about the user; muted stays stored but is never injected. */
@Serializable
data class StoredMemory(
    val id: String,
    val content: String,
    val kind: String = "user",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val muted: Boolean = false,
)
@Serializable
data class StoredDraft(val text: String = "", val attachments: List<StoredMedia> = emptyList(), val editingMessageId: String? = null)
@Serializable
data class WorkspaceDraft(val path: String = "", val text: String = "", val dirty: Boolean = false)
@Serializable
data class StoredMedia(val path: String, val name: String, val mimeType: String, val size: Long)
@Serializable
data class StoredChat(
    val id: String,
    val title: String,
    val model: String,
    val updatedAt: Long,
    val messages: List<StoredMessage> = emptyList(),
    val imageMode: Boolean = false,
    val providerId: String = LEGACY_PROVIDER_ID,
    val imageProviderId: String = LEGACY_PROVIDER_ID,
    val imageModel: String = "",
    /** The chosen effort stop; blank leaves the provider's default in place. */
    val effort: String = "",
)
