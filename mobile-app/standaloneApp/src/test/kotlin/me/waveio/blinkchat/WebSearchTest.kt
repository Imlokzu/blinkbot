package me.waveio.blinkchat

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The parser must survive attribute order variants the host search already handles. */
class WebSearchTest {
    private fun client(body: String, status: HttpStatusCode = HttpStatusCode.OK) = HttpClient(MockEngine) {
        engine { addHandler { respond(body.toByteArray(), status, headersOf("Content-Type" to listOf("text/html; charset=utf-8"))) } }
    }

    @Test
    fun `parses lite results with snippets in order`() = runTest {
        val html = """
            <html><body><table>
            <tr><td>1.</td><td><a rel="nofollow" class="result-link" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fkotlinlang.org%2Fdocs%2Fhome.html">Kotlin docs</a></td></tr>
            <tr><td class="result-snippet">Official <b>documentation</b> for the Kotlin language.</td></tr>
            <tr><td>2.</td><td><a rel="nofollow" class="result-link" href="https://blog.example.com/post">A blog &amp; more</a></td></tr>
            <tr><td class="result-snippet">Second snippet.</td></tr>
            </table></body></html>
        """.trimIndent()
        val results = parseDdgResults(html, 5)
        assertEquals(2, results.size)
        assertEquals("Kotlin docs", results[0].title)
        assertEquals("https://kotlinlang.org/docs/home.html", results[0].url)
        assertEquals("Official documentation for the Kotlin language.", results[0].snippet)
        assertEquals("https://blog.example.com/post", results[1].url)
        assertEquals("A blog & more", results[1].title)
    }

    @Test
    fun `parses html endpoint with href before class`() = runTest {
        val html = """<a href="https://example.org/page" class="result__a">Example <em>page</em></a>
            <a class="result__snippet" href="#">A <b>snippet</b> here</a>"""
        val results = parseDdgResults(html, 5)
        assertEquals(1, results.size)
        assertEquals("Example page", results[0].title)
        assertEquals("https://example.org/page", results[0].url)
        assertEquals("A snippet here", results[0].snippet)
    }

    @Test
    fun `drops unusable links and caps the count`() = runTest {
        val html = (1..8).joinToString("") { i ->
            """<a class="result-link" href="https://example.com/$i">Result $i</a>"""
        } + """<a class="result-link" href="javascript:void(0)">Bad</a>"""
        val results = parseDdgResults(html, 5)
        assertEquals(5, results.size)
        assertEquals("https://example.com/1", results.first().url)
    }

    @Test
    fun `unwraps ddg redirect and rejects junk`() {
        assertEquals("https://example.com/x", unwrapResultUrl("//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fx"))
        assertEquals("", unwrapResultUrl("//duckduckgo.com/l/?uddg=ftp://example.com/x"))
        assertEquals("", unwrapResultUrl("//duckduckgo.com/l/?q=nothing"))
        assertEquals("", unwrapResultUrl("notaurl"))
        assertEquals("https://example.com/plain", unwrapResultUrl("https://example.com/plain"))
    }

    @Test
    fun `decodes entities including numeric ones`() {
        assertEquals("\"a<b> & 'x' y z", decodeEntities("&quot;a&lt;b&gt; &amp; &#39;x&#x27; y&nbsp;z"))
        assertEquals("", decodeEntities("&#x110000;"))
    }

    @Test
    fun `challenge page surfaces as search failure`() = runTest {
        val challenge = "<html><body anomaly>Too many requests. rate limit exceeded (429)</body></html>"
        try {
            WebSearch.search(client(challenge), "anything")
            org.junit.Assert.fail("expected search failure")
        } catch (expected: ProviderFailure) {
            assertEquals(WebToolErrors.SEARCH_FAILED, expected.key)
        }
    }

    @Test
    fun `429 status surfaces as search failure`() = runTest {
        try {
            WebSearch.search(client("blocked", HttpStatusCode.TooManyRequests), "anything")
            org.junit.Assert.fail("expected search failure")
        } catch (expected: ProviderFailure) {
            assertEquals(WebToolErrors.SEARCH_FAILED, expected.key)
        }
    }

    @Test
    fun `empty page means no results, not an error`() = runTest {
        val results = WebSearch.search(client("<html></html>"), "anything")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `encodeResults stays under the result cap`() {
        val many = (1..12).map { WebResult("t$it", "https://e.com/$it") }
        val encoded = encodeResults(many, Json { ignoreUnknownKeys = true })
        assertEquals(WebLimits.RESULT_COUNT, Regex("\"url\"").findAll(encoded).count())
    }
}
