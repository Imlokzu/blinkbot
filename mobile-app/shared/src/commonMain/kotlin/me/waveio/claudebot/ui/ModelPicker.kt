package me.waveio.claudebot.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import me.waveio.claudebot.state.AppState

/** Tune the current model first; the model catalog is one explicit action away. */
@Composable
fun ModelPicker(state: AppState, actions: AppActions) {
    var query by remember { mutableStateOf("") }
    var effortPage by remember { mutableStateOf(true) }
    val modelListState = rememberLazyListState()
    val pageTransforms = remember { mutableMapOf<Pair<Boolean, Boolean>, ContentTransform>() }
    pageTransforms.forEach { (key, transform) ->
        transform.targetContentZIndex = if (key.first == effortPage) 1f else 0f
    }
    LaunchedEffect(state.modelPickerOpen) {
        if (state.modelPickerOpen) {
            query = ""
            effortPage = true
            modelListState.scrollToItem(0)
        }
    }
    val palette = LocalPalette.current
    val density = LocalDensity.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val adaptive = LocalAdaptiveLayout.current
    val windowHeight = with(density) { adaptive.height.roundToPx() }
    val landscape = adaptive.compactHeight && adaptive.contentWidth >= 600.dp
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
        Modifier.width(adaptive.contentWidth.coerceAtMost(if (effortPage) 288.dp else 472.dp)).padding(horizontal = 16.dp).heightIn(max = availableHeight).testTag("model-picker"),
        offset = IntOffset(0, inset),
        surfacePadding = if (effortPage) 4.dp else 12.dp,
        drawBorder = true,
        surfaceShape = RoundedCornerShape(if (effortPage) 12.dp else 24.dp),
        blurEntrance = true,
    ) {
        if (!landscape && !effortPage) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconAction("back", tr("nav.back"), { focus.clearFocus(); keyboard?.hide(); effortPage = true })
            Text(tr("model.title"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.ink, modifier = Modifier.weight(1f))
            IconAction("close", tr("action.close"), { actions.modelPicker(false) })
        }
        val reduced = LocalReducedMotion.current
        AnimatedContent(effortPage, modifier = Modifier.weight(1f, fill = false), transitionSpec = {
            pageTransforms.getOrPut(targetState to reduced) {
                val duration = if (reduced) 0 else 180
                val direction = if (targetState) 1 else -1
                (fadeIn(tween(duration)) + slideInHorizontally(tween(duration)) { it / 16 * direction } togetherWith
                    fadeOut(tween(if (reduced) 0 else 110)) + slideOutHorizontally(tween(duration)) { -it / 16 * direction })
                    .using(SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> tween(duration) }))
                    .apply { targetContentZIndex = 1f }
            }
        }, label = "modelPage") { effortsVisible ->
            if (effortsVisible) Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).transitionInput(effortPage)) {
                key(state.selectedModel, selected?.efforts) {
                    EffortSlider(
                        effort = state.effort,
                        efforts = selected?.efforts.orEmpty(),
                        modelLabel = selected?.label ?: state.selectedModel,
                        enabled = state.modelPickerOpen && effortPage && !state.modelsLoading && selected?.available == true,
                        onSelect = actions::selectEffort,
                        onChangeModel = { effortPage = false },
                    )
                }
            } else {
                val choices: @Composable (Modifier) -> Unit = { modifier ->
                    val filtered = state.models.filter { it.label.contains(query, true) || it.provider.contains(query, true) }
                    LazyColumn(modifier.heightIn(max = 400.dp).selectableGroup(), state = modelListState) {
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
                                IntelGaugeButton(state, model.id, modifier = Modifier.padding(horizontal = 5.dp))
                                if (picked) Glyph("check", modifier = Modifier.size(17.dp), tint = palette.accent)
                            }
                        }
                        if (filtered.isEmpty() && !state.modelsLoading) item { Text(tr("model.empty"), color = palette.muted, modifier = Modifier.padding(16.dp)) }
                    }
                }
                val search: @Composable (Modifier) -> Unit = { modifier ->
                    BotField(query, { query = it }, modifier, placeholder = tr("model.search"), icon = "search")
                }
                val currentSearch by rememberUpdatedState(search)
                val currentChoices by rememberUpdatedState(choices)
                val stableSearch = remember { movableContentOf<Modifier> { currentSearch(it) } }
                val stableChoices = remember { movableContentOf<Modifier> { currentChoices(it) } }
                // Moving these slots keeps focus and list position during host
                // resizes. Only short, wide panes need the compact two columns.
                if (landscape) Row(Modifier.fillMaxWidth().transitionInput(!effortPage), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    IconAction("back", tr("nav.back"), { focus.clearFocus(); keyboard?.hide(); effortPage = true })
                    stableSearch(Modifier.weight(.45f))
                    stableChoices(Modifier.weight(.55f))
                    IconAction("close", tr("action.close"), { actions.modelPicker(false) })
                } else Column(Modifier.fillMaxWidth().transitionInput(!effortPage)) {
                    stableSearch(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    stableChoices(Modifier.weight(1f, fill = false))
                }
            }
        }
    }
}
