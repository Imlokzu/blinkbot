package me.waveio.claudebot.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.waveio.claudebot.state.BotQuestion

@Composable
fun BotQuestionPanel(question: BotQuestion, actions: AppActions, maxHeight: Dp, backEnabled: Boolean) {
    key(question.id) {
        var answer by remember { mutableStateOf("") }
        val palette = LocalPalette.current
        val title = tr("question.title")
        val submit = { if (answer.isNotBlank()) actions.answerQuestion(question.id, answer) }
        NativeBackHandler(backEnabled) { actions.dismissQuestion(question.id) }
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState())
            .padding(8.dp).testTag("composer-question").semantics { paneTitle = title },
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = palette.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    IconAction("close", tr("question.dismiss"), { actions.dismissQuestion(question.id) })
                }
                Text(question.text, color = palette.ink, fontSize = 17.sp, lineHeight = 24.sp,
                    fontWeight = FontWeight.Medium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                question.options.forEach { option ->
                    ActionButton(option, { actions.answerQuestion(question.id, option) }, Modifier.fillMaxWidth(), maxLines = Int.MAX_VALUE)
                }
                if (question.allowCustom) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                        val placeholder = tr("question.custom")
                        BasicTextField(answer, { if (it.length <= 8000) answer = it },
                            Modifier.weight(1f).heightIn(min = 48.dp, max = 120.dp).padding(10.dp)
                                .testTag("composer-input").semantics { contentDescription = placeholder },
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = palette.ink),
                            cursorBrush = SolidColor(palette.accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { submit() }),
                            decorationBox = { field -> Box {
                                if (answer.isEmpty()) Text(placeholder, color = palette.muted)
                                field()
                            } })
                        IconAction("send", tr("question.send"), submit,
                            Modifier.size(44.dp).clip(CircleShape).background(palette.secondary),
                            enabled = answer.isNotBlank())
                    }
                }
        }
    }
}
