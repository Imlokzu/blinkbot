package me.waveio.claudebot.platform

import kotlinx.coroutines.flow.StateFlow

data class PickedFile(val name: String, val mimeType: String, val bytes: ByteArray)

/** Native integrations keep shared UI independent of platform controllers. */
interface PlatformBridge {
    val platformName: String
    val systemLanguage: String
    val foreground: StateFlow<Boolean>
    val incomingPairing: StateFlow<String?>
    val reducedMotion: Boolean
    fun readPreference(key: String): String?
    fun writePreference(key: String, value: String?)
    /** Native background writers override this with a single locked transaction. */
    fun updatePreferences(keys: List<String>, transform: (Map<String, String?>) -> Map<String, String?>) {
        val changes = transform(keys.associateWith(::readPreference))
        require(changes.keys.all { it in keys })
        changes.forEach { (key, value) -> writePreference(key, value) }
    }
    fun readSecret(key: String): String?
    fun writeSecret(key: String, value: String?)
    fun scanQr(onResult: (String?) -> Unit)
    /** kind is one of photo, camera, document, wallpaper. Cancellation returns null. */
    fun pickFile(kind: String, onResult: (PickedFile?) -> Unit)
    /** Recording emits actual normalized amplitude; the result is an audio file. */
    fun startRecording(onAmplitude: (Float) -> Unit, onResult: (PickedFile?) -> Unit, onPartial: (PickedFile) -> Unit)
    fun stopRecording()
    fun cancelRecording()
    fun haptic()
    fun copyText(value: String)
    fun shareText(value: String)
    fun requestNotifications(onResult: (Boolean) -> Unit)
    fun notifyReply(title: String, body: String, conversationId: String)
    fun nowMillis(): Long
    fun newId(): String
}
