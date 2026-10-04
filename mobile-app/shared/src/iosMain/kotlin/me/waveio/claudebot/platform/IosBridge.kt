package me.waveio.claudebot.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.waveio.claudebot.data.PairingCode
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIDevice
import platform.posix.memcpy

/** Swift owns native controllers; Kotlin owns coroutine state and the fixed shared contract. */
class IosSecretResult(val value: String?, val errorMessage: String?)

interface IosNativeDelegate {
    val systemLanguage: String
    val reducedMotion: Boolean
    fun readPreference(key: String): String?
    fun writePreference(key: String, value: String?)
    // Swift errors are not automatically thrown into Kotlin. Return an explicit status.
    fun readSecret(key: String): IosSecretResult
    fun writeSecret(key: String, value: String?): String?
    fun scanQr(onResult: (String?) -> Unit)
    fun pickFile(kind: String, onResult: (PickedFile?) -> Unit)
    fun pickFiles(kind: String, onResult: (List<PickedFile>) -> Unit)
    fun saveFile(file: PickedFile, onResult: (Boolean) -> Unit)
    fun shareFile(file: PickedFile, onResult: (Boolean) -> Unit)
    fun startRecording(onAmplitude: (Float) -> Unit, onResult: (PickedFile?) -> Unit, onPartial: (PickedFile) -> Unit)
    fun stopRecording()
    fun cancelRecording()
    fun haptic()
    fun copyText(value: String)
    fun shareText(value: String)
    fun requestNotifications(onResult: (Boolean) -> Unit)
    fun notifyReply(title: String, body: String, conversationId: String)
}

class IosBridge(private val delegate: IosNativeDelegate) : PlatformBridge {
    override val platformName: String = "ios"
    override val deviceName: String = "${UIDevice.currentDevice.model} · iOS ${UIDevice.currentDevice.systemVersion}"
    private val active = MutableStateFlow(false)
    override val foreground: StateFlow<Boolean> = active.asStateFlow()
    private val pairing = MutableStateFlow<String?>(null)
    override val incomingPairing: StateFlow<String?> = pairing.asStateFlow()
    override val systemLanguage: String get() = delegate.systemLanguage
    override val reducedMotion: Boolean get() = delegate.reducedMotion

    fun setForeground(value: Boolean) { active.value = value }

    /** Validate before retaining a URL; shared state decides whether pairing is allowed. */
    fun deliverIncomingPairing(value: String) {
        if (value.length > 4096 || !value.startsWith("claudebot://pair")) return
        if (runCatching { PairingCode.parse(value) }.isFailure) return
        pairing.value = value
    }

    override fun readPreference(key: String) = delegate.readPreference(key)
    override fun writePreference(key: String, value: String?) = delegate.writePreference(key, value)
    override fun readSecret(key: String): String? {
        val result = delegate.readSecret(key)
        result.errorMessage?.let { throw IllegalStateException(it) }
        return result.value
    }
    override fun writeSecret(key: String, value: String?) {
        delegate.writeSecret(key, value)?.let { throw IllegalStateException(it) }
    }
    override fun scanQr(onResult: (String?) -> Unit) = delegate.scanQr(onResult)
    override fun pickFile(kind: String, onResult: (PickedFile?) -> Unit) = delegate.pickFile(kind, onResult)
    override fun pickFiles(kind: String, onResult: (List<PickedFile>) -> Unit) = delegate.pickFiles(kind, onResult)
    override fun saveFile(file: PickedFile, onResult: (Boolean) -> Unit) = delegate.saveFile(file, onResult)
    override fun shareFile(file: PickedFile, onResult: (Boolean) -> Unit) = delegate.shareFile(file, onResult)
    override fun startRecording(onAmplitude: (Float) -> Unit, onResult: (PickedFile?) -> Unit, onPartial: (PickedFile) -> Unit) =
        delegate.startRecording(onAmplitude, onResult, onPartial)
    override fun stopRecording() = delegate.stopRecording()
    override fun cancelRecording() = delegate.cancelRecording()
    override fun haptic() = delegate.haptic()
    override fun copyText(value: String) = delegate.copyText(value)
    override fun shareText(value: String) = delegate.shareText(value)
    override fun requestNotifications(onResult: (Boolean) -> Unit) = delegate.requestNotifications(onResult)
    override fun notifyReply(title: String, body: String, conversationId: String) =
        delegate.notifyReply(title, body, conversationId)
    override fun nowMillis() = (NSDate().timeIntervalSince1970 * 1000).toLong()
    override fun newId() = NSUUID().UUIDString
}

/** Copy only a bounded native buffer, without millions of Swift-to-Kotlin element calls. */
@OptIn(ExperimentalForeignApi::class)
fun iosPickedFile(name: String, mimeType: String, data: NSData): PickedFile? {
    val size = data.length
    if (size == 0UL || size > 20UL * 1024UL * 1024UL) return null
    val bytes = ByteArray(size.toInt())
    bytes.usePinned { memcpy(it.addressOf(0), data.bytes, size) }
    return PickedFile(name, mimeType, bytes)
}

/** Keep export conversion bounded and avoid per-byte Swift-to-Kotlin calls. */
@OptIn(ExperimentalForeignApi::class)
fun iosFileData(file: PickedFile): NSData? {
    if (file.bytes.size > PickedFileLimits.MAX_BYTES) return null
    // Empty exports have no addressOf(0), but still represent a valid OS document.
    if (file.bytes.isEmpty()) return NSData()
    return file.bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = file.bytes.size.toULong()) }
}
