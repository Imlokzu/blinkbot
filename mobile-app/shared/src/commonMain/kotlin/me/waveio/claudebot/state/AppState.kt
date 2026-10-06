package me.waveio.claudebot.state

import kotlinx.serialization.Serializable
import me.waveio.claudebot.data.MobileSkill
import me.waveio.claudebot.data.WorkFile
import me.waveio.claudebot.data.WebPreview
import kotlinx.serialization.json.JsonElement

enum class Screen { Chat, Search, Files, Agents, Profile, Appearance, Models, Notifications, Personalization, Queue, Skills, Updates }

@Serializable
data class Preferences(
    val theme: String = "system",
    val language: String = "system",
    val wallpaper: Boolean = true,
    val fullWallpaper: Boolean = true,
    val wallpaperScreens: Set<String> = setOf("Chat"),
    val wallpaperDim: Float = 0.16f,
    val wallpaperBlur: Float = 22f,
    val haptics: Boolean = true,
    val answerHaptics: Boolean = true,
    val notifications: Boolean = false,
    val notificationPreview: Boolean = false,
    val defaultModelMode: String = "last",
    val defaultModel: String = "",
    val lastModel: String = "",
    val chatModels: Map<String, String> = emptyMap(),
    val chatEfforts: Map<String, String> = emptyMap(),
)

/** History timestamps are Unix seconds; unknown dates remain unknown. */
data class ConversationRow(val id: String, val title: String, val updatedAt: Long? = null)
data class MobileUpdate(
    val versionName: String,
    val versionCode: Int,
    val changelog: List<String> = emptyList(),
    val url: String? = null,
    val iosUrl: String? = null,
    val sha256: String? = null,
    val mandatory: Boolean = false,
)
data class ModelRow(val id: String, val label: String, val provider: String, val brand: String, val available: Boolean = true, val efforts: List<String> = emptyList(), val vision: Boolean? = null)
data class IntelligenceRow(val index: Float, val coverage: Int, val total: Int)
data class ActivityRow(val id: String, val label: String, val detail: String = "", val status: String = "running", val input: JsonElement? = null, val result: JsonElement? = null)
data class ContentPart(val type: String, val text: String = "", val stepIds: List<String> = emptyList(), val noteId: String? = null, val note: Boolean = false)
data class MessageRow(
    val id: String,
    val role: String,
    val text: String,
    val bubbles: List<String> = emptyList(),
    val steps: List<ActivityRow> = emptyList(),
    val model: String = "",
    val live: Boolean = false,
    val parts: List<ContentPart> = emptyList(),
    val attachments: List<DraftAttachment> = emptyList(),
    /** The bot's emoji on a human message. */
    val reaction: String? = null,
    /** The human's emojis on assistant bubbles, keyed by decimal bubble index. */
    val reactions: Map<String, String> = emptyMap(),
    val timestamp: Long? = null,
    val workFiles: List<WorkFile> = emptyList(),
    /** Ephemeral animation identity, retained when the host assigns a saved ID. */
    val presentationId: String? = null,
)
data class FileRow(val path: String, val name: String, val directory: Boolean, val size: Long = 0)
data class PreviewItem(val path: String, val name: String, val mimeType: String, val source: String)
@Serializable
data class DraftAttachment(val path: String, val name: String, val mimeType: String, val size: Long)
data class PendingRow(val id: String, val text: String, val state: String, val scheduledAt: String? = null, val sessionId: String = "")

@Serializable
data class OutboxItem(
    val clientId: String,
    val sessionId: String,
    val message: String,
    val model: String,
    val effort: String,
    val delivery: String = "queue",
    val scheduledAt: String? = null,
    val attachments: List<DraftAttachment> = emptyList(),
    val forkMessageId: String? = null,
    val forkAction: String? = null,
    val lastError: String? = null,
    val deliveryDeclined: Boolean = false,
    val questionId: String? = null,
)

data class AppState(
    val preferences: Preferences = Preferences(),
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val initializing: Boolean = false,
    val initializationError: String? = null,
    val codePairingOpen: Boolean = false,
    val pairingCode: String = "",
    val pairingServer: String = "",
    val pairingError: String? = null,
    val baseUrl: String = "https://api-bot.waveio.me",
    val screen: Screen = Screen.Chat,
    val menuOpen: Boolean = false,
    val modelPickerOpen: Boolean = false,
    val skills: List<MobileSkill> = emptyList(),
    val skillsLoading: Boolean = false,
    val skillsError: Boolean = false,
    val attachmentPickerOpen: Boolean = false,
    val sendModeOpen: Boolean = false,
    val scheduling: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val noticeDetail: String? = null,
    val update: MobileUpdate? = null,
    val installedVersion: String = "",
    val updateBeta: Boolean = false,
    val updatePromptOpen: Boolean = false,
    val updateStatus: String = "update.notChecked",
    val updateChecking: Boolean = false,
    val updateInstalling: Boolean = false,
    val updateError: String? = null,
    val conversations: List<ConversationRow> = emptyList(),
    val sessionId: String = "",
    val messages: List<MessageRow> = emptyList(),
    val questions: List<BotQuestion> = emptyList(),
    val draft: String = "",
    val editingMessageId: String? = null,
    val attachments: List<DraftAttachment> = emptyList(),
    /** Ephemeral encoded images; never serialized into preferences or the outbox. */
    val attachmentThumbnails: Map<String, ByteArray> = emptyMap(),
    val mediaGeneration: Long = 0,
    val imageFailures: Set<String> = emptySet(),
    val uploading: Boolean = false,
    val models: List<ModelRow> = emptyList(),
    val modelsLoading: Boolean = false,
    val intelligence: Map<String, IntelligenceRow> = emptyMap(),
    val intelligenceLoading: Boolean = false,
    val selectedModel: String = "",
    val effort: String = "none",
    val busy: Boolean = false,
    val activeJobId: String? = null,
    val pending: List<PendingRow> = emptyList(),
    val allPending: List<PendingRow> = emptyList(),
    val queuePaused: Boolean = false,
    val steerAvailable: Boolean = false,
    val dictationOpen: Boolean = false,
    val recording: Boolean = false,
    val transcribing: Boolean = false,
    val amplitude: Float = 0f,
    val transcript: String = "",
    val search: String = "",
    val directory: String = "",
    val files: List<FileRow> = emptyList(),
    val openFile: String? = null,
    val fileSessionId: String = "",
    val fileText: String = "",
    val fileEditable: Boolean = false,
    val fileSaveState: String = "saved",
    val profileName: String = "",
    val profilePersona: String = "",
    val customWallpaper: ByteArray? = null,
    val offlineQuestion: Boolean = false,
    val previewTitle: String? = null,
    val previewText: String = "",
    val previewBytes: ByteArray? = null,
    val previewPath: String? = null,
    /** Workspace-relative files and /uploads/ manifests use different APIs. */
    val previewSource: String? = null,
    val previewSessionId: String = "",
    val previewWorkspacePath: String? = null,
    val previewRevision: Long = 0,
    val previewEditable: Boolean = false,
    val previewWeb: WebPreview? = null,
    val previewWebError: Boolean = false,
    val previewItems: List<PreviewItem> = emptyList(),
    val previewMimeType: String = "",
    val previewExporting: Boolean = false,
    val previewTruncated: Boolean = false,
)
