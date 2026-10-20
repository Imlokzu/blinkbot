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
import me.waveio.claudebot.state.AppState
import me.waveio.claudebot.state.Preferences
import me.waveio.claudebot.state.Screen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Product branding stays independent of editable bot names and old server profiles. */
class BrandingSettingsTest {
    @get:Rule val compose = createComposeRule()
    private var state by mutableStateOf(AppState(screen = Screen.Profile))
    private val savedNames = mutableListOf<String>()
    private val actions = Proxy.newProxyInstance(AppActions::class.java.classLoader, arrayOf(AppActions::class.java)) { proxy, method, args ->
        when (method.name) {
            "equals" -> return@newProxyInstance proxy === args[0]
            "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
            "toString" -> return@newProxyInstance "BrandingSettingsTest actions"
            "navigate" -> state = state.copy(screen = args[0] as Screen)
            "profileName" -> state = state.copy(profileName = args[0] as String)
            "saveProfile" -> savedNames += state.profileName
            else -> error("Unexpected action in isolated branding test: ${method.name}")
        }
        null
    } as AppActions

    private fun launch(language: String, dark: Boolean): LocaleText {
        val strings = runBlocking { LocaleText.load(language) }
        state = AppState(
            screen = Screen.Profile,
            baseUrl = "https://fixture.invalid",
            installedVersion = "1.0.0-fixture",
            preferences = Preferences(language = language, theme = if (dark) "dark" else "light", haptics = false),
        )
        compose.setContent {
            val density = LocalDensity.current
            MobileTheme(dark) {
                CompositionLocalProvider(
                    LocalText provides strings,
                    LocalReducedMotion provides true,
                    LocalDensity provides Density(density.density, 1f),
                ) {
                    Box(Modifier.size(390.dp, 740.dp).background(LocalPalette.current.background).testTag("branding-settings")) {
                        SettingsScreen(state, actions)
                    }
                }
            }
        }
        return strings
    }

    private fun screenshot(name: String) {
        val output = File("build/reports/branding-settings/$name.png").apply { parentFile.mkdirs() }
        ImageIO.write(compose.onNodeWithTag("branding-settings").captureToImage().toAwtImage(), "png", output)
    }

    private fun verifyBrandingAndPersonalization(language: String, dark: Boolean) {
        val strings = launch(language, dark)
        val appName = strings.get("app.name")
        assertEquals("Both shipped locales retain the product name", "Blink", appName)
        val theme = if (dark) "dark" else "light"
        // The exact legacy server value is data, not a new hardcoded UI label.
        val profiles = listOf("legacy" to "\u041a\u043b\u043e\u0434 \u0411\u043e\u0442", "custom" to "Orbit companion")
        profiles.forEach { (fixture, profileName) ->
            compose.runOnIdle { state = state.copy(screen = Screen.Profile, profileName = profileName) }
            compose.onNodeWithText(appName).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(profileName).assertDoesNotExist()
            assertEquals("Rendering Settings must not overwrite the server profile", profileName, state.profileName)
            screenshot("$language-$theme-$fixture-profile")

            compose.onNodeWithText(strings.get("profile.personalization")).performScrollTo().performClick()
            assertEquals(Screen.Personalization, state.screen)
            val field = compose.onNodeWithContentDescription(strings.get("profile.name"))
            field.performScrollTo().assertIsDisplayed().assertTextEquals(profileName)
            val editedName = "Edited $fixture companion"
            field.performTextReplacement(editedName)
            field.assertTextEquals(editedName)
            assertEquals("Personalization still edits the bot name", editedName, state.profileName)
            screenshot("$language-$theme-$fixture-personalization")
            compose.onNodeWithText(strings.get("profile.save")).performScrollTo().performClick()
            assertEquals(editedName, savedNames.last())

            compose.runOnIdle { actions.navigate(Screen.Profile) }
            compose.onNodeWithText(appName).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(editedName).assertDoesNotExist()
            compose.onNodeWithText(strings.get("profile.update")).performScrollTo().performClick()
            assertEquals(Screen.Updates, state.screen)
            compose.onNodeWithTag("app-updates").assertIsDisplayed()
            compose.onNodeWithText(appName).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(strings.get("update.installed", "version" to state.installedVersion)).assertIsDisplayed()
            compose.onNodeWithText(profileName).assertDoesNotExist()
            compose.onNodeWithText(editedName).assertDoesNotExist()
            assertEquals("Updates must not change the saved personal name", editedName, state.profileName)
            screenshot("$language-$theme-$fixture-updates")
        }
        assertEquals(listOf("Edited legacy companion", "Edited custom companion"), savedNames)
    }

    @Test fun englishLightKeepsBlinkBrandingAndEditableBotNames() {
        verifyBrandingAndPersonalization(language = "en", dark = false)
    }

    @Test fun ukrainianDarkKeepsBlinkBrandingAndEditableBotNames() {
        verifyBrandingAndPersonalization(language = "uk", dark = true)
    }
}
