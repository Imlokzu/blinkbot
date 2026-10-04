package me.waveio.claudebot.platform

import kotlinx.coroutines.flow.StateFlow

data class PickedFile(val name: String, val mimeType: String, val bytes: ByteArray)

/** Native pickers enforce both limits, including providers that ignore selection limits. */
object PickedFileLimits {
    const val MAX_SELECTION = 10
    const val MAX_BYTES = 20 * 1024 * 1024
}

/** Native integrations keep shared UI independent of platform controllers. */
interface PlatformBridge {
    val platformName: String
    /** Human-readable model and OS label shown in the owner dashboard. */
    val deviceName: String get() = platformName
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
    /** Photo/document selection: at most 10 files and 20 MiB total; cancellation is empty.
     * Camera/wallpaper remain single selections. Invalid or oversized files are skipped.
     */
    fun pickFiles(kind: String, onResult: (List<PickedFile>) -> Unit) {
        pickFile(kind) { onResult(listOfNotNull(it)) }
    }
    /** Save original bytes through the OS picker. True means the write completed. */
    fun saveFile(file: PickedFile, onResult: (Boolean) -> Unit) { onResult(false) }
    /** Share original bytes with filename/MIME. Android confirms chooser handoff;
     * iOS confirms activity completion. Neither guarantees delivery to a recipient.
     */
    fun shareFile(file: PickedFile, onResult: (Boolean) -> Unit) { onResult(false) }
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
