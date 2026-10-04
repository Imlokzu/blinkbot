package me.waveio.claudebot.state

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateVerificationTest {
    private val digest = "0123456789abcdef".repeat(4)

    @Test fun installRequiresAnExactSha256Digest() {
        assertTrue(updateChecksumMatches(digest, digest.uppercase()))
        assertFalse(updateChecksumMatches(null, digest))
        assertFalse(updateChecksumMatches("", digest))
        assertFalse(updateChecksumMatches("not-a-digest", digest))
        assertFalse(updateChecksumMatches(digest.dropLast(1), digest))
        assertFalse(updateChecksumMatches(digest, digest.dropLast(1) + "0"))
    }

    @Test fun whitespaceAroundAValidDigestIsHarmlessButExtraCharactersAreNot() {
        assertTrue(updateChecksumMatches("  $digest\n", "\t${digest.uppercase()}"))
        assertFalse(updateChecksumMatches("$digest\nmetadata", digest))
        assertFalse(updateChecksumMatches(digest, "$digest:extra"))
    }
}
