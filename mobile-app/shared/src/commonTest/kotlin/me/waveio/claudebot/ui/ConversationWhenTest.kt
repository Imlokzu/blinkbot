package me.waveio.claudebot.ui

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConversationWhenTest {
    private val utc = TimeZone.UTC
    private val now = Instant.parse("2026-11-05T15:00:00Z")
    private fun at(iso: String) = Instant.parse(iso).epochSeconds

    @Test fun historyRowsGetAShortTruthfulDate() {
        assertEquals("09:05", formatConversationWhen(at("2026-11-05T09:05:00Z"), now, utc, "Yesterday"))
        assertEquals("Yesterday", formatConversationWhen(at("2026-11-04T23:59:00Z"), now, utc, "Yesterday"))
        assertEquals("28.10", formatConversationWhen(at("2026-10-28T12:00:00Z"), now, utc, "Yesterday"))
        assertEquals("3.12.2025", formatConversationWhen(at("2025-12-03T12:00:00Z"), now, utc, "Yesterday"))
        // No date from the host means no date on screen.
        assertNull(formatConversationWhen(null, now, utc, "Yesterday"))
        assertNull(formatConversationWhen(0, now, utc, "Yesterday"))
    }
}
