package me.waveio.claudebot.ui

import kotlinx.coroutines.awaitCancellation
import me.waveio.claudebot.data.WebPreviewResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DesktopBrowserResourceServerTest {
    @Test
    fun servesOnlyExpectedHostAndGetAndKeepsTheLoaderCredentialFree() {
        val paths = mutableListOf<Pair<String, Boolean>>()
        val server = DesktopBrowserResourceServer(false, { path, main, _ ->
            synchronized(paths) { paths += path to main }
            WebPreviewResource("ok".toByteArray(), "text/plain")
        }, {})
        try {
            val wrongHost = request(server, "/index.html", host = "attacker.localhost:${port(server)}", destination = "document")
            assertEquals(403, wrongHost.status)
            val post = request(server, "/index.html", method = "POST", body = "data", destination = "document")
            assertEquals(403, post.status)
            val valid = request(server, "/assets/main.txt")
            assertEquals(200, valid.status)
            assertEquals("ok", valid.body)
            assertEquals("text/plain; charset=utf-8", valid.headers["content-type"])
            assertEquals(listOf("/assets/main.txt" to false), synchronized(paths) { paths.toList() })
            assertEquals("*", valid.headers["access-control-allow-origin"])
            assertTrue(valid.headers.getValue("content-security-policy").contains("sandbox allow-scripts"))
            assertTrue(valid.headers.getValue("content-security-policy").contains(server.origin))
            assertFalse(valid.headers.getValue("content-security-policy").contains("'self'"))
        } finally {
            server.close()
        }
    }

    @Test
    fun rejectsTraversalAndTrustedEditorDocumentRoutesBeforeLoading() {
        val paths = mutableListOf<Pair<String, Boolean>>()
        val server = DesktopBrowserResourceServer(true, { path, main, _ ->
            synchronized(paths) { paths += path to main }
            WebPreviewResource("editor".toByteArray(), "text/html")
        }, {})
        try {
            assertEquals(403, request(server, "/%2e%2e/private.js").status)
            assertEquals(403, request(server, "/other.html", destination = "document").status)
            val document = request(server, "/index.html", destination = "document")
            assertEquals(200, document.status)
            assertEquals(listOf("/index.html" to true), synchronized(paths) { paths.toList() })
            assertFalse(document.headers.getValue("content-security-policy").contains("sandbox allow-scripts"))
        } finally {
            server.close()
        }
    }

    @Test
    fun reportsMissingMainAndNonSuccessCriticalAssetsButNotOptionalImages() {
        val failures = AtomicInteger()
        val server = DesktopBrowserResourceServer(false, { path, _, _ ->
            when (path) {
                "/index.html" -> WebPreviewResource("error page".toByteArray(), "text/html", 404)
                "/assets/app.js" -> WebPreviewResource("broken".toByteArray(), "text/javascript", 503)
                else -> WebPreviewResource(ByteArray(0), "image/png", 404)
            }
        }, { failures.incrementAndGet() })
        try {
            val main = request(server, "/index.html", destination = "document")
            assertEquals(404, main.status)
            assertEquals("error page", main.body)
            assertEquals(503, request(server, "/assets/app.js", destination = "script").status)
            assertEquals(404, request(server, "/images/missing.png", destination = "image").status)
            assertEquals(1, failures.get()) // The error callback is coalesced per preview.
        } finally {
            server.close()
        }
    }

    @Test
    fun recognizesTheEntryDocumentWhenFetchMetadataIsUnavailable() {
        var mainDocument = false
        val server = DesktopBrowserResourceServer(false, { _, main, _ ->
            mainDocument = main
            WebPreviewResource("entry".toByteArray(), "text/html")
        }, {}, entry = "/app/index.html")
        try {
            assertEquals(200, request(server, "/app/index.html").status)
            assertTrue(mainDocument)
        } finally { server.close() }
    }

    @Test
    fun closeCancelsAnInFlightLoaderAndStopsAcceptingRequests() {
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val server = DesktopBrowserResourceServer(false, { _, _, _ ->
            entered.countDown()
            try {
                awaitCancellation()
            } finally {
                cancelled.countDown()
            }
        }, {})
        val requestThread = Thread { runCatching { request(server, "/assets/slow.js", destination = "script") } }.apply { start() }
        try {
            assertTrue("loader should start", entered.await(3, TimeUnit.SECONDS))
            server.close()
            assertTrue("closing the server cancels the resource callback", cancelled.await(3, TimeUnit.SECONDS))
            requestThread.join(3_000)
            assertFalse("request worker should finish after close", requestThread.isAlive)
            assertFalse("closed server should refuse new connections", runCatching { request(server, "/index.html") }.isSuccess)
        } finally {
            server.close()
            requestThread.join(3_000)
        }
    }

    private data class HttpResult(val status: Int, val headers: Map<String, String>, val body: String)

    private fun port(server: DesktopBrowserResourceServer): Int = server.origin.substringAfterLast(':').toInt()

    private fun request(
        server: DesktopBrowserResourceServer,
        path: String,
        host: String = server.origin.removePrefix("http://"),
        method: String = "GET",
        body: String = "",
        destination: String? = null,
    ): HttpResult {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        Socket("127.0.0.1", port(server)).use { socket ->
            socket.soTimeout = 5_000
            val headers = buildString {
                append("$method $path HTTP/1.1\r\n")
                append("Host: $host\r\n")
                append("Connection: close\r\n")
                if (destination != null) append("Sec-Fetch-Dest: $destination\r\n")
                if (bytes.isNotEmpty()) append("Content-Length: ${bytes.size}\r\n")
                append("\r\n")
            }.toByteArray(StandardCharsets.US_ASCII)
            socket.getOutputStream().apply { write(headers); write(bytes); flush() }
            val response = socket.getInputStream().readBytes().toString(StandardCharsets.ISO_8859_1)
            val split = response.indexOf("\r\n\r\n")
            require(split >= 0) { "Malformed HTTP response: $response" }
            val lines = response.substring(0, split).split("\r\n")
            val status = lines.first().split(' ')[1].toInt()
            val responseHeaders = lines.drop(1).mapNotNull { line ->
                val colon = line.indexOf(':')
                if (colon < 1) null else line.substring(0, colon).lowercase() to line.substring(colon + 1).trim()
            }.toMap()
            val rawBody = response.substring(split + 4)
            val decodedBody = if (responseHeaders["transfer-encoding"] == "chunked") decodeChunked(rawBody) else rawBody
            return HttpResult(status, responseHeaders, decodedBody)
        }
    }

    private fun decodeChunked(raw: String): String {
        val output = StringBuilder()
        var cursor = 0
        while (cursor < raw.length) {
            val end = raw.indexOf("\r\n", cursor)
            if (end < 0) break
            val length = raw.substring(cursor, end).substringBefore(';').toIntOrNull(16) ?: break
            cursor = end + 2
            if (length == 0) break
            output.append(raw.substring(cursor, (cursor + length).coerceAtMost(raw.length)))
            cursor += length + 2
        }
        return output.toString()
    }
}
