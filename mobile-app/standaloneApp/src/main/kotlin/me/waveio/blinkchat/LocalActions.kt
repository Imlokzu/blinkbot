package me.waveio.blinkchat

import me.waveio.claudebot.ui.AppActions
import me.waveio.claudebot.state.Preferences
import me.waveio.claudebot.state.Screen
import me.waveio.claudebot.data.WebPreviewResource

/** Explicitly report host-only actions instead of exposing a second connection flow. */
abstract class LocalActions : AppActions {
    protected abstract fun unavailable()
    override fun connect() { unavailable() }
    override fun codePairing(open: Boolean) { unavailable() }
    override fun pairingCode(value: String) { unavailable() }
    override fun pairingServer(value: String) { unavailable() }
    override fun connectWithCode() { unavailable() }
    override fun navigate(screen: Screen) { unavailable() }
    override fun newChat() { unavailable() }
    override fun openChat(id: String) { unavailable() }
    override fun renameChat(id: String, title: String) { unavailable() }
    override fun deleteChat(id: String) { unavailable() }
    override fun menu(open: Boolean) { unavailable() }
    override fun modelPicker(open: Boolean) { unavailable() }
    override fun selectModel(id: String) { unavailable() }
    override fun selectEffort(value: String) { unavailable() }
    override fun draft(value: String) { unavailable() }
    override fun send(delivery: String, scheduledAt: String?) { unavailable() }
    override fun stop() { unavailable() }
    override fun resumeQueue() { unavailable() }
    override fun cancelPending(id: String) { unavailable() }
    override fun retryPending(id: String) { unavailable() }
    override fun sendModes(open: Boolean) { unavailable() }
    override fun schedule(open: Boolean) { unavailable() }
    override fun attachments(open: Boolean) { unavailable() }
    override fun pickFile(kind: String) { unavailable() }
    override fun removeAttachment(path: String) { unavailable() }
    override fun loadAttachmentThumbnail(path: String) { unavailable() }
    override fun previewAttachment(path: String) { unavailable() }
    override fun closePreview() { unavailable() }
    override fun editPreview() { unavailable() }
    override fun reloadPreview() { unavailable() }
    override fun buildPreviewProject() { unavailable() }
    override suspend fun loadWebPreviewResource(revision: Long, path: String): WebPreviewResource? = null
    override fun webPreviewFailed(revision: Long) { unavailable() }
    override fun startDictation() { unavailable() }
    override fun stopDictation() { unavailable() }
    override fun cancelDictation() { unavailable() }
    override fun useTranscript() { unavailable() }
    override fun copyContent(text: String) { unavailable() }
    override fun shareContent(text: String) { unavailable() }
    override fun openLink(url: String) { unavailable() }
    override fun useSkill(name: String) { unavailable() }
    override fun copyMessage(id: String) { unavailable() }
    override fun shareMessage(id: String) { unavailable() }
    override fun editMessage(id: String) { unavailable() }
    override fun cancelEdit() { unavailable() }
    override fun regenerate(id: String) { unavailable() }
    override fun reaction(id: String, bubbleIndex: Int, emoji: String?) { unavailable() }
    override fun search(value: String) { unavailable() }
    override fun openDirectory(path: String) { unavailable() }
    override fun openFile(path: String) { unavailable() }
    override fun closeFile() { unavailable() }
    override fun fileText(value: String) { unavailable() }
    override suspend fun loadEditorResource(id: String, path: String): WebPreviewResource? = null
    override fun retryFileSave() { unavailable() }
    override fun preferences(value: Preferences) { unavailable() }
    override fun resetWallpaper() { unavailable() }
    override fun requestNotifications() { unavailable() }
    override fun profileName(value: String) { unavailable() }
    override fun profilePersona(value: String) { unavailable() }
    override fun saveProfile() { unavailable() }
    override fun disconnect() { unavailable() }
    override fun dismissNotice() { unavailable() }
    override fun dismissUpdate() { unavailable() }
    override fun checkForUpdate() { unavailable() }
    override fun installUpdate() { unavailable() }
    override fun updateBeta(enabled: Boolean) { unavailable() }
    override fun offlineDelivery(allow: Boolean) { unavailable() }
    override fun refresh() { unavailable() }
}
