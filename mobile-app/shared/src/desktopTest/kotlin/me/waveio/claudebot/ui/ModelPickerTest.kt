package me.waveio.claudebot.ui

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.compose.ui.unit.height
import java.io.File
import java.lang.reflect.Proxy
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import me.waveio.claudebot.state.AppState
import me.waveio.claudebot.state.IntelligenceRow
import me.waveio.claudebot.state.ModelRow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercises the shared native picker without a host connection or provider calls. */
@OptIn(ExperimentalTestApi::class)
class ModelPickerTest {
    @get:Rule val compose = createComposeRule()
    private var state by mutableStateOf(AppState())
    private val saved = mutableListOf<String>()
    private val actions = Proxy.newProxyInstance(AppActions::class.java.classLoader, arrayOf(AppActions::class.java)) { proxy, method, args ->
        when (method.name) {
            "equals" -> return@newProxyInstance proxy === args[0]
            "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
            "toString" -> return@newProxyInstance "ModelPickerTest actions"
        }
        when (method.name) {
            "modelPicker" -> state = state.copy(modelPickerOpen = args[0] as Boolean)
            "selectModel" -> {
                val model = state.models.first { it.id == args[0] }
                state = state.copy(selectedModel = model.id, effort = state.effort.takeIf { it in model.efforts } ?: "none")
            }
            "selectEffort" -> {
                saved += args[0] as String
                state = state.copy(effort = args[0] as String)
            }
        }
        null
    } as AppActions

    private fun launch(dark: Boolean = false, language: String = "en", fontScale: Float = 1f, efforts: List<String> = listOf("none", "off", "low", "high")) {
        val strings = runBlocking { LocaleText.load(language) }
        state = AppState(
            modelPickerOpen = true,
            models = listOf(
                ModelRow("fixture/claude", "Claude Sonnet", "fixture", "anthropic", efforts = efforts),
                ModelRow("fixture/gpt", "GPT Extended Context Preview", "fixture", "openai", efforts = listOf("none", "high")),
                ModelRow("fixture/offline", "Unavailable model", "fixture", "openai", available = false),
            ),
            selectedModel = "fixture/claude",
            intelligence = mapOf("fixture/claude" to IntelligenceRow(67f, 6, 6), "fixture/gpt" to IntelligenceRow(48f, 4, 6)),
        )
        compose.setContent {
            val density = LocalDensity.current
            MobileTheme(dark) {
                CompositionLocalProvider(
                    LocalText provides strings,
                    LocalReducedMotion provides true,
                    LocalDensity provides Density(density.density, fontScale),
                    LocalAdaptiveLayout provides AdaptiveLayout(390.dp, 700.dp),
                ) { ModelPicker(state, actions) }
            }
        }
        compose.waitForIdle()
    }

    private fun screenshot(name: String) {
        val output = File("build/reports/model-picker/$name.png").apply { parentFile.mkdirs() }
        ImageIO.write(compose.onNodeWithTag("model-picker").captureToImage().toAwtImage(), "png", output)
    }

    @Test fun effortOpensFirstAndOneButtonOpensModels() {
        launch()
        compose.onNodeWithTag("effort-slider").assertIsDisplayed()
        compose.onNodeWithContentDescription("Find a model").assertDoesNotExist()
        val panel = compose.onNodeWithTag("model-picker").getUnclippedBoundsInRoot()
        assertEquals(256f, panel.width.value, .1f)
        assertTrue("The PC-style effort pane stays compact", panel.height <= 120.dp)
        screenshot("effort-light")
        compose.onNodeWithTag("change-model").performClick()
        compose.onNodeWithContentDescription("Find a model").assertIsDisplayed().performTextInput("GPT")
        screenshot("models-light")
        compose.onNodeWithText("GPT Extended Context Preview").performClick()
        compose.onNodeWithTag("effort-slider").assertIsDisplayed()
        assertEquals("fixture/gpt", state.selectedModel)
        compose.onNodeWithTag("effort-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        assertEquals(listOf("high"), saved)
        compose.onAllNodes(isRoot()).onLast().performTouchInput { click(bottomRight - Offset(5f, 5f)) }
        compose.onNodeWithTag("model-picker").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(modelPickerOpen = true) }
        compose.onNodeWithTag("effort-slider").assertIsDisplayed()
        compose.onNodeWithText("High").assertIsDisplayed()
    }

    @Test fun dragPreviewsThenSavesOnceAndDisablingRestoresSavedEffort() {
        launch()
        val slider = compose.onNodeWithTag("effort-slider")
        slider.performTouchInput { down(Offset(20f, centerY)); moveTo(center, delayMillis = 50); moveTo(Offset(width - 20f, centerY), delayMillis = 50) }
        compose.onNodeWithText("High").assertIsDisplayed()
        assertTrue("Dragging must not persist intermediate stops", saved.isEmpty())
        slider.performTouchInput { up() }
        assertEquals(listOf("high"), saved)
        screenshot("effort-high-light")
        slider.performTouchInput { down(Offset(width - 20f, centerY)); moveTo(center, delayMillis = 50); moveTo(Offset(20f, centerY), delayMillis = 50) }
        // Skiko 1.10.3 enqueueCancel is a no-op. Cancel the native gesture by
        // disabling it; Android instrumentation covers actual touch cancellation.
        compose.runOnIdle { state = state.copy(modelsLoading = true) }
        slider.performTouchInput { up() }
        compose.runOnIdle { state = state.copy(modelsLoading = false) }
        compose.waitForIdle()
        assertEquals(listOf("high"), saved)
        compose.onNodeWithText("High").assertIsDisplayed()
        // Auto and explicit off must stay distinct settings.
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        assertEquals("off", state.effort)
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        assertEquals("none", state.effort)
    }

    @Test fun replacingTheModelDuringDragCannotSaveTheOldPreview() {
        launch()
        val slider = compose.onNodeWithTag("effort-slider")
        slider.performTouchInput { down(Offset(20f, centerY)); moveTo(center, delayMillis = 50); moveTo(Offset(width - 20f, centerY), delayMillis = 50) }
        compose.runOnIdle { state = state.copy(selectedModel = "fixture/gpt") }
        slider.performTouchInput { up() }
        assertTrue(saved.isEmpty())
        compose.onNodeWithText("Automatic").assertIsDisplayed()
    }

    @Test fun losingFocusCancelsThePreviewWithoutSaving() {
        launch()
        val slider = compose.onNodeWithTag("effort-slider")
        slider.performTouchInput { down(Offset(20f, centerY)); moveTo(center, delayMillis = 50); moveTo(Offset(width - 20f, centerY), delayMillis = 50) }
        compose.onNodeWithTag("change-model").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        slider.performTouchInput { up() }
        assertTrue(saved.isEmpty())
        compose.onNodeWithText("Automatic").assertIsDisplayed()
    }

    @Test fun automaticOnlyModelHasDisabledSliderAndReachableModelButton() {
        launch(efforts = emptyList())
        compose.onNodeWithTag("effort-slider").assertIsNotEnabled()
        compose.onNodeWithText("This model manages its own reasoning effort.").assertIsDisplayed()
        compose.onNodeWithTag("change-model").performClick()
        compose.onNodeWithText("Unavailable model").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("effort-slider").assertIsDisplayed()
        assertTrue(saved.isEmpty())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun keyboardCanChooseEffortWithoutOpeningModels() {
        launch()
        val slider = compose.onNodeWithTag("effort-slider")
        slider.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        slider.performKeyInput { pressKey(Key.DirectionRight) }
        assertEquals("off", state.effort)
        assertEquals(listOf("off"), saved)
        slider.performKeyInput { pressKey(Key.DirectionRight) }
        assertEquals("low", state.effort)
        assertEquals(listOf("off", "low"), saved)
    }

    @Test fun ukrainianDarkLargeTextKeepsSliderAndActionsVisible() {
        launch(dark = true, language = "uk", fontScale = 1.5f)
        compose.onNodeWithTag("effort-slider").assertIsDisplayed()
        compose.onNodeWithTag("change-model").assertIsDisplayed()
        screenshot("effort-dark-large-uk")
        compose.onNodeWithTag("change-model").performClick()
        screenshot("models-dark-large-uk")
    }
}
