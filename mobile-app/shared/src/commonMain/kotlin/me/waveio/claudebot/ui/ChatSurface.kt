package me.waveio.claudebot.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.semantics.*
import com.mikepenz.markdown.m3.Markdown
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.first
import me.waveio.claudebot.state.*


@Composable
fun ChatSurface(state: AppState, actions: AppActions, reducedMotion: Boolean, headerHeight: Dp, header: @Composable () -> Unit) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val palette = LocalPalette.current
    val density = LocalDensity.current
    val topPanel = headerHeight + with(density) { WindowInsets.safeDrawing.getTop(this).toDp() }
    var composerHeight by remember { mutableIntStateOf(0) }
    val bottomPanel = with(density) { composerHeight.toDp() }
    val historyLayer = rememberGraphicsLayer()
    var historyOrigin by remember { mutableStateOf(Offset.Zero) }
    var scrollingToLatest by remember(state.sessionId) { mutableStateOf(false) }
    val seen = remember(state.sessionId) { mutableSetOf<String>() }
    var following by remember(state.sessionId) { mutableStateOf(true) }
    val fingerDown = remember(state.sessionId) { mutableStateOf(false) }
    val bottomTolerance = with(density) { 2.dp.toPx() }
    val reactionSignature = remember(state.messages) {
        state.messages.joinToString("|") { message ->
            "${message.id}:${message.reaction}:${message.reactions.entries.sortedBy { it.key }.joinToString(",")}"
        }
    }
    val tailVisible by remember(list, composerHeight, bottomTolerance) { derivedStateOf {
        val layout = list.layoutInfo
        val last = layout.visibleItemsInfo.lastOrNull()
        layout.totalItemsCount == 0 || last != null && last.index == layout.totalItemsCount - 1 &&
            last.offset + last.size <= layout.viewportEndOffset - composerHeight + bottomTolerance
    } }
    val waiting = (state.busy && state.messages.lastOrNull()?.let { it.role != "assistant" || it.text.isBlank() && it.parts.none { part -> part.text.isNotBlank() } } != false) ||
        (state.pending.any { it.state == "queued" } && state.messages.lastOrNull()?.role == "user")
    var showTyping by remember(state.sessionId) { mutableStateOf(false) }
    LaunchedEffect(waiting, state.sessionId) {
        showTyping = false
        if (waiting) { delay(850); showTyping = true }
    }
    LaunchedEffect(tailVisible, fingerDown.value, list.isScrollInProgress) {
        if (tailVisible && !fingerDown.value && !list.isScrollInProgress) following = true
    }
    val scrollIntent = remember(state.sessionId) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (fingerDown.value && source == NestedScrollSource.UserInput && available.y > 0f) following = false
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (fingerDown.value && source == NestedScrollSource.UserInput && consumed.y < 0f && !list.canScrollForward) following = true
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(state.sessionId) { if (state.messages.isNotEmpty()) list.scrollToItem(state.messages.lastIndex) }
    LaunchedEffect(state.messages.size) {
        if (following && !scrollingToLatest && list.layoutInfo.totalItemsCount > 0) list.scrollToItem(list.layoutInfo.totalItemsCount - 1)
    }
    LaunchedEffect(reactionSignature) {
        if (following && !scrollingToLatest && list.layoutInfo.totalItemsCount > 0) {
            yield()
            list.scrollToItem(list.layoutInfo.totalItemsCount - 1)
        }
    }
    LaunchedEffect(list, state.sessionId) {
        // Markdown measures after parsing. Follow that measured growth rather
        // than jumping to the top of the same long bubble on every token.
        snapshotFlow {
            val layout = list.layoutInfo
            listOf(layout.totalItemsCount, layout.visibleItemsInfo.lastOrNull()?.index ?: -1,
                layout.visibleItemsInfo.lastOrNull()?.size ?: 0, layout.visibleItemsInfo.lastOrNull()?.offset ?: 0,
                layout.viewportEndOffset, layout.afterContentPadding,
                if (fingerDown.value) 1 else 0, if (following) 1 else 0, if (scrollingToLatest) 1 else 0)
        }.collect { geometry ->
            val count = geometry[0]
            if (following && !scrollingToLatest && count > 0) coroutineScope {
                // A user drag may cancel this mutation. Keep that cancellation
                // inside a child so later growth can resume following.
                launch {
                    snapshotFlow { !list.isScrollInProgress && !fingerDown.value }.first { it }
                    if (!following || scrollingToLatest) return@launch
                    if (geometry[1] != count - 1) list.scrollToItem(count - 1)
                    val layout = list.layoutInfo
                    val last = layout.visibleItemsInfo.lastOrNull() ?: return@launch
                    val excess = last.offset + last.size + layout.afterContentPadding - layout.viewportEndOffset
                    if (excess > 0) list.scrollBy(excess.toFloat())
                }.join()
            }
        }
    }
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).testTag("chat-surface")) {
        Box(Modifier.align(Alignment.TopCenter).widthIn(max = ChatContentMaxWidth).fillMaxSize().testTag("chat-column")) {
        Box(Modifier.fillMaxSize()) {
            if (state.messages.isEmpty() && !state.loading) {
                Box(Modifier.fillMaxSize().padding(top = topPanel, bottom = bottomPanel), contentAlignment = Alignment.Center) {
                    Welcome(Modifier.padding(horizontal = 38.dp), reducedMotion)
                }
            } else {
                LazyColumn(state = list, overscrollEffect = null, modifier = Modifier.fillMaxSize().testTag("chat-history")
                    .onGloballyPositioned { historyOrigin = it.positionInRoot() }
                    .chatEdges(topPanel, bottomPanel, historyLayer).nestedScroll(scrollIntent).pointerInput(state.sessionId) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        fingerDown.value = true
                        try {
                            do { val event = awaitPointerEvent(PointerEventPass.Initial) } while (event.changes.any { it.pressed })
                        } finally { fingerDown.value = false }
                    }
                }, contentPadding = PaddingValues(start = 18.dp, top = topPanel + 20.dp, end = 18.dp, bottom = bottomPanel + 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    items(state.messages, key = { it.presentationId ?: it.id }) { message ->
                        MessageContent(message, actions, reducedMotion, state.busy, state.attachmentThumbnails, state.mediaGeneration, state.baseUrl, state.imageFailures, seen.add(message.presentationId ?: message.id) && (message.live || message.presentationId != null || message.id.startsWith("u-")))
                    }
                    if (showTyping && waiting) item(key = "typing") {
                        EnterMotion(true) { motion -> Box(motion.testTag("chat-typing")) { TypingIndicator() } }
                    }
                }
            }
            if (state.loading) LoadingDots(Modifier.align(Alignment.Center))
            if (!tailVisible && state.messages.isNotEmpty()) {
                GlassLatestButton(historyLayer, historyOrigin, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomPanel + 12.dp)) {
                    if (!scrollingToLatest) scope.launch {
                        scrollingToLatest = true
                        following = true
                        try {
                            val lastIndex = list.layoutInfo.totalItemsCount - 1
                            if (lastIndex < 0) return@launch
                            if (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index != lastIndex) {
                                if (reducedMotion) list.scrollToItem(lastIndex) else list.animateScrollToItem(lastIndex)
                            }
                            // A final bubble may be taller than the viewport. Its
                            // measured tail, not its item start, is the destination.
                            val layout = list.layoutInfo
                            val last = layout.visibleItemsInfo.lastOrNull()
                            if (last != null) {
                                val distance = (last.offset + last.size + layout.afterContentPadding - layout.viewportEndOffset).coerceAtLeast(0).toFloat()
                                if (reducedMotion) list.scrollBy(distance) else list.animateScrollBy(distance, tween(320, easing = FastOutSlowInEasing))
                            }
                        } finally { scrollingToLatest = false }
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().testTag("chat-header").panelTouchBarrier()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))) { header() }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().testTag("chat-composer")
            .onSizeChanged { composerHeight = it.height }.panelTouchBarrier()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
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
    }
}

/** Take part in hit testing so covered messages cannot receive panel taps. */
private fun Modifier.panelTouchBarrier(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope { while (true) awaitPointerEvent() }
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
private fun MessageContent(message: MessageRow, actions: AppActions, reducedMotion: Boolean, busy: Boolean, thumbnails: Map<String, ByteArray>, mediaGeneration: Long, origin: String, imageFailures: Set<String>, animate: Boolean) {
    val palette = LocalPalette.current
    val user = message.role == "user"
    var menuText by remember(message.id) { mutableStateOf<String?>(null) }
    var selection by remember(message.id) { mutableStateOf<String?>(null) }
    var selectedBubble by remember(message.id) { mutableIntStateOf(0) }
    var selectedNote by remember(message.id) { mutableStateOf(false) }
    NativeBackHandler(menuText != null) { menuText = null }
    val lines = message.bubbles.filter { it.isNotBlank() }.ifEmpty { listOf(message.text).filter { it.isNotBlank() } }
    val parts = message.parts.ifEmpty { lines.map { ContentPart("text", it) } }
    MessageArrival(animate, user) { motion ->
    Column(motion.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        for ((partIndex, part) in parts.withIndex()) {
            if (part.type == "steps") {
                ActivityTree(message.steps.filter { it.id in part.stepIds }, message.live)
                continue
            }
            val line = part.text
            if (line.isBlank()) continue
            key(part.noteId, partIndex) {
            EnterMotion(message.live, streaming = message.live) { bubbleMotion ->
            Box(bubbleMotion) {
                Surface(
                    modifier = Modifier.widthIn(max = if (user) 320.dp else 600.dp).testTag("message-bubble:${message.id}:$partIndex").combinedClickable(onClick = {}, onLongClick = { selectedBubble = parts.take(partIndex).count { it.type == "text" }; selectedNote = part.note || part.noteId != null; menuText = line }),
                    shape = RoundedCornerShape(21.dp, 21.dp, if (user) 6.dp else 21.dp, if (user) 21.dp else 6.dp),
                    color = if (user) palette.userBubble else palette.botBubble,
                ) {
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        if (user) Text(line, color = palette.userInk, fontSize = 16.sp, lineHeight = 23.sp)
                        else ReplyMarkdown(line, origin, message.live, mediaGeneration, thumbnails, imageFailures, actions)
                    }
                }
            }
            }
            }
            val bubbleIndex = parts.take(partIndex).count { it.type == "text" }
            val emoji = if (user) message.reaction else message.reactions[bubbleIndex.toString()]
            val reactionLabel = emoji?.let { tr(if (user) "reaction.bot" else "reaction.yours", "emoji" to it) }.orEmpty()
            if (emoji != null) Surface(
                modifier = Modifier.padding(start = if (user) 0.dp else 8.dp, top = 1.dp)
                    .semantics { contentDescription = reactionLabel }
                    .clickable(enabled = !user) { actions.reaction(message.id, bubbleIndex, null) },
                shape = RoundedCornerShape(12.dp),
                color = palette.secondary,
                tonalElevation = 0.dp,
            ) {
                Text(emoji, fontSize = 18.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
            }
        }
        if (message.steps.isNotEmpty() && parts.none { it.type == "steps" }) ActivityTree(message.steps, message.live)
        val media = message.attachments.map { PreviewItem(it.path, it.name, it.mimeType, "upload") } +
            message.workFiles.filterNot { it.active }.map { PreviewItem(it.path, it.name, it.mimeType, "workspace") }
        key(mediaGeneration) {
            AttachmentGallery(media, thumbnails, actions, message.workFiles.associate { "workspace:${it.path}" to it.revision })
        }
        message.workFiles.filter { it.active }.forEach { file ->
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                LoadingDots()
                Text(tr("media.creating", "name" to file.name), color = palette.muted, fontSize = 12.sp)
            }
        }
        if (!user && message.model.isNotBlank() && !message.live) Text(message.model.substringAfterLast('/'), color = palette.muted, fontSize = 10.sp, modifier = Modifier.padding(start = 6.dp, top = 2.dp))
    }
    }
    MotionPopup(menuText != null, { menuText = null }, Modifier.widthIn(max = 300.dp).fillMaxWidth(), alignment = Alignment.Center, focusable = false) {
        if (!user && !message.live && !selectedNote) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf("👍", "❤️", "😂", "😮", "😢", "👎").forEach { emoji ->
                Text(emoji, fontSize = 24.sp, modifier = Modifier.size(44.dp).clip(CircleShape).clickable {
                    actions.reaction(message.id, selectedBubble, if (message.reactions[selectedBubble.toString()] == emoji) null else emoji); menuText = null
                }.padding(6.dp))
            }
        }
        MenuRow("copy", tr("chat.copy"), { actions.copyContent(menuText.orEmpty()); menuText = null })
        MenuRow("select", tr("chat.select"), { selection = menuText; menuText = null })
        MenuRow("share", tr("chat.share"), { actions.shareContent(menuText.orEmpty()); menuText = null })
        if (user) MenuRow("edit", tr("chat.edit"), { menuText = null; actions.editMessage(message.id) }, enabled = !busy)
        else MenuRow("retry", tr("chat.regenerate"), { menuText = null; actions.regenerate(message.id) }, enabled = !busy)

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
    val strings = LocalText.current
    var details by remember { mutableStateOf<ActivityRow?>(null) }
    val summary = steps.map { toolLabel(it.label, strings) }.filter { it.isNotBlank() }.distinct().take(2).joinToString(" · ")
    Column(Modifier.fillMaxWidth().padding(start = 5.dp)) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Glyph(when { failed -> "close"; interrupted -> "stop"; live -> "time"; else -> "check" }, modifier = Modifier.size(16.dp), tint = if (failed) MaterialTheme.colorScheme.error else p.muted)
            Spacer(Modifier.width(8.dp)); Text(if (!expanded && !live && !failed && !interrupted && summary.isNotBlank()) summary else tr(when { failed -> "chat.toolsFailed"; interrupted -> "chat.toolsInterrupted"; else -> "chat.tools" }, "count" to steps.size), fontSize = 12.sp, color = p.muted, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(if (expanded) tr("chat.hide") else tr("chat.details"), color = p.accent, fontSize = 12.sp)
        }
        AnimatedVisibility(expanded, enter = if (LocalReducedMotion.current) EnterTransition.None else expandVertically() + fadeIn(), exit = if (LocalReducedMotion.current) ExitTransition.None else shrinkVertically() + fadeOut()) {
            Column {
                steps.forEachIndexed { index, step ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { details = step }.heightIn(min = 42.dp), verticalAlignment = Alignment.CenterVertically) {
                        Canvas(Modifier.width(22.dp).height(42.dp)) {
                            val x = 5.dp.toPx(); val y = size.height / 2
                            drawLine(p.line, Offset(x, 0f), Offset(x, if (index == steps.lastIndex) y else size.height), 1.dp.toPx())
                            drawLine(p.line, Offset(x, y), Offset(size.width - 3.dp.toPx(), y), 1.dp.toPx())
                        }
                        Glyph(when { step.label.contains("search", true) -> "search"; step.label.contains("edit", true) || step.label.contains("write", true) -> "edit"; else -> "file" }, modifier = Modifier.size(16.dp), tint = if (step.status == "error") MaterialTheme.colorScheme.error else p.muted)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(toolLabel(step.label), fontSize = 12.sp, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (step.detail.isNotBlank()) Text(step.detail, fontSize = 11.sp, color = p.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (live && step.status in listOf("running", "started")) LoadingDots(Modifier.padding(8.dp))
                    }
                }
            }
        }
    }
    details?.let { step -> BotDialog({ details = null }) {
        Text(toolLabel(step.label), color = p.ink, fontWeight = FontWeight.SemiBold)
        SelectionContainer(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            Text(step.detail.ifBlank { toolLabel(step.label) }, color = p.muted, modifier = Modifier.padding(vertical = 14.dp))
        }
        QuietAction(tr("action.done"), { details = null }, Modifier.align(Alignment.End))
    } }

}

@Composable
private fun TypingIndicator() {
    Box(Modifier.padding(start = 2.dp).clip(RoundedCornerShape(19.dp, 19.dp, 19.dp, 6.dp)).background(Color(0xFFFFFDF8)).padding(horizontal = 16.dp, vertical = 13.dp)) {
        LoadingDots(color = Color(0xFF6C645D))
    }
}
