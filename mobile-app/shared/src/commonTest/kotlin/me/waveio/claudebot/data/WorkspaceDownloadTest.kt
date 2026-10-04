package me.waveio.claudebot.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Device authorization stays on one origin and downloads retain every byte. */
class WorkspaceDownloadTest {
    @Test fun binaryAndTextDownloadsUseTheAuthenticatedScopedRawEndpoint() = runTest {
        val bytes = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte(), 13, 10, 0, 0xff.toByte())
        val client = HttpClient(MockEngine { request ->
            assertEquals("paired.example", request.url.host)
            assertEquals("/api/mobile/workspace/download", request.url.encodedPath)
            assertEquals("session/diagram.png", request.url.parameters["path"])
            assertEquals("chat_1", request.url.parameters["session_id"])
            assertEquals("Bearer fixture-device", request.headers[HttpHeaders.Authorization])
            respond(bytes, headers = headersOf(HttpHeaders.ContentType, "image/png"))
        })
        val api = BotApi("https://paired.example", "fixture-device", client)
        try { assertContentEquals(bytes, api.downloadWorkspace("session/diagram.png", "chat_1")) }
        finally { api.close(); client.close() }
    }

    @Test fun rawDownloadRejectsRedirectsBeforeAnyExternalRequest() = runTest {
        var requests = 0
        val client = HttpClient(MockEngine {
            requests++
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://outside.example/file"))
        })
        val api = BotApi("https://paired.example", "fixture-device", client)
        try {
            assertEquals("redirect_rejected", assertFailsWith<ApiFailure> { api.downloadWorkspace("session/file.pdf") }.code)
            assertEquals(1, requests)
        } finally { api.close(); client.close() }
    }

    @Test fun invalidWorkspaceDestinationsNeverIssueRequests() = runTest {
        var requests = 0
        val client = HttpClient(MockEngine { requests++; respond("") })
        val api = BotApi("https://paired.example", "fixture-device", client)
        try {
            for (path in listOf("../secret", "/etc/passwd", "https://outside.example/file", "session/%2e%2e/secret")) {
                assertFailsWith<ApiFailure> { api.downloadWorkspace(path) }
            }
            assertEquals(0, requests)
        } finally { api.close(); client.close() }
    }

    @Test fun oversizedContentLengthIsRejectedBeforeCopyingTheBody() = runTest {
        val client = HttpClient(MockEngine { respond("", headers = headersOf(HttpHeaders.ContentLength, (20L * 1024 * 1024 + 1).toString())) })
        val api = BotApi("https://paired.example", "fixture-device", client)
        try { assertEquals(413, assertFailsWith<ApiFailure> { api.downloadWorkspace("session/large.bin") }.status) }
        finally { api.close(); client.close() }
    }
}
