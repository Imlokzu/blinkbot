package me.waveio.claudebot.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.ktor.http.decodeURLPart
import me.waveio.claudebot.data.WebPreviewResource

/**
 * Runs a built web app in a disposable, credential-free native origin.
 * [entry] is relative to the preview root. [loadResource] receives a decoded,
 * leading-slash path only, never an origin, query, fragment or request headers.
 * Changing [entry] or [revision] replaces the entire browsing context.
 * The document has an opaque sandbox origin: localStorage/sessionStorage and
 * other origin-backed persistence are unavailable, including to child frames.
 * HTML is served only for top-level navigation. Fetching HTML fragments or
 * importing an HTML fallback as a script/style reports an unsupported response.
 */
@Composable
expect fun NativeWebAppPreview(
    entry: String,
    revision: Long,
    loadResource: suspend (String) -> WebPreviewResource?,
    onError: () -> Unit,
    modifier: Modifier = Modifier,
)

internal const val PreviewResourceTimeoutMillis = 15_000L
internal const val PreviewMaxResourceBytes = 32 * 1024 * 1024

internal const val PreviewCsp = "default-src 'none'; " +
    "script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; " +
    "img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self'; " +
    "media-src 'self' data: blob:; object-src 'none'; worker-src 'none'; " +
    "child-src 'none'; frame-src 'none'; frame-ancestors 'none'; " +
    "form-action 'none'; base-uri 'none'; manifest-src 'none'; " +
    "sandbox allow-scripts"

internal val PreviewResponseHeaders = mapOf(
    "Access-Control-Allow-Origin" to "*",
    "Access-Control-Allow-Methods" to "GET",
    "Content-Security-Policy" to PreviewCsp,
    "Cache-Control" to "no-store",
    "X-Content-Type-Options" to "nosniff",
    "Referrer-Policy" to "no-referrer",
    "Permissions-Policy" to "camera=(), microphone=(), geolocation=(), payment=(), usb=()",
)

/** Decode once; the transport must encode the resulting path as one query value. */
internal fun previewResourcePath(encodedPath: String): String? {
    if (encodedPath.length > 4096 || Regex("%(2f|5c)", RegexOption.IGNORE_CASE).containsMatchIn(encodedPath)) return null
    val path = runCatching { encodedPath.decodeURLPart() }.getOrNull() ?: return null
    if (path.any { it <= '\u001f' || it == '\u007f' || it in "\\%?#:" }) return null
    val segments = path.removePrefix("/").split('/')
    if (segments.any { it == "." || it == ".." } || path.startsWith("//")) return null
    return "/" + segments.joinToString("/")
}

internal fun previewEntryPath(entry: String): String? {
    if (entry.isBlank() || entry.contains('%')) return null
    return previewResourcePath(entry)?.takeUnless { it == "/" || it.endsWith('/') }
}

internal fun previewSpaRoute(path: String): Boolean = !path.substringAfterLast('/').contains('.')

private val PreviewJavaScriptMimes = setOf("text/javascript", "application/javascript", "text/ecmascript", "application/ecmascript")

internal fun previewCriticalAsset(path: String, mime: String? = null, destination: String? = null): Boolean =
    destination in setOf("script", "style") || path.substringAfterLast('.').lowercase() in setOf("js", "mjs", "css") ||
        mime == "text/css" || mime in PreviewJavaScriptMimes

/** Fetch Metadata is supplied by some native providers, not guaranteed by the callback API. */
internal fun previewAssetMimeMatches(path: String, mime: String, destination: String? = null): Boolean = when {
    destination == "script" -> mime in PreviewJavaScriptMimes
    destination == "style" -> mime == "text/css"
    path.substringAfterLast('.').lowercase() in setOf("js", "mjs") -> mime in PreviewJavaScriptMimes
    path.substringAfterLast('.').lowercase() == "css" -> mime == "text/css"
    else -> true
}

// Defense in depth at document start. Engine-created initial blank documents may
// precede injection; the opaque sandbox prevents application code from accessing
// those realms. Origin/path checks and credential-free callbacks remain mandatory.
internal val PreviewBootstrap = """
    (() => {
      const lock = (object, name, value) => {
        try { Object.defineProperty(object, name, {value, writable: false, configurable: false}); } catch (_) {}
      };
      const deny = function() { throw new DOMException('', 'SecurityError'); };
      for (const name of ['WebSocket', 'WebTransport', 'RTCPeerConnection', 'webkitRTCPeerConnection',
                          'Worker', 'SharedWorker', 'EventSource']) lock(globalThis, name, deny);
      lock(globalThis, 'open', () => null);
      if (globalThis.ServiceWorkerContainer) lock(ServiceWorkerContainer.prototype, 'register', deny);
      if (globalThis.Navigator) lock(Navigator.prototype, 'sendBeacon', () => false);
      try { Object.defineProperty(Document.prototype, 'cookie', {get: () => '', set: () => {}, configurable: false}); } catch (_) {}
      document.addEventListener('submit', event => event.preventDefault(), true);
      document.addEventListener('click', event => {
        const link = event.target.closest && event.target.closest('a');
        if (link && link.hasAttribute('download')) event.preventDefault();
      }, true);
    })();
""".trimIndent()

/** Validate host responses; platform document-start injection leaves HTML bytes intact. */
internal fun guardedPreviewResource(resource: WebPreviewResource): WebPreviewResource? {
    if (resource.bytes.size > PreviewMaxResourceBytes || resource.status !in 200..299 && resource.status !in 400..599) return null
    val mime = resource.mimeType.substringBefore(';').trim().lowercase()
    if (!Regex("[a-z0-9.+-]+/[a-z0-9.+-]+").matches(mime)) return null
    return resource.copy(mimeType = mime)
}
