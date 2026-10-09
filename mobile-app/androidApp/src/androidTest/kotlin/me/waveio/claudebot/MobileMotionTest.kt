package me.waveio.claudebot

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import me.waveio.claudebot.data.WebPreviewResource
import me.waveio.claudebot.state.AppState
import me.waveio.claudebot.state.ModelRow
import me.waveio.claudebot.state.Preferences
import me.waveio.claudebot.state.Screen
import me.waveio.claudebot.ui.AppActions
import me.waveio.claudebot.ui.LocalText
import me.waveio.claudebot.ui.LocaleText
import me.waveio.claudebot.ui.MobileShell
import me.waveio.claudebot.ui.MobileTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Isolates real shell interactions from network, microphone, and notification activity. */
class MobileMotionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var strings: LocaleText
    private lateinit var inputMode: InputModeManager
    private var state by mutableStateOf(AppState())
    private var newChats = 0
    private val actions = object : MotionFixtureActions() {
        override fun navigate(screen: Screen) { state = state.copy(screen = screen, menuOpen = false) }
        override fun menu(open: Boolean) { state = state.copy(menuOpen = open) }
        override fun newChat() { newChats++; state = state.copy(screen = Screen.Chat) }
        override fun modelPicker(open: Boolean) { state = state.copy(modelPickerOpen = open) }
        override fun selectModel(id: String) { state = state.copy(selectedModel = id) }
        override fun selectEffort(value: String) { state = state.copy(effort = value) }
        override fun preferences(value: Preferences) { state = state.copy(preferences = value) }
    }

    @After fun restoreClockForActivityTeardown() {
        // Activity destruction may need a frame even when the assertions leave
        // the clock paused to inspect an intermediate animation state.
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    private fun launch(
        screen: Screen = Screen.Profile,
        reducedMotion: Boolean = false,
        models: List<ModelRow> = listOf(
            ModelRow("fixture/claude", "Claude Sonnet", "fixture", "anthropic", efforts = listOf("none", "high")),
            ModelRow("fixture/gpt", "GPT", "fixture", "openai", efforts = listOf("none", "high")),
        ),
    ) {
        strings = runBlocking { LocaleText.load("en") }
        state = AppState(
            connected = true,
            screen = screen,
            preferences = Preferences(language = "en", theme = "light", wallpaper = false, haptics = false),
            models = models,
            selectedModel = models.first().id,
        )
        compose.setContent {
            val manager = LocalInputModeManager.current
            SideEffect { inputMode = manager }
            MobileTheme(dark = false) {
                CompositionLocalProvider(LocalText provides strings) {
                    MobileShell(state, actions, reducedMotion)
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun iconPressHasVisibleFeedbackAndCancellationDoesNotNavigate() {
        launch()
        val button = compose.onNodeWithContentDescription(strings.get("nav.new"))
        val idle = button.captureToImage()
        compose.mainClock.autoAdvance = false

        button.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(400)
        val pressed = button.captureToImage()
        assertTrue("Holding a control should visibly acknowledge the press", changedPixels(idle, pressed) > 0)

        button.performTouchInput { cancel() }
        compose.mainClock.advanceTimeBy(500)
        assertEquals("Cancelling the gesture must not create a chat", 0, newChats)
        assertEquals(Screen.Profile, state.screen)
        assertEquals("Cancelled controls must return to their idle appearance", 0, changedPixels(idle, button.captureToImage()))

        button.performTouchInput { click() }
        compose.mainClock.advanceTimeBy(500)
        assertEquals("A completed tap should still activate exactly once", 1, newChats)
        assertEquals(Screen.Chat, state.screen)
    }

    @Test fun reducedMotionKeepsImmediateStablePressFeedback() {
        launch(reducedMotion = true)
        val button = compose.onNodeWithContentDescription(strings.get("nav.new"))
        val idle = button.captureToImage()
        compose.mainClock.autoAdvance = false

        button.performTouchInput { down(center) }
        compose.mainClock.advanceTimeByFrame()
        val pressed = button.captureToImage()
        assertTrue("Reduced motion must retain visible touch feedback", changedPixels(idle, pressed) > 0)
        compose.mainClock.advanceTimeBy(500)
        assertEquals("Reduced-motion feedback should not animate while held", 0, changedPixels(pressed, button.captureToImage()))

        button.performTouchInput { cancel() }
        compose.mainClock.advanceTimeByFrame()
        assertEquals("Reduced-motion feedback should clear immediately", 0, changedPixels(idle, button.captureToImage()))
        assertEquals(0, newChats)
    }

    @Test fun rapidSettingsNavigationKeepsOutgoingContentDistinctAndInactive() {
        launch()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(strings.get("profile.appearance")).performClick()
        compose.mainClock.advanceTimeByFrame()

        // Both pages retain their own visual content, but only the destination
        // may expose actions to accessibility while the outgoing page fades.
        compose.onAllNodesWithText(strings.get("appearance.deviceOnly")).assertCountEquals(1)
        compose.onNodeWithText(strings.get("profile.language")).assertDoesNotExist()
        compose.onAllNodesWithText(strings.get("profile.language"), useUnmergedTree = true).assertCountEquals(1)

        compose.onNodeWithContentDescription(strings.get("nav.back")).performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(strings.get("profile.models")).performClick()
        assertEquals("The reversed destination must receive the next tap immediately", Screen.Models, state.screen)
        compose.mainClock.advanceTimeBy(700)

        compose.onNodeWithText(strings.get("defaults.help")).assertIsDisplayed()
        compose.onNodeWithText(strings.get("appearance.deviceOnly")).assertDoesNotExist()
        assertEquals(Screen.Models, state.screen)
    }

    @Test fun reducedMotionNavigationSettlesOnTheNextFrame() {
        launch(reducedMotion = true)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(strings.get("profile.appearance")).performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()

        compose.onNodeWithText(strings.get("appearance.deviceOnly")).assertIsDisplayed()
        compose.onNodeWithText(strings.get("profile.language")).assertDoesNotExist()
        compose.onNodeWithContentDescription(strings.get("nav.back")).performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(strings.get("profile.models")).assertIsDisplayed()
        compose.onNodeWithText(strings.get("appearance.deviceOnly")).assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun outgoingPageRejectsTouchesAndKeyboardActivation() {
        launch()
        val models = compose.onNodeWithText(strings.get("profile.models"))
        val oldPosition = models.fetchSemanticsNode().boundsInRoot.center
        compose.runOnIdle { assertTrue("Keyboard input mode must be available", inputMode.requestInputMode(InputMode.Keyboard)) }
        models.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        models.assertIsFocused()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { actions.navigate(Screen.Agents) }
        compose.mainClock.advanceTimeByFrame()

        compose.onNodeWithText(strings.get("profile.models")).assertDoesNotExist()
        compose.onRoot().performKeyInput { pressKey(Key.Enter) }
        assertEquals("A fading page must not retain keyboard activation", Screen.Agents, state.screen)
        // Agents has no control at the old row's position. Without an exit
        // guard this tap reaches the still-composed Models link underneath.
        compose.onRoot().performTouchInput { click(oldPosition) }
        assertEquals("A fading page must not navigate again", Screen.Agents, state.screen)
        compose.mainClock.advanceTimeBy(700)
    }

    @Test fun returningFromEffortKeepsTheModelListPosition() {
        val models = (1..18).map { number ->
            ModelRow("fixture/$number", "Model $number", "fixture", "openai", efforts = listOf("none", "high"))
        }
        launch(screen = Screen.Chat, models = models)
        compose.onNodeWithText("Model 1").performClick()
        compose.onNode(hasScrollToNodeAction() and hasAnyAncestor(hasTestTag("model-picker")))
            .performScrollToNode(hasText("Model 14"))
        compose.onNodeWithText("Model 14").performClick()
        compose.onNodeWithText(strings.get("model.high")).performClick()
        compose.onNodeWithContentDescription(strings.get("nav.back")).performClick()

        // The selected item must remain reachable at the same list position.
        compose.onNode(hasText("Model 14") and hasAnyAncestor(hasTestTag("model-picker"))).assertIsDisplayed()
        assertEquals("fixture/14", state.selectedModel)
        assertEquals("high", state.effort)
    }

    @Test fun rapidModelPageReversalKeepsTheListInteractive() {
        launch(screen = Screen.Chat)
        compose.onNodeWithText("Claude Sonnet").performClick()
        compose.onNodeWithText("GPT").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("GPT").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithContentDescription(strings.get("nav.back")).performClick()
        compose.mainClock.advanceTimeByFrame()

        // The retained list is now the target, so the fading effort page must
        // not sit above it and consume this immediate second model selection.
        compose.onNode(hasText("Claude Sonnet") and hasAnyAncestor(hasTestTag("model-picker"))).performClick()
        assertEquals("fixture/claude", state.selectedModel)
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithText(strings.get("action.done")).assertIsDisplayed()
    }

    @Test fun closingTheEffortPanelRemovesItsActionsBeforeTheExitFinishes() {
        launch(screen = Screen.Chat)
        compose.onNodeWithText("Claude Sonnet").performClick()
        compose.onNodeWithText(strings.get("model.effort")).performClick()
        compose.onNodeWithText(strings.get("model.high")).assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(strings.get("action.done")).performClick()
        compose.mainClock.advanceTimeByFrame()

        // A fading panel must not remain a screen-reader or tap target.
        compose.onNodeWithText(strings.get("model.high")).assertDoesNotExist()
        compose.onNodeWithText(strings.get("action.done")).assertDoesNotExist()
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithTag("model-picker").assertDoesNotExist()
        assertEquals(false, state.modelPickerOpen)

        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("Claude Sonnet").performClick()
        compose.onNodeWithText(strings.get("model.title")).assertIsDisplayed()
        compose.onNodeWithText(strings.get("action.done")).assertDoesNotExist()
    }

    @Test fun unavailableDefaultModelCannotBeSelected() {
        launch(screen = Screen.Models, models = listOf(
            ModelRow("fixture/current", "Current model", "fixture", "anthropic"),
            ModelRow("fixture/available", "Available model", "fixture", "openai"),
            ModelRow("fixture/offline", "Unavailable model", "fixture", "openai", available = false),
        ))
        val unavailable = compose.onNodeWithText("Unavailable model")
        unavailable.assertIsNotEnabled()
        unavailable.performTouchInput { click() }
        assertEquals("", state.preferences.defaultModel)
        assertEquals("last", state.preferences.defaultModelMode)

        compose.onNodeWithText("Available model").performClick()
        assertEquals("fixture/available", state.preferences.defaultModel)
        assertEquals("fixed", state.preferences.defaultModelMode)
        assertEquals("New-chat preferences must not replace the current model", "fixture/current", state.selectedModel)
    }

    private fun changedPixels(first: ImageBitmap, second: ImageBitmap): Int {
        assertEquals(first.width, second.width)
        assertEquals(first.height, second.height)
        val before = first.toPixelMap()
        val after = second.toPixelMap()
        var changed = 0
        for (y in 0 until first.height) for (x in 0 until first.width) {
            if (before[x, y] != after[x, y]) changed++
        }
        return changed
    }
}

/** Explicit inert fixture: these tests never start platform or backend work. */
private open class MotionFixtureActions : AppActions {
    override fun connect() = Unit
    override fun codePairing(open: Boolean) = Unit
    override fun pairingCode(value: String) = Unit
    override fun pairingServer(value: String) = Unit
    override fun connectWithCode() = Unit
    override fun navigate(screen: Screen) = Unit
    override fun newChat() = Unit
    override fun openChat(id: String) = Unit
    override fun renameChat(id: String, title: String) = Unit
    override fun deleteChat(id: String) = Unit
    override fun menu(open: Boolean) = Unit
    override fun modelPicker(open: Boolean) = Unit
    override fun selectModel(id: String) = Unit
    override fun selectEffort(value: String) = Unit
    override fun draft(value: String) = Unit
    override fun send(delivery: String, scheduledAt: String?) = Unit
    override fun stop() = Unit
    override fun resumeQueue() = Unit
    override fun cancelPending(id: String) = Unit
    override fun retryPending(id: String) = Unit
    override fun sendModes(open: Boolean) = Unit
    override fun schedule(open: Boolean) = Unit
    override fun attachments(open: Boolean) = Unit
    override fun pickFile(kind: String) = Unit
    override fun removeAttachment(path: String) = Unit
    override fun loadAttachmentThumbnail(path: String) = Unit
    override fun previewAttachment(path: String) = Unit
    override fun closePreview() = Unit
    override fun editPreview() = Unit
    override fun reloadPreview() = Unit
    override fun buildPreviewProject() = Unit
    override suspend fun loadWebPreviewResource(revision: Long, path: String): WebPreviewResource? = null
    override fun webPreviewFailed(revision: Long) = Unit
    override fun startDictation() = Unit
    override fun stopDictation() = Unit
    override fun cancelDictation() = Unit
    override fun useTranscript() = Unit
    override fun copyContent(text: String) = Unit
    override fun shareContent(text: String) = Unit
    override fun openLink(url: String) = Unit
    override fun useSkill(name: String) = Unit
    override fun copyMessage(id: String) = Unit
    override fun shareMessage(id: String) = Unit
    override fun editMessage(id: String) = Unit
    override fun cancelEdit() = Unit
    override fun regenerate(id: String) = Unit
    override fun reaction(id: String, bubbleIndex: Int, emoji: String?) = Unit
    override fun search(value: String) = Unit
    override fun openDirectory(path: String) = Unit
    override fun openFile(path: String) = Unit
    override fun closeFile() = Unit
    override fun fileText(value: String) = Unit
    override fun retryFileSave() = Unit
    override fun preferences(value: Preferences) = Unit
    override fun resetWallpaper() = Unit
    override fun requestNotifications() = Unit
    override fun profileName(value: String) = Unit
    override fun profilePersona(value: String) = Unit
    override fun saveProfile() = Unit
    override fun disconnect() = Unit
    override fun dismissNotice() = Unit
    override fun dismissUpdate() = Unit
    override fun checkForUpdate() = Unit
    override fun installUpdate() = Unit
    override fun updateBeta(enabled: Boolean) = Unit
    override fun offlineDelivery(allow: Boolean) = Unit
    override fun refresh() = Unit
}
