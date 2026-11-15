package me.waveio.blinkchat

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.BrowserUserAgent
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Only localization keys reach the UI or the model; raw search pages stay private. */
internal object WebToolErrors {
    const val SEARCH_FAILED = "standalone.tools.searchFailed"
    const val URL_BLOCKED = "standalone.tools.urlBlocked"
    const val URL_UNREADABLE = "standalone.tools.urlUnreadable"
}

internal object WebLimits {
    const val RESULT_COUNT = 5
    const val SNIPPET_CHARS = 200
    const val FETCH_BYTES = 1024 * 1024
    const val FETCH_CHARS = 8000
    const val FETCH_REDIRECTS = 5
}

@Serializable
data class WebResult(val title: String, val url: String, val snippet: String = "")

/** A single shared, hardened client for every web tool call; the owner closes it. */
fun webToolClient(): HttpClient = HttpClient(OkHttp) {
    // Redirects are followed by hand so every hop passes the private-address check.
    followRedirects = false
    expectSuccess = false
    BrowserUserAgent()
    install(HttpTimeout) { requestTimeoutMillis = 30_000; connectTimeoutMillis = 10_000 }
    // OkHttp follows redirects at the engine level, which would bypass the per-hop
    // private-address re-check; both layers must refuse to redirect on their own.
    engine { config { followRedirects(false) } }
}

private val DDG_ENDPOINTS = listOf(
    "https://lite.duckduckgo.com/lite/",
    "https://html.duckduckgo.com/html/",
)

/**
 * Keyless DuckDuckGo search so web search works with no account or API key. The HTML
 * parser ports Virtual Bot/tools/search.py, which already handles both endpoint
 * variants and their attribute orders.
 */
object WebSearch {
    suspend fun search(client: HttpClient, query: String, count: Int = WebLimits.RESULT_COUNT): List<WebResult> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) throw ProviderFailure("standalone.error.tool")
        // A challenge page looks like HTTP 200, so the body decides, not the status.
        for (endpoint in DDG_ENDPOINTS) {
            val response = try { client.get(endpoint) { url { parameters.append("q", trimmed) } } }
                catch (_: Exception) { throw ProviderFailure(WebToolErrors.SEARCH_FAILED) }
            if (response.status.value == 429) throw ProviderFailure(WebToolErrors.SEARCH_FAILED, 429)
            val text = readBounded(response).toString(Charsets.UTF_8)
            if (isRateLimitPage(text)) throw ProviderFailure(WebToolErrors.SEARCH_FAILED)
            val results = parseDdgResults(text, count)
            if (results.isNotEmpty()) return results
        }
        // Both endpoints answered honestly with nothing: the query has no hits.
        return emptyList()
    }
}

/** JSON so the model can cite links verbatim; an empty list means the query had no hits. */
fun encodeResults(results: List<WebResult>, json: Json): String =
    json.encodeToString(results.take(WebLimits.RESULT_COUNT))

internal fun isRateLimitPage(text: String): Boolean =
    Regex("rate[ -]?limit|too many requests|\\b429\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)

/** Decode the handful of entities pages actually use; anything exotic is left as-is. */
internal fun decodeEntities(text: String): String {
    val named = mapOf("&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&apos;" to "'", "&nbsp;" to " ")
    var result = text
    for ((entity, value) in named) result = result.replace(entity, value)
    fun codepoint(digits: String, radix: Int): String = digits.toIntOrNull(radix)
        ?.takeIf { it in 1..0x10FFFF && it !in 0xD800..0xDFFF }
        ?.let { runCatching { String(Character.toChars(it)) }.getOrNull() } ?: ""
    result = result.replace(Regex("&#(\\d+);")) { match -> codepoint(match.groupValues[1], 10) }
    result = result.replace(Regex("&#[xX]([0-9A-Fa-f]+);")) { match -> codepoint(match.groupValues[1], 16) }
    return result
}

internal fun cleanWebText(text: String): String = decodeEntities(text).replace(Regex("\\s+"), " ").trim()

/**
 * DDG result links arrive as redirects (//duckduckgo.com/l/?uddg=…); unwrap them so the
 * model sees the real destination, and keep only usable http(s) links.
 */
internal fun unwrapResultUrl(raw: String): String {
    val url = cleanWebText(raw)
    // DDG emits protocol-relative redirect links; treat them as https.
    val absolute = if (url.startsWith("//")) "https:$url" else url
    // Ktor parses scheme-less input leniently (and invents a localhost host),
    // so an explicit scheme is required before anything else is trusted.
    if (!absolute.startsWith("http://", true) && !absolute.startsWith("https://", true)) return ""
    val parsed = try { URLBuilder(absolute).build() } catch (_: Exception) { return "" }
    if (parsed.protocol.name !in listOf("http", "https") || parsed.host.isBlank()) return ""
    val host = parsed.host.lowercase()
    if (host == "duckduckgo.com" || host.endsWith(".duckduckgo.com")) {
        val target = parsed.parameters["uddg"] ?: return ""
        if (!target.startsWith("http://", true) && !target.startsWith("https://", true)) return ""
        val inner = try { URLBuilder(target).build() } catch (_: Exception) { return "" }
        if (inner.protocol.name !in listOf("http", "https") || inner.host.isBlank()) return ""
        return inner.toString()
    }
    return parsed.toString()
}

private val LINK_REGEX = Regex(
    "<a[^>]*(?:href=['\"]([^'\"]+)['\"][^>]*class=['\"][^'\"]*(?:result-link|result__a)[^'\"]*['\"]" +
        "|class=['\"][^'\"]*(?:result-link|result__a)[^'\"]*['\"][^>]*href=['\"]([^'\"]+)['\"])[^>]*>(.*?)</a>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)

private val SNIPPET_REGEX = Regex(
    "<(?:td|a)[^>]*class=['\"][^'\"]*(?:result-snippet|result__snippet)[^'\"]*['\"][^>]*>(.*?)</(?:td|a)>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)

/** Snippets follow the same order as result links, so pairing is positional. */
internal fun parseDdgResults(html: String, count: Int): List<WebResult> {
    val results = mutableListOf<WebResult>()
    for (match in LINK_REGEX.findAll(html)) {
        val url = unwrapResultUrl(match.groupValues[1].ifEmpty { match.groupValues[2] })
        val title = cleanWebText(match.groupValues[3].replace(Regex("<[^>]+>"), ""))
        if (title.isNotEmpty() && url.isNotEmpty()) results += WebResult(title, url)
    }
    for ((index, snippetMatch) in SNIPPET_REGEX.findAll(html).withIndex()) {
        if (index >= results.size) break
        val snippet = cleanWebText(snippetMatch.groupValues[1].replace(Regex("<[^>]+>"), ""))
        if (snippet.isNotEmpty()) results[index] = results[index].copy(snippet = snippet.take(WebLimits.SNIPPET_CHARS))
    }
    return results.take(count)
}

/**
 * Reads a public web page as plain text for the model: HTTPS only, private addresses
 * rejected at every hop, bounded download, and the returned text is wrapped in a
 * nonce-delimited untrusted envelope so page content cannot forge tool output.
 */
object UrlFetcher {
    suspend fun fetch(
        client: HttpClient,
        url: String,
        maxBytes: Int = WebLimits.FETCH_BYTES,
        maxChars: Int = WebLimits.FETCH_CHARS,
    ): String {
        var current = validatedUrl(url)
        val bytes = withContext(Dispatchers.IO) {
            var fetched: ByteArray? = null
            for (hop in 0..WebLimits.FETCH_REDIRECTS) {
                assertPublicHost(current.host)
                val response = try { client.get(current.buildString()) }
                    catch (_: Exception) { throw ProviderFailure(WebToolErrors.URL_UNREADABLE) }
                val location = response.headers["Location"]
                if (location != null) {
                    if (hop == WebLimits.FETCH_REDIRECTS) throw ProviderFailure(WebToolErrors.URL_UNREADABLE)
                    // Each hop is re-validated from scratch; a redirect may not downgrade to http.
                    current = resolveRedirect(current.buildString(), location)
                    continue
                }
                if (!response.status.isSuccess()) throw ProviderFailure(WebToolErrors.URL_UNREADABLE, response.status.value)
                checkTextResponse(response)
                fetched = readBounded(response, maxBytes)
                break
            }
            fetched ?: throw ProviderFailure(WebToolErrors.URL_UNREADABLE)
        }
        val text = htmlToText(bytes.toString(Charsets.UTF_8))
        if (text.isBlank()) throw ProviderFailure(WebToolErrors.URL_UNREADABLE)
        val truncated = if (text.length > maxChars) text.take(maxChars) + "…" else text
        return wrapUntrusted(current.buildString(), truncated)
    }
}

internal fun validatedUrl(raw: String): URLBuilder {
    val candidate = raw.trim()
    if (candidate.isEmpty() || candidate.length > 2048 || candidate.any(Char::isISOControl)) throw ProviderFailure(WebToolErrors.URL_BLOCKED)
    val parsed = try { URLBuilder(candidate) } catch (_: Exception) { throw ProviderFailure(WebToolErrors.URL_BLOCKED) }
    // Only https: a plain-http hop would otherwise carry the page text unencrypted,
    // and embedded credentials have no business in a chat tool URL.
    if (parsed.protocol.name != "https" || parsed.host.isBlank()) throw ProviderFailure(WebToolErrors.URL_BLOCKED)
    if (parsed.user != null || parsed.password != null) throw ProviderFailure(WebToolErrors.URL_BLOCKED)
    return parsed
}

/** java.net.URI resolution covers relative and protocol-relative Location headers. */
internal fun resolveRedirect(base: String, location: String): URLBuilder {
    val resolved = try { java.net.URI(base).resolve(location.trim()) }
        catch (_: Exception) { throw ProviderFailure(WebToolErrors.URL_BLOCKED) }
    return validatedUrl(resolved.toString())
}

/** DNS resolution happens here, on IO; IP-literal hosts resolve without a lookup. */
internal suspend fun assertPublicHost(host: String) {
    val addresses = withContext(Dispatchers.IO) {
        try { InetAddress.getAllByName(host).toList() }
        catch (_: Exception) { throw ProviderFailure(WebToolErrors.URL_UNREADABLE) }
    }
    if (addresses.any(::isPrivateAddress)) throw ProviderFailure(WebToolErrors.URL_BLOCKED)
}

/**
 * The block list is deliberately wider than Java's isSiteLocalAddress: cloud metadata
 * endpoints and IPv6 unique-local addresses must never be reachable either.
 */
internal fun isPrivateAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress) return true
    if (address is Inet6Address) {
        val first = address.address.firstOrNull()?.toInt()?.and(0xFF) ?: 0
        if (first in 0xFC..0xFD) return true
    }
    return false
}

private val TEXT_TYPES = setOf(
    "text/html", "application/xhtml+xml", "application/json", "application/xml", "text/plain", "image/svg+xml",
)

private fun checkTextResponse(response: HttpResponse) {
    val mime = response.contentType()?.withoutParameters()?.toString()?.lowercase() ?: return
    if (mime !in TEXT_TYPES && !mime.startsWith("text/")) throw ProviderFailure(WebToolErrors.URL_UNREADABLE)
}

/** Oversized pages are truncated, not rejected: the model still gets the opening text. */
private suspend fun readBounded(response: HttpResponse, maxBytes: Int = WebLimits.FETCH_BYTES): ByteArray {
    val channel = response.bodyAsChannel()
    val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
    val buffer = ByteArray(8192)
    while (!channel.isClosedForRead) {
        val room = maxBytes - output.size()
        if (room <= 0) break
        val count = channel.readAvailable(buffer, 0, minOf(buffer.size, room))
        if (count <= 0) break
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

/** Strip scripts, styles, comments and tags; block-level tags become line breaks. */
internal fun htmlToText(html: String): String {
    var text = html.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\s*\\1\\s*>"), " ")
    text = text.replace(Regex("(?s)<!--.*?-->"), " ")
    text = text.replace(Regex("(?i)</(p|div|section|article|header|footer|h[1-6]|li|tr|table|blockquote|pre|title)>"), "\n")
    text = text.replace(Regex("(?i)<br\\s*/?>"), "\n")
    text = text.replace(Regex("<[^>]+>"), " ")
    text = decodeEntities(text)
    text = text.replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
    text = text.replace(Regex(" ?\\n ?"), "\n")
    text = text.replace(Regex("\\n{3,}"), "\n\n")
    return text.trim()
}

internal const val UNTRUSTED_BEGIN = "BEGIN_UNTRUSTED_WEB_CONTENT"
internal const val UNTRUSTED_END = "END_UNTRUSTED_WEB_CONTENT"

/**
 * Fetched page text is model input, not trusted output: the nonce envelope lets the
 * harness tell page content apart from tool verdicts (PocketPal's injection defense).
 */
internal fun wrapUntrusted(source: String, content: String): String {
    val nonce = UUID.randomUUID().toString()
    // The page must not be able to close its own envelope.
    val safe = content.replace(UNTRUSTED_BEGIN, "UNTRUSTED-BEGIN").replace(UNTRUSTED_END, "UNTRUSTED-END")
    return "$UNTRUSTED_BEGIN nonce=$nonce source=$source\n$safe\n$UNTRUSTED_END nonce=$nonce"
}
