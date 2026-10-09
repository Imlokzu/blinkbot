package me.waveio.claudebot.data

import io.ktor.http.Url
import io.ktor.http.decodeURLPart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.waveio.claudebot.resources.Res

@Serializable
data class WorkspaceEditorDocument(
    val id: String,
    val path: String,
    val kind: String,
    val content: String,
    val readOnly: Boolean = false,
    val theme: String = "light",
    val language: String = "en",
    val saveState: String = "saved",
)

data class WorkspaceEditorAction(
    val action: String,
    val sequence: Long,
    val content: String? = null,
    val path: String? = null,
)

/** Remember one session per document ID; capture it when starting an async action. */
class WorkspaceEditorSession {
    private var owner: Any? = null
    private var flushAction: (((Boolean) -> Unit) -> Unit)? = null
    private var resolveAction: ((Long, String?, String?) -> Unit)? = null

    fun flush(callback: (Boolean) -> Unit) {
        val action = flushAction
        if (action == null) callback(false) else action(callback)
    }

    fun resolveAction(sequence: Long, path: String? = null, error: String? = null) {
        resolveAction?.invoke(sequence, path, error)
    }

    internal fun attach(owner: Any, flush: ((Boolean) -> Unit) -> Unit, resolve: (Long, String?, String?) -> Unit) {
        this.owner = owner
        flushAction = flush
        resolveAction = resolve
    }

    internal fun detach(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        flushAction = null
        resolveAction = null
    }
}

const val WorkspaceEditorMaxContentBytes = 2_000_000
const val WorkspaceEditorMaxMessageBytes = WorkspaceEditorMaxContentBytes * 6 + 4096
const val WorkspaceEditorMaxResourceBytes = 20 * 1024 * 1024
const val WorkspaceEditorResourceTimeoutMillis = 15_000L
const val WorkspaceEditorFlushTimeoutMillis = 3_000L
private const val MaxJavaScriptInteger = 9_007_199_254_740_991L
private val editorKinds = setOf("markdown", "code", "text", "html", "drawing", "mermaid")
private val imageMimes = setOf("image/png", "image/jpeg", "image/gif", "image/webp", "image/avif", "image/svg+xml", "image/x-icon", "image/vnd.microsoft.icon")
private val editorErrorCodes = setOf("invalid_document", "document_too_large", "invalid_drawing", "invalid_mermaid", "resource_unavailable", "editor_failed")
private val editorJson = Json { encodeDefaults = true }

fun validWorkspaceEditorResourcePath(path: String): Boolean {
    if (runCatching { checkedWorkspacePath(path) }.isFailure) return false
    val name = path.lowercase()
    return name.endsWith(".excalidraw") || name.endsWith(".excalidraw.json") ||
        name.substringAfterLast('.') in setOf("png", "jpg", "jpeg", "gif", "webp", "avif", "svg", "ico")
}

/** No executable workspace asset may join the trusted editor application. */
fun guardedWorkspaceEditorResource(path: String, resource: WebPreviewResource): WebPreviewResource? {
    if (!validWorkspaceEditorResourcePath(path) || resource.bytes.size > WorkspaceEditorMaxResourceBytes || resource.status !in 200..299) return null
    val mime = resource.mimeType.substringBefore(';').trim().lowercase()
    val drawing = path.lowercase().let { it.endsWith(".excalidraw") || it.endsWith(".excalidraw.json") }
    return resource.copy(mimeType = mime).takeIf {
        mime in imageMimes || drawing && mime in setOf("application/json", "text/plain", "application/octet-stream")
    }
}

/** Decode each path once; encoded separators, URLs and traversal never reach the host. */
fun workspaceEditorResourcePath(encoded: String): String? {
    if (encoded.length > 4096 || !encoded.startsWith('/') || encoded.startsWith("//") ||
        Regex("%(2f|5c)", RegexOption.IGNORE_CASE).containsMatchIn(encoded)) return null
    val decoded = runCatching { encoded.decodeURLPart() }.getOrNull() ?: return null
    val relative = decoded.removePrefix("/")
    return runCatching { checkedWorkspacePath(relative) }.getOrNull()?.let { "/$it" }
}

private fun boundedContent(value: String) = value.length <= WorkspaceEditorMaxContentBytes &&
    value.encodeToByteArray().size <= WorkspaceEditorMaxContentBytes

fun validWorkspaceEditorDocument(document: WorkspaceEditorDocument): Boolean =
    document.id.isNotBlank() && document.id.length <= 256 && document.id.none(Char::isISOControl) &&
        document.kind in editorKinds && document.theme in setOf("light", "dark") && document.language in setOf("en", "uk") &&
        runCatching { checkedWorkspacePath(document.path) }.isSuccess && boundedContent(document.content)

sealed interface WorkspaceEditorEvent {
    data object Ready : WorkspaceEditorEvent
    data class Change(val sequence: Long, val content: String) : WorkspaceEditorEvent
    data class Action(val value: WorkspaceEditorAction) : WorkspaceEditorEvent
    data class Error(val code: String) : WorkspaceEditorEvent
    data class Flushed(val token: String) : WorkspaceEditorEvent
}

/** Frame/origin checks are platform-owned; this checks the document and payload. */
fun parseWorkspaceEditorEvent(raw: String, document: WorkspaceEditorDocument, previousSequence: Long): WorkspaceEditorEvent? {
    if (raw.length > WorkspaceEditorMaxMessageBytes || raw.encodeToByteArray().size > WorkspaceEditorMaxMessageBytes) return null
    val message = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
    fun string(key: String) = (message[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val type = string("type") ?: return null
    if (type == "ready") return WorkspaceEditorEvent.Ready.takeIf { message.keys == setOf("type") }
    if (string("id") != document.id) return null
    if (type == "error") return string("code")?.takeIf { it in editorErrorCodes }?.let { WorkspaceEditorEvent.Error(it) }
    if (type == "flushed") return string("token")?.takeIf { it.isNotBlank() && it.length <= 128 }?.let { WorkspaceEditorEvent.Flushed(it) }
    val sequence = (message["sequence"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        ?.takeIf { it > previousSequence && it in 1..MaxJavaScriptInteger } ?: return null
    if (type == "change") return string("content")?.takeIf { !document.readOnly && boundedContent(it) }
        ?.let { WorkspaceEditorEvent.Change(sequence, it) }
    if (type != "action") return null
    val action = string("action") ?: return null
    val path = string("path")
    val content = string("content")
    val allowed = when (action) {
        "openWorkspace" -> path != null && runCatching { checkedWorkspacePath(path) }.isSuccess && content == null
        "openExternal" -> path != null && path.length <= 4096 && path.none(Char::isISOControl) && content == null &&
            runCatching { Url(path).let { it.protocol.name == "https" && it.host.isNotBlank() && it.user == null && it.password == null } }.getOrDefault(false)
        "convertMermaid" -> document.kind == "mermaid" && path == null && content != null && boundedContent(content)
        "exportDrawing" -> document.kind in setOf("drawing", "mermaid") && path == null && content != null && boundedContent(content) &&
            content.startsWith("data:image/png;base64,") && content.removePrefix("data:image/png;base64,").let { payload ->
                payload.isNotEmpty() && payload.length % 4 == 0 && Regex("[A-Za-z0-9+/]+={0,2}").matches(payload)
            }
        "createDrawing" -> !document.readOnly && document.kind == "markdown" && path == null && content == null
        "retry" -> path == null && content == null
        else -> false
    }
    return WorkspaceEditorEvent.Action(WorkspaceEditorAction(action, sequence, content, path)).takeIf { allowed }
}

/** Application-owned assets are loaded solely from the generated bundle manifest. */
@Serializable
data class WorkspaceEditorManifest(val entry: String, val files: Map<String, String>)

private val manifestJson = Json { ignoreUnknownKeys = true }

suspend fun loadWorkspaceEditorManifest(): WorkspaceEditorManifest {
    val bytes = Res.readBytes("files/workspace-editor/manifest.json")
    require(bytes.size <= 512 * 1024)
    val manifest = manifestJson.decodeFromString<WorkspaceEditorManifest>(bytes.decodeToString())
    require(manifest.entry == "index.html" && manifest.files[manifest.entry] == "text/html")
    require(manifest.files.size <= 2048 && manifest.files.all { (path, mime) ->
        workspaceEditorResourcePath("/$path") == "/$path" && !path.startsWith("workspace/") &&
            Regex("[a-z0-9.+-]+/[a-z0-9.+-]+").matches(mime)
    })
    return manifest
}

suspend fun loadWorkspaceEditorAsset(manifest: WorkspaceEditorManifest, path: String): WebPreviewResource? {
    val relative = path.removePrefix("/")
    val mime = manifest.files[relative] ?: return null
    val bytes = Res.readBytes("files/workspace-editor/$relative")
    return WebPreviewResource(bytes, mime).takeIf { bytes.size <= WorkspaceEditorMaxResourceBytes }
}

/** Trusted document CSP: no sandbox origin, no remote resources, frames or workers. */
val WorkspaceEditorResponseHeaders = mapOf(
    "Access-Control-Allow-Origin" to "*",
    "Access-Control-Allow-Methods" to "GET",
    "Content-Security-Policy" to ("default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; " +
        "img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self'; media-src 'none'; " +
        "object-src 'none'; worker-src 'none'; frame-src 'none'; child-src 'none'; " +
        "frame-ancestors 'none'; form-action 'none'; base-uri 'none'; manifest-src 'none'"),
    "Cache-Control" to "no-store",
    "X-Content-Type-Options" to "nosniff",
    "Referrer-Policy" to "no-referrer",
    "Permissions-Policy" to "camera=(), microphone=(), geolocation=(), payment=(), usb=()",
)

/** Shared handshake, sequence and flush state; all calls belong to the UI thread. */
class WorkspaceEditorBridge(
    document: WorkspaceEditorDocument,
    private val scope: CoroutineScope,
    private val evaluate: (String, (Boolean) -> Unit) -> Unit,
    var onChange: (String) -> Unit,
    var onAction: (WorkspaceEditorAction) -> Unit,
    var onError: (String) -> Unit,
) {
    private var document = document
    private var alive = true
    private var ready = false
    private var sequence = 0L
    private var flushCounter = 0L
    private val flushes = mutableMapOf<String, Pair<(Boolean) -> Unit, Job>>()
    private val actions = mutableSetOf<Long>()
    private val readyTimeout = scope.launch {
        delay(WorkspaceEditorResourceTimeoutMillis)
        if (alive && !ready) onError("editor_failed")
    }

    fun update(document: WorkspaceEditorDocument) {
        if (!alive || document.id != this.document.id || !validWorkspaceEditorDocument(document)) return
        val previous = this.document
        this.document = document
        if (ready && previous.copy(content = "") != document.copy(content = "")) {
            val metadata = editorJson.encodeToJsonElement(document).jsonObject.filterKeys { it != "content" }
            call("updateHost", JsonObject(metadata).toString())
        }
    }

    fun receive(raw: String) {
        if (!alive) return
        val event = parseWorkspaceEditorEvent(raw, document, sequence) ?: return
        when (event) {
            WorkspaceEditorEvent.Ready -> if (!ready) {
                if (!validWorkspaceEditorDocument(document)) { onError("invalid_document"); return }
                ready = true
                readyTimeout.cancel()
                call("openDocument", editorJson.encodeToString(document))
            }
            is WorkspaceEditorEvent.Change -> if (ready) {
                sequence = event.sequence
                onChange(event.content)
            }
            is WorkspaceEditorEvent.Action -> if (ready) {
                sequence = event.value.sequence
                if (event.value.action == "createDrawing") actions += event.value.sequence
                onAction(event.value)
            }
            is WorkspaceEditorEvent.Error -> onError(event.code)
            is WorkspaceEditorEvent.Flushed -> flushes.remove(event.token)?.let { (callback, timeout) -> timeout.cancel(); callback(true) }
        }
    }

    fun flush(callback: (Boolean) -> Unit) {
        if (!alive || !ready) { callback(false); return }
        val token = "flush-${++flushCounter}"
        val timeout = scope.launch {
            delay(WorkspaceEditorFlushTimeoutMillis)
            flushes.remove(token)?.first?.invoke(false)
        }
        flushes[token] = callback to timeout
        call("flush", Json.encodeToString(token)) { success ->
            if (!success) flushes.remove(token)?.let { (waiting, timer) -> timer.cancel(); waiting(false) }
        }
    }

    fun resolveAction(sequence: Long, path: String?, error: String?) {
        if (!alive || !ready || !actions.remove(sequence)) return
        val validPath = path?.takeIf { runCatching { checkedWorkspacePath(it) }.isSuccess }
        val payload = buildJsonObject {
            put("id", document.id); put("sequence", sequence)
            validPath?.let { put("path", it) }
            if (error != null || validPath == null) put("error", error?.take(128) ?: "invalid_document")
        }
        call("resolveAction", payload.toString())
    }

    private fun call(method: String, argument: String, done: ((Boolean) -> Unit)? = null) {
        val safeArgument = argument.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
        evaluate("(() => { if (!window.BlinkWorkspace || typeof window.BlinkWorkspace.$method !== 'function') return false; window.BlinkWorkspace.$method($safeArgument); return true; })()") { success ->
            if (alive) { if (!success) onError("editor_failed"); done?.invoke(success) }
        }
    }

    fun close() {
        if (!alive) return
        alive = false
        readyTimeout.cancel()
        val pending = flushes.values.toList()
        flushes.clear(); actions.clear()
        pending.forEach { (callback, timeout) -> timeout.cancel(); callback(false) }
    }
}
