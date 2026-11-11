package me.waveio.blinkchat

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Only localization keys reach the UI; raw paths and system messages stay private. */
internal object ChatErrors {
    const val PATH = "standalone.error.path"
    const val STORAGE = "standalone.error.storage"
}

internal object ChatLimits {
    const val MEDIA_BYTES = 20 * 1024 * 1024
    const val WORKSPACE_BYTES = 512 * 1024
    const val WORKSPACE_DEPTH = 12
    const val WORKSPACE_ENTRIES = 500
    const val PATH_LENGTH = 512
    const val SEGMENT_LENGTH = 255
}

private val TEXT_EXTENSIONS = setOf("md", "txt", "json", "csv", "html", "js", "css", "kt", "py")

/**
 * Pure sandbox policy so JVM tests can prove the guard without an Android runtime.
 * Returns a normalized relative path or throws; it never touches the filesystem.
 */
fun workspaceRelativePath(path: String): String {
    val candidate = path.trim()
    if (candidate.isEmpty() || candidate.length > ChatLimits.PATH_LENGTH) throw ProviderFailure(ChatErrors.PATH)
    if ('\\' in candidate || candidate.any { it.isISOControl() }) throw ProviderFailure(ChatErrors.PATH)
    // Absolute POSIX paths and Windows drive/UNC forms are outside the notes sandbox.
    if (candidate.startsWith("/") || candidate.matches(Regex("^[A-Za-z]:.*"))) throw ProviderFailure(ChatErrors.PATH)
    val segments = candidate.split('/').filter { it.isNotEmpty() && it != "." }
    if (segments.isEmpty() || segments.size > ChatLimits.WORKSPACE_DEPTH) throw ProviderFailure(ChatErrors.PATH)
    if (segments.any { it == ".." || it.length > ChatLimits.SEGMENT_LENGTH }) throw ProviderFailure(ChatErrors.PATH)
    val relative = segments.joinToString("/")
    if (relative.substringAfterLast('.', "").lowercase() !in TEXT_EXTENSIONS) throw ProviderFailure(ChatErrors.PATH)
    return relative
}

/** Filename and MIME policy shared by private storage and the native pickers. */
internal object ChatFileNames {
    fun display(name: String?): String? = name
        ?.substringAfterLast('/')?.substringAfterLast('\\')
        ?.filterNot { it.isISOControl() }?.trim()?.take(180)
        ?.takeUnless { it.isEmpty() || it == "." || it == ".." }

    fun mime(value: String?): String = value?.trim()?.takeIf {
        it.length <= 127 && it.matches(Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+"))
    } ?: "application/octet-stream"

    /** Generated names carry no user text, so a missing display name needs no localization. */
    fun generated(mime: String, name: String? = null): String = "${UUID.randomUUID()}.${extension(mime, name)}"

    private fun extension(mime: String, name: String?): String = when (mime.lowercase()) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/avif" -> "avif"
        "image/heic" -> "heic"
        "application/pdf" -> "pdf"
        "application/json" -> "json"
        "text/plain" -> "txt"
        "text/markdown" -> "md"
        "text/csv" -> "csv"
        "text/html" -> "html"
        else -> name?.substringAfterLast('.', "")
            ?.takeIf { it.length in 1..8 && it.all { character -> character.isLetterOrDigit() } }
            ?.lowercase() ?: "bin"
    }
}

/** Never trust advertised sizes: a provider or a private file may exceed its budget. */
internal object ChatBoundedBytes {
    fun read(input: InputStream, limit: Int, checkCancelled: () -> Unit = {}): ByteArray? {
        require(limit >= 0)
        val output = ByteArrayOutputStream(minOf(limit, 8192))
        val buffer = ByteArray(8192)
        while (true) {
            checkCancelled()
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), limit.toLong() - output.size() + 1).toInt())
            checkCancelled()
            if (count < 0) return output.toByteArray()
            if (count == 0) {
                val next = input.read()
                checkCancelled()
                if (next < 0) return output.toByteArray()
                if (output.size() == limit) return null
                output.write(next)
            } else {
                if (count > limit - output.size()) return null
                output.write(buffer, 0, count)
            }
        }
    }
}

/**
 * Private persistence for the standalone app: settings and chats in filesDir,
 * the provider key in Keystore-backed ciphertext, media and notes in sandboxes.
 * Callers decide the thread; every failure throws instead of discarding data.
 */
class ChatStorage(context: Context) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val settings = appContext.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
    private val secrets = settings
    private val lock = Any()

    fun loadSettings(): ProviderSettings = synchronized(lock) {
        val raw = settings.getString(KEY_SETTINGS, null) ?: return@synchronized ProviderSettings()
        decode(raw) { json.decodeFromString<ProviderSettings>(it) }
    }

    /** The settings document never carries the API key; that lives only in [writeApiKey]. */
    fun saveSettings(value: ProviderSettings) = synchronized(lock) {
        val committed = settings.edit().putString(KEY_SETTINGS, json.encodeToString(value)).commit()
        if (!committed) throw ProviderFailure(ChatErrors.STORAGE)
    }

    /** Endpoint changes and their encrypted credential commit as one preference transaction. */
    fun saveConnection(value: ProviderSettings, newKey: String?) = synchronized(keystoreLock) {
        synchronized(lock) {
            val previousSettings = settings.getString(KEY_SETTINGS, null)
            val previousCiphertext = settings.getString(API_KEY, null)
            val edit = settings.edit().putString(KEY_SETTINGS, json.encodeToString(value))
            if (newKey != null) {
                if (newKey.isBlank()) edit.remove(API_KEY)
                else edit.putString(API_KEY, encryptSecret(API_KEY, newKey))
            }
            if (!edit.commit()) {
                // SharedPreferences changes its memory cache even when the disk commit fails.
                // Restore the matching old pair before the controller can make another request.
                settings.edit().apply {
                    if (previousSettings == null) remove(KEY_SETTINGS) else putString(KEY_SETTINGS, previousSettings)
                    if (previousCiphertext == null) remove(API_KEY) else putString(API_KEY, previousCiphertext)
                }.commit()
                throw ProviderFailure(ChatErrors.STORAGE)
            }
        }
    }

    fun readApiKey(): String? = synchronized(keystoreLock) { readSecret(API_KEY) }

    fun writeApiKey(value: String?) = synchronized(keystoreLock) {
        try { writeSecret(API_KEY, value) } catch (_: Exception) { throw ProviderFailure(ChatErrors.STORAGE) }
    }

    fun loadChats(): List<StoredChat> = synchronized(lock) {
        val file = File(appContext.filesDir, CHATS_FILE)
        if (!file.exists()) return@synchronized emptyList()
        val bytes = try { AtomicFile(file).readFully() } catch (_: Exception) { throw ProviderFailure(ChatErrors.STORAGE) }
        // A corrupt history must surface at startup, not become an empty list that overwrites it.
        decode(bytes.toString(Charsets.UTF_8)) { json.decodeFromString<List<StoredChat>>(it) }
    }

    fun saveChats(chats: List<StoredChat>) = synchronized(lock) {
        writeAtomic(File(appContext.filesDir, CHATS_FILE), json.encodeToString(chats).toByteArray(Charsets.UTF_8))
    }

    fun saveMedia(bytes: ByteArray, name: String, mimeType: String): StoredMedia {
        if (bytes.isEmpty() || bytes.size > ChatLimits.MEDIA_BYTES) throw ProviderFailure(ChatErrors.STORAGE)
        val mime = ChatFileNames.mime(mimeType)
        // The incoming name is display text only; the on-disk name is always generated.
        val fileName = ChatFileNames.generated(mime, name)
        val file = File(root(MEDIA_DIR), fileName)
        writeAtomic(file, bytes)
        return StoredMedia(file.absolutePath, ChatFileNames.display(name) ?: fileName, mime, bytes.size.toLong())
    }

    fun mediaBytes(path: String): ByteArray {
        val file = mediaFile(path)
        if (!file.isFile) throw ProviderFailure(ChatErrors.PATH)
        return readBounded(file, ChatLimits.MEDIA_BYTES) ?: throw ProviderFailure(ChatErrors.STORAGE)
    }

    fun listWorkspace(): List<String> {
        val root = root(WORKSPACE_DIR)
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown().maxDepth(ChatLimits.WORKSPACE_DEPTH)
            .onEnter { directory -> !Files.isSymbolicLink(directory.toPath()) && (directory == root || contained(root, canonical(directory))) }
            .filter { it.isFile && runCatching { !Files.isSymbolicLink(it.toPath()) }.getOrDefault(false) }
            .mapNotNull { file -> runCatching { workspaceRelativePath(file.relativeTo(root).invariantSeparatorsPath) }.getOrNull() }
            .sorted()
            .take(ChatLimits.WORKSPACE_ENTRIES)
            .toList()
    }

    fun readWorkspace(path: String): String {
        val file = workspaceFile(path)
        if (!file.isFile) throw ProviderFailure(ChatErrors.PATH)
        val bytes = readBounded(file, ChatLimits.WORKSPACE_BYTES) ?: throw ProviderFailure(ChatErrors.STORAGE)
        return try {
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            // Replacement characters would silently rewrite a file the tool cannot represent.
            throw ProviderFailure(ChatErrors.STORAGE)
        }
    }

    fun writeWorkspace(path: String, content: String) {
        val file = workspaceFile(path)
        if (file.exists() && !file.isFile) throw ProviderFailure(ChatErrors.PATH)
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > ChatLimits.WORKSPACE_BYTES) throw ProviderFailure(ChatErrors.STORAGE)
        writeAtomic(file, bytes)
    }

    private fun workspaceFile(path: String): File {
        val relative = workspaceRelativePath(path)
        val root = root(WORKSPACE_DIR)
        val target = canonical(File(root, relative))
        // Canonicalization resolves symlinks, so an inside link to outside is refused here.
        if (!contained(root, target)) throw ProviderFailure(ChatErrors.PATH)
        return target
    }

    private fun mediaFile(path: String): File {
        val candidate = path.trim()
        if (candidate.isEmpty() || '\\' in candidate || candidate.any { it.isISOControl() }) throw ProviderFailure(ChatErrors.PATH)
        val root = root(MEDIA_DIR)
        if (candidate.startsWith("/")) {
            val absolute = canonical(File(candidate))
            if (!contained(root, absolute)) throw ProviderFailure(ChatErrors.PATH)
            return absolute
        }
        val name = if (candidate.startsWith("$MEDIA_DIR/")) candidate.removePrefix("$MEDIA_DIR/") else candidate
        if (name.isEmpty() || '/' in name || name == "." || name == "..") throw ProviderFailure(ChatErrors.PATH)
        val target = canonical(File(root, name))
        if (!contained(root, target)) throw ProviderFailure(ChatErrors.PATH)
        return target
    }

    private fun root(name: String): File {
        val files = canonical(appContext.filesDir)
        val directory = canonical(File(files, name))
        if (!contained(files, directory)) throw ProviderFailure(ChatErrors.PATH)
        return directory
    }

    private fun canonical(file: File): File =
        try { file.canonicalFile } catch (_: Exception) { throw ProviderFailure(ChatErrors.PATH) }

    private fun contained(root: File, file: File) = file.path.startsWith(root.path + File.separator)

    private fun readBounded(file: File, limit: Int): ByteArray? = synchronized(lock) {
        try { file.inputStream().use { ChatBoundedBytes.read(it, limit) } }
        catch (_: Exception) { throw ProviderFailure(ChatErrors.STORAGE) }
    }

    private fun writeAtomic(file: File, bytes: ByteArray) = synchronized(lock) {
        try {
            file.parentFile?.mkdirs()
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try {
                output.write(bytes)
                atomic.finishWrite(output)
            } catch (error: Throwable) {
                atomic.failWrite(output)
                throw error
            }
        } catch (_: Exception) {
            // AtomicFile leaves the previous document intact when the new one cannot commit.
            throw ProviderFailure(ChatErrors.STORAGE)
        }
    }

    private inline fun <T> decode(value: String, block: (String) -> T): T =
        try { block(value) } catch (_: Exception) { throw ProviderFailure(ChatErrors.STORAGE) }

    private fun readSecret(key: String): String? = try {
        val encoded = secrets.getString(key, null) ?: return null
        val data = Base64.decode(encoded, Base64.NO_WRAP)
        if (data.size < 12 + 16) return null
        val secretKey = keyStore().getKey(KEYSTORE_ALIAS, null) as? SecretKey ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, data.copyOfRange(0, 12)))
        cipher.updateAAD(key.toByteArray(Charsets.UTF_8))
        cipher.doFinal(data, 12, data.size - 12).toString(Charsets.UTF_8)
    } catch (_: Exception) {
        // Corrupt preferences, missing keys, and an unavailable Keystore fail closed.
        null
    }

    private fun writeSecret(key: String, value: String?) {
        val edit = secrets.edit()
        if (value == null) {
            edit.remove(key)
        } else {
            edit.putString(key, encryptSecret(key, value))
        }
        if (!edit.commit()) throw ProviderFailure(ChatErrors.STORAGE)
    }

    private fun encryptSecret(key: String, value: String): String {
            val store = keyStore()
            fun newStorageKey(): SecretKey = KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
                init(KeyGenParameterSpec.Builder(KEYSTORE_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build())
                generateKey()
            }
            val secretKey = try { store.getKey(KEYSTORE_ALIAS, null) as? SecretKey }
                catch (_: java.security.UnrecoverableKeyException) { store.deleteEntry(KEYSTORE_ALIAS); null }
                ?: newStorageKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            try { cipher.init(Cipher.ENCRYPT_MODE, secretKey) }
            catch (_: java.security.InvalidKeyException) {
                // A newly pasted key can recover from an invalidated app-owned alias.
                store.deleteEntry(KEYSTORE_ALIAS)
                cipher.init(Cipher.ENCRYPT_MODE, newStorageKey())
            }
            check(cipher.iv.size == 12)
            // Binding the entry name as AAD prevents swapping ciphertext between keys.
            cipher.updateAAD(key.toByteArray(Charsets.UTF_8))
            return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    companion object {
        const val API_KEY = "api_key"
        private const val SETTINGS_FILE = "chat-settings"
        private const val KEY_SETTINGS = "provider.settings.v1"
        private const val CHATS_FILE = "chats.json"
        private const val MEDIA_DIR = "media"
        private const val WORKSPACE_DIR = "workspace"
        private const val KEYSTORE_ALIAS = "me.waveio.blinkchat.secrets.v1"
        // Key creation must be serialized across storage instances in this process.
        private val keystoreLock = Any()
    }
}
