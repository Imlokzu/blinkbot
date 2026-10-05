package me.waveio.claudebot.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The native app receives assets and public statuses, never backend diagnostics. */
class BotApiWebPreviewTest {
    private val clients = mutableListOf<HttpClient>()
    private val apis = mutableListOf<BotApi>()

    private fun api(engine: MockEngine): BotApi {
        val client = HttpClient(engine)
        clients += client
        return BotApi("https://paired.example:8443", "fixture-device", client).also { apis += it }
    }

    @AfterTest fun closeClients() {
        apis.forEach { it.close() }
        clients.forEach { it.close() }
    }

    @Test fun metadataUsesTheAuthenticatedOwnerAndSessionEndpoint() = runTest {
        val api = api(MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("https", request.url.protocol.name)
            assertEquals("paired.example", request.url.host)
            assertEquals(8443, request.url.port)
            assertEquals("/api/mobile/workspace/web-preview", request.url.encodedPath)
            assertEquals("project/index.html", request.url.parameters["path"])
            assertEquals("chat_1", request.url.parameters["session_id"])
            assertEquals("Bearer fixture-device", request.headers[HttpHeaders.Authorization])
            respond("""{"ready":true,"root":"project/dist","entry":"index.html","project_path":"project","kind":"web","buildable":true}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val preview = api.webPreview("project/index.html", "chat_1")
        assertEquals(WebPreview(true, "project/dist", "index.html", "project", "web", buildable = true), preview)
    }

    @Test fun successfulAssetsKeepOriginalBytesMimeAndActualStatus() = runTest {
        val bytes = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte(), 13, 10, 0, 0xff.toByte())
        for (status in listOf(HttpStatusCode.OK, HttpStatusCode.Created, HttpStatusCode.PartialContent, HttpStatusCode.NoContent)) {
            val expected = if (status == HttpStatusCode.NoContent) ByteArray(0) else bytes
            val api = api(MockEngine { request ->
                assertEquals(HttpMethod.Get, request.method)
                assertEquals("https", request.url.protocol.name)
                assertEquals("paired.example", request.url.host)
                assertEquals(8443, request.url.port)
                assertEquals("/api/mobile/workspace/web-resource", request.url.encodedPath)
                assertEquals("project/dist", request.url.parameters["root"])
                assertEquals("assets/my asset.wasm", request.url.parameters["path"])
                assertEquals("index.html", request.url.parameters["entry"])
                assertEquals("chat_1", request.url.parameters["session_id"])
                assertEquals("Bearer fixture-device", request.headers[HttpHeaders.Authorization])
                respond(expected, status, headersOf(HttpHeaders.ContentType, "application/wasm; charset=binary"))
            })
            val resource = api.webPreviewResource("project/dist", "assets/my asset.wasm", "index.html", "chat_1")
            assertContentEquals(expected, resource.bytes)
            assertEquals("application/wasm", resource.mimeType)
            assertEquals(status.value, resource.status)
        }
    }

    @Test fun missingContentTypeHasAConservativeBinaryDefault() = runTest {
        val bytes = byteArrayOf(0, 1, 2)
        val api = api(MockEngine { respond(bytes) })
        val resource = api.webPreviewResource("project/dist", "asset.bin", "index.html", "")
        assertContentEquals(bytes, resource.bytes)
        assertEquals("application/octet-stream", resource.mimeType)
        assertEquals(200, resource.status)
    }

    @Test fun backendErrorsKeepTheirStatusButExposeNoDetailsOrBytes() = runTest {
        for (status in listOf(400, 401, 403, 404, 429, 503, 599)) {
            val api = api(MockEngine {
                respond("""{"detail":{"code":"private_diagnostic","path":"/private/fixture/file"}}""",
                    HttpStatusCode.fromValue(status), headersOf(
                        HttpHeaders.ContentType to listOf("application/json"),
                        // Error bodies are discarded before the success-body reader.
                        HttpHeaders.ContentLength to listOf((20L * 1024 * 1024 + 1).toString()),
                    ))
            })
            val resource = api.webPreviewResource("project/dist", "missing.js", "index.html", "chat_1")
            assertEquals(status, resource.status)
            assertEquals("text/plain", resource.mimeType)
            assertTrue(resource.bytes.isEmpty())
        }
    }

    @Test fun redirectsAreEmptyForbiddenResponsesAndNeverFollowed() = runTest {
        for (status in listOf(301, 302, 303, 304, 307, 308)) {
            for (destination in listOf("https://outside.example/private", "https://paired.example:8443/api/setup")) {
                var requests = 0
                val api = api(MockEngine { request ->
                    requests++
                    assertEquals("paired.example", request.url.host)
                    assertEquals("/api/mobile/workspace/web-resource", request.url.encodedPath)
                    respond("private redirect body", HttpStatusCode.fromValue(status), headersOf(
                        HttpHeaders.Location to listOf(destination),
                        HttpHeaders.ContentType to listOf("text/html"),
                    ))
                })
                val resource = api.webPreviewResource("project/dist", "asset.js", "index.html", "chat_1")
                assertEquals(403, resource.status)
                assertEquals("text/plain", resource.mimeType)
                assertTrue(resource.bytes.isEmpty())
                assertEquals(1, requests)
            }
        }
    }

    @Test fun oversizedSuccessfulContentLengthStillUsesTheBoundedReader() = runTest {
        val api = api(MockEngine {
            respond(ByteArray(0), headers = headersOf(HttpHeaders.ContentLength, (20L * 1024 * 1024 + 1).toString()))
        })
        val failure = assertFailsWith<ApiFailure> {
            api.webPreviewResource("project/dist", "large.js", "index.html", "chat_1")
        }
        assertEquals(413, failure.status)
        assertEquals("workspace_file_too_large", failure.code)
    }

    @Test fun oversizedSuccessWithoutContentLengthIsBoundedWhileReading() = runTest {
        val api = api(MockEngine { respond(ByteArray(20 * 1024 * 1024 + 1)) })
        val failure = assertFailsWith<ApiFailure> {
            api.webPreviewResource("project/dist", "large.js", "index.html", "chat_1")
        }
        assertEquals(413, failure.status)
        assertEquals("workspace_file_too_large", failure.code)
    }
}
