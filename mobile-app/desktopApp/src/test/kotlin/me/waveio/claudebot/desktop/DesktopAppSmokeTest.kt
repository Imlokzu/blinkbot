package me.waveio.claudebot.desktop

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.flow.MutableStateFlow
import me.waveio.claudebot.App
import me.waveio.claudebot.platform.PlatformBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/** Exercises the shipping shared Compose app with a silent, offline desktop boundary. */
class DesktopAppSmokeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun startupCanOpenManualPairingInDarkThemeWithoutNativeServices() {
        val platform = SilentDesktopPlatform()
        val systemBars = mutableListOf<Pair<Boolean, Boolean>>()

        compose.setContent {
            App(platform, onSystemBarAppearance = { status, navigation -> systemBars += status to navigation })
        }

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Your bot. Always close.").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Your bot. Always close.").assertExists()
        compose.onNodeWithText("Enter connection code").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription("Connection code").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Connection code").performTextInput("fixture-code")
        compose.onNodeWithContentDescription("Connection code").assertTextEquals("fixture-code")
        compose.waitForIdle()

        assertFalse(systemBars.isEmpty())
        assertEquals(false to false, systemBars.last())
        assertEquals(null, platform.secrets["device_token"])
    }

    private class SilentDesktopPlatform : PlatformBridge {
        override val platformName = "desktop-test"
        override val systemLanguage = "en"
        override val reducedMotion = true
        override val foreground = MutableStateFlow(true)
        override val incomingPairing = MutableStateFlow<String?>(null)
        val preferences = mutableMapOf("preferences.v1" to """{"language":"en","theme":"dark","wallpaper":false}""")
        val secrets = mutableMapOf<String, String>()

        override fun readPreference(key: String) = preferences[key]
        override fun writePreference(key: String, value: String?) {
            if (value == null) preferences.remove(key) else preferences[key] = value
        }
        override fun readSecret(key: String) = secrets[key]
        override fun writeSecret(key: String, value: String?) {
            if (value == null) secrets.remove(key) else secrets[key] = value
        }
        override fun scanQr(onResult: (String?) -> Unit) = onResult(null)
        override fun pickFile(kind: String, onResult: (me.waveio.claudebot.platform.PickedFile?) -> Unit) = onResult(null)
        override fun startRecording(onAmplitude: (Float) -> Unit, onResult: (me.waveio.claudebot.platform.PickedFile?) -> Unit,
            onPartial: (me.waveio.claudebot.platform.PickedFile) -> Unit) = Unit
        override fun stopRecording() = Unit
        override fun cancelRecording() = Unit
        override fun haptic() = Unit
        override fun copyText(value: String) = Unit
        override fun shareText(value: String) = Unit
        override fun requestNotifications(onResult: (Boolean) -> Unit) = onResult(false)
        override fun notifyReply(title: String, body: String, conversationId: String) = Unit
        override fun nowMillis() = 1_791_043_200_000L
        override fun newId() = "desktop-test"
    }
}
