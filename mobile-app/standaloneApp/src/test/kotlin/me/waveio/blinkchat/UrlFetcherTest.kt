package me.waveio.blinkchat

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.Headers
import io.ktor.http.headersOf
import java.net.InetAddress
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Guards use IP-literal hosts so unit tests never touch DNS; real hostnames resolve
 * through the same assertPublicHost path at runtime.
 */
class UrlFetcherTest {
    private val publicHost = "93.184.216.34" // literal, public: no lookup in tests

    private class MockResponse(
        val body: String,
        val status: HttpStatusCode = HttpStatusCode.OK,
        val headers: Headers = headersOf("Content-Type" to listOf("text/html; charset=utf-8")),
    )

    /** One response per request, in order; the last one repeats if the test over-requests. */
    private fun sequentialClient(vararg responses: MockResponse): HttpClient {
        var next = 0
        return HttpClient(MockEngine) {
            // Mirror webToolClient: redirects are followed by hand in the fetch loop.
            followRedirects = false
            engine {
                addHandler {
                    val response = responses[next.coerceAtMost(responses.size - 1)]
                    next++
                    respond(response.body.toByteArray(), response.status, response.headers)
                }
            }
        }
    }

    @Test
    fun `strips scripts styles and tags into readable text`() {
        val html = """
            <html><head><title>Page</title><style>body { color: red }</style>
            <script>var x = "<div>not text</div>";</script></head>
            <body><!-- hidden --><h1>Heading</h1><p>First &amp; only<br/>para</p><a href="#">link text</a></body></html>
        """.trimIndent()
        val text = htmlToText(html)
        for (expected in listOf("Page", "Heading", "First & only", "para", "link text")) assertTrue(text.contains(expected))
        for (gone in listOf("not text", "color: red", "hidden")) assertFalse(text.contains(gone))
    }

    @Test
    fun `wraps output in a nonce envelope and neutralizes the markers`() {
        val wrapped = wrapUntrusted("https://example.com", "before ${UNTRUSTED_BEGIN}evil${UNTRUSTED_END} after")
        assertTrue(wrapped.startsWith("$UNTRUSTED_BEGIN nonce="))
        assertTrue(wrapped.contains("source=https://example.com"))
        // The page cannot close its own envelope: only the harness's final marker carries a nonce.
        assertEquals(1, Regex(Regex.escape("$UNTRUSTED_END nonce=")).findAll(wrapped).count())
        assertTrue(wrapped.contains("UNTRUSTED-BEGINevilUNTRUSTED-END"))
    }

    @Test
    fun `fetch returns untrusted-wrapped text`() = runTest {
        val html = "<html><body><p>Hello world</p><script>evil()</script></body></html>"
        val result = UrlFetcher.fetch(sequentialClient(MockResponse(html)), "https://$publicHost/page")
        assertTrue(result.contains(UNTRUSTED_BEGIN))
        assertTrue(result.contains("Hello world"))
        assertFalse(result.contains("evil()"))
    }

    @Test
    fun `follows https redirects and names the final source`() = runTest {
        val result = UrlFetcher.fetch(
            sequentialClient(
                MockResponse("", HttpStatusCode.Found, headersOf("Location" to listOf("https://$publicHost/final"))),
                MockResponse("<html><body><p>Final page</p></body></html>"),
            ),
            "https://$publicHost/start",
        )
        assertTrue("source in: " + result.take(200), result.contains("source=https://$publicHost/final"))
        assertTrue("body in: " + result.take(200), result.contains("Final page"))
    }

    @Test
    fun `rejects redirect downgrade to http`() = runTest {
        try {
            UrlFetcher.fetch(
                sequentialClient(MockResponse("", HttpStatusCode.Found, headersOf("Location" to listOf("http://$publicHost/")))),
                "https://$publicHost/start",
            )
            fail("expected blocked redirect")
        } catch (expected: ProviderFailure) {
            assertEquals(WebToolErrors.URL_BLOCKED, expected.key)
        }
    }

    @Test
    fun `blocks loopback private and unique-local hosts`() = runTest {
        for (host in listOf("127.0.0.1", "10.1.2.3", "192.168.0.5", "172.16.9.9", "169.254.169.254", "0.0.0.0")) {
            try {
                validatedUrl("https://$host/")
                assertPublicHost(host)
                fail("expected blocked host $host")
            } catch (expected: ProviderFailure) {
                assertEquals(WebToolErrors.URL_BLOCKED, expected.key)
            }
        }
        assertTrue(isPrivateAddress(InetAddress.getByName("fc00::1")))
        assertTrue(isPrivateAddress(InetAddress.getByName("fe80::1")))
        assertFalse(isPrivateAddress(InetAddress.getByName(publicHost)))
    }

    @Test
    fun `rejects plain http and embedded credentials`() {
        for (url in listOf("http://$publicHost/", "https://user:pass@$publicHost/")) {
            try { validatedUrl(url); fail("expected blocked $url") }
            catch (expected: ProviderFailure) { assertEquals(WebToolErrors.URL_BLOCKED, expected.key) }
        }
    }

    @Test
    fun `non-text content types are unreadable`() = runTest {
        try {
            UrlFetcher.fetch(
                sequentialClient(MockResponse("bytes", headers = headersOf("Content-Type" to listOf("application/octet-stream")))),
                "https://$publicHost/file",
            )
            fail("expected unreadable")
        } catch (expected: ProviderFailure) {
            assertEquals(WebToolErrors.URL_UNREADABLE, expected.key)
        }
    }

    @Test
    fun `oversized pages truncate instead of failing`() = runTest {
        val long = "<html><body><p>" + "word ".repeat(4000) + "</p></body></html>"
        val result = UrlFetcher.fetch(sequentialClient(MockResponse(long)), "https://$publicHost/long", maxBytes = 4096, maxChars = 100)
        assertTrue(result.contains("…"))
        assertTrue(result.contains(UNTRUSTED_BEGIN))
    }

    @Test
    fun `too many redirects surface as unreadable`() = runTest {
        val loop = MockResponse("", HttpStatusCode.Found, headersOf("Location" to listOf("https://$publicHost/loop")))
        try {
            UrlFetcher.fetch(sequentialClient(loop, loop, loop, loop, loop, loop, loop), "https://$publicHost/start")
            fail("expected unreadable after redirect loop")
        } catch (expected: ProviderFailure) {
            assertEquals(WebToolErrors.URL_UNREADABLE, expected.key)
        }
    }
}
