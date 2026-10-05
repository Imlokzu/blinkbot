package me.waveio.claudebot.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamingTailRangeTest {
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
