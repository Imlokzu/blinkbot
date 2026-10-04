package me.waveio.claudebot.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import me.waveio.claudebot.state.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Composer(state: AppState, actions: AppActions) {
    val p = LocalPalette.current
    val window = LocalWindowInfo.current.containerSize
    val landscape = window.width > window.height
    val keyboard = LocalSoftwareKeyboardController.current
    val popupRise = with(LocalDensity.current) { (-58).dp.roundToPx() }
    val sendLabel = tr("chat.send")
    val dictating = state.dictationOpen
    val amplitude = animateFloatAsState(if (state.recording) state.amplitude.coerceIn(0f, 1f) else 0f, tween(if (LocalReducedMotion.current) 0 else 80), label = "voiceAmplitude")
    val corners = RoundedCornerShape(25.dp)
    val shownDraft = if (dictating) listOf(state.draft, state.transcript).filter { it.isNotBlank() }.joinToString(" ") else state.draft
    Column(Modifier.padding(horizontal = 12.dp, vertical = if (landscape) 0.dp else 8.dp).fillMaxWidth().drawBehind {
        if (dictating) {
            val brush = Brush.linearGradient(listOf(p.accent.copy(alpha = .65f), Color(0xFFDAB397), Color(0xFFC4826A)), start = Offset(0f, size.height * amplitude.value), end = Offset(size.width, size.height * (1f - amplitude.value)))
            val radius = CornerRadius(25.dp.toPx())
            drawRoundRect(brush, cornerRadius = radius, style = Stroke((4f + amplitude.value * 4f).dp.toPx()), alpha = .045f + amplitude.value * .08f)
            drawRoundRect(brush, cornerRadius = radius, style = Stroke((1f + amplitude.value * 1.5f).dp.toPx()), alpha = .4f + amplitude.value * .4f)
        }
    }.clip(corners).background(p.surface).border(1.dp, if (dictating) p.accent.copy(alpha = .65f) else p.line, corners).padding(if (landscape) 0.dp else 8.dp)) {
        if (state.editingMessageId != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("chat.edit"), color = p.muted, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(start = 10.dp))
            IconAction("close", tr("input.cancel"), actions::cancelEdit)
        }
        if (state.attachments.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            state.attachments.forEach { item -> key(state.mediaGeneration, item.path) {
                if (item.mimeType.startsWith("image/")) Box {
                    AttachmentThumbnail(item, state.attachmentThumbnails[item.path], actions, compact = true)
                    IconAction("close", tr("input.removeAttachment"), { actions.removeAttachment(item.path) },
                        Modifier.align(Alignment.TopEnd).padding(2.dp).size(28.dp).clip(CircleShape).background(p.surface.copy(alpha = .92f)))
                } else Row(Modifier.clip(RoundedCornerShape(12.dp)).background(p.secondary).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph("file", modifier = Modifier.size(17.dp), tint = p.muted)
                    Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, color = p.ink, fontSize = 12.sp, modifier = Modifier.widthIn(max = 135.dp).padding(start = 7.dp))
                    IconAction("close", tr("input.removeAttachment"), { actions.removeAttachment(item.path) }, Modifier.size(36.dp))
                }
            } }
        }
        val input: @Composable (Modifier) -> Unit = { modifier ->
        BasicTextField(shownDraft, actions::draft, modifier.heightIn(min = if (dictating && !landscape) 78.dp else 48.dp, max = if (landscape) 96.dp else 190.dp).padding(11.dp, 11.dp),
            readOnly = dictating, textStyle = MaterialTheme.typography.bodyLarge.copy(color = p.ink, fontSize = 16.sp), cursorBrush = SolidColor(p.accent),
            decorationBox = { field -> Box { if (shownDraft.isEmpty()) Text(tr(if (dictating) "input.dictationHelp" else "chat.placeholder"), color = p.muted, fontSize = 15.sp, lineHeight = 22.sp); field() } })
        }
        val controls: @Composable RowScope.() -> Unit = {
            if (dictating) {
                IconAction("close", tr("input.cancel"), actions::cancelDictation)
                VoiceBars(amplitude, state.recording)
                Text(tr(if (state.transcribing) "input.transcribing" else if (state.recording) "input.listening" else "input.review"), color = p.muted, fontSize = 11.sp, modifier = Modifier.weight(1f).padding(start = 9.dp))
                if (state.recording) RoundComposerAction("stop", tr("chat.stop"), actions::stopDictation, true)
                else if (!state.transcribing && state.transcript.isNotBlank()) RoundComposerAction("check", tr("input.useText"), actions::useTranscript, true)
                else if (state.transcribing) LoadingDots(Modifier.padding(14.dp))
            } else {
                Box {
                    IconAction("attach", tr("input.attach"), { keyboard?.hide(); actions.attachments(!state.attachmentPickerOpen) }, enabled = !state.uploading)
                }
                if (state.uploading) LoadingDots(Modifier.padding(horizontal = 6.dp))
                if (!landscape) Spacer(Modifier.weight(1f))
                IconAction("mic", tr("input.microphone"), { keyboard?.hide(); actions.startDictation() })
                if (state.draft.isNotBlank() || state.attachments.isNotEmpty() || !state.busy) Box {
                    val enabled = !state.uploading && (state.draft.isNotBlank() || state.attachments.isNotEmpty())
                    Box(Modifier.size(44.dp).clip(CircleShape).background(if (enabled) p.ink else p.secondary)
                        .semantics { contentDescription = sendLabel; if (!enabled) disabled() }
                        .combinedClickable(enabled = enabled, role = Role.Button, onClick = { actions.send() }, onLongClick = { actions.sendModes(true) }), contentAlignment = Alignment.Center) {
                        Glyph("send", modifier = Modifier.size(20.dp), tint = if (enabled) p.background else p.muted)
                    }
                    MotionPopup(state.sendModeOpen, { actions.sendModes(false) }, Modifier.width(260.dp), Alignment.BottomEnd, IntOffset(0, popupRise), focusable = false) {
                        MenuRow("time", tr("queue.title"), { actions.sendModes(false); actions.send("queue") })
                        if (state.steerAvailable) MenuRow("edit", tr("queue.steer"), { actions.sendModes(false); actions.send("steer") }, enabled = state.busy)
                        MenuRow("calendar", tr("queue.later"), { actions.sendModes(false); actions.schedule(true) })
                    }
                }
                if (state.busy) RoundComposerAction("stop", tr("chat.stop"), actions::stop)
            }
        }
        if (landscape) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            input(Modifier.weight(1f))
            controls()
        } else {
            input(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, content = controls)
        }
    }
}

@Composable
private fun RoundComposerAction(icon: String, label: String, onClick: () -> Unit, accent: Boolean = false) {
    val p = LocalPalette.current
    Box(Modifier.size(46.dp).clip(CircleShape).background(if (accent) p.accent else p.ink).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Glyph(icon, label, Modifier.size(20.dp), if (accent) Color.White else p.background)
    }
}

@Composable
private fun VoiceBars(amplitude: State<Float>, active: Boolean) {
    val color = LocalPalette.current.accent
    Canvas(Modifier.width(43.dp).height(25.dp)) {
        repeat(7) { index ->
            val middle = 1f - kotlin.math.abs(index - 3) / 4f
            val height = 3.dp.toPx() + if (active) amplitude.value * size.height * middle else 0f
            val x = index * size.width / 7f + 2.dp.toPx()
            drawLine(color, Offset(x, (size.height - height) / 2), Offset(x, (size.height + height) / 2), 2.4.dp.toPx(), StrokeCap.Round)
        }
    }
}

@Composable
fun LoadingDots(modifier: Modifier = Modifier, color: Color = LocalPalette.current.muted) {
    val reduced = LocalReducedMotion.current
    if (reduced) {
        Canvas(modifier.width(27.dp).height(14.dp)) {
            repeat(3) { index -> drawCircle(color.copy(alpha = .8f), 2.dp.toPx(), Offset((4 + index * 8).dp.toPx(), size.height / 2)) }
        }
        return
    }
    val transition = rememberInfiniteTransition(label = "waiting")
    val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1050)), label = "waitingDots")
    Canvas(modifier.width(27.dp).height(14.dp)) {
        repeat(3) { index ->
            val pulse = if (reduced) .8f else .35f + .65f * ((kotlin.math.sin((phase * 2f * kotlin.math.PI - index * .8f)).toFloat() + 1f) / 2f)
            drawCircle(color.copy(alpha = pulse), 2.dp.toPx(), Offset((4 + index * 8).dp.toPx(), size.height / 2))
        }
    }
}

@Composable
fun AttachmentMenu(state: AppState, actions: AppActions) {
    val p = LocalPalette.current
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val bottom = WindowInsets.safeDrawing.getBottom(density)
    val height = with(density) { (window.height - WindowInsets.safeDrawing.getTop(density) - bottom).toDp() }
    MotionPopup(state.attachmentPickerOpen, { actions.attachments(false) }, Modifier.fillMaxWidth().heightIn(max = height), Alignment.BottomCenter, IntOffset(0, -bottom), focusable = false) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(tr("input.attach"), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = p.ink, modifier = Modifier.padding(10.dp, 10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("camera" to "camera", "photo" to "photo", "document" to "file").forEach { (kind, icon) ->
                    Column(Modifier.weight(1f).heightIn(min = 88.dp).clip(RoundedCornerShape(17.dp)).background(p.secondary).clickable(role = Role.Button) { actions.attachments(false); actions.pickFile(kind) }
                        .padding(vertical = 15.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Glyph(icon, modifier = Modifier.size(26.dp))
                        Text(tr("input.$kind"), color = p.ink, fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
            Hairline(Modifier.padding(vertical = 10.dp))
            MenuRow("skills", tr("input.skills"), { actions.attachments(false); actions.navigate(Screen.Skills) })
            MenuRow("folder", tr("files.root"), { actions.attachments(false); actions.navigate(Screen.Files) })
            MenuRow("calendar", tr("queue.later"), { actions.attachments(false); actions.schedule(true) }, enabled = state.draft.isNotBlank() || state.attachments.isNotEmpty())
        }
    }
}
