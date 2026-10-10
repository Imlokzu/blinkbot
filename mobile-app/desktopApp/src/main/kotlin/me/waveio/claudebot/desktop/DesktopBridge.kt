package me.waveio.claudebot.desktop

import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.RGBLuminanceSource
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import me.waveio.claudebot.platform.PickedFile
import me.waveio.claudebot.platform.PickedFileLimits
import me.waveio.claudebot.platform.PlatformBridge
import me.waveio.claudebot.data.PairingCode
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun readBoundedStream(input: InputStream, limit: Int): ByteArray? {
    require(limit >= 0)
    val output = ByteArrayOutputStream(minOf(limit, 8192))
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val count = input.read(buffer, 0, minOf(buffer.size, limit - total + 1))
        if (count < 0) return output.toByteArray()
        if (count == 0) continue
        total += count
        if (total > limit) return null
        output.write(buffer, 0, count)
    }
}

/** JVM desktop integrations for the shared application. UI callbacks are always delivered on Swing's EDT. */
class DesktopBridge(
    private val supportDirectory: Path = defaultSupportDirectory(),
    private val secretStore: DesktopSecretStore = MacKeychainSecretStore(),
    private val captureFactory: (AudioFormat) -> TargetDataLine = { format ->
        AudioSystem.getLine(DataLine.Info(TargetDataLine::class.java, format)) as TargetDataLine
    },
) : PlatformBridge {
    override val platformName = "macos"
    override val deviceName: String get() = "${System.getProperty("os.arch", "Mac")} · macOS ${System.getProperty("os.version", "")}".trim()
    override val appVersionCode = 1
    override val appVersionName = "1.0.0"
    override val systemLanguage: String get() = Locale.getDefault().language.ifBlank { "en" }
    private val foregroundState = MutableStateFlow(true)
    override val foreground: StateFlow<Boolean> = foregroundState
    private val pairingState = MutableStateFlow<String?>(null)
    override val incomingPairing: StateFlow<String?> = pairingState
    override val reducedMotion = false

    private val preferencesFile = supportDirectory.resolve("preferences.properties")
    private val recordingLock = Any()
    private var activeRecording: Recording? = null
    private val shareProcesses = java.util.concurrent.ConcurrentHashMap.newKeySet<Process>()
    private val closed = AtomicBoolean(false)

    fun close() {
        closed.set(true)
        cancelRecording()
        shareProcesses.toList().forEach { it.destroyForcibly() }
    }

    fun setForeground(value: Boolean) { foregroundState.value = value }

    fun deliverIncomingPairing(value: String) {
        if (value.length > 4096 || !value.startsWith("claudebot://pair")) return
        if (runCatching { PairingCode.parse(value) }.isSuccess) pairingState.value = value
    }

    override fun readPreference(key: String): String? = synchronized(preferencesFile.toString().intern()) {
        readPreferences().getProperty(key)
    }

    override fun writePreference(key: String, value: String?) = synchronized(preferencesFile.toString().intern()) {
        val updated = readPreferences()
        if (value == null) updated.remove(key) else updated[key] = value
        writePreferences(updated)
    }

    override fun updatePreferences(keys: List<String>, transform: (Map<String, String?>) -> Map<String, String?>) {
        synchronized(preferencesFile.toString().intern()) {
            val current = readPreferences()
            val changes = transform(keys.associateWith { current.getProperty(it) })
            require(changes.keys.all { it in keys })
            changes.forEach { (key, value) -> if (value == null) current.remove(key) else current[key] = value }
            writePreferences(current)
        }
    }

    override fun readSecret(key: String): String? = secretStore.read(key)
    override fun writeSecret(key: String, value: String?) = secretStore.write(key, value)

    override fun scanQr(onResult: (String?) -> Unit) = onEdt {
        val selected = chooseFiles(false).firstOrNull()
        val value = selected?.let { runCatching { decodeQr(it) }.getOrNull() }
        onResult(value)
    }

    override fun pickFile(kind: String, onResult: (PickedFile?) -> Unit) {
        if (kind == "camera") { onEdt { onResult(null) }; return }
        pickFiles(kind, onResult = { onResult(it.firstOrNull()) })
    }

    override fun pickFiles(kind: String, onResult: (List<PickedFile>) -> Unit) = onEdt {
        if (kind !in setOf("photo", "photos", "document", "documents", "wallpaper")) {
            onResult(emptyList()); return@onEdt
        }
        val selected = chooseFiles(kind == "photos" || kind == "documents")
        onResult(readBoundedSelection(selected))
    }

    override fun saveFile(file: PickedFile, onResult: (Boolean) -> Unit) = onEdt {
        if (file.bytes.size > PickedFileLimits.MAX_BYTES) { onResult(false); return@onEdt }
        val dialog = FileDialog(null as Frame?, null, FileDialog.SAVE)
        dialog.file = safeFileName(file.name)
        dialog.isVisible = true
        val chosen = dialog.file?.let { dialog.directory?.let(Path::of)?.resolve(it) }
        dialog.dispose()
        if (chosen == null) { onResult(false); return@onEdt }
        onResult(runCatching { writeAtomically(chosen, file.bytes); true }.getOrDefault(false))
    }

    override fun shareFile(file: PickedFile, onResult: (Boolean) -> Unit) {
        if (file.bytes.size > PickedFileLimits.MAX_BYTES) { onEdt { onResult(false) }; return }
        Thread({
            var output: Path? = null
            val result = runCatching {
                val directory = supportDirectory.resolve("shared-files")
                Files.createDirectories(directory)
                output = directory.resolve("${UUID.randomUUID()}-${safeFileName(file.name)}")
                writeAtomically(requireNotNull(output), file.bytes)
                share("file", requireNotNull(output).toAbsolutePath().toString())
            }.getOrDefault(false)
            output?.let { runCatching { Files.deleteIfExists(it) } }
            onEdt { onResult(result) }
        }, "blink-native-share").apply { isDaemon = true; start() }
    }

    override fun startRecording(onAmplitude: (Float) -> Unit, onResult: (PickedFile?) -> Unit, onPartial: (PickedFile) -> Unit) {
        val capture = Recording(onAmplitude, onResult, onPartial)
        val accepted = synchronized(recordingLock) {
            if (activeRecording != null) false else {
                activeRecording = capture
                true
            }
        }
        if (!accepted) { onEdt { onResult(null) }; return }
        Thread({ capture.run() }, "blink-desktop-audio").apply { isDaemon = true; start() }
    }

    override fun stopRecording() { synchronized(recordingLock) { activeRecording }?.stop() }

    override fun cancelRecording() { synchronized(recordingLock) { activeRecording }?.cancel() }

    override fun haptic() = Unit

    override fun copyText(value: String) = onEdt {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(value), null)
    }

    override fun shareText(value: String) {
        Thread({ runCatching { share("text", value) } }, "blink-native-share").apply { isDaemon = true; start() }
    }

    private fun share(kind: String, value: String): Boolean {
        if (closed.get()) return false
        if (value.toByteArray(StandardCharsets.UTF_8).size > 2 * 1024 * 1024) return false
        val executable = System.getProperty("blink.share.helper") ?: return false
        val process = ProcessBuilder(executable).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        shareProcesses += process
        try {
            if (closed.get()) return false
            process.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                writer.write(buildJsonObject { put("kind", kind); put("value", value) }.toString())
            }
            if (!process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly(); return false
            }
            val result = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readLine() }
            return process.exitValue() == 0 && result == "OK"
        } finally { shareProcesses -= process; if (process.isAlive) process.destroyForcibly() }
    }

    override fun openExternalUrl(url: String) {
        if (!url.startsWith("https://", ignoreCase = true) || !Desktop.isDesktopSupported()) return
        runCatching { Desktop.getDesktop().browse(java.net.URI(url)) }
    }

    override fun installPackage(file: PickedFile, onResult: (Boolean) -> Unit) = onEdt { onResult(false) }

    override fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    override fun requestNotifications(onResult: (Boolean) -> Unit) = onEdt { onResult(false) }

    override fun notifyReply(title: String, body: String, conversationId: String) {
        if (!System.getProperty("os.name", "").startsWith("Mac", ignoreCase = true)) return
        runCatching {
            val script = "on run argv\n display notification (item 2 of argv) with title (item 1 of argv)\nend run"
            val process = ProcessBuilder("osascript", "-e", script, title, body).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    override fun nowMillis(): Long = System.currentTimeMillis()
    override fun newId(): String = UUID.randomUUID().toString()

    private fun readPreferences(): Properties = Properties().also { properties ->
        if (Files.isRegularFile(preferencesFile)) Files.newInputStream(preferencesFile).use(properties::load)
    }

    private fun writePreferences(properties: Properties) {
        Files.createDirectories(supportDirectory)
        val temp = Files.createTempFile(supportDirectory, ".preferences-", ".tmp")
        try {
            Files.newOutputStream(temp, StandardOpenOption.TRUNCATE_EXISTING).use { properties.store(it, "Blink desktop preferences") }
            runCatching { Files.setPosixFilePermissions(temp, setOf(java.nio.file.attribute.PosixFilePermission.OWNER_READ, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE)) }
            FileChannel.open(temp, StandardOpenOption.WRITE).use { it.force(true) }
            moveAtomically(temp, preferencesFile)
        } finally { Files.deleteIfExists(temp) }
    }

    private fun writeAtomically(path: Path, bytes: ByteArray) {
        require(bytes.size <= PickedFileLimits.MAX_BYTES)
        val parent = path.toAbsolutePath().parent
        Files.createDirectories(parent)
        val temp = Files.createTempFile(parent, ".blink-export-", ".tmp")
        try {
            Files.write(temp, bytes, StandardOpenOption.TRUNCATE_EXISTING)
            moveAtomically(temp, path)
        } finally { Files.deleteIfExists(temp) }
    }

    private fun moveAtomically(from: Path, to: Path) {
        try { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: AtomicMoveNotSupportedException) { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING) }
    }

    private fun chooseFiles(multiple: Boolean): List<Path> {
        val chooser = JFileChooser().apply {
            dialogTitle = null
            isMultiSelectionEnabled = multiple
            isAcceptAllFileFilterUsed = true
        }
        val result = chooser.showOpenDialog(null)
        if (result != JFileChooser.APPROVE_OPTION) return emptyList()
        return (if (multiple) chooser.selectedFiles.toList() else listOfNotNull(chooser.selectedFile))
            .map { it.toPath() }.take(PickedFileLimits.MAX_SELECTION)
    }

    private fun readBoundedSelection(paths: List<Path>): List<PickedFile> {
        var remaining = PickedFileLimits.MAX_BYTES
        return paths.take(PickedFileLimits.MAX_SELECTION).mapNotNull { path ->
            runCatching {
                if (Files.size(path) > remaining) return@runCatching null
                val bytes = Files.newInputStream(path).use { readAtMost(it, remaining) } ?: return@runCatching null
                remaining -= bytes.size
                val name = path.fileName?.toString() ?: "file"
                PickedFile(name, Files.probeContentType(path) ?: "application/octet-stream", bytes)
            }.getOrNull()
        }
    }

    private fun decodeQr(path: Path): String? {
        if (Files.size(path) > PickedFileLimits.MAX_BYTES) return null
        val image = readBoundedImage(path) ?: return null
        val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        return runCatching { MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(image.width, image.height, pixels)))).text }.getOrNull()
    }

    private fun readBoundedImage(path: Path): java.awt.image.BufferedImage? {
        val imageInput = ImageIO.createImageInputStream(path.toFile()) ?: return null
        return imageInput.use {
            val reader = ImageIO.getImageReaders(imageInput).asSequence().firstOrNull() ?: return null
            try {
                reader.input = imageInput
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                if (width <= 0 || height <= 0) return null
                val pixels = width.toLong() * height
                val subsample = maxOf(
                    1,
                    ((width.toLong() + 4095) / 4096).toInt(),
                    ((height.toLong() + 4095) / 4096).toInt(),
                    kotlin.math.ceil(kotlin.math.sqrt(pixels / 16_777_216.0)).toInt(),
                )
                reader.read(0, reader.defaultReadParam.apply { setSourceSubsampling(subsample, subsample, 0, 0) })
            } finally { reader.dispose() }
        }
    }

    private fun safeFileName(name: String): String {
        val cleaned = name.substringAfterLast('/').substringAfterLast('\\').map { char ->
            if (char.code < 32 || char in ":*?\"<>|/") '_' else char
        }.joinToString("").trim().trim('.')
        return cleaned.take(180).ifBlank { "file" }
    }

    private fun <T> onEdt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun readAtMost(input: InputStream, limit: Int): ByteArray? = readBoundedStream(input, limit)

    private inner class Recording(
        private val amplitude: (Float) -> Unit,
        private val result: (PickedFile?) -> Unit,
        private val partial: (PickedFile) -> Unit,
    ) {
        private val stopped = AtomicBoolean(false)
        private val cancelled = AtomicBoolean(false)
        @Volatile private var line: TargetDataLine? = null

        fun stop() { closeCapture(cancel = false) }
        fun cancel() { closeCapture(cancel = true); finish(null, allowCancelled = true) }

        private fun closeCapture(cancel: Boolean) {
            val current = synchronized(recordingLock) {
                stopped.set(true)
                if (cancel) cancelled.set(true)
                line
            }
            current?.let { target ->
                Thread({ runCatching { target.close() } }, "blink-desktop-audio-close").apply {
                    isDaemon = true
                    start()
                }
            }
        }

        fun run() {
            var output: PickedFile? = null
            var ownedCapture: TargetDataLine? = null
            val format = AudioFormat(16_000f, 16, 1, true, false)
            val data = ByteArrayOutputStream()
            try {
                val capture = captureFactory(format)
                ownedCapture = capture
                val acquired = synchronized(recordingLock) {
                    if (stopped.get() || cancelled.get()) false else { line = capture; true }
                }
                if (!acquired) {
                    capture.close()
                    return
                }
                capture.open(format)
                if (stopped.get() || cancelled.get()) return
                capture.start()
                if (stopped.get() || cancelled.get()) return
                val chunk = ByteArray(4096)
                var lastPartial = 0L
                while (!stopped.get() && data.size() + chunk.size <= PickedFileLimits.MAX_BYTES - 44) {
                    val count = capture.read(chunk, 0, chunk.size)
                    if (count <= 0) continue
                    data.write(chunk, 0, count)
                    val level = pcmAmplitude(chunk, count)
                    onEdt { amplitude(level) }
                    val now = System.currentTimeMillis()
                    if (now - lastPartial >= 1500L) {
                        lastPartial = now
                        val snapshot = wavBytes(data.toByteArray(), format)
                        onEdt { partial(PickedFile("recording.wav", "audio/wav", snapshot)) }
                    }
                }
                capture.stop()
                capture.close()
                if (!cancelled.get() && data.size() > 0) output = PickedFile("recording.wav", "audio/wav", wavBytes(data.toByteArray(), format))
            } catch (_: Exception) {
                if (!cancelled.get() && data.size() > 0) output = PickedFile("recording.wav", "audio/wav", wavBytes(data.toByteArray(), format))
            } finally {
                synchronized(recordingLock) { if (line === ownedCapture) line = null }
                runCatching { ownedCapture?.close() }
                finish(output)
            }
        }

        private fun finish(value: PickedFile?, allowCancelled: Boolean = false) {
            val shouldDeliver = synchronized(recordingLock) {
                if (activeRecording !== this) false else { activeRecording = null; true }
            }
            if (shouldDeliver && (allowCancelled || !cancelled.get())) onEdt { result(value) }
        }
    }

    companion object {
        internal fun defaultSupportDirectory(): Path = Path.of(System.getProperty("user.home"), "Library", "Application Support", "Blink")
        internal fun pcmAmplitude(bytes: ByteArray, size: Int): Float {
            if (size < 2) return 0f
            var sum = 0.0
            var count = 0
            var offset = 0
            while (offset + 1 < size) {
                val sample = (bytes[offset].toInt() and 0xff) or (bytes[offset + 1].toInt() shl 8)
                sum += sample.toDouble() * sample
                count++
                offset += 2
            }
            return if (count == 0) 0f else (kotlin.math.sqrt(sum / count) / 32768.0).toFloat().coerceIn(0f, 1f)
        }

        internal fun wavBytes(pcm: ByteArray, format: AudioFormat): ByteArray {
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(StandardCharsets.US_ASCII)); header.putInt(36 + pcm.size)
            header.put("WAVEfmt ".toByteArray(StandardCharsets.US_ASCII)); header.putInt(16)
            header.putShort(1); header.putShort(format.channels.toShort()); header.putInt(format.sampleRate.toInt())
            header.putInt(format.sampleRate.toInt() * format.frameSize); header.putShort(format.frameSize.toShort())
            header.putShort(format.sampleSizeInBits.toShort()); header.put("data".toByteArray(StandardCharsets.US_ASCII)); header.putInt(pcm.size)
            return header.array() + pcm
        }

    }
}

/** Secret values are never logged or written to the preferences file. */
interface DesktopSecretStore {
    fun read(key: String): String?
    fun write(key: String, value: String?)
}

/** Uses a small Security.framework helper with bounded stdin/stdout framing. */
class MacKeychainSecretStore(private val service: String = "me.waveio.blink.desktop") : DesktopSecretStore {
    override fun read(key: String): String? {
        if (!System.getProperty("os.name", "").startsWith("Mac", ignoreCase = true)) return null
        val response = runHelper("READ", key, null)
        return when (response.firstOrNull()) {
            "MISSING" -> null
            "VALUE" -> decode(response.getOrNull(1) ?: error("Keychain helper returned an invalid response"))
            else -> error("Keychain read failed")
        }
    }

    override fun write(key: String, value: String?) {
        if (!System.getProperty("os.name", "").startsWith("Mac", ignoreCase = true)) return
        val response = runHelper(if (value == null) "DELETE" else "WRITE", key, value)
        check(response.firstOrNull() == "OK") { "Keychain write failed" }
    }

    private fun runHelper(operation: String, key: String, value: String?): List<String> {
        require(key.length <= 256 && service.length <= 256 && (value?.toByteArray(StandardCharsets.UTF_8)?.size ?: 0) <= 65_536)
        val executable = System.getProperty("blink.keychain.helper")
            ?.takeIf { it.isNotBlank() }?.let(Path::of)
            ?: error("Keychain helper is not configured")
        require(Files.isExecutable(executable)) { "Keychain helper is unavailable" }
        val process = ProcessBuilder(executable.toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val request = listOf(operation, encode(service), encode(key), value?.let(::encode) ?: "-")
            .joinToString("\n", postfix = "\n").toByteArray(StandardCharsets.UTF_8)
        val responseFuture = java.util.concurrent.CompletableFuture.supplyAsync {
            val output = process.inputStream.use { it.readNBytes(100_001) }
            check(output.size <= 100_000) { "Keychain helper response exceeded its limit" }
            String(output, StandardCharsets.UTF_8).split('\n').let { lines ->
                if (lines.lastOrNull() == "") lines.dropLast(1) else lines
            }
        }
        val requestFuture = java.util.concurrent.CompletableFuture.runAsync {
            process.outputStream.use { it.write(request) }
        }
        if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.inputStream.close()
            process.outputStream.close()
            responseFuture.cancel(true)
            requestFuture.cancel(true)
            error("Keychain operation timed out")
        }
        requestFuture.get(1, java.util.concurrent.TimeUnit.SECONDS)
        val response = responseFuture.get(1, java.util.concurrent.TimeUnit.SECONDS)
        check(process.exitValue() == 0 && response.size <= 2) { "Keychain helper failed" }
        return response
    }

    private fun encode(value: String): String = java.util.Base64.getEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    private fun decode(value: String): String = String(java.util.Base64.getDecoder().decode(value), StandardCharsets.UTF_8)
}
