package me.waveio.claudebot.state

import kotlin.test.Test
import kotlin.test.assertEquals

class ModelEffortTest {
    @Test fun missingDefaultDoesNotGuessTheFirstSupportedStop() {
        val model = ModelRow("fixture/model", "Model", "fixture", "fixture", efforts = listOf("low", "high"))
        assertEquals("none", model.resolveEffort("none"))
        assertEquals("none", model.resolveEffort("adaptive"))
        assertEquals("high", model.resolveEffort("high"))
    }

    @Test fun legacySelectionUsesOnlyTheDeclaredSupportedDefault() {
        val model = ModelRow("fixture/model", "Model", "fixture", "fixture",
            efforts = listOf("low", "medium", "high"), effortDefault = "medium")
        assertEquals("medium", model.resolveEffort("none"))
        assertEquals("medium", model.resolveEffort("off"))
        assertEquals("high", model.resolveEffort("high"))
        assertEquals("none", model.copy(effortDefault = "adaptive").resolveEffort("none"))
    }
}
