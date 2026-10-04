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
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val windowSize = LocalWindowInfo.current.containerSize
    val windowHeight = windowSize.height
    val landscape = windowSize.width > windowSize.height
    val keyboardHeight = WindowInsets.ime.getBottom(density)
    val safeTop = WindowInsets.safeDrawing.getTop(density)
    val safeBottom = maxOf(keyboardHeight, WindowInsets.safeDrawing.getBottom(density))
    // On short windows the keyboard leaves too little room below the header.
    // Lift the panel within the safe area and let its choices share one scroller.
    val compact = with(density) { (windowHeight - safeTop - safeBottom).toDp() < 400.dp }
    val denseRows = compact && keyboardHeight > 0
    val inset = safeTop + with(density) { (if (compact) 8.dp else 60.dp).roundToPx() }
    val availableHeight = with(density) { (windowHeight - inset - safeBottom).toDp() - 14.dp }.coerceAtLeast(0.dp)
    val selected = state.models.firstOrNull { it.id == state.selectedModel }
    MotionPopup(
        state.modelPickerOpen,
        { actions.modelPicker(false) },
        Modifier.padding(horizontal = 16.dp).widthIn(max = 440.dp).fillMaxWidth().heightIn(max = availableHeight),
        offset = IntOffset(0, inset),
        surfacePadding = 12.dp,
        drawBorder = true,
        surfaceShape = RoundedCornerShape(24.dp),
        blurEntrance = true,
    ) {
        if (!landscape || effortPage) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (effortPage) IconAction("back", tr("nav.back"), { effortPage = false })
            Text(tr(if (effortPage) "model.effort" else "model.title"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.ink, modifier = Modifier.weight(1f).padding(start = if (effortPage) 0.dp else 10.dp))
            IconAction("close", tr("action.close"), { actions.modelPicker(false) })
        }
        val reduced = LocalReducedMotion.current
        AnimatedContent(effortPage, modifier = Modifier.weight(1f, fill = false), transitionSpec = { fadeIn(tween(if (reduced) 0 else 140)) togetherWith fadeOut(tween(if (reduced) 0 else 90)) }, label = "modelPage") { effortsVisible ->
            if (effortsVisible) Column(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    BrandMark(selected?.brand.orEmpty(), Modifier.size(22.dp)); Spacer(Modifier.width(10.dp))
                    Text(selected?.label ?: state.selectedModel, color = palette.muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
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
            } else {
                val choices: @Composable (Modifier) -> Unit = { modifier ->
                    val hasImage = state.attachments.any { it.mimeType.startsWith("image/") } || state.error == "error.imageModel"
                    val filtered = state.models.filter { it.label.contains(query, true) || it.provider.contains(query, true) }
                        .let { models -> if (hasImage) models.sortedByDescending { it.vision == true } else models }
                    LazyColumn(modifier.heightIn(max = 400.dp).selectableGroup()) {
                        if (state.modelsLoading) item {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                LoadingDots(); Spacer(Modifier.width(10.dp))
                                Text(tr("model.loading"), color = palette.muted, fontSize = 13.sp)
                            }
                        }
                        items(filtered, key = { it.id }) { model ->
                            val picked = model.id == state.selectedModel
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(16.dp)).background(if (picked) palette.secondary else palette.surface)
                                .semantics { this.selected = picked }.clickable(enabled = model.available, role = Role.RadioButton) { actions.selectModel(model.id); focus.clearFocus(); keyboard?.hide(); effortPage = true }
                                .padding(12.dp, if (denseRows) 6.dp else 13.dp), verticalAlignment = Alignment.CenterVertically) {
                                BrandMark(model.brand, Modifier.size(24.dp)); Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(model.label, color = if (model.available) palette.ink else palette.muted, fontSize = 14.sp, lineHeight = if (denseRows) 18.sp else 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(if (model.available) model.provider else tr("model.unavailable"), color = palette.muted, fontSize = 11.sp, lineHeight = if (denseRows) 14.sp else 24.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (model.vision == true) Glyph("photo", label = tr("model.images"), modifier = Modifier.size(17.dp), tint = palette.muted)
                                if (picked) Glyph("check", modifier = Modifier.size(17.dp), tint = palette.accent)
                            }
                        }
                        if (filtered.isEmpty() && !state.modelsLoading) item { Text(tr("model.empty"), color = palette.muted, modifier = Modifier.padding(16.dp)) }
                        item {
                            Hairline(Modifier.padding(top = 9.dp, bottom = 4.dp))
                            MenuRow("effort", tr("model.effort"), { focus.clearFocus(); keyboard?.hide(); effortPage = true }, tr("model.${state.effort}"), enabled = selected != null, trailing = {
                                Glyph("down", modifier = Modifier.size(13.dp), tint = palette.muted)
                            })
                        }
                    }
                }
                // Keep the search field in the same composition while the IME
                // animates. Landscape uses the available width for two columns.
                if (landscape) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    BotField(query, { query = it }, Modifier.weight(.45f), placeholder = tr("model.search"), icon = "search")
                    choices(Modifier.weight(.55f))
                    IconAction("close", tr("action.close"), { actions.modelPicker(false) })
                } else Column(Modifier.fillMaxWidth()) {
                    BotField(query, { query = it }, placeholder = tr("model.search"), icon = "search")
                    Spacer(Modifier.height(10.dp))
                    choices(Modifier.weight(1f, fill = false))
                }
            }
        }
    }
}
