package me.waveio.claudebot.platform

import org.junit.Assert.*
import org.junit.Test

class NativePairingLinksTest {
    @Test fun deliversOnlyThePairingNativeRoute() {
        assertTrue(NativePairingLinks.accepts("claudebot://pair?server=https%3A%2F%2Fexample.invalid&code=fixture"))
        assertTrue(NativePairingLinks.accepts("claudebot://pair/?code=fixture"))
        assertFalse(NativePairingLinks.accepts("https://example.invalid/?code=fixture"))
        assertFalse(NativePairingLinks.accepts("claudebot://conversation?code=fixture"))
        assertFalse(NativePairingLinks.accepts("claudebot://pair/other?code=fixture"))
        assertFalse(NativePairingLinks.accepts("claudebot://other@pair?code=fixture"))
        assertFalse(NativePairingLinks.accepts("claudebot://pair:443?code=fixture"))
    }

    @Test fun malformedOrOversizedDeliveryIsIgnoredWithoutLoggingCodes() {
        assertFalse(NativePairingLinks.accepts("claudebot://pair"))
        assertFalse(NativePairingLinks.accepts("claudebot://pair?code=fixture#fragment"))
        assertFalse(NativePairingLinks.accepts(" claudebot://pair?code=fixture"))
        assertFalse(NativePairingLinks.accepts("claudebot://pair?code=fixture\n"))
        assertFalse(NativePairingLinks.accepts("claudebot://pair?code=" + "x".repeat(4096)))
        assertFalse(NativePairingLinks.accepts("claudebot://pair?code=%"))
    }
}
