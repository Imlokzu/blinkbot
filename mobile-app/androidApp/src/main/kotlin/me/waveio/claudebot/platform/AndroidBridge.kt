package me.waveio.claudebot.platform

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
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
import me.waveio.claudebot.MainActivity
import me.waveio.claudebot.BotApplication
import me.waveio.claudebot.R

/** Activity-owned, registered before STARTED, and stable across recompositions. */
class AndroidBridge(private val activity: ComponentActivity) : PlatformBridge, NativeAtomicPreferences, DefaultLifecycleObserver {
    override val platformName: String = "android"
    override val deviceName: String = "${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}"
    private val context = activity.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val active = MutableStateFlow(false)
    override val foreground: StateFlow<Boolean> = (context as? BotApplication)?.nativeForeground?.foreground ?: active.asStateFlow()
    private val pairing = MutableStateFlow<String?>(null)
    override val incomingPairing: StateFlow<String?> = pairing.asStateFlow()

    fun deliverIncomingPairing(value: String) {
        if (!destroyed && NativePairingLinks.accepts(value)) pairing.value = value
    }
    override val systemLanguage get() = context.resources.configuration.locales[0].language
    override val reducedMotion get() = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    private val persistence = NativeOutboxStore(context)
    private var destroyed = false
    private var stopped = false
    private var qrResult: ((String?) -> Unit)? = null
    private var fileResult: ((List<PickedFile>) -> Unit)? = null
    private var fileJob: Job? = null
    private var fileKind: String? = null
    private var fileGeneration = 0L
    private var cameraFile: File? = null
    private var cameraUri: Uri? = null
    private var exportResult: ((Boolean) -> Unit)? = null
    private var exportFile: PickedFile? = null
    private var exportJob: Job? = null
    private var exportGeneration = 0L
    private var permissionResult: ((Boolean) -> Unit)? = null
    private var permissionInFlight = false
    private var recordingResult: ((PickedFile?) -> Unit)? = null
    private var recordingGeneration = 0L
    private var pcmRecorder: AndroidPcmRecorder? = null

    private val permissionLauncher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        permissionInFlight = false
        val callback = permissionResult
        permissionResult = null
        callback?.invoke(allowed && !destroyed)
    }
    private val qrLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = qrResult
        qrResult = null
        callback?.invoke(if (!destroyed && result.resultCode == android.app.Activity.RESULT_OK) result.data?.getStringExtra(QrScannerActivity.RESULT_QR) else null)
    }
    private val documentLauncher = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (fileKind == "document") receiveFile(uri)
    }
    private val photoLauncher = activity.registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (fileKind == "photo" || fileKind == "wallpaper") receiveFile(uri)
    }
    private val documentsLauncher = activity.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (fileKind == "documents") receiveFiles(uris)
    }
    private val photosLauncher = activity.registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(PickedFileLimits.MAX_SELECTION)) { uris ->
        if (fileKind == "photos") receiveFiles(uris)
    }
    private val saveLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (exportFile != null) {
            receiveSave(if (result.resultCode == android.app.Activity.RESULT_OK) result.data?.data else null)
        }
    }
    private val cameraLauncher = activity.registerForActivityResult(object : ActivityResultContracts.TakePicture() {
        override fun createIntent(context: Context, input: Uri): Intent = super.createIntent(context, input).apply {
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("", input)
        }
    }) { success -> if (fileKind == "camera") receiveFile(if (success) cameraUri else null) }

    init {
        if (temporaryFilesCleaned.compareAndSet(false, true)) {
            NativeFileTransfers.removeExpiredShares(context.cacheDir)
            NativeTemporaryFiles.removeAbandoned(context.cacheDir) { file ->
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.native-files", file)
                context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
        activity.lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) { stopped = false }
    override fun onResume(owner: LifecycleOwner) { active.value = true }
    override fun onPause(owner: LifecycleOwner) { active.value = false }
    override fun onStop(owner: LifecycleOwner) { stopped = true; cancelRecording() }
    override fun onDestroy(owner: LifecycleOwner) {
        destroyed = true
        active.value = false
        val qrCallback = qrResult
        qrResult = null
        val permissionCallback = permissionResult
        permissionResult = null
        scope.cancel()
        activity.lifecycle.removeObserver(this)
        // All resources must be cleaned even when a client callback throws.
        try { cancelRecording() } finally {
            try { finishFile(null) } finally {
                try { finishExport(false) } finally {
                    try { qrCallback?.invoke(null) } finally { permissionCallback?.invoke(false) }
                }
            }
        }
    }

    override fun readPreference(key: String): String? = persistence.readPreference(key)
    override fun writePreference(key: String, value: String?) {
        val changed = persistence.writePreference(key, value)
        if (key == "device_id" || key == "server") NativeOutboxWork.credentialsChanged(context)
        if (changed && (key.endsWith(NativeOutboxStore.QUEUE_SUFFIX) || key.endsWith(NativeOutboxStore.ALLOWED_SUFFIX) || key == "server")) {
            NativeOutboxWork.schedule(context)
        }
    }
    override fun updatePreferences(keys: List<String>, transform: (Map<String, String?>) -> Map<String, String?>) {
        if (persistence.updatePreferences(keys, transform) && keys.any {
            it.endsWith(NativeOutboxStore.QUEUE_SUFFIX) || it.endsWith(NativeOutboxStore.ALLOWED_SUFFIX) ||
                it.endsWith(NativeOutboxStore.FAILED_SUFFIX)
        }) NativeOutboxWork.schedule(context)
    }
    override fun readSecret(key: String) = persistence.readSecret(key)
    override fun writeSecret(key: String, value: String?) {
        persistence.writeSecret(key, value)
        if (key == "device_token") NativeOutboxWork.credentialsChanged(context)
    }

    private fun nativeBusy() = destroyed || stopped || activity.isFinishing || qrResult != null || fileResult != null || exportResult != null || recordingResult != null || permissionInFlight

    private fun requestPermission(permission: String, callback: (Boolean) -> Unit) {
        if (destroyed) { callback(false); return }
        if (ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED) { callback(true); return }
        if (permissionInFlight) { callback(false); return }
        permissionInFlight = true
        permissionResult = callback
        try { permissionLauncher.launch(permission) } catch (_: RuntimeException) {
            permissionInFlight = false
            permissionResult = null
            callback(false)
        }
    }

    override fun scanQr(onResult: (String?) -> Unit) {
        if (nativeBusy()) { onResult(null); return }
        qrResult = onResult
        requestPermission(Manifest.permission.CAMERA) { allowed ->
            if (allowed && qrResult != null) {
                try { qrLauncher.launch(Intent(activity, QrScannerActivity::class.java)) } catch (_: RuntimeException) { finishQr() }
            } else finishQr()
        }
    }

    private fun finishQr() {
        val callback = qrResult
        qrResult = null
        callback?.invoke(null)
    }

    override fun pickFile(kind: String, onResult: (PickedFile?) -> Unit) {
        launchPicker(kind) { onResult(it.firstOrNull()) }
    }

    override fun pickFiles(kind: String, onResult: (List<PickedFile>) -> Unit) {
        launchPicker(when (kind) { "photo" -> "photos"; "document" -> "documents"; else -> kind }, onResult)
    }

    private fun launchPicker(kind: String, onResult: (List<PickedFile>) -> Unit) {
        if (nativeBusy()) { onResult(emptyList()); return }
        fileResult = onResult
        fileKind = kind
        val generation = ++fileGeneration
        try {
            when (kind) {
                "photo", "wallpaper" -> photoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                "document" -> documentLauncher.launch(arrayOf("*/*"))
                "photos" -> photosLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                "documents" -> documentsLauncher.launch(arrayOf("*/*"))
                "camera" -> requestPermission(Manifest.permission.CAMERA) { allowed ->
                    if (generation != fileGeneration) return@requestPermission
                    if (!allowed || fileResult == null) { finishFile(null); return@requestPermission }
                    try {
                        val directory = File(context.cacheDir, "native-camera").apply { mkdirs() }
                        cameraFile = File.createTempFile("capture-", ".jpg", directory)
                        cameraUri = FileProvider.getUriForFile(context, "${context.packageName}.native-files", cameraFile!!)
                        cameraLauncher.launch(cameraUri!!)
                    } catch (_: RuntimeException) { finishFile(null) } catch (_: java.io.IOException) { finishFile(null) }
                }
                else -> finishFile(null)
            }
        } catch (_: RuntimeException) {
            // An earlier callback may have already started another picker.
            if (generation == fileGeneration) finishFile(null)
        }
    }

    private fun receiveFile(uri: Uri?) = receiveFiles(listOfNotNull(uri))

    private fun receiveFiles(uris: List<Uri>) {
        if (fileResult == null) return
        if (uris.isEmpty() || destroyed) { finishFile(null); return }
        val generation = fileGeneration
        fileJob?.cancel()
        fileJob = scope.launch(start = CoroutineStart.LAZY) {
            val files = try {
                withContext(Dispatchers.IO) {
                    val readingContext = currentCoroutineContext()
                    NativeFileTransfers.readSelection(uris.take(PickedFileLimits.MAX_SELECTION).distinct(),
                        checkCancelled = { readingContext.ensureActive() }) { uri, limit ->
                        try { readUri(uri, limit) { readingContext.ensureActive() } }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { null }
                    }
                }
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { emptyList() }
            if (!destroyed && generation == fileGeneration) finishFiles(files)
        }
        fileJob?.start()
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
        val bytes = resolver.openInputStream(uri)?.use { BoundedFiles.read(it, limit, checkCancelled) } ?: return null
        if (bytes.isEmpty()) return null
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val safeName = name?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() } ?: "attachment-${newId()}"
        return PickedFile(safeName, mime, bytes)
    }

    private fun finishFile(file: PickedFile?) {
        finishFiles(listOfNotNull(file))
    }

    private fun finishFiles(files: List<PickedFile>) {
        val callback = fileResult
        fileResult = null
        fileKind = null
        fileGeneration++
        fileJob?.cancel()
        fileJob = null
        val uri = cameraUri
        val temporaryFile = cameraFile
        cameraUri = null
        cameraFile = null
        uri?.let {
            runCatching { context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        }
        runCatching { temporaryFile?.delete() }
        callback?.invoke(files)
    }

    override fun saveFile(file: PickedFile, onResult: (Boolean) -> Unit) {
        if (nativeBusy() || !NativeFileTransfers.valid(file)) { onResult(false); return }
        exportResult = onResult
        // Snapshot bytes because callers may reuse or mutate their buffer while the picker is open.
        exportFile = file.copy(bytes = file.bytes.copyOf())
        val generation = ++exportGeneration
        try {
            saveLauncher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = NativeFileTransfers.mimeType(file.mimeType)
                putExtra(Intent.EXTRA_TITLE, NativeFileTransfers.safeName(file.name))
            })
        } catch (_: RuntimeException) { if (generation == exportGeneration) finishExport(false) }
    }

    private fun receiveSave(uri: Uri?) {
        val file = exportFile ?: return
        if (uri == null || destroyed) { finishExport(false); return }
        val generation = exportGeneration
        exportJob = scope.launch(start = CoroutineStart.LAZY) {
            val success = try {
                withContext(Dispatchers.IO) {
                    val writingContext = currentCoroutineContext()
                    context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                        var offset = 0
                        while (offset < file.bytes.size) {
                            writingContext.ensureActive()
                            val count = minOf(8192, file.bytes.size - offset)
                            output.write(file.bytes, offset, count)
                            offset += count
                        }
                        output.flush()
                        writingContext.ensureActive()
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
        if (nativeBusy() || !NativeFileTransfers.valid(file)) { onResult(false); return }
        exportResult = onResult
        val snapshot = file.copy(bytes = file.bytes.copyOf())
        val generation = ++exportGeneration
        exportJob = scope.launch(start = CoroutineStart.LAZY) {
            var temporary: File? = null
            var handedOff = false
            var success = false
            try {
                // Assign before resuming Main so cancellation cannot orphan a newly written file.
                withContext(Dispatchers.IO) { temporary = NativeFileTransfers.prepareShare(context.cacheDir, snapshot) }
                val shared = temporary
                if (shared != null && !destroyed && !stopped && !activity.isFinishing && generation == exportGeneration) {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.native-files", shared,
                        NativeFileTransfers.safeName(snapshot.name))
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = NativeFileTransfers.mimeType(snapshot.mimeType)
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri("", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.native_share)))
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

    private fun finishExport(success: Boolean) {
        val callback = exportResult
        exportResult = null
        exportFile = null
        exportGeneration++
        exportJob?.cancel()
        exportJob = null
        callback?.invoke(success)
    }

    override fun startRecording(
        onAmplitude: (Float) -> Unit,
        onResult: (PickedFile?) -> Unit,
        onPartial: (PickedFile) -> Unit,
    ) {
        if (nativeBusy()) { onResult(null); return }
        recordingResult = onResult
        val generation = ++recordingGeneration
        requestPermission(Manifest.permission.RECORD_AUDIO) { allowed ->
            if (generation != recordingGeneration || recordingResult == null) return@requestPermission
            if (!allowed) { cancelRecording(); return@requestPermission }
            try {
                val capture = AndroidPcmRecorder(scope,
                    onAmplitude = { value ->
                        if (generation == recordingGeneration && recordingResult != null) onAmplitude(value)
                    },
                    onPartial = { file ->
                        if (generation == recordingGeneration && recordingResult != null) onPartial(file)
                    },
                    onResult = { file ->
                        if (generation == recordingGeneration && recordingResult != null) {
                            pcmRecorder = null
                            val callback = recordingResult
                            recordingResult = null
                            callback?.invoke(file)
                        }
                    })
                pcmRecorder = capture
                capture.start()
            } catch (_: Exception) { cancelRecording() }
        }
    }

    override fun stopRecording() {
        // The recorder preserves the final full clip across repeated Stop calls.
        val capture = pcmRecorder
        if (capture == null) cancelRecording() else capture.stop()
    }

    override fun cancelRecording() {
        recordingGeneration++
        val capture = pcmRecorder
        pcmRecorder = null
        val callback = recordingResult
        recordingResult = null
        try { capture?.cancel() } finally { callback?.invoke(null) }
    }

    override fun haptic() { if (!destroyed) activity.window.decorView.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK) }
    override fun copyText(value: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(context.getString(R.string.app_name), value))
    }
    override fun shareText(value: String) {
        if (destroyed) return
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, value)
        runCatching { activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.native_share))) }
    }

    override fun requestNotifications(onResult: (Boolean) -> Unit) {
        if (nativeBusy()) { onResult(false); return }
        if (Build.VERSION.SDK_INT >= 33) requestPermission(Manifest.permission.POST_NOTIFICATIONS) { allowed ->
            onResult(allowed && NotificationManagerCompat.from(context).areNotificationsEnabled())
        } else onResult(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }

    override fun notifyReply(title: String, body: String, conversationId: String) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val channelId = "conversation-replies"
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(channelId, context.getString(R.string.native_reply_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = context.getString(R.string.native_reply_description)
            lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
        })
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data = Uri.Builder().scheme("claudebot").authority("conversation").appendPath(conversationId).build()
            putExtra("conversationId", conversationId)
        }
        val pending = PendingIntent.getActivity(context, conversationId.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_native_notification)
            .setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(pending).setAutoCancel(true).build()
        try { manager.notify(conversationId, 0, notification) } catch (_: SecurityException) { /* Permission may be revoked between checking and posting. */ }
    }

    override fun nowMillis() = System.currentTimeMillis()
    override fun newId() = UUID.randomUUID().toString()

    private companion object {
        // A new process has no live capture; sweep only our own abandoned files.
        val temporaryFilesCleaned = AtomicBoolean(false)
    }
}
