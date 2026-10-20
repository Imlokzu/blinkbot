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
import kotlin.math.abs
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

    private fun launch(dark: Boolean = false, language: String = "en", fontScale: Float = 1f, efforts: List<String> = listOf("none", "off", "low", "high"), viewportWidth: Int = 390) {
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
                    LocalAdaptiveLayout provides AdaptiveLayout(viewportWidth.dp, 700.dp),
                ) { ModelPicker(state, actions) }
            }
        }
        compose.waitForIdle()
    }

    private fun screenshot(name: String) {
        val output = File("build/reports/model-picker/$name.png").apply { parentFile.mkdirs() }
        ImageIO.write(compose.onNodeWithTag("model-picker").captureToImage().toAwtImage(), "png", output)
    }

    private fun captureEffortBrand(modelLabel: String, expectedColor: Int, name: String): IntArray {
        val brand = compose.onNodeWithTag("effort-model-brand", useUnmergedTree = true).assertIsDisplayed()
        val bounds = brand.getUnclippedBoundsInRoot()
        val label = compose.onNodeWithText(modelLabel, useUnmergedTree = true).assertIsDisplayed().getUnclippedBoundsInRoot()
        assertEquals(14f, bounds.width.value, .1f)
        assertEquals(14f, bounds.height.value, .1f)
        assertTrue("The provider logo precedes the model label without overlap", bounds.right <= label.left)
        var image = brand.captureToImage().toAwtImage()
        var pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        fun coloredPixels() = pixels.count { pixel ->
            (pixel ushr 24) > 128 && listOf(16, 8, 0).all { shift ->
                abs((pixel ushr shift and 255) - (expectedColor ushr shift and 255)) <= 24
            }
        }
        // Resource-backed painters can finish loading after the header's
        // semantics are ready; wait for actual paint rather than a fixed delay.
        val paintFailure = runCatching {
            compose.waitUntil(timeoutMillis = 3_000) {
                image = brand.captureToImage().toAwtImage()
                pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
                coloredPixels() >= maxOf(1, pixels.size / 30)
            }
        }.exceptionOrNull()
        val output = File("build/reports/model-picker/$name-logo.png").apply { parentFile.mkdirs() }
        ImageIO.write(image, "png", output)
        screenshot(name)
        val evidence = "$name must paint its provider glyph: ${coloredPixels()} of ${pixels.size} pixels match #${expectedColor.toString(16)}, required >= ${maxOf(1, pixels.size / 30)}"
        if (paintFailure != null) throw AssertionError(evidence, paintFailure)
        assertTrue(evidence, coloredPixels() >= maxOf(1, pixels.size / 30))
        return pixels
    }

    private fun catalogHorizontalBounds(strings: LocaleText): Map<String, Pair<Float, Float>> = buildMap {
        for (model in state.models.filter { it.id in state.intelligence }) {
            val label = compose.onNodeWithText(model.label, useUnmergedTree = true).assertIsDisplayed().getUnclippedBoundsInRoot()
            val intelligence = state.intelligence.getValue(model.id)
            val description = strings.get("model.intelligence", "score" to intelligence.index.toInt(),
                "coverage" to intelligence.coverage, "total" to intelligence.total)
            val gauge = compose.onNodeWithContentDescription(description, useUnmergedTree = true).assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue("The model label retains usable space beside its gauge", label.width > 0.dp && label.right <= gauge.left)
            put("${model.id} label", label.left.value to label.right.value)
            put("${model.id} gauge", gauge.left.value to gauge.right.value)
        }
    }

    @Test fun effortHeaderRendersTheSelectedProviderBeforeAndAfterSwitchingModels() {
        launch()
        val anthropic = captureEffortBrand("Claude Sonnet", 0xB85A40, "effort-brand-anthropic-light")
        compose.onNodeWithTag("change-model").performClick()
        compose.onNodeWithText("GPT Extended Context Preview").performClick()
        assertEquals("fixture/gpt", state.selectedModel)
        compose.onNodeWithTag("effort-slider").assertIsDisplayed()
        val openai = captureEffortBrand("GPT Extended Context Preview", 0x0F886B, "effort-brand-openai-light")
        assertFalse("Provider changes must replace the rendered logo, not just retain a tagged placeholder", anthropic.contentEquals(openai))
        assertTrue("Changing providers must not persist an effort setting", saved.isEmpty())
    }

    @Test fun selectionKeepsModelLabelsAndGaugesAlignedInNarrowLargeTextLayout() {
        launch(dark = true, language = "uk", fontScale = 1.5f, viewportWidth = 320)
        val strings = runBlocking { LocaleText.load("uk") }
        captureEffortBrand("Claude Sonnet", 0xE59A81, "effort-brand-anthropic-dark-large-320")
        compose.onNodeWithTag("change-model").performClick()
        assertEquals(288f, compose.onNodeWithTag("model-picker").getUnclippedBoundsInRoot().width.value, .1f)
        compose.onNodeWithText("Claude Sonnet").assertIsSelected()
        compose.onNodeWithText("GPT Extended Context Preview").assertIsNotSelected()
        val initial = catalogHorizontalBounds(strings)
        screenshot("models-selection-claude-dark-large-320")
        // A host selection update keeps the list open, exposing both rows'
        // checkmark changes without a page transition masking a layout shift.
        for (selected in listOf("fixture/gpt", "fixture/claude")) {
            compose.runOnIdle { state = state.copy(selectedModel = selected) }
            compose.onNodeWithText(if (selected == "fixture/gpt") "GPT Extended Context Preview" else "Claude Sonnet").assertIsSelected()
            val current = catalogHorizontalBounds(strings)
            initial.forEach { (part, bounds) ->
                assertEquals("$part left edge must not move with selection", bounds.first, current.getValue(part).first, .1f)
                assertEquals("$part right edge must not move with selection", bounds.second, current.getValue(part).second, .1f)
            }
            if (selected == "fixture/gpt") screenshot("models-selection-gpt-dark-large-320")
        }
        compose.onNodeWithText("GPT Extended Context Preview").performClick()
        captureEffortBrand("GPT Extended Context Preview", 0x65D6B6, "effort-brand-openai-dark-large-320")
        assertTrue(saved.isEmpty())
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
