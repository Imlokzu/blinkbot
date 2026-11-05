package me.waveio.claudebot.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EffortScaleTest {
    @Test fun automaticIsItsOwnModeNotTheFirstStop() {
        val scale = effortScale("none", listOf("none", "off", "low", "high"))
        // Automatic used to be the leftmost dot next to Off and read as "minimum".
        assertEquals(listOf("off", "low", "high"), scale.levels)
        assertTrue(scale.automatic)
        val off = effortScale("off", listOf("none", "off", "low", "high"))
        assertFalse(off.automatic)
        assertEquals(0, off.current)
    }

    @Test fun theShownStopIsAlwaysTheSavedEffort() {
        assertEquals(2, effortScale("high", listOf("none", "off", "low", "high")).current)
        // A level this model does not list is shown as Automatic, which is what the
        // controller normalizes it to before sending; nothing claims an unsent level.
        val unsupported = effortScale("xhigh", listOf("none", "low"))
        assertTrue(unsupported.automatic)
        assertTrue(effortScale("none", emptyList()).levels.isEmpty())
    }
}
