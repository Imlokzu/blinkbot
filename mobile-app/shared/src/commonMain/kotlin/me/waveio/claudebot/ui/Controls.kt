package me.waveio.claudebot.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

val LocalReducedMotion = staticCompositionLocalOf { false }

/** Short lived blur only: idle surfaces are opaque and need no backdrop rendering. */
@Composable
fun MotionPopup(
    open: Boolean, onDismiss: () -> Unit, modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.TopCenter, offset: IntOffset = IntOffset.Zero,
    anchorBounds: androidx.compose.ui.geometry.Rect? = null,
    focusable: Boolean = true,
    surfacePadding: Dp = 12.dp,
    drawBorder: Boolean = true,
    surfaceShape: Shape = RoundedCornerShape(24.dp),
    blurEntrance: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val target = remember { MutableTransitionState(false) }
    target.targetState = open
    val reduced = LocalReducedMotion.current
    val blurSteps = remember { (1..5).map { BlurEffect(it.toFloat(), it.toFloat()) } }
    val motion = updateTransition(target, label = "panel")
    val progress = motion.animateFloat(transitionSpec = {
        tween(if (reduced) 0 else if (targetState) MotionTiming.Panel else MotionTiming.Exit, easing = FastOutSlowInEasing)
    }, label = "panelReveal") { if (it) 1f else 0f }
    val edgePx = with(LocalDensity.current) { 8.dp.toPx() }
    if (target.currentState || target.targetState) {
        val popupAlignment = if (anchorBounds != null) Alignment { popupSize, windowSize, layoutDirection ->
            val anchor = anchorBounds ?: return@Alignment IntOffset.Zero
            val popupW = popupSize.width.toFloat(); val popupH = popupSize.height.toFloat()
            val windowW = windowSize.width.toFloat(); val windowH = windowSize.height.toFloat()
            val spaceAbove = anchor.top
            val spaceBelow = windowH - anchor.bottom
            val x = (anchor.center.x - popupW / 2f).coerceIn(edgePx, (windowW - popupW - edgePx).coerceAtLeast(edgePx))
            val y = when {
                spaceAbove > popupH + 8f -> anchor.top - popupH - 8f
                spaceBelow > popupH + 8f -> anchor.bottom + 8f
                else -> anchor.center.y - popupH / 2f
            }
            IntOffset(x.roundToInt(), y.roundToInt().coerceIn(0, (windowH - popupH).toInt().coerceAtLeast(0)))
        } else alignment
        Popup(alignment = popupAlignment, offset = offset, onDismissRequest = { if (open) onDismiss() }, properties = PopupProperties(focusable = focusable && open)) {
            val palette = LocalPalette.current
            Column(modifier.transitionInput(open).graphicsLayer {
                val value = progress.value
                alpha = value
                scaleX = if (reduced) 1f else 0.96f + value * 0.04f; scaleY = scaleX
                translationY = if (reduced) 0f else (1f - value) * -5.dp.toPx()
                transformOrigin = TransformOrigin(0.5f, 0f)
                val blur = ((1f - value) * 5f).toInt()
                renderEffect = if (blurEntrance && !reduced && value in 0.02f..0.97f && blur > 0) blurSteps[blur - 1] else null
            }.clip(surfaceShape).background(palette.surface)
                .then(if (drawBorder) Modifier.border(1.dp, palette.line, surfaceShape) else Modifier)
                .padding(surfacePadding), content = content)
        }
    }
}

@Composable
fun BotDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val p = LocalPalette.current
        EnterMotion(true, Modifier.padding(16.dp).widthIn(max = 480.dp).fillMaxWidth()) { transition ->
            Column(transition.clip(RoundedCornerShape(26.dp)).background(p.surface).border(1.dp, p.line, RoundedCornerShape(26.dp)).padding(16.dp), content = content)
        }
    }
}

@Composable
fun ActionButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    primary: Boolean = false, enabled: Boolean = true, icon: String? = null,
    maxLines: Int = 2,
) {
    val p = LocalPalette.current
    val reduced = LocalReducedMotion.current
    Row(modifier.heightIn(min = 48.dp).graphicsLayer { alpha = if (enabled) 1f else .4f }
        .clip(RoundedCornerShape(15.dp)).background(if (primary) p.ink else p.secondary)
        .clickable(interactionSource = null, indication = remember(primary, p, reduced) {
            PressIndication(if (primary) p.background else p.ink, reduced)
        }, enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        val ink = if (primary) p.background else p.ink
        if (icon != null) { Glyph(icon, modifier = Modifier.size(18.dp), tint = ink); Spacer(Modifier.width(8.dp)) }
        Text(text, color = ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = maxLines)
    }
}

@Composable
fun QuietAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(text, color = LocalPalette.current.ink.copy(alpha = if (enabled) 1f else .4f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun ChoicePill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val reduced = LocalReducedMotion.current
    val surface by animateColorAsState(if (selected) p.ink else p.secondary, tween(if (reduced) 0 else MotionTiming.Release), label = "choiceSurface")
    val ink by animateColorAsState(if (selected) p.background else p.ink, tween(if (reduced) 0 else MotionTiming.Release), label = "choiceInk")
    Box(modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(14.dp)).background(surface)
        .semantics { this.selected = selected }.clickable(role = Role.RadioButton, onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(text, color = ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun BotField(
    value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier,
    placeholder: String = "", label: String? = null, singleLine: Boolean = true, icon: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val p = LocalPalette.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (label != null) Text(label, fontSize = 12.sp, color = p.muted)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(p.secondary).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Glyph(icon, modifier = Modifier.size(18.dp), tint = p.muted); Spacer(Modifier.width(9.dp)) }
            BasicTextField(value, onChange, Modifier.weight(1f).heightIn(min = 46.dp).padding(vertical = 13.dp)
                .semantics { contentDescription = label ?: placeholder },
                singleLine = singleLine, textStyle = MaterialTheme.typography.bodyMedium.copy(color = p.ink, fontSize = 15.sp), cursorBrush = SolidColor(p.accent),
                keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
                decorationBox = { field -> Box { if (value.isEmpty()) Text(placeholder, color = p.muted, fontSize = 15.sp); field() } })
        }
    }
}

@Composable
fun MenuRow(icon: String, title: String, onClick: () -> Unit, subtitle: String? = null, enabled: Boolean = true, trailing: @Composable (() -> Unit)? = null) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Glyph(icon, modifier = Modifier.size(21.dp), tint = p.ink.copy(alpha = if (enabled) 1f else .4f))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = p.ink.copy(alpha = if (enabled) 1f else .4f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, color = p.muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) = Box(modifier.fillMaxWidth().height(1.dp).background(LocalPalette.current.line))

@Composable
fun BotToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    val position by animateFloatAsState(if (checked) 1f else 0f, tween(if (LocalReducedMotion.current) 0 else 140), label = "toggle")
    val track by animateColorAsState(if (checked) p.ink else p.line, tween(if (LocalReducedMotion.current) 0 else 180), label = "toggleTrack")
    val thumb by animateColorAsState(if (checked) p.background else p.surface, tween(if (LocalReducedMotion.current) 0 else 180), label = "toggleThumb")
    Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).semantics { toggleableState = if (checked) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off }
        .clickable(role = Role.Switch) { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = p.ink, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Canvas(Modifier.size(46.dp, 28.dp)) {
            drawRoundRect(track, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
            drawCircle(thumb, radius = 10.dp.toPx(), center = Offset(14.dp.toPx() + position * 18.dp.toPx(), size.height / 2))
        }
    }
}

@Composable
fun BotSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    val p = LocalPalette.current
    var width by remember { mutableFloatStateOf(1f) }
    val change by rememberUpdatedState(onChange)
    val fraction = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = p.ink, fontSize = 14.sp)
        Canvas(Modifier.fillMaxWidth().height(44.dp).onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }.semantics {
            contentDescription = label; progressBarRangeInfo = ProgressBarRangeInfo(value, range)
            setProgress { change(it.coerceIn(range)); true }
        }.pointerInput(range) { detectTapGestures { offset -> change(range.start + (offset.x / width).coerceIn(0f, 1f) * (range.endInclusive - range.start)) } }
            .pointerInput(range) { detectHorizontalDragGestures { event, _ -> event.consume(); change(range.start + (event.position.x / width).coerceIn(0f, 1f) * (range.endInclusive - range.start)) } }) {
            val y = size.height / 2; val edge = 10.dp.toPx(); val x = edge + (size.width - edge * 2) * fraction
            drawLine(p.line, Offset(edge, y), Offset(size.width - edge, y), 4.dp.toPx(), StrokeCap.Round)
            drawLine(p.ink, Offset(edge, y), Offset(x, y), 4.dp.toPx(), StrokeCap.Round)
            drawCircle(p.ink, 9.dp.toPx(), Offset(x, y))
        }
    }
}
