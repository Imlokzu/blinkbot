package me.waveio.claudebot.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ReplyImageTransportTest {
    @Test fun externalImagesAreRequestedOnlyThroughTheAuthenticatedHost() = runTest {
        val url = "https://images.example/render?q=a%20b&size=large"
        val bytes = byteArrayOf(1, 2, 3)
        val client = HttpClient(MockEngine { request ->
            assertEquals("bot.example", request.url.host)
            assertEquals("/api/mobile/images/fetch", request.url.encodedPath)
            assertEquals(url, request.url.parameters["url"])
            assertEquals("Bearer test-device", request.headers[HttpHeaders.Authorization])
            respond(bytes, headers = headersOf(HttpHeaders.ContentType, "image/png"))
        })
        val api = BotApi("https://bot.example", "test-device", client)
        try { val image = api.fetchReplyImage(url); assertContentEquals(bytes, image.bytes); assertEquals("image/png", image.mimeType) }
        finally { api.close(); client.close() }
    }
    @Test fun redirectsAndNonImageResponsesAreNeverAcceptedAsImages() = runTest {
        for (status in listOf(HttpStatusCode.Found, HttpStatusCode.OK)) {
            var requests = 0
            val client = HttpClient(MockEngine {
                requests++
                respond("<html>not an image</html>", status, headersOf(HttpHeaders.ContentType to listOf("text/html"), HttpHeaders.Location to listOf("https://outside.example/image")))
            })
            val api = BotApi("https://bot.example", "test-device", client)
            try { assertFailsWith<ApiFailure> { api.fetchReplyImage("https://images.example/a.jpg") }; assertEquals(1, requests) }
            finally { api.close(); client.close() }
        }
    }
    @Test fun invalidSourceUrlsFailBeforeAnyNetworkRequest() = runTest {
        val client = HttpClient(MockEngine { error("Unexpected request") })
        val api = BotApi("https://bot.example", "test-device", client)
        try {
            for (url in listOf("file:///image.jpg", "https://" + "user:pass" + "@images.example/a.jpg"))
                assertFailsWith<ApiFailure> { api.fetchReplyImage(url) }
        } finally { api.close(); client.close() }
    }
}
