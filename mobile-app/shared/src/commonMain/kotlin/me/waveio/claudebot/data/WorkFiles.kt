package me.waveio.claudebot.data

import kotlinx.serialization.json.*

/** A tool-backed workspace output, never a link inferred from assistant prose. */
data class WorkFile(
    val path: String,
    val kind: String,
    val revision: Int = 0,
    val active: Boolean = false,
) {
    val name: String get() = path.substringAfterLast('/')
    val mimeType: String get() = workspaceMimeType(path)
}

private val workspaceTool = Regex("(?:^|__)workspace_(write|show|delete)$")
private val nativeWrites = setOf("write", "write_file")
private val completed = setOf("done", "completed", "success")
private val active = setOf("active", "running")

/** Only canonical relative paths may enter the owner's workspace API. */
internal fun checkedWorkspacePath(path: String): String {
    if (path.isBlank() || path != path.trim() || path.length > 1024 ||
        path.startsWith('/') || path.any { it == '\\' || it == ':' || it == '%' || it == '?' || it == '#' || it.isISOControl() } ||
        path.split('/').any { it.isBlank() || it in setOf(".", "..") }) {
        throw ApiFailure(0, "invalid_workspace_path")
    }
    return path
}

private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun objectValue(value: JsonElement?): JsonObject? = when (value) {
    is JsonObject -> value
    is JsonPrimitive -> value.string()?.takeIf { it.trimStart().startsWith('{') }?.let {
        runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull()
    }
    else -> null
}

/** MCP may wrap structured JSON in any text block, not necessarily block zero. */
private fun objects(value: JsonElement?, depth: Int = 0): List<JsonObject> {
    if (depth > 6) return emptyList()
    val obj = objectValue(value) ?: return emptyList()
    return listOf(obj) + listOf("structuredContent", "result", "output").flatMap { objects(obj[it], depth + 1) } +
        (obj["content"] as? JsonArray).orEmpty().take(40).flatMap { block ->
            objects((block as? JsonObject)?.get("text"), depth + 1)
        }
}

private fun failed(value: JsonElement?): Boolean = objects(value).any { obj ->
    obj["error"]?.let { it != JsonNull && it != JsonPrimitive(false) && it != JsonPrimitive("") } == true ||
        listOf("isError", "is_error").any { (obj[it] as? JsonPrimitive)?.booleanOrNull == true } ||
        listOf("ok", "success").any { (obj[it] as? JsonPrimitive)?.booleanOrNull == false }
}

private fun resultTexts(value: JsonElement?): List<String> {
    return listOfNotNull(value.string()) + objects(value).flatMap { item ->
        listOfNotNull(item["text"].string()) + (item["content"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("text").string() }
    }
}

private val wrote = Regex("^Successfully wrote(?:\\s+\\d+\\s+bytes)?\\s+to\\s+(.+?)[\\r\\n]*$", RegexOption.IGNORE_CASE)

fun workspaceRootFromSteps(steps: List<ToolStep>): String? = steps.asReversed().firstNotNullOfOrNull { step ->
    if (Regex("(?:^|__)workspace_info$").containsMatchIn(step.label.orEmpty()) && step.status in completed && !failed(step.result))
        objects(step.result).firstNotNullOfOrNull { it["root"].string()?.takeIf { root -> root.startsWith('/') && !root.contains("/../") } }
    else null
}

private fun pathFor(step: ToolStep, root: String?): String? {
    val output = objects(step.result).firstNotNullOfOrNull { obj ->
        listOf("path", "shown", "file_path", "filePath").firstNotNullOfOrNull { obj[it].string()?.takeIf(String::isNotBlank) }
    } ?: resultTexts(step.result).firstNotNullOfOrNull { wrote.matchEntire(it.trim())?.groupValues?.get(1) }
    val input = objectValue(step.input)
    val candidate = output ?: listOf("path", "file_path", "filePath").firstNotNullOfOrNull { input?.get(it).string()?.takeIf(String::isNotBlank) }
        ?: step.detail?.takeIf { workspaceTool.containsMatchIn(step.label.orEmpty()) && it.isNotBlank() }
        ?: return null
    // Absolute native paths need a tool-disclosed workspace root. Guessing a
    // substring named "workspace" could silently expose an unrelated file.
    val relative = if (candidate.startsWith('/')) {
        val prefix = root?.trimEnd('/')?.plus('/') ?: return null
        if (!candidate.startsWith(prefix)) return null
        candidate.removePrefix(prefix)
    } else candidate
    return runCatching { checkedWorkspacePath(relative) }.getOrNull()
}

/** Saved and live activity share the same extraction; unsuccessful calls create no files. */
fun collectWorkFiles(steps: List<ToolStep>, workspaceRoot: String? = null): List<WorkFile> {
    val files = linkedMapOf<String, WorkFile>()
    var root = workspaceRoot
    for (step in steps) {
        val label = step.label.orEmpty()
        if (Regex("(?:^|__)workspace_info$").containsMatchIn(label) && step.status in completed && !failed(step.result)) {
            root = objects(step.result).firstNotNullOfOrNull { it["root"].string() } ?: root
        }
        val tool = workspaceTool.find(label)?.groupValues?.get(1)
            ?: "write".takeIf { label.lowercase() in nativeWrites }
            ?: continue
        if (step.status !in completed && step.status !in active || failed(step.result)) continue
        // Native writes require an actual result; old workspace saves may
        // retain only a successful status and their original input.
        if (label.lowercase() in nativeWrites && step.status in completed &&
            objects(step.result).none { obj -> listOf("path", "file_path", "filePath").any { obj[it].string()?.isNotBlank() == true } } &&
            resultTexts(step.result).none { wrote.matches(it.trim()) }) continue
        val path = pathFor(step, root) ?: continue
        val old = files[path]
        if (tool == "delete") {
            if (step.status in completed) files.remove(path)
            continue
        }
        files.remove(path)
        files[path] = WorkFile(path, workspaceFileKind(path), (old?.revision ?: 0) + if (tool == "write" && step.status in completed) 1 else 0, step.status in active)
    }
    return files.values.reversed()
}

fun workspaceFileKind(path: String): String {
    val name = path.lowercase()
    if (name.endsWith(".excalidraw") || name.endsWith(".excalidraw.json")) return "drawing"
    return when (name.substringAfterLast('.', "")) {
        "html", "htm" -> "html"
        "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "avif" -> "image"
        "md", "markdown" -> "markdown"
        "mmd", "mermaid" -> "mermaid"
        "js", "jsx", "ts", "tsx", "py", "json", "css", "sh", "yaml", "yml", "toml", "sql" -> "code"
        else -> "text"
    }
}

fun workspaceMimeType(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "svg" -> "image/svg+xml"
    "avif" -> "image/avif"
    "ico" -> "image/x-icon"
    "pdf" -> "application/pdf"
    "doc" -> "application/msword"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "xls" -> "application/vnd.ms-excel"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "ppt" -> "application/vnd.ms-powerpoint"
    "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    "odt" -> "application/vnd.oasis.opendocument.text"
    "ods" -> "application/vnd.oasis.opendocument.spreadsheet"
    "odp" -> "application/vnd.oasis.opendocument.presentation"
    "zip" -> "application/zip"
    "tar" -> "application/x-tar"
    "gz" -> "application/gzip"
    "7z" -> "application/x-7z-compressed"
    "rar" -> "application/vnd.rar"
    "rtf" -> "application/rtf"
    "csv" -> "text/csv"
    "xml" -> "application/xml"
    "html", "htm" -> "text/html"
    "json", "excalidraw" -> "application/json"
    "md", "markdown" -> "text/markdown"
    "txt", "text", "log", "mmd", "mermaid", "js", "jsx", "ts", "tsx", "py", "css", "sh", "yaml", "yml", "toml", "sql" -> "text/plain"
    else -> "application/octet-stream"
}
