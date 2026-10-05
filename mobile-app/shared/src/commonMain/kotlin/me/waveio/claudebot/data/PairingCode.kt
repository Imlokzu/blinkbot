package me.waveio.claudebot.data

import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url

/** Never use a QR code's server verbatim as an HTTP request URL. */
data class PairingCode(val server: String, val code: String) {
    override fun toString(): String = "PairingCode(redacted)"

    companion object {
        /** The human code is ephemeral; QR secrets keep their original case. */
        fun manual(server: String, value: String): PairingCode {
            val code = value.filterNot { it.isWhitespace() || it == '-' }.uppercase()
            if (value.length > 64 || code.length != 8 || code.any { it !in "23456789ABCDEFGHJKLMNPQRSTUVWXYZ" }) {
                throw ApiFailure(0, "invalid_pairing")
            }
            return PairingCode(normalizeApiOrigin(server.trim()), code)
        }

        fun parse(qr: String): PairingCode = try {
            val url = Url(qr)
            if (qr != qr.trim() || qr.any(Char::isISOControl) || url.protocol.name != "claudebot" || url.host != "pair" ||
                url.encodedPath !in listOf("", "/") || url.user != null || url.password != null ||
                url.fragment.isNotEmpty() || url.specifiedPort != 0 ||
                url.parameters.names() != setOf("server", "code")) {
                throw ApiFailure(0, "invalid_pairing")
            }
            val server = url.parameters.getAll("server")?.singleOrNull()
                ?: throw ApiFailure(0, "invalid_pairing")
            val code = url.parameters.getAll("code")?.singleOrNull()
                ?.takeIf { it.isNotBlank() && it.length <= 128 && it.none(Char::isISOControl) }
                ?: throw ApiFailure(0, "invalid_pairing")
            PairingCode(normalizeApiOrigin(server), code)
        } catch (failure: ApiFailure) {
            throw failure
        } catch (_: Exception) {
            throw ApiFailure(0, "invalid_pairing")
        }
    }
}

/** Accept an origin or its /api spelling; never a proxy subpath or query token. */
fun normalizeApiOrigin(baseUrl: String): String = try {
    val url = Url(baseUrl)
    if (!baseUrl.startsWith("https://", ignoreCase = true) || baseUrl != baseUrl.trim() ||
        baseUrl.any { it.isISOControl() || it.isWhitespace() || it == '\\' } || url.protocol != URLProtocol.HTTPS ||
        url.host.isBlank() || url.user != null || url.password != null ||
        url.encodedPath !in listOf("", "/", "/api", "/api/") ||
        url.parameters.names().isNotEmpty() || url.fragment.isNotEmpty() ||
        '?' in baseUrl || '#' in baseUrl) {
        throw ApiFailure(0, "invalid_server")
    }
    URLBuilder().apply {
        protocol = URLProtocol.HTTPS
        host = url.host
        port = url.port
    }.buildString().removeSuffix("/")
} catch (failure: ApiFailure) {
    throw failure
} catch (_: Exception) {
    throw ApiFailure(0, "invalid_server")
}
