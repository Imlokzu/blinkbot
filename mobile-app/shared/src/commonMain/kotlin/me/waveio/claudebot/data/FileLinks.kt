package me.waveio.claudebot.data

import io.ktor.http.Url

data class FileLinkTarget(val path: String, val source: String, val sessionId: String? = null)

private val fileLinkScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
private val fileLinkSession = Regex("[A-Za-z0-9_-]{1,64}")
private val fileLinkRoutes = setOf("file", "preview", "uploads")

private fun invalidFileLink(): Nothing = throw ApiFailure(0, "invalid_workspace_path")

/** Decode exactly once. URL libraries may replace malformed UTF-8 or normalize dot paths. */
private fun decodeFileLinkPart(value: String): String {
    val decoded = StringBuilder()
    var index = 0
    while (index < value.length) {
        if (value[index] != '%') {
            decoded.append(value[index++])
            continue
        }
        val bytes = mutableListOf<Byte>()
        while (index < value.length && value[index] == '%') {
            if (index + 2 >= value.length) invalidFileLink()
            val high = value[index + 1].digitToIntOrNull(16) ?: invalidFileLink()
            val low = value[index + 2].digitToIntOrNull(16) ?: invalidFileLink()
            bytes += ((high shl 4) or low).toByte()
            index += 3
        }
        try {
            decoded.append(bytes.toByteArray().decodeToString(throwOnInvalidSequence = true))
        } catch (_: Exception) {
            invalidFileLink()
        }
    }
    return decoded.toString().also {
        // Raw Unicode must be well formed too; do not accept lone surrogates.
        try { it.encodeToByteArray(throwOnInvalidSequence = true) } catch (_: Exception) { invalidFileLink() }
    }
}

private fun fileLinkSessionId(query: String?, defaultSession: String): String {
    var session = defaultSession
    val seen = mutableSetOf<String>()
    if (!query.isNullOrEmpty()) {
        for (part in query.split('&')) {
            val key = decodeFileLinkPart(part.substringBefore('='))
            if (key !in setOf("session_id", "r") || !seen.add(key)) invalidFileLink()
            val value = decodeFileLinkPart(part.substringAfter('=', ""))
            if (value.any(Char::isISOControl)) invalidFileLink()
            if (key == "session_id") session = value
        }
    }
    if (session.isNotEmpty() && !fileLinkSession.matches(session)) invalidFileLink()
    return session
}

private fun decodedFileLinkSegments(path: String): List<String> = path.split('/').map {
    val decoded = decodeFileLinkPart(it)
    // An encoded separator must not change the URL's segment structure.
    if ('/' in decoded) invalidFileLink()
    decoded
}

private fun markdownFileLinkPath(path: String, base: String): String {
    val directory = checkedWorkspacePath(base).substringBeforeLast('/', "")
    val resolved = if (directory.isEmpty()) mutableListOf() else directory.split('/').toMutableList()
    val rawSegments = path.removeSuffix("/").split('/')
    for ((raw, decoded) in rawSegments.zip(decodedFileLinkSegments(path.removeSuffix("/")))) {
        when (raw) {
            "." -> Unit
            ".." -> if (resolved.isEmpty()) invalidFileLink() else resolved.removeAt(resolved.lastIndex)
            else -> resolved += checkedWorkspacePath(decoded)
        }
    }
    return checkedWorkspacePath(resolved.joinToString("/"))
}

/**
 * Map owned file links to authenticated API paths without forwarding arbitrary URLs.
 * Workspace paths are decoded once; upload URLs retain their original encoding.
 * Valid foreign HTTP(S) links and unrecognized routes return null unchanged.
 * Unsafe links throw a stable error so callers cannot fall back to a browser.
 * Relative Markdown paths require a canonical workspace base-file path.
 */
fun fileLinkTarget(
    value: String,
    origin: String,
    defaultSession: String = "",
    workspaceBase: String? = null,
): FileLinkTarget? {
    val link = value.substringBefore('#')
    if (link.isEmpty()) return null
    if (link != link.trim() || link.any { it.isISOControl() || it == '\\' }) invalidFileLink()
    val owner = try { Url(normalizeApiOrigin(origin)) } catch (_: Exception) { invalidFileLink() }
    val absolute = fileLinkScheme.containsMatchIn(link)
    val local: String
    if (absolute) {
        val scheme = link.substringBefore(':').lowercase()
        if (scheme !in setOf("https", "http") || !link.substringAfter(':').startsWith("//")) invalidFileLink()
        val remainder = link.substringAfter("://")
        val authority = remainder.takeWhile { it != '/' && it != '?' }
        if (authority.isBlank() || authority.any { it.isWhitespace() || it == '@' || it == '%' }) invalidFileLink()
        val portSuffix = if (authority.startsWith('[')) {
            val bracket = authority.indexOf(']')
            if (bracket <= 1) invalidFileLink()
            authority.substring(bracket + 1)
        } else {
            if (authority.substringBefore(':').isEmpty() || authority.count { it == ':' } > 1 ||
                '[' in authority || ']' in authority) invalidFileLink()
            if (':' in authority) ":" + authority.substringAfter(':') else ""
        }
        if (portSuffix.isNotEmpty()) {
            val port = portSuffix.removePrefix(":")
            if (!portSuffix.startsWith(':') || port.isEmpty() || port.any { it !in '0'..'9' } ||
                port.toIntOrNull() !in 1..65535) invalidFileLink()
        }
        val url = try { Url(link) } catch (_: Exception) { invalidFileLink() }
        if (url.host.isBlank() || url.user != null || url.password != null) invalidFileLink()
        if (url.protocol != owner.protocol || !url.host.equals(owner.host, ignoreCase = true) || url.port != owner.port) return null
        // Inspect the original path: a normalized URL could conceal traversal.
        local = remainder.substring(authority.length).ifEmpty { "/" }
    } else {
        if (link.startsWith("//")) invalidFileLink()
        local = link
    }
    val path = local.substringBefore('?')
    val query = local.substringAfter('?', "").takeIf { '?' in local }
    if (!path.startsWith('/')) {
        if (absolute || workspaceBase == null) return null
        if (path.isEmpty()) invalidFileLink()
        return FileLinkTarget(markdownFileLinkPath(path, workspaceBase), "workspace", fileLinkSessionId(query, defaultSession))
    }
    val rawRoute = path.removePrefix("/").substringBefore('/')
    val route = decodeFileLinkPart(rawRoute)
    if (route.any { it == '/' || it == '\\' || it.isISOControl() }) invalidFileLink()
    if (route !in fileLinkRoutes) return null
    if (rawRoute != route || !path.startsWith("/$route/")) invalidFileLink()
    val relative = path.removePrefix("/$route/")
    if (route == "uploads") {
        if (query != null) invalidFileLink()
        val name = decodeFileLinkPart(relative)
        if (name.isBlank() || name in setOf(".", "..") ||
            name.any { it == '/' || it == '\\' || it == '?' || it == '#' || it == '%' || it.isISOControl() }) invalidFileLink()
        return FileLinkTarget(path, "upload")
    }
    val workspacePath = if (route == "preview") relative.removeSuffix("/") else relative
    val decoded = decodedFileLinkSegments(workspacePath).joinToString("/")
    return FileLinkTarget(checkedWorkspacePath(decoded), "workspace", fileLinkSessionId(query, defaultSession))
}
