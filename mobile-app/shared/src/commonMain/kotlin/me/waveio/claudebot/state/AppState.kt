package me.waveio.claudebot.state

import kotlinx.serialization.Serializable

enum class Screen { Chat, Search, Files, Agents, Profile, Appearance, Models, Notifications, Personalization, Queue }

@Serializable
data class Preferences(
    val theme: String = "system",
    val language: String = "system",
    val wallpaper: Boolean = true,
    val fullWallpaper: Boolean = false,
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

data class ConversationRow(val id: String, val title: String)
data class ModelRow(val id: String, val label: String, val provider: String, val brand: String, val available: Boolean = true, val efforts: List<String> = emptyList())
data class ActivityRow(val id: String, val label: String, val detail: String = "", val status: String = "running")
data class ContentPart(val type: String, val text: String = "", val stepIds: List<String> = emptyList(), val noteId: String? = null)
data class MessageRow(val id: String, val role: String, val text: String, val bubbles: List<String> = emptyList(), val steps: List<ActivityRow> = emptyList(), val model: String = "", val live: Boolean = false, val parts: List<ContentPart> = emptyList(), val attachments: List<DraftAttachment> = emptyList())
data class FileRow(val path: String, val name: String, val directory: Boolean, val size: Long = 0)
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
)

data class AppState(
    val preferences: Preferences = Preferences(),
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val baseUrl: String = "https://api-bot.waveio.me",
    val screen: Screen = Screen.Chat,
    val menuOpen: Boolean = false,
    val modelPickerOpen: Boolean = false,
    val attachmentPickerOpen: Boolean = false,
    val sendModeOpen: Boolean = false,
    val scheduling: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val noticeDetail: String? = null,
    val conversations: List<ConversationRow> = emptyList(),
    val sessionId: String = "",
    val messages: List<MessageRow> = emptyList(),
    val draft: String = "",
    val editingMessageId: String? = null,
    val attachments: List<DraftAttachment> = emptyList(),
    val uploading: Boolean = false,
    val models: List<ModelRow> = emptyList(),
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
)
