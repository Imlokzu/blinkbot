package me.waveio.claudebot.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EffortScaleTest {
    @Test fun onlyRealHostStopsAreSelectable() {
        val scale = effortScale("low", listOf("none", "", "low", "high", "high"))
        assertEquals(listOf("low", "high"), scale.levels)
        assertTrue(scale.supported)
        assertEquals(0, scale.current)
    }

    @Test fun unknownSelectionIsNotPresentedAsAutomaticOrARealLevel() {
        assertEquals(1, effortScale("high", listOf("low", "high")).current)
        val unsupported = effortScale("xhigh", listOf("low"))
        assertFalse(unsupported.supported)
        assertFalse(effortScale("none", listOf("low", "high")).supported)
        assertTrue(effortScale("none", emptyList()).levels.isEmpty())
    }
}
