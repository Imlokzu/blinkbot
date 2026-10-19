package me.waveio.claudebot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import java.lang.reflect.Proxy
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import me.waveio.claudebot.state.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Settings use silent previews; no physical actuator is touched by UI tests. */
class HapticsSettingsTest {
    @get:Rule val compose = createComposeRule()
    private var state by mutableStateOf(AppState(screen = Screen.Profile, agentHapticsSupported = true))
    private val previews = mutableListOf<AgentHaptic>()
    private val actions = Proxy.newProxyInstance(AppActions::class.java.classLoader, arrayOf(AppActions::class.java)) { proxy, method, args ->
        when (method.name) {
            "equals" -> return@newProxyInstance proxy === args[0]
            "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
            "toString" -> return@newProxyInstance "HapticsSettingsTest actions"
            "navigate" -> state = state.copy(screen = args[0] as Screen)
            "preferences" -> state = state.copy(preferences = args[0] as Preferences)
            "previewHaptic" -> previews += args[0] as AgentHaptic
        }
        null
    } as AppActions

    private fun launch(language: String = "en", dark: Boolean = false, large: Boolean = false) {
        val strings = runBlocking { LocaleText.load(language) }
        compose.setContent {
            val density = LocalDensity.current
            MobileTheme(dark) {
                CompositionLocalProvider(LocalText provides strings, LocalReducedMotion provides true,
                    LocalDensity provides Density(density.density, if (large) 1.5f else 1f)) {
                    Box(Modifier.size(390.dp, 740.dp).background(LocalPalette.current.background).testTag("haptics-settings")) { SettingsScreen(state, actions) }
                }
            }
        }
    }

    private fun screenshot(name: String) {
        val output = File("build/reports/agent-haptics/$name.png").apply { parentFile.mkdirs() }
        ImageIO.write(compose.onNodeWithTag("haptics-settings").captureToImage().toAwtImage(), "png", output)
    }

    @Test fun profileOpensControlsAndEachPreviewHasItsOwnPattern() {
        launch()
        compose.onNodeWithText("Haptic feedback").performScrollTo().performClick()
        assertEquals(Screen.Haptics, state.screen)
        screenshot("settings-light")
        compose.onNodeWithText("Thinking").performClick()
        assertFalse(state.preferences.thinkingHaptics)
        compose.onNodeWithText("Strong").performScrollTo().performClick()
        assertEquals("strong", state.preferences.hapticStrength)
        compose.onNodeWithText("Lively").performScrollTo().performClick()
        assertEquals("lively", state.preferences.hapticCadence)
        listOf("Thinking pulse", "Tool double tap", "Reply tick").forEach {
            compose.onNodeWithText(it).performScrollTo().performClick()
        }
        assertEquals(AgentHaptic.entries.toList(), previews)
        screenshot("settings-controls-light")
        compose.onNodeWithText("Haptic feedback").performScrollTo().performClick()
        compose.onNodeWithText("Thinking pulse").assertDoesNotExist()
        assertFalse(state.preferences.haptics)
    }

    @Test fun unsupportedDevicesExplainWhyPreviewsAreDisabled() {
        state = state.copy(screen = Screen.Haptics, agentHapticsSupported = false)
        launch()
        compose.onNodeWithText("Reply tick").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Vibration previews are available on supported Android phones and iPhones.").performScrollTo().assertIsDisplayed()
        assertTrue(previews.isEmpty())
    }

    @Test fun ukrainianLargeTextKeepsControlsReachable() {
        state = state.copy(screen = Screen.Haptics)
        launch(language = "uk", dark = true, large = true)
        compose.onNodeWithText("Робота інструментів").performScrollTo().performClick()
        assertFalse(state.preferences.toolHaptics)
        compose.onNodeWithText("Клацання відповіді").performScrollTo().performClick()
        assertEquals(listOf(AgentHaptic.Streaming), previews)
        screenshot("settings-dark-large-uk")
    }
}
