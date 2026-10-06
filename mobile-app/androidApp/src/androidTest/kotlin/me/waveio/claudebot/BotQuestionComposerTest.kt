package me.waveio.claudebot

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import android.view.KeyEvent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextLayoutResult
import kotlinx.coroutines.runBlocking
import me.waveio.claudebot.state.*
import me.waveio.claudebot.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.lang.reflect.Proxy

/** Composer expansion interaction on the real shell; state transport has gated SSE tests. */
class BotQuestionComposerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val state = mutableStateOf(AppState(connected = true, sessionId = "chat", draft = "Unsent draft"))
    private val replies = mutableListOf<Pair<String, String>>()
    private lateinit var strings: LocaleText
    private val actions = Proxy.newProxyInstance(AppActions::class.java.classLoader, arrayOf(AppActions::class.java)) { proxy, method, args ->
        if (method.name == "equals") return@newProxyInstance proxy === args[0]
        if (method.name == "hashCode") return@newProxyInstance System.identityHashCode(proxy)
        if (method.name == "toString") return@newProxyInstance "QuestionActions"
        when (method.name) {
            "menu" -> state.value = state.value.copy(menuOpen = args[0] as Boolean)
            "answerQuestion" -> {
                replies += args[0] as String to args[1] as String
                state.value = state.value.copy(questions = state.value.questions.filterNot { it.id == args[0] })
            }
            "dismissQuestion" -> state.value = state.value.copy(questions = state.value.questions.filterNot { it.id == args[0] })
        }
        null
    } as AppActions

    private fun prompt(id: String = "question", custom: Boolean = true, session: String = "chat") =
        BotQuestion(id, session, "job", "Which format should I use?", listOf("PDF", "Markdown"), custom)

    private fun launch(question: BotQuestion = prompt(), language: String = "en", fontScale: Float = 1f) {
        strings = runBlocking { LocaleText.load(language) }
        state.value = state.value.copy(questions = listOf(question))
        compose.setContent {
            MobileTheme(dark = true) {
                CompositionLocalProvider(LocalText provides strings, LocalReducedMotion provides true,
                    LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    MobileShell(state.value, actions, reducedMotion = true)
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun opensOnArrivalAndOptionSendsOnlyTheChosenAnswer() {
        launch()
        compose.onNodeWithText("Which format should I use?").assertIsDisplayed()
            .assert(hasAnyAncestor(hasTestTag("chat-composer")))
        val panel = compose.onNodeWithTag("composer-question").fetchSemanticsNode().boundsInRoot
        val composer = compose.onNodeWithTag("chat-composer").fetchSemanticsNode().boundsInRoot
        assertTrue(panel.top >= composer.top && panel.bottom <= composer.bottom)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try {
            File(compose.activity.getExternalFilesDir(null), "question-composer.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { bitmap.recycle() }
        compose.onNodeWithText("PDF").performClick()
        compose.onNodeWithText("Which format should I use?").assertDoesNotExist()
        assertEquals(listOf("question" to "PDF"), replies)
        assertEquals("Unsent draft", state.value.draft)
    }

    @Test fun customAnswerSurvivesStreamRecompositionAndSendsFromKeyboard() {
        launch(language = "uk")
        compose.onNodeWithContentDescription(strings.get("question.send")).assertIsNotEnabled()
        compose.onNodeWithContentDescription(strings.get("question.custom")).performClick().performTextInput("Plain text")
        compose.waitUntil(5000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        compose.waitForIdle()
        val panel = compose.onNodeWithTag("composer-question").fetchSemanticsNode().boundsInRoot
        val header = compose.onNodeWithTag("chat-header").fetchSemanticsNode().boundsInRoot
        assertTrue("The keyboard must leave the question below the header", panel.top >= header.bottom)
        compose.runOnIdle { state.value = state.value.copy(busy = true, messages = listOf(MessageRow("answer", "assistant", "Still working", live = true))) }
        compose.onNodeWithContentDescription(strings.get("question.custom")).assertTextContains("Plain text").performImeAction()
        compose.waitForIdle()
        assertEquals(listOf("question" to "Plain text"), replies)
        assertEquals("Unsent draft", state.value.draft)
    }

    @Test fun choicesOnlyHideFreeTextAndCloseDoesNotSend() {
        launch(prompt(custom = false))
        compose.onNodeWithContentDescription(strings.get("question.custom")).assertDoesNotExist()
        compose.onNodeWithText("Markdown").assertIsDisplayed()
        compose.onNodeWithContentDescription(strings.get("question.dismiss")).performClick()
        compose.onNodeWithText("Which format should I use?").assertDoesNotExist()
        assertTrue(replies.isEmpty())
    }

    @Test fun longOptionShowsItsWholeLabelWithoutClipping() {
        val option = "Keep the original layout and include every detailed explanation in the exported document, with all supporting information."
        launch(prompt(custom = false).copy(options = listOf(option)), fontScale = 1.8f)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(option, useUnmergedTree = true).performScrollTo()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.single().lineCount > 2)
        assertFalse(layouts.single().hasVisualOverflow)
        compose.onNodeWithText(option).performClick()
        assertEquals(listOf("question" to option), replies)
    }

    @Test fun backClosesTheExpansionAndKeepsTheActivity() {
        launch()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5000) { state.value.questions.isEmpty() }
        assertFalse(compose.activity.isFinishing)
        assertEquals("Unsent draft", state.value.draft)
        assertTrue(replies.isEmpty())
    }

    @Test fun backClosesTheDrawerBeforeDismissingTheQuestion() {
        launch()
        val menu = compose.onAllNodesWithContentDescription(strings.get("nav.menu"))
        if (menu.fetchSemanticsNodes().isEmpty()) return // A tablet has a persistent sidebar.
        menu[0].performClick()
        compose.waitForIdle()
        assertTrue(state.value.menuOpen)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5000) { !state.value.menuOpen }
        assertEquals(1, state.value.questions.size)
        compose.onNodeWithText("Which format should I use?").assertIsDisplayed()
    }

    @Test fun anotherChatsQuestionIsHiddenAndNewQuestionResetsCustomInput() {
        launch(prompt(session = "another"))
        compose.onNodeWithText("Which format should I use?").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(sessionId = "another") }
        compose.onNodeWithContentDescription(strings.get("question.custom")).performTextInput("Old answer")
        compose.runOnIdle { state.value = state.value.copy(questions = listOf(prompt("next", session = "another"))) }
        compose.onNodeWithContentDescription(strings.get("question.custom")).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithContentDescription(strings.get("question.send")).assertIsNotEnabled()
    }
}
