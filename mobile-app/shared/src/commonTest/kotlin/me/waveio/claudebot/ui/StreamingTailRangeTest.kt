package me.waveio.claudebot.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamingTailRangeTest {
    @Test fun revealAdvancesInsideWordsWithoutSplittingGraphemes() {
        val text = "token stream 🌊 keeps moving"
        assertEquals(4, nextStreamRevealIndex(text, 0, 4))
        val emojiStart = text.indexOf("🌊")
        assertEquals(emojiStart + 2, nextStreamRevealIndex(text, emojiStart, 1))
        assertEquals("token", text.substring(0, nextStreamRevealIndex(text, 0, 5)))
    }

    @Test fun everyRevealBoundaryPreservesJoinedUnicodeGlyphs() {
        val glyphs = listOf(
            "e\u0301", "\u0915\u093F", "1\uFE0F\u20E3", "\u2764\uFE0F",
            "\uD83D\uDC4D\uD83C\uDFFD", "\uD83D\uDC69\u200D\uD83D\uDCBB",
            "\uD83C\uDDFA\uD83C\uDDF8", "\uD834\uDD5F\uD834\uDD65",
            "\u1100\u1161\u11A8", "\r\n",
            "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC7F",
        )
        val text = glyphs.joinToString("")
        var offset = 0
        for (glyph in glyphs) {
            assertEquals(offset + glyph.length, nextStreamRevealIndex(text, offset, 1), glyph)
            offset += glyph.length
        }
        // A new snapshot may extend the last already visible glyph.
        assertEquals(2, nextStreamRevealIndex("e\u0301x", 1, 1))
        val flag = "\uD83C\uDDFA\uD83C\uDDF8"
        assertEquals(flag.length, nextStreamRevealIndex(flag + flag, 2, 1))
    }

    @Test fun revealStartsFromTheSuppliedSnapshotAndOnlyQueuesLiveAppends() {
        val history = "An existing answer. ".repeat(20)
        val reveal = StreamTextReveal(history, true)
        assertEquals(history, reveal.rendered)
        assertFalse(reveal.pending)
        assertFalse(reveal.animateTail)
        reveal.update(history + "microfragment", true)
        assertEquals(history, reveal.rendered)
        assertEquals(history + "mi", reveal.advance(0))
        assertTrue(reveal.pending)
        assertEquals(history + "micr", reveal.advance(16))
        assertTrue(reveal.animateTail)
        reveal.advance(StreamRevealDeadlineMillis)
        assertEquals(reveal.target, reveal.rendered)
    }

    @Test fun largeAndRepeatedBurstsCannotExtendTheOriginalDeadline() {
        val reveal = StreamTextReveal("Prefix ", true)
        reveal.update("Prefix " + "a".repeat(1000), true)
        assertTrue(reveal.target.length - reveal.rendered.length <= 16)
        assertTrue(reveal.rendered.startsWith("Prefix "))
        reveal.advance(1000)
        for (elapsed in listOf(16L, 32L, 64L, 80L)) {
            val previous = reveal.rendered
            reveal.update(reveal.target + "b".repeat(32), true)
            assertTrue(reveal.rendered.startsWith(previous))
            reveal.advance(1000 + elapsed)
        }
        reveal.advance(1000 + StreamRevealDeadlineMillis)
        assertEquals(reveal.target, reveal.rendered)
        assertFalse(reveal.pending)
    }

    @Test fun correctionsTruncationsCompletionAndReducedMotionClearPendingTextImmediately() {
        val updates = listOf("Prefix micro", "Corrected answer", "", "Prefix microfragment", "Prefix final answer")
        for (text in updates) for (enabled in listOf(true, false)) {
            val reveal = StreamTextReveal("Prefix ", true)
            reveal.update("Prefix microfragment pending", true)
            reveal.advance(0)
            // Includes a truncation which still extends the visible prefix.
            reveal.update(text, enabled)
            assertEquals(text, reveal.rendered)
            assertFalse(reveal.pending)
            assertFalse(reveal.animateTail)
            assertEquals(text, reveal.advance(1000), "Old queued suffix must never return")
        }
        val inactive = StreamTextReveal("History", false)
        inactive.update("History with a snapshot", true)
        assertEquals(inactive.target, inactive.rendered)
        assertFalse(inactive.animateTail)
    }

    @Test fun largeMarkdownBlocksUseRealChunksWithoutAdditionalParseFrames() {
        val full = "A long existing answer. ".repeat(100)
        val reveal = StreamTextReveal(full, true)
        assertEquals(full, reveal.rendered)
        assertFalse(reveal.pending)
        val revision = reveal.revision
        reveal.update(full + "New provider chunk", true)
        assertEquals(reveal.target, reveal.rendered)
        assertFalse(reveal.pending)
        assertTrue(reveal.animateTail)
        assertEquals(revision, reveal.revision)
        assertEquals(reveal.target, reveal.advance(1000))
    }

    @Test fun settledModeChangesPreserveMarkdownIdentityButPendingTextStillFlushes() {
        val reveal = StreamTextReveal("Settled", true)
        reveal.update("Settled reply", true)
        reveal.advance(0)
        reveal.advance(StreamRevealDeadlineMillis)
        assertFalse(reveal.pending)
        assertTrue(reveal.animateTail)
        val revision = reveal.revision
        for (enabled in listOf(false, true, false, true)) {
            reveal.update("Settled reply", enabled)
            assertEquals(revision, reveal.revision, "A mode-only change must not remount Markdown")
            assertEquals("Settled reply", reveal.rendered)
            assertFalse(reveal.pending)
            assertFalse(reveal.animateTail)
        }
        reveal.update("Settled reply continues", true)
        assertTrue(reveal.pending)
        reveal.update(reveal.target, false)
        assertEquals(revision + 1, reveal.revision, "Flushing pending text still invalidates stale parsing")
        assertEquals("Settled reply continues", reveal.rendered)
        assertFalse(reveal.pending)
        assertFalse(reveal.animateTail)
    }

    @Test fun appendedTextKeepsTheExistingPrefixSharpAndCapsLargeChunks() {
        val previous = "Earlier text stays sharp: "
        val chunks = listOf(
            "x" to "x",
            "new words" to "new words",
            "0123456789abcdef" to "0123456789abcdef",
            "0123456789abcdefg" to "123456789abcdefg",
            "line\nword" to "line\nword",
            "new words \t\n" to "new words",
        )
        for ((chunk, expected) in chunks) {
            val range = assertTail(previous, previous + chunk, expected)
            assertTrue(range.first >= previous.length, "An ASCII append must never re-blur the previous text")
            assertTrue(range.last - range.first + 1 <= 16, "A large chunk must not animate in full")
        }
        assertTail(null, "0123456789abcdefghijkl", "6789abcdefghijkl")
    }

    @Test fun unchangedWhitespaceAndTruncationStaySharpWhileReplacementsGetABoundedTail() {
        val unchanged = listOf<Pair<String?, String>>(
            null to "",
            null to " \t\n",
            "Answer" to "Answer",
            "Answer" to "Answer \t\n",
            "Answer with a suffix" to "Answer",
            "Answer" to "",
            "Answer " to "Answer",
        )
        for ((previous, current) in unchanged) {
            assertNull(changedStreamTail(previous, current), "No fresh visible text: previous=$previous, current=$current")
        }
        assertTail("Original wrong", "Original right", "right")
        assertTail("Text is incorrect", "Text is fixed", "fixed")
        assertTail("Old reply", "New answer \n", "New answer")
        assertTail("Obsolete", "0123456789abcdefg", "123456789abcdefg")
    }

    @Test fun aTailBoundaryPreservesOneGlyphWithoutCollectingEarlierFlags() {
        val prefix = "Stable text: "
        val glyphs = listOf(
            "" to "\uD83D\uDE42",
            "e" to "e\u0301",
            "\u0915" to "\u0915\u093F",
            "1" to "1\u20DD",
            "\u2764" to "\u2764\uFE0F",
            "\u4E00" to "\u4E00\uDB40\uDD00",
            "\uD83D\uDC4D" to "\uD83D\uDC4D\uD83C\uDFFD",
            "\uD83D\uDC69" to "\uD83D\uDC69\u200D\uD83D\uDCBB",
            "\uD83C\uDDFA" to "\uD83C\uDDFA\uD83C\uDDF8",
        )
        for ((previousGlyph, glyph) in glyphs) {
            // The nominal one-unit cap must expand to the complete affected glyph.
            assertTail(prefix + previousGlyph, prefix + glyph, glyph, limit = 1)
        }

        val flag = "\uD83C\uDDFA\uD83C\uDDF8"
        val previous = prefix + flag.repeat(8)
        // Regional indicators form pairs, not one unbounded cluster of flags.
        assertTail(previous, previous + flag, flag)
        assertTail(previous, previous + flag.take(2), flag.take(2))
        assertTail(previous + flag.take(2), previous + flag, flag)

        // Supplementary-plane combining marks have surrogate UTF-16 code units.
        val musicalBase = "\uD834\uDD5F"
        val musicalGlyph = musicalBase + "\uD834\uDD65"
        assertTail(prefix + musicalBase, prefix + musicalGlyph, musicalGlyph, limit = 1)
    }

    private fun assertTail(previous: String?, current: String, expected: String, limit: Int = 16): IntRange {
        val range = assertNotNull(changedStreamTail(previous, current, limit))
        val end = current.trimEnd().length
        assertEquals(end - expected.length until end, range, "The range must identify only the expected visible suffix")
        assertEquals(expected, current.substring(range.first, range.last + 1))
        assertTrue(range.first >= 0 && range.last < current.length)
        assertFalse(current[range.first].isLowSurrogate(), "A range must not start inside a surrogate pair")
        assertFalse(current[range.last].isHighSurrogate(), "A range must not end inside a surrogate pair")
        return range
    }
}
