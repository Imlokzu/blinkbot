package me.waveio.claudebot

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.waveio.claudebot.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Render actual bundled math fonts and native layouts, with no server or WebView. */
class MathRenderingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val source = mutableStateOf("")

    private fun launch(text: String) {
        source.value = text
        val strings = runBlocking { LocaleText.load("en") }
        compose.setContent {
            MobileTheme(dark = true) {
                CompositionLocalProvider(LocalText provides strings, LocalReducedMotion provides true) {
                    Column(Modifier.fillMaxSize().drawBehind { drawRect(Color(0xFF13110F)) }.padding(20.dp).testTag("math-fixture")) {
                        ChatMarkdown(source.value)
                    }
                }
            }
        }
    }

    private fun waitForDisplay(count: Int) {
        compose.waitUntil(10000) {
            val nodes = compose.onAllNodesWithTag("math-display").fetchSemanticsNodes()
            nodes.size == count && nodes.all { it.boundsInRoot.height > 40f }
        }
        compose.waitForIdle()
    }

    @Test fun fractionsRootsIntegralsAndMatricesRenderInDisplayBlocks() {
        launch("""
            # Mathematics

            ${'$'}${'$'}\frac{-b\pm\sqrt{b^2-4ac}}{2a}${'$'}${'$'}

            ${'$'}${'$'}\int_0^1 x^2\,dx=\frac{1}{3}${'$'}${'$'}

            ${'$'}${'$'}\begin{pmatrix}1&2\\3&4\end{pmatrix}${'$'}${'$'}
        """.trimIndent())
        waitForDisplay(3)
        compose.onNodeWithText("Mathematics").assertIsDisplayed()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try {
            File(compose.activity.getExternalFilesDir(null), "math-formulas.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { bitmap.recycle() }
    }

    @Test fun inlineFractionUsesATextPlaceholderWithoutLosingSurroundingWords() {
        launch("The fraction ${'$'}\\frac{1}{2}${'$'} equals one half.")
        val layouts = mutableListOf<TextLayoutResult>()
        compose.waitUntil(10000) {
            val nodes = compose.onAllNodesWithText("The fraction", substring = true).fetchSemanticsNodes()
            if (nodes.isEmpty()) false else {
                layouts.clear()
                compose.onNodeWithText("The fraction", substring = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                layouts.singleOrNull()?.placeholderRects?.singleOrNull() != null
            }
        }
        val layout = layouts.single()
        assertTrue(layout.layoutInput.text.text.endsWith(" equals one half."))
        assertFalse(layout.layoutInput.text.text.contains('\uE000'))
        assertTrue(layout.placeholderRects.single()!!.height > 20)
    }

    @Test fun codeAndPricesStayLiteralAndPartialMathBecomesAFormula() {
        val before = "Price ${'$'}5 or ${'$'}10.\n\n`${'$'}x^2${'$'}`\n\n${'$'}${'$'}\\frac{1}{2}"
        launch(before)
        try {
            compose.waitUntil(10000) { compose.onAllNodesWithText("Price ${'$'}5 or ${'$'}10.", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        } catch (failure: Throwable) {
            compose.onRoot(useUnmergedTree = true).printToLog("MathUiRegression")
            throw failure
        }
        compose.onNode(SemanticsMatcher("Exact literal math code") { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.trim() == "${'$'}x^2${'$'}" } == true
        }, useUnmergedTree = true).assertExists()
        compose.onAllNodesWithTag("math-display").assertCountEquals(0)
        compose.runOnIdle { source.value = before + "${'$'}${'$'}" }
        waitForDisplay(1)
        compose.onNodeWithText("Price ${'$'}5 or ${'$'}10.").assertExists()
    }

    @Test fun formulaOnlyLinkRetainsItsLinkAnnotation() {
        launch("[${'$'}x^2${'$'}](https://example.org)")
        val layouts = mutableListOf<TextLayoutResult>()
        compose.waitUntil(10000) {
            if (compose.onAllNodesWithText("x^2", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) false
            else {
                layouts.clear()
                compose.onNodeWithText("x^2", useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                layouts.singleOrNull()?.placeholderRects?.singleOrNull() != null
            }
        }
        val text = layouts.single().layoutInput.text
        assertEquals(1, text.getLinkAnnotations(0, text.length).filter { it.start < it.end }.size)
    }

    @Test fun tableCellKeepsItsInlineFormulaAndNeighbouringValue() {
        launch("| Formula | Value |\n| --- | --- |\n| ${'$'}\\sqrt{9}${'$'} | 3 |")
        compose.waitUntil(10000) { compose.onAllNodesWithText("3", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.waitUntil(10000) {
            val nodes = compose.onAllNodesWithText("\\sqrt{9}", useUnmergedTree = true).fetchSemanticsNodes()
            if (nodes.isEmpty()) false else {
                layouts.clear()
                compose.onNodeWithText("\\sqrt{9}", useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                layouts.singleOrNull()?.placeholderRects?.singleOrNull() != null
            }
        }
        assertFalse(layouts.single().hasVisualOverflow)
    }
}
