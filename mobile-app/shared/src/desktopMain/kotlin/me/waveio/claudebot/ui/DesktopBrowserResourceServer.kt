package me.waveio.claudebot.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.waveio.claudebot.data.WebPreviewResource
import me.waveio.claudebot.data.WorkspaceEditorMaxResourceBytes
import me.waveio.claudebot.data.WorkspaceEditorResponseHeaders
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import com.sun.net.httpserver.HttpServer

/** Private loopback transport for browser resources; request credentials never reach [loadResource]. */
internal class DesktopBrowserResourceServer(
    private val trusted: Boolean,
    private val loadResource: suspend (path: String, mainDocument: Boolean, origin: String) -> WebPreviewResource?,
    private val onFailure: () -> Unit,
    private val entry: String = "/index.html",
) : AutoCloseable {
    private val host = "${if (trusted) "editor" else "preview"}-${UUID.randomUUID()}.localhost"
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val executor = Executors.newFixedThreadPool(4) { action ->
        Thread(action, "blink-browser-resource").apply { isDaemon = true }
    }
    val origin = "http://$host:${server.address.port}"
    private val alive = AtomicBoolean(true)
    private val failureReported = AtomicBoolean(false)
    private val requests = SupervisorJob()

    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                val path = desktopBrowserPath("$origin${exchange.requestURI}", origin, trusted)
                val destination = exchange.requestHeaders.getFirst("Sec-Fetch-Dest")
                val mainDocument = destination == "document" || destination == null && path == entry
                val valid = alive.get() && exchange.requestMethod == "GET" &&
                    exchange.requestHeaders.getFirst("Host") == "$host:${server.address.port}" && path != null &&
                    (!mainDocument || !trusted || path == "/index.html")

                val resource = if (valid) runCatching {
                    runBlocking(requests + Dispatchers.IO) {
                        withTimeout(PreviewResourceTimeoutMillis) {
                            loadResource(requireNotNull(path), mainDocument, origin)
                        }
                    }
                }.getOrNull()?.takeIf(::isSafeResource) else null

                val critical = mainDocument || previewCriticalAsset(path.orEmpty(), resource?.mimeType, destination)
                if (valid && critical && (resource == null || resource.status !in 200..299)) reportFailure()

                val response = resource ?: WebPreviewResource(ByteArray(0), "text/plain", 403)
                (if (trusted) WorkspaceEditorResponseHeaders else previewHeaders()).forEach { (name, value) ->
                    exchange.responseHeaders.set(name, value)
                }
                val mime = safeMime(response.mimeType)
                val contentType = if (mime.startsWith("text/") || mime in setOf("application/javascript", "application/json", "image/svg+xml"))
                    "$mime; charset=utf-8" else mime
                exchange.responseHeaders.set("Content-Type", contentType)
                exchange.responseHeaders.set("Connection-Allowlist", "(\"$origin/*\"); webrtc=block")
                exchange.sendResponseHeaders(response.status, if (response.bytes.isEmpty()) -1 else response.bytes.size.toLong())
                if (response.bytes.isNotEmpty()) exchange.responseBody.write(response.bytes)
            } finally {
                exchange.close()
            }
        }
        server.start()
    }

    private fun isSafeResource(resource: WebPreviewResource): Boolean {
        val maximum = if (trusted) WorkspaceEditorMaxResourceBytes else PreviewMaxResourceBytes
        val validStatus = resource.status in 200..299 || resource.status in 400..599
        return resource.bytes.size <= maximum && validStatus && safeMimeOrNull(resource.mimeType) != null
    }

    private fun safeMime(value: String): String = safeMimeOrNull(value) ?: "text/plain"

    private fun safeMimeOrNull(value: String): String? {
        val mime = value.substringBefore(';').trim().lowercase()
        return mime.takeIf { Regex("[a-z0-9.+-]+/[a-z0-9.+-]+").matches(it) }
    }

    private fun previewHeaders(): Map<String, String> = PreviewResponseHeaders +
        ("Content-Security-Policy" to PreviewCsp.replace("'self'", origin))

    private fun reportFailure() {
        if (alive.get() && failureReported.compareAndSet(false, true)) onFailure()
    }

    override fun close() {
        if (!alive.compareAndSet(true, false)) return
        requests.cancel(CancellationException("Browser resource server closed"))
        server.stop(0)
        executor.shutdownNow()
    }
}
