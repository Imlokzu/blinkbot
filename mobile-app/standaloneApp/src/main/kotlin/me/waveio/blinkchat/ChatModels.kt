package me.waveio.blinkchat

import kotlinx.serialization.Serializable

@Serializable
data class ProviderSettings(
    val baseUrl: String = "https://token-plan.maas.qwencloudapi.com/compatible-mode/v1",
    val chatModel: String = "qwen4-max-preview",
    val imageModel: String = "qwen-image-3.0-pro",
    val imageEndpoint: String = "https://token-plan.maas.qwencloudapi.com/api/v1/services/aigc/multimodal-generation/generation",
    val imageProtocol: String = "qwen",
    val systemPrompt: String = "",
    val toolsEnabled: Boolean = true,
)

/** Provider messages are independent of the host protocol and carry tool round trips. */
data class ApiMessage(
    val role: String,
    val text: String = "",
    val images: List<String> = emptyList(),
    val toolCalls: List<ApiToolCall> = emptyList(),
    val toolCallId: String? = null,
)
data class ApiToolCall(val id: String, val name: String, val arguments: String)
data class ApiReply(val text: String, val model: String, val toolCalls: List<ApiToolCall> = emptyList())
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
)
@Serializable
data class StoredStep(val id: String, val name: String, val status: String)
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
)
