package me.waveio.claudebot.ui

import me.waveio.claudebot.state.Preferences
import me.waveio.claudebot.state.Screen
import me.waveio.claudebot.data.WebPreviewResource

interface AppActions {
    fun connect()
    fun codePairing(open: Boolean)
    fun pairingCode(value: String)
    fun pairingServer(value: String)
    fun connectWithCode()
    fun navigate(screen: Screen)
    fun newChat()
    fun openChat(id: String)
    fun renameChat(id: String, title: String)
    fun deleteChat(id: String)
    fun menu(open: Boolean)
    fun modelPicker(open: Boolean)
    fun selectModel(id: String)
    fun selectEffort(value: String)
    fun draft(value: String)
    fun send(delivery: String = "queue", scheduledAt: String? = null)
    fun answerQuestion(id: String, answer: String) {}
    fun dismissQuestion(id: String) {}
    fun stop()
    fun resumeQueue()
    fun cancelPending(id: String)
    fun retryPending(id: String)
    fun sendModes(open: Boolean)
    fun schedule(open: Boolean)
    fun attachments(open: Boolean)
    fun pickFile(kind: String)
    fun removeAttachment(path: String)
    fun loadReplyImage(path: String, source: String, retry: Boolean = false) {}
    fun previewReplyImage(path: String, source: String) {}
    fun loadAttachmentThumbnail(path: String)
    fun previewAttachment(path: String)
    fun previewWorkFile(path: String) {}
    fun loadWorkFileThumbnail(path: String) {}
    fun savePreview() {}
    fun sharePreview() {}
    fun closePreview()
    fun editPreview()
    fun reloadPreview()
    fun buildPreviewProject()
    suspend fun loadWebPreviewResource(revision: Long, path: String): WebPreviewResource?
    fun webPreviewFailed(revision: Long)
    fun startDictation()
    fun stopDictation()
    fun cancelDictation()
    fun useTranscript()
    fun copyContent(text: String)
    fun shareContent(text: String)
    fun openLink(url: String)
    fun useSkill(name: String)
    fun copyMessage(id: String)
    fun shareMessage(id: String)
    fun editMessage(id: String)
    fun cancelEdit()
    fun regenerate(id: String)
    fun reaction(id: String, bubbleIndex: Int, emoji: String?)
    fun search(value: String)
    fun openDirectory(path: String)
    fun openFile(path: String)
    /** Browsing opens a reader; openFile remains the explicit editing action. */
    fun browseFile(path: String) { openFile(path) }
    fun closeFile()
    fun fileText(value: String)
    fun editorChanged(id: String, content: String) {}
    fun copyEditorContent(id: String) {}
    fun saveEditorDrawing(id: String, content: String) {}
    fun previewEditedFile(id: String) {}
    fun reloadFile() {}
    fun reloadRemoteFile() {}
    fun keepLocalFile() {}
    fun saveFileCopy(name: String) {}
    fun createFile(name: String, kind: String) {}
    fun openEditorLink(id: String, path: String) {}
    fun createEditorDrawing(id: String, onCreated: (String?) -> Unit) { onCreated(null) }
    fun convertEditorMermaid(id: String, content: String) {}
    suspend fun loadEditorResource(id: String, path: String): WebPreviewResource? = null
    fun retryFileSave()
    fun preferences(value: Preferences)
    fun resetWallpaper()
    fun requestNotifications()
    fun profileName(value: String)
    fun profilePersona(value: String)
    fun saveProfile()
    fun disconnect()
    fun dismissNotice()
    fun dismissUpdate()
    fun checkForUpdate()
    fun installUpdate()
    fun updateBeta(enabled: Boolean)
    fun offlineDelivery(allow: Boolean)
    fun refresh()
}
