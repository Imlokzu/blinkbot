package me.waveio.claudebot.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class PairingCodeTest {
    @Test fun manualCodesNormalizeSeparatorsButNotQrTokens() {
        assertEquals("ABCDEFGH", PairingCode.manual(" https://bot.example/api/ ", "ab cd-efgh").code)
        assertEquals("https://bot.example", PairingCode.manual("https://bot.example/api", "ABCD-EFGH").server)
        assertEquals("Mixed-Case_Qr", PairingCode.parse("claudebot://pair?server=https%3A%2F%2Fbot.example&code=Mixed-Case_Qr").code)
    }

    @Test fun manualCodeRejectsAmbiguousLettersAndWrongLengths() {
        listOf("", "1234-5678", "ABCD-EFGI", "ABCD-EFGO", "ABCD-EFGH2", "ABCD/EFGH").forEach {
            assertEquals("invalid_pairing", assertFailsWith<ApiFailure> { PairingCode.manual("https://bot.example", it) }.code)
        }
    }

    @Test fun originAndApiSuffixNormalizeToOneOrigin() {
        assertEquals("https://bot.example", normalizeApiOrigin("https://bot.example/api/"))
        assertEquals("https://bot.example:8443", normalizeApiOrigin("https://bot.example:8443/"))
        val result = PairingCode.parse("claudebot://pair?server=https%3A%2F%2Fbot.example%2Fapi&code=one-time-code")
        assertEquals("https://bot.example", result.server)
        assertEquals("one-time-code", result.code)
        assertFalse(result.toString().contains(result.code))
    }

    @Test fun unsupportedConnectionFormsAreRejectedBeforeNetworking() {
        listOf("http://bot.example", "https://user@bot.example", "https://bot.example/nested/api",
            "https://bot.example?setting=x", "https://bot.example#section", " https://bot.example").forEach {
            assertEquals("invalid_server", assertFailsWith<ApiFailure> { normalizeApiOrigin(it) }.code)
        }
        listOf("claudebot://pair?server=https%3A%2F%2Fbot.example",
            "claudebot://pair?server=https%3A%2F%2Fbot.example&code=",
            "claudebot://pair?server=https%3A%2F%2Fbot.example&code=a&code=b",
            "claudebot://pair?server=https%3A%2F%2Fbot.example&code=a&extra=b",
            "claudebot://other?server=https%3A%2F%2Fbot.example&code=a").forEach {
            assertFailsWith<ApiFailure> { PairingCode.parse(it) }
        }
    }
}
