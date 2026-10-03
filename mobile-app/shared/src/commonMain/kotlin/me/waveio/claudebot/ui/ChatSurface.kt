package me.waveio.claudebot.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.mikepenz.markdown.m3.Markdown
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.waveio.claudebot.state.*

@Composable
fun ChatSurface(state: AppState, actions: AppActions, reducedMotion: Boolean) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val palette = LocalPalette.current
    var following by remember(state.sessionId) { mutableStateOf(true) }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress to list.canScrollForward }.collect { (scrolling, canForward) ->
            if (scrolling) following = !canForward
        }
    }
    LaunchedEffect(state.sessionId) { if (state.messages.isNotEmpty()) list.scrollToItem(state.messages.lastIndex) }
    LaunchedEffect(state.messages.lastOrNull()?.text, state.messages.size, state.messages.lastOrNull()?.steps) {
        if (following && list.layoutInfo.totalItemsCount > 0) list.scrollToItem(list.layoutInfo.totalItemsCount - 1)
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.messages.isEmpty() && !state.loading) {
                Welcome(Modifier.align(Alignment.Center).padding(horizontal = 38.dp), reducedMotion)
            } else {
                LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp, 20.dp, 18.dp, 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    items(state.messages, key = { it.id }) { message ->
                        MessageContent(message, actions, reducedMotion)
                    }
                    if (state.busy && state.messages.lastOrNull()?.text.isNullOrBlank()) item { TypingIndicator(reducedMotion) }
                }
            }
            if (state.loading) CircularProgressIndicator(Modifier.align(Alignment.Center).size(24.dp), strokeWidth = 2.dp)
            if (!following && state.messages.isNotEmpty()) {
                FilledTonalButton(onClick = { following = true; scope.launch { list.animateScrollToItem((list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) } }, modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)) {
                    Glyph("down", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp)); Text(tr("chat.latest"), fontSize = 12.sp)
                }
            }
        }
        if (state.pending.isNotEmpty() || state.queuePaused) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph("time", modifier = Modifier.size(15.dp), tint = palette.muted)
                Spacer(Modifier.width(6.dp))
                Text(if (state.queuePaused) tr("queue.paused") else tr("queue.title") + " · " + state.pending.size, fontSize = 12.sp, color = palette.muted, modifier = Modifier.weight(1f))
                if (state.queuePaused) TextButton(onClick = actions::resumeQueue) { Text(tr("queue.resume"), fontSize = 12.sp) }
            }
        }
        Composer(state, actions)
    }
}

@Composable
private fun Welcome(modifier: Modifier, reducedMotion: Boolean) {
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(reducedMotion) {
        if (!reducedMotion) while (true) { delay(2000); index = (index + 1) % 4 }
    }
    AnimatedContent(index, modifier = modifier.heightIn(min = 105.dp), transitionSpec = { fadeIn(tween(if (reducedMotion) 0 else 320)) togetherWith fadeOut(tween(if (reducedMotion) 0 else 180)) }, label = "welcome") { current ->
        Text(tr("welcome.$current"), fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = 33.sp, lineHeight = 39.sp, color = LocalPalette.current.ink)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageContent(message: MessageRow, actions: AppActions, reducedMotion: Boolean) {
    val palette = LocalPalette.current
    val user = message.role == "user"
    var menuPart by remember(message.id) { mutableStateOf<Int?>(null) }
    var selection by remember(message.id) { mutableStateOf(false) }
    var visible by remember(message.id) { mutableStateOf(!message.live || reducedMotion) }
    LaunchedEffect(message.id) { visible = true }
    val opacity by animateFloatAsState(if (visible) 1f else 0f, tween(if (reducedMotion) 0 else 220), label = "messageReveal")
    val lines = message.bubbles.filter { it.isNotBlank() }.ifEmpty { listOf(message.text).filter { it.isNotBlank() } }
    val parts = message.parts.ifEmpty { lines.map { ContentPart("text", it) } }
    Column(Modifier.fillMaxWidth().graphicsLayer {
        alpha = opacity
        translationY = if (reducedMotion) 0f else (1f - opacity) * 12.dp.toPx()
        renderEffect = if (!reducedMotion && opacity < 0.99f) BlurEffect(radiusX = (1f - opacity) * 9f, radiusY = (1f - opacity) * 9f, edgeTreatment = TileMode.Decal) else null
    }, horizontalAlignment = if (user) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        for ((partIndex, part) in parts.withIndex()) {
            if (part.type == "steps") {
                ActivityTree(message.steps.filter { it.id in part.stepIds }, message.live)
                continue
            }
            val line = part.text
            if (line.isBlank()) continue
            Box {
                Surface(
                    modifier = Modifier.widthIn(max = if (user) 320.dp else 600.dp).combinedClickable(onClick = {}, onLongClick = { menuPart = partIndex }),
                    shape = RoundedCornerShape(21.dp, 21.dp, if (user) 6.dp else 21.dp, if (user) 21.dp else 6.dp),
                    color = if (user) palette.surface else palette.surface.copy(alpha = if (palette.dark) 0.7f else 0.78f),
                ) {
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        if (user) Text(line, color = palette.ink, fontSize = 16.sp, lineHeight = 23.sp)
                        else Markdown(line, modifier = Modifier.fillMaxWidth())
                    }
                }
                DropdownMenu(expanded = menuPart == partIndex, onDismissRequest = { menuPart = null }) {
                    DropdownMenuItem(text = { Text(tr("chat.copy")) }, onClick = { menuPart = null; actions.copyMessage(message.id) })
                    DropdownMenuItem(text = { Text(tr("chat.select")) }, onClick = { menuPart = null; selection = true })
                    DropdownMenuItem(text = { Text(tr("chat.share")) }, onClick = { menuPart = null; actions.shareMessage(message.id) })
                    if (user) DropdownMenuItem(text = { Text(tr("chat.edit")) }, onClick = { menuPart = null; actions.editMessage(message.id) })
                    else DropdownMenuItem(text = { Text(tr("chat.regenerate")) }, onClick = { menuPart = null; actions.regenerate(message.id) })
                }
            }
        }
        if (message.steps.isNotEmpty() && parts.none { it.type == "steps" }) ActivityTree(message.steps, message.live)
        message.attachments.forEach { attachment ->
            OutlinedButton(onClick = { actions.previewAttachment(attachment.path) }, shape = RoundedCornerShape(14.dp)) {
                Glyph("file", modifier = Modifier.size(17.dp)); Spacer(Modifier.width(7.dp)); Text(attachment.name, fontSize = 13.sp, maxLines = 1)
            }
        }
        if (message.live && message.text.isNotBlank()) TypingIndicator(reducedMotion)
    }
    if (selection) Dialog(onDismissRequest = { selection = false }) {
        GlassCard { Column(Modifier.padding(20.dp).heightIn(max = 600.dp)) {
            SelectionContainer(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { Text(message.text, color = palette.ink) }
            TextButton(onClick = { selection = false }, modifier = Modifier.align(Alignment.End)) { Text(tr("action.done")) }
        } }
    }
}

@Composable
private fun ActivityTree(steps: List<ActivityRow>, live: Boolean) {
    var expanded by remember { mutableStateOf(live) }
    LaunchedEffect(live) { if (!live) expanded = false }
    val p = LocalPalette.current
    val failed = steps.any { it.status in setOf("error", "failed") }
    val interrupted = !live && steps.any { it.status in setOf("running", "started", "stopped", "interrupted", "cancelled") }
    Column(Modifier.fillMaxWidth().padding(start = 5.dp)) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Glyph(when { failed -> "close"; interrupted -> "stop"; live -> "time"; else -> "check" }, modifier = Modifier.size(16.dp), tint = if (failed) MaterialTheme.colorScheme.error else p.muted)
            Spacer(Modifier.width(8.dp)); Text(tr(when { failed -> "chat.toolsFailed"; interrupted -> "chat.toolsInterrupted"; else -> "chat.tools" }, "count" to steps.size), fontSize = 12.sp, color = p.muted, modifier = Modifier.weight(1f))
            Text(if (expanded) tr("chat.hide") else tr("chat.details"), color = p.accent, fontSize = 12.sp)
        }
        AnimatedVisibility(expanded) {
            Column {
                steps.forEachIndexed { index, step ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 42.dp), verticalAlignment = Alignment.CenterVertically) {
                        Canvas(Modifier.width(22.dp).height(42.dp)) {
                            val x = 5.dp.toPx(); val y = size.height / 2
                            drawLine(p.line, Offset(x, 0f), Offset(x, if (index == steps.lastIndex) y else size.height), 1.dp.toPx())
                            drawLine(p.line, Offset(x, y), Offset(size.width - 3.dp.toPx(), y), 1.dp.toPx())
                        }
                        Glyph(when { step.label.contains("search", true) -> "search"; step.label.contains("edit", true) || step.label.contains("write", true) -> "edit"; else -> "file" }, modifier = Modifier.size(16.dp), tint = if (step.status == "error") MaterialTheme.colorScheme.error else p.muted)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(step.label, fontSize = 12.sp, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (step.detail.isNotBlank()) Text(step.detail, fontSize = 11.sp, color = p.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (live && step.status in listOf("running", "started")) CircularProgressIndicator(Modifier.padding(8.dp).size(12.dp), strokeWidth = 1.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun TypingIndicator(reducedMotion: Boolean) {
    val transition = rememberInfiniteTransition(label = "typing")
    val phase by transition.animateFloat(0.3f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "typingPulse")
    val p = LocalPalette.current
    Surface(shape = RoundedCornerShape(18.dp), color = p.surface.copy(alpha = 0.8f)) {
        Row(Modifier.padding(13.dp, 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(3) { Box(Modifier.size(4.dp).background(p.muted.copy(alpha = if (reducedMotion) 0.8f else phase), CircleShape)) }
            Spacer(Modifier.width(4.dp)); Text(tr("chat.writing"), color = p.muted, fontSize = 11.sp)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Composer(state: AppState, actions: AppActions) {
    val p = LocalPalette.current
    GlassCard(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth()) {
        Column(Modifier.padding(8.dp)) {
            if (state.editingMessageId != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("chat.edit"), color = p.muted, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(start = 10.dp))
                IconAction("close", tr("input.cancel"), actions::cancelEdit)
            }
            if (state.attachments.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                state.attachments.forEach { item -> InputChip(selected = false, onClick = { actions.removeAttachment(item.path) }, label = { Text(item.name, maxLines = 1, modifier = Modifier.widthIn(max = 160.dp)) }, trailingIcon = { Glyph("close", tr("input.removeAttachment"), Modifier.size(13.dp)) }) }
            }
            BasicTextField(value = state.draft, onValueChange = actions::draft, modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp, max = 170.dp).padding(10.dp, 10.dp), textStyle = MaterialTheme.typography.bodyLarge.copy(color = p.ink, fontSize = 16.sp), cursorBrush = SolidColor(p.accent), decorationBox = { field ->
                Box { if (state.draft.isEmpty()) Text(tr("chat.placeholder"), color = p.muted, fontSize = 16.sp); field() }
            })
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    IconAction("attach", tr("input.attach"), { actions.attachments(!state.attachmentPickerOpen) }, enabled = !state.uploading)
                    DropdownMenu(state.attachmentPickerOpen, { actions.attachments(false) }) {
                        listOf("photo", "camera", "document").forEach { kind -> DropdownMenuItem(text = { Text(tr("input.$kind")) }, onClick = { actions.attachments(false); actions.pickFile(kind) }) }
                    }
                }
                if (state.uploading) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                Spacer(Modifier.weight(1f))
                IconAction("mic", tr("input.microphone"), actions::startDictation)
                if (state.draft.isNotBlank() || state.attachments.isNotEmpty() || !state.busy) {
                    Box {
                        Box(Modifier.size(44.dp).clip(CircleShape).background(p.ink).combinedClickable(onClick = { actions.send() }, onLongClick = { actions.sendModes(true) }), contentAlignment = Alignment.Center) {
                            Glyph("send", tr("chat.send"), Modifier.size(21.dp), p.background)
                        }
                        DropdownMenu(state.sendModeOpen, { actions.sendModes(false) }) {
                            DropdownMenuItem(text = { Text(tr("queue.title")) }, onClick = { actions.sendModes(false); actions.send("queue") })
                            DropdownMenuItem(text = { Text(tr("queue.steer")) }, enabled = state.busy && state.steerAvailable, onClick = { actions.sendModes(false); actions.send("steer") })
                            DropdownMenuItem(text = { Text(tr("queue.later")) }, onClick = { actions.sendModes(false); actions.schedule(true) })
                        }
                    }
                }
                if (state.busy) IconAction("stop", tr("chat.stop"), actions::stop)
            }
        }
    }
}
