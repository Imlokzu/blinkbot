package me.waveio.claudebot.state

import kotlin.test.Test
import kotlin.test.assertEquals

class SnapshotBubblesTest {
    @Test fun whitespaceAroundExplicitBoundariesPreservesAllAuthoritativeBytes() {
        val examples = listOf(
            "First  \n\nSecond " to listOf("First", "Second"),
            "First\n\n  Second\n" to listOf("First", "Second"),
            "  First\n\nSecond\n\nThird\t" to listOf("First", "Second", "Third"),
            "One\n\nparagraph\n\n| A |\n|---|\n| B |  " to listOf("One\n\nparagraph", "| A |\n|---|\n| B |"),
        )
        for ((text, labels) in examples) {
            val parts = snapshotBubbles(text, labels)
            assertEquals(labels.size, parts.size)
            assertEquals(text, parts.joinToString("\n\n"))
            assertEquals(labels, parts.map(String::trim))
        }
    }

    @Test fun differentContentAndUnmarkedParagraphsNeverInventBoundaries() {
        for (labels in listOf(emptyList(), listOf("Wrong", "Second"), listOf("First", ""), listOf("First", "Second", "Extra")))
            assertEquals(listOf("First\n\nSecond "), snapshotBubbles("First\n\nSecond ", labels))
        assertEquals(listOf("First Second"), snapshotBubbles("First Second", listOf("First", "Second")))
    }
}
