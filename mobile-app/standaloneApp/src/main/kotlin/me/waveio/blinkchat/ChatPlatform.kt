package me.waveio.blinkchat

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.waveio.claudebot.platform.PickedFile
import me.waveio.claudebot.platform.PlatformBridge

/**
 * Activity-owned native integrations for Blink Chat. Launchers are registered in
 * the constructor, before the activity reaches STARTED, and stay stable across
 * recompositions. Camera capture uses the system app; microphone and notifications are deferred.
 */
class ChatPlatform(
    private val activity: ComponentActivity,
    val storage: ChatStorage,
) : PlatformBridge, DefaultLifecycleObserver {
    override val platformName: String = "android"
    override val deviceName: String = "${Build.MODEL} · Android ${Build.VERSION.RELEASE}"
    override val appVersionCode: Int = BuildConfig.VERSION_CODE
    override val appVersionName: String = BuildConfig.VERSION_NAME
    private val context = activity.applicationContext
    private val preferences = context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
    private val preferenceLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val active = MutableStateFlow(false)
    override val foreground: StateFlow<Boolean> = active.asStateFlow()
    private val pairing = MutableStateFlow<String?>(null)
    override val incomingPairing: StateFlow<String?> = pairing.asStateFlow()
    override val systemLanguage: String get() = context.resources.configuration.locales[0].language
    override val reducedMotion: Boolean
        get() = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    private var destroyed = false
    private var stopped = false
    private var fileResult: ((List<PickedFile>) -> Unit)? = null
    private var fileKind: String? = null
    private var fileJob: Job? = null
    private var fileGeneration = 0L
    private var cameraFile: File? = null
    private var cameraUri: Uri? = null
    private var exportResult: ((Boolean) -> Unit)? = null
    private var exportFile: PickedFile? = null
    private var exportJob: Job? = null
    private var exportGeneration = 0L

    private val photoLauncher = activity.registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (fileKind == "photo" || fileKind == "camera" || fileKind == "wallpaper") receiveFiles(listOfNotNull(uri))
    }
    private val cameraLauncher = activity.registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = cameraUri
        if (fileKind == "camera" && saved && uri != null) receiveFiles(listOf(uri))
        else if (fileKind == "camera") finishFiles(emptyList())
    }
    private val photosLauncher = activity.registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_SELECTION)) { uris ->
        if (fileKind == "photos") receiveFiles(uris)
    }
    private val documentLauncher = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (fileKind == "document") receiveFiles(listOfNotNull(uri))
    }
    private val documentsLauncher = activity.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (fileKind == "documents") receiveFiles(uris)
    }
    private val saveLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (exportFile != null) receiveSave(if (result.resultCode == Activity.RESULT_OK) result.data?.data else null)
    }

    init {
        if (exportsSwept.compareAndSet(false, true)) {
            scope.launch {
                try { withContext(Dispatchers.IO) { removeExpiredExports() } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { }
            }
        }
        activity.lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) { stopped = false }
    override fun onResume(owner: LifecycleOwner) { active.value = true }
    override fun onPause(owner: LifecycleOwner) { active.value = false }
    override fun onStop(owner: LifecycleOwner) { stopped = true }
    override fun onDestroy(owner: LifecycleOwner) { cleanup() }

    /** Idempotent: MainActivity calls it and the lifecycle observer also fires. */
    fun cleanup() {
        if (destroyed) return
        destroyed = true
        active.value = false
        activity.lifecycle.removeObserver(this)
        scope.cancel()
        fileJob?.cancel()
        fileJob = null
        exportJob?.cancel()
        exportJob = null
        // Drop the callbacks so a pending picker cannot keep the controller alive.
        fileResult = null
        fileKind = null
        exportResult = null
        exportFile = null
        fileGeneration++
        cameraFile?.delete(); cameraFile = null; cameraUri = null
        exportGeneration++
    }

    override fun readPreference(key: String): String? = synchronized(preferenceLock) { preferences.getString(key, null) }

    override fun writePreference(key: String, value: String?) = synchronized(preferenceLock) { commit(mapOf(key to value)) }

    override fun updatePreferences(keys: List<String>, transform: (Map<String, String?>) -> Map<String, String?>) =
        synchronized(preferenceLock) {
            val declared = keys.distinct()
            val changes = transform(declared.associateWith(::readPreference))
            require(changes.keys.all { it in declared })
            commit(changes)
        }

    private fun commit(changes: Map<String, String?>) {
        val edit = preferences.edit()
        var changed = false
        for ((key, value) in changes) {
            if (preferences.getString(key, null) == value) continue
            changed = true
            if (value == null) edit.remove(key) else edit.putString(key, value)
        }
        if (changed && !edit.commit()) throw ProviderFailure(ChatErrors.STORAGE)
    }

    override fun readSecret(key: String): String? = if (key == ChatStorage.API_KEY) storage.readApiKey() else null

    override fun writeSecret(key: String, value: String?) {
        if (key == ChatStorage.API_KEY) storage.writeApiKey(value)
    }

    /** Pairing uses a provider key, so no camera permission is requested for a QR scan. */
    override fun scanQr(onResult: (String?) -> Unit) { if (!destroyed) onResult(null) }

    override fun pickFile(kind: String, onResult: (PickedFile?) -> Unit) = launchPicker(kind) { onResult(it.firstOrNull()) }

    override fun pickFiles(kind: String, onResult: (List<PickedFile>) -> Unit) =
        launchPicker(when (kind) { "photo" -> "photos"; "document" -> "documents"; else -> kind }, onResult)

    private fun launchPicker(kind: String, onResult: (List<PickedFile>) -> Unit) {
        if (busy()) { if (!destroyed) onResult(emptyList()); return }
        fileResult = onResult
        fileKind = kind
        try {
            when (kind) {
                "photo", "wallpaper" -> photoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                "camera" -> {
                    val directory = File(context.cacheDir, "$EXPORTS_DIR/camera").apply { mkdirs() }
                    val file = File(directory, "${UUID.randomUUID()}.jpg")
                    cameraFile = file
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                    cameraUri = uri
                    cameraLauncher.launch(uri)
                }
                "photos" -> photosLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                "document" -> documentLauncher.launch(arrayOf("image/*"))
                "documents" -> documentsLauncher.launch(arrayOf("image/*"))
                else -> finishFiles(emptyList())
            }
        } catch (_: RuntimeException) {
            finishFiles(emptyList())
        }
    }

    private fun receiveFiles(uris: List<Uri>) {
        if (fileResult == null) return
        if (uris.isEmpty() || destroyed) { finishFiles(emptyList()); return }
        val generation = fileGeneration
        fileJob?.cancel()
        fileJob = scope.launch(start = CoroutineStart.LAZY) {
            val files = try {
                withContext(Dispatchers.IO) {
                    val reading = currentCoroutineContext()
                    readSelection(uris.take(MAX_SELECTION).distinct()) { uri, limit ->
                        try { readUri(uri, limit) { reading.ensureActive() } }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { null }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { emptyList() }
            if (!destroyed && generation == fileGeneration) finishFiles(files)
        }
        fileJob?.start()
    }

    /** The aggregate budget is passed to every reader so providers cannot exceed it. */
    private fun readSelection(uris: List<Uri>, read: (Uri, Int) -> PickedFile?): List<PickedFile> {
        val files = mutableListOf<PickedFile>()
        var remaining = ChatLimits.MEDIA_BYTES
        for (uri in uris) {
            if (remaining == 0) break
            val file = read(uri, remaining) ?: continue
            if (file.bytes.isEmpty() || file.bytes.size > remaining) continue
            files += file
            remaining -= file.bytes.size
        }
        return files
    }

    private fun readUri(uri: Uri, limit: Int, checkCancelled: () -> Unit): PickedFile? {
        checkCancelled()
        val resolver = context.contentResolver
        var name: String? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameColumn >= 0) name = cursor.getString(nameColumn)
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn) && cursor.getLong(sizeColumn) > limit) return null
            }
        }
        checkCancelled()
        // Unknown or lying metadata still cannot allocate more than the remaining budget.
        val bytes = resolver.openInputStream(uri)?.use { ChatBoundedBytes.read(it, limit, checkCancelled) } ?: return null
        if (bytes.isEmpty()) return null
        val mime = ChatFileNames.mime(resolver.getType(uri))
        return PickedFile(ChatFileNames.display(name) ?: ChatFileNames.generated(mime), mime, bytes)
    }

    private fun finishFiles(files: List<PickedFile>) {
        val callback = fileResult
        fileResult = null
        fileKind = null
        cameraFile?.delete(); cameraFile = null; cameraUri = null
        fileGeneration++
        fileJob?.cancel()
        fileJob = null
        if (!destroyed) callback?.invoke(files)
    }

    override fun saveFile(file: PickedFile, onResult: (Boolean) -> Unit) {
        if (busy() || !exportable(file)) { if (!destroyed) onResult(false); return }
        exportResult = onResult
        // Snapshot the bytes: callers may reuse their buffer while the picker is open.
        exportFile = file.copy(bytes = file.bytes.copyOf())
        val generation = ++exportGeneration
        try {
            saveLauncher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = ChatFileNames.mime(file.mimeType)
                putExtra(Intent.EXTRA_TITLE, ChatFileNames.display(file.name) ?: ChatFileNames.generated(file.mimeType, file.name))
            })
        } catch (_: RuntimeException) {
            if (generation == exportGeneration) finishExport(false)
        }
    }

    private fun receiveSave(uri: Uri?) {
        val file = exportFile ?: return
        if (uri == null || destroyed) { finishExport(false); return }
        val generation = exportGeneration
        exportJob?.cancel()
        exportJob = scope.launch(start = CoroutineStart.LAZY) {
            val success = try {
                withContext(Dispatchers.IO) {
                    val writing = currentCoroutineContext()
                    context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                        var offset = 0
                        while (offset < file.bytes.size) {
                            writing.ensureActive()
                            val count = minOf(8192, file.bytes.size - offset)
                            output.write(file.bytes, offset, count)
                            offset += count
                        }
                        output.flush()
                        writing.ensureActive()
                        true
                    } ?: false
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            if (!destroyed && generation == exportGeneration) finishExport(success)
        }
        exportJob?.start()
    }

    override fun shareFile(file: PickedFile, onResult: (Boolean) -> Unit) {
        if (busy() || !exportable(file)) { if (!destroyed) onResult(false); return }
        exportResult = onResult
        val snapshot = file.copy(bytes = file.bytes.copyOf())
        val generation = ++exportGeneration
        exportJob?.cancel()
        exportJob = scope.launch(start = CoroutineStart.LAZY) {
            var temporary: File? = null
            var handedOff = false
            var success = false
            try {
                // Assign before resuming Main so cancellation cannot orphan a written file.
                withContext(Dispatchers.IO) { temporary = prepareExport(snapshot) }
                val shared = temporary
                if (shared != null && !destroyed && !stopped && !activity.isFinishing && generation == exportGeneration) {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", shared, shared.name)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = ChatFileNames.mime(snapshot.mimeType)
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri("", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    activity.startActivity(Intent.createChooser(intent, context.getString(R.string.native_share)))
                    handedOff = true
                    success = true
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { success = false }
            finally { if (!handedOff) temporary?.parentFile?.deleteRecursively() }
            if (!destroyed && generation == exportGeneration) finishExport(success)
        }
        exportJob?.start()
    }

    private fun exportable(file: PickedFile) = file.bytes.isNotEmpty() && file.bytes.size <= ChatLimits.MEDIA_BYTES

    private fun prepareExport(file: PickedFile): File? {
        val root = File(context.cacheDir, EXPORTS_DIR)
        removeExpiredExports()
        if (!root.isDirectory && !root.mkdirs()) return null
        val retained = root.listFiles().orEmpty()
        if (retained.size >= MAX_EXPORTS) return null
        val usedBytes = retained.sumOf { entry -> entry.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
        if (usedBytes > MAX_EXPORT_BYTES - file.bytes.size) return null
        val directory = File(root, UUID.randomUUID().toString())
        if (!directory.mkdirs()) return null
        return try {
            File(directory, ChatFileNames.display(file.name) ?: ChatFileNames.generated(file.mimeType, file.name))
                .apply { writeBytes(file.bytes) }
        } catch (error: Exception) {
            directory.deleteRecursively()
            throw error
        }
    }

    private fun removeExpiredExports(now: Long = System.currentTimeMillis()) {
        File(context.cacheDir, EXPORTS_DIR).listFiles()?.forEach { directory ->
            if (directory.isDirectory && now - directory.lastModified() >= EXPORT_LIFETIME_MS) directory.deleteRecursively()
        }
    }

    private fun finishExport(success: Boolean) {
        val callback = exportResult
        exportResult = null
        exportFile = null
        exportGeneration++
        exportJob?.cancel()
        exportJob = null
        if (!destroyed) callback?.invoke(success)
    }

    /** Phase 1 requests no microphone permission, so no capture is ever fabricated. */
    override fun startRecording(onAmplitude: (Float) -> Unit, onResult: (PickedFile?) -> Unit, onPartial: (PickedFile) -> Unit) {
        if (!destroyed) onResult(null)
    }

    override fun stopRecording() = Unit
    override fun cancelRecording() = Unit

    override fun haptic() {
        if (!destroyed) activity.window.decorView.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    override fun copyText(value: String) {
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(context.getString(R.string.standalone_app_name), value))
    }

    override fun shareText(value: String) {
        if (destroyed) return
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, value)
        runCatching { activity.startActivity(Intent.createChooser(intent, context.getString(R.string.native_share))) }
    }

    override fun openExternalUrl(url: String) {
        if (destroyed || !url.startsWith("https://", ignoreCase = true)) return
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        if (!uri.scheme.equals("https", ignoreCase = true)) return
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    }

    override fun requestNotifications(onResult: (Boolean) -> Unit) { if (!destroyed) onResult(false) }

    override fun notificationsAllowed(): Boolean = false

    override fun notifyReply(title: String, body: String, conversationId: String) = Unit

    override fun nowMillis(): Long = System.currentTimeMillis()
    override fun newId(): String = UUID.randomUUID().toString()

    private fun busy() = destroyed || stopped || activity.isFinishing || fileResult != null || exportResult != null

    private companion object {
        const val PREFERENCES_FILE = "chat-preferences"
        const val EXPORTS_DIR = "exports"
        const val MAX_SELECTION = 4
        const val MAX_EXPORTS = 32
        const val MAX_EXPORT_BYTES = 80L * 1024 * 1024
        const val EXPORT_LIFETIME_MS = 24 * 60 * 60 * 1000L
        // A new process has no live chooser; sweep only our own abandoned exports.
        val exportsSwept = AtomicBoolean(false)
    }
}
