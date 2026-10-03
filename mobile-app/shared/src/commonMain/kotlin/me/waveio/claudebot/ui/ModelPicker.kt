package me.waveio.claudebot.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import me.waveio.claudebot.state.AppState

/** Pick a model first, then tune its effort without hunting through a chip strip. */
@Composable
fun ModelPicker(state: AppState, actions: AppActions) {
    var query by remember(state.modelPickerOpen) { mutableStateOf("") }
    var effortPage by remember(state.modelPickerOpen) { mutableStateOf(false) }
    val palette = LocalPalette.current
    val density = LocalDensity.current
    val inset = WindowInsets.safeDrawing.getTop(density) + with(density) { 60.dp.roundToPx() }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val keyboardHeight = WindowInsets.ime.getBottom(density)
    val availableHeight = with(density) { (windowHeight - inset - keyboardHeight).toDp() - 14.dp }.coerceAtLeast(160.dp)
    val selected = state.models.firstOrNull { it.id == state.selectedModel }
    MotionPopup(state.modelPickerOpen, { actions.modelPicker(false) }, Modifier.padding(horizontal = 16.dp).widthIn(max = 440.dp).fillMaxWidth().heightIn(max = availableHeight), offset = IntOffset(0, inset)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (effortPage) IconAction("back", tr("nav.back"), { effortPage = false })
            Text(tr(if (effortPage) "model.effort" else "model.title"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.ink, modifier = Modifier.weight(1f).padding(start = if (effortPage) 0.dp else 10.dp))
            IconAction("close", tr("action.close"), { actions.modelPicker(false) })
        }
        val reduced = LocalReducedMotion.current
        AnimatedContent(effortPage, modifier = Modifier.weight(1f, fill = false), transitionSpec = { fadeIn(tween(if (reduced) 0 else 140)) togetherWith fadeOut(tween(if (reduced) 0 else 90)) }, label = "modelPage") { effortsVisible ->
            if (effortsVisible) Column(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    BrandMark(selected?.brand.orEmpty(), Modifier.size(22.dp)); Spacer(Modifier.width(10.dp))
                    Text(selected?.label ?: state.selectedModel, color = palette.muted, fontSize = 13.sp)
                }
                val efforts = selected?.efforts.orEmpty().ifEmpty { listOf("none") }
                Column(Modifier.weight(1f, fill = false).heightIn(max = 350.dp).verticalScroll(rememberScrollState()).selectableGroup()) {
                    efforts.forEach { effort ->
                        val picked = state.effort == effort
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(16.dp)).background(if (picked) palette.secondary else palette.surface)
                            .semantics { this.selected = picked }.clickable(role = Role.RadioButton) { actions.selectEffort(effort) }
                            .padding(14.dp, 15.dp), verticalAlignment = Alignment.CenterVertically) {
                            Glyph("effort", tint = if (picked) palette.accent else palette.muted, modifier = Modifier.size(19.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(tr("model.$effort"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.ink)
                                Text(tr("model.effortHint.$effort"), fontSize = 11.sp, color = palette.muted)
                            }
                            if (picked) Glyph("check", modifier = Modifier.size(17.dp), tint = palette.accent)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                ActionButton(tr("action.done"), { actions.modelPicker(false) }, Modifier.fillMaxWidth(), primary = true)
            } else Column(Modifier.fillMaxWidth()) {
                BotField(query, { query = it }, placeholder = tr("model.search"), icon = "search")
                Spacer(Modifier.height(10.dp))
                val filtered = state.models.filter { it.label.contains(query, true) || it.provider.contains(query, true) }
                LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 310.dp).selectableGroup()) {
                    items(filtered, key = { it.id }) { model ->
                        val picked = model.id == state.selectedModel
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(16.dp)).background(if (picked) palette.secondary else palette.surface)
                            .semantics { this.selected = picked }.clickable(enabled = model.available, role = Role.RadioButton) { actions.selectModel(model.id); focus.clearFocus(); keyboard?.hide(); effortPage = true }
                            .padding(12.dp, 13.dp), verticalAlignment = Alignment.CenterVertically) {
                            BrandMark(model.brand, Modifier.size(24.dp)); Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(model.label, color = if (model.available) palette.ink else palette.muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (model.available) model.provider else tr("model.unavailable"), color = palette.muted, fontSize = 11.sp)
                            }
                            if (picked) Glyph("check", modifier = Modifier.size(17.dp), tint = palette.accent)
                        }
                    }
                    if (filtered.isEmpty()) item { Text(tr("model.empty"), color = palette.muted, modifier = Modifier.padding(16.dp)) }
                }
                Hairline(Modifier.padding(top = 9.dp, bottom = 4.dp))
                MenuRow("effort", tr("model.effort"), { focus.clearFocus(); keyboard?.hide(); effortPage = true }, selected?.label, trailing = {
                    Text(tr("model.${state.effort}"), color = palette.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp)); Glyph("down", modifier = Modifier.size(13.dp), tint = palette.muted)
                })
            }
        }
    }
}
