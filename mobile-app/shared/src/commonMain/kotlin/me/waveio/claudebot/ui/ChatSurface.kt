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
import androidx.compose.ui.text.font.FontWeight
import me.waveio.claudebot.resources.Res
import me.waveio.claudebot.resources.lora_italic
import org.jetbrains.compose.resources.Font
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
        var previous = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
        snapshotFlow { Triple(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, list.canScrollForward) }.collect { (index, offset, canForward) ->
            if (!canForward) following = true
            else if (list.isScrollInProgress && (index < previous.first || index == previous.first && offset < previous.second)) following = false
            previous = index to offset
        }
    }
    LaunchedEffect(state.sessionId) { if (state.messages.isNotEmpty()) list.scrollToItem(state.messages.lastIndex) }
    LaunchedEffect(state.messages.lastOrNull()?.text, state.messages.size, state.messages.lastOrNull()?.steps, state.messages.lastOrNull()?.parts) {
        if (following && list.layoutInfo.totalItemsCount > 0) list.scrollToItem(list.layoutInfo.totalItemsCount - 1)
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.messages.isEmpty() && !state.loading) {
                Welcome(Modifier.align(Alignment.Center).padding(horizontal = 38.dp), reducedMotion)
            } else {
                LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp, 20.dp, 18.dp, 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    items(state.messages, key = { it.id }) { message ->
                        MessageContent(message, actions, reducedMotion, state.busy)
                    }
                    if (state.busy || state.pending.any { it.state == "queued" } && state.messages.lastOrNull()?.role == "user") item { TypingIndicator() }
                }
            }
            if (state.loading) LoadingDots(Modifier.align(Alignment.Center))
            if (!following && state.messages.isNotEmpty()) {
                ActionButton(tr("chat.latest"), { following = true; scope.launch { list.animateScrollToItem((list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) } }, Modifier.align(Alignment.BottomCenter).padding(12.dp), icon = "down")
            }
        }
        if (state.pending.size > 1 || state.pending.isNotEmpty() && state.busy || state.queuePaused) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph("time", modifier = Modifier.size(15.dp), tint = palette.muted)
                Spacer(Modifier.width(6.dp))
                Text(if (state.queuePaused) tr("queue.paused") else tr("queue.title") + " · " + state.pending.size, fontSize = 12.sp, color = palette.muted, modifier = Modifier.weight(1f))
                if (state.queuePaused) QuietAction(tr("queue.resume"), actions::resumeQueue)
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
        Text(tr("welcome.$current"), fontFamily = FontFamily(Font(Res.font.lora_italic, FontWeight.Medium, FontStyle.Italic)), fontStyle = FontStyle.Italic, fontSize = 33.sp, lineHeight = 39.sp, color = LocalPalette.current.ink)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageContent(message: MessageRow, actions: AppActions, reducedMotion: Boolean, busy: Boolean) {
    val palette = LocalPalette.current
    val user = message.role == "user"
    var menuText by remember(message.id) { mutableStateOf<String?>(null) }
    var selection by remember(message.id) { mutableStateOf<String?>(null) }
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
                    modifier = Modifier.widthIn(max = if (user) 320.dp else 600.dp).combinedClickable(onClick = {}, onLongClick = { menuText = line }),
                    shape = RoundedCornerShape(21.dp, 21.dp, if (user) 6.dp else 21.dp, if (user) 21.dp else 6.dp),
                    color = if (user) palette.userBubble else palette.botBubble,
                ) {
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        if (user) Text(line, color = palette.userInk, fontSize = 16.sp, lineHeight = 23.sp)
                        else Markdown(line, modifier = Modifier.fillMaxWidth())
                    }
                }

            }
        }
        if (message.steps.isNotEmpty() && parts.none { it.type == "steps" }) ActivityTree(message.steps, message.live)
        message.attachments.forEach { attachment ->
            ActionButton(attachment.name, { actions.previewAttachment(attachment.path) }, icon = if (attachment.mimeType.startsWith("image/")) "photo" else "file")
        }
        if (!user && message.model.isNotBlank() && !message.live) Text(message.model.substringAfterLast('/'), color = palette.muted, fontSize = 10.sp, modifier = Modifier.padding(start = 6.dp, top = 2.dp))
    }
    if (menuText != null) BotDialog({ menuText = null }) {
        Text(menuText.orEmpty(), color = palette.muted, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(8.dp, 10.dp))
        Hairline(Modifier.padding(vertical = 7.dp))
        MenuRow("copy", tr("chat.copy"), { actions.copyContent(menuText.orEmpty()); menuText = null })
        MenuRow("select", tr("chat.select"), { selection = menuText; menuText = null })
        MenuRow("share", tr("chat.share"), { actions.shareContent(menuText.orEmpty()); menuText = null })
        if (user) MenuRow("edit", tr("chat.edit"), { menuText = null; actions.editMessage(message.id) }, enabled = !busy)
        else MenuRow("retry", tr("chat.regenerate"), { menuText = null; actions.regenerate(message.id) }, enabled = !busy)
        QuietAction(tr("action.close"), { menuText = null }, Modifier.fillMaxWidth())
    }
    if (selection != null) BotDialog({ selection = null }) {
        SelectionContainer(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) { Text(selection.orEmpty(), color = palette.ink) }
        QuietAction(tr("action.done"), { selection = null }, Modifier.align(Alignment.End))
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
        AnimatedVisibility(expanded, enter = if (LocalReducedMotion.current) EnterTransition.None else expandVertically() + fadeIn(), exit = if (LocalReducedMotion.current) ExitTransition.None else shrinkVertically() + fadeOut()) {
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
                        if (live && step.status in listOf("running", "started")) LoadingDots(Modifier.padding(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun TypingIndicator() {
    Box(Modifier.padding(start = 2.dp).clip(RoundedCornerShape(19.dp, 19.dp, 19.dp, 6.dp)).background(Color(0xFFFFFDF8)).padding(horizontal = 16.dp, vertical = 13.dp)) {
        LoadingDots(color = Color(0xFF6C645D))
    }
}
