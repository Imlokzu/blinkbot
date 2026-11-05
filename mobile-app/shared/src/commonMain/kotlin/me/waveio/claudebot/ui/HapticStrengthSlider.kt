package me.waveio.claudebot.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.waveio.claudebot.state.HapticLevelMin
import kotlin.math.roundToInt

/** Steps at which a drag plays a preview tap; finer than this would buzz continuously. */
private const val PreviewSteps = 12

/**
 * Agent-haptic strength as a bar chart that grows to the right. Every step the thumb
 * crosses plays one tap at that exact strength ([onPreview]), so the owner feels the
 * level instead of reading it. Taps stay spaced by [PreviewSteps]; the motor and the
 * system haptic setting still decide what is physically possible.
 */
@Composable
fun HapticStrengthSlider(
    label: String, value: Float, onChange: (Float) -> Unit, onPreview: (Float) -> Unit,
    lowLabel: String, highLabel: String, previewEnabled: Boolean,
) {
    val p = LocalPalette.current
    val reduced = LocalReducedMotion.current
    var width by remember { mutableFloatStateOf(1f) }
    var dragging by remember { mutableStateOf(false) }
    var lastStep by remember { mutableIntStateOf(-1) }
    val change by rememberUpdatedState(onChange)
    val preview by rememberUpdatedState(onPreview)
    val canPreview by rememberUpdatedState(previewEnabled)
    val range = HapticLevelMin..1f
    val fraction = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    val lift by animateFloatAsState(if (dragging) 1f else 0f, tween(if (reduced) 0 else MotionTiming.Release), label = "strengthLift")

    fun emit(x: Float, force: Boolean) {
        val f = (x / width).coerceIn(0f, 1f)
        val level = range.start + f * (range.endInclusive - range.start)
        change(level)
        val step = (f * PreviewSteps).roundToInt()
        if (canPreview && (force || step != lastStep)) { lastStep = step; preview(level) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = p.ink, fontSize = 14.sp)
        Canvas(Modifier.fillMaxWidth().height(56.dp).onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }.semantics {
            contentDescription = label; progressBarRangeInfo = ProgressBarRangeInfo(value, range)
            setProgress { target -> change(target.coerceIn(range)); if (canPreview) preview(target.coerceIn(range)); true }
        }.pointerInput(Unit) { detectTapGestures { emit(it.x, force = true) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true; lastStep = -1; emit(it.x, force = true) },
                    onDragEnd = { dragging = false }, onDragCancel = { dragging = false },
                ) { event, _ -> event.consume(); emit(event.position.x, force = false) }
            }) {
            // Bars rise from left to right like a signal meter; those up to the thumb are inked.
            val bars = 24
            val gap = 3.dp.toPx()
            val barWidth = (size.width - gap * (bars - 1)) / bars
            val floor = 6.dp.toPx()
            val top = 6.dp.toPx()
            for (index in 0 until bars) {
                val position = (index + .5f) / bars
                val height = floor + (size.height - top - floor) * (.18f + .82f * position)
                val active = position <= fraction
                val x = index * (barWidth + gap)
                // One hue only: the inked part fades in toward the thumb, the rest stays a quiet line.
                val color = if (active) p.ink.copy(alpha = .38f + .62f * position) else p.line
                drawRoundRect(color, Offset(x, size.height - height), Size(barWidth, height), CornerRadius(barWidth / 2))
            }
            val thumbX = (fraction * size.width).coerceIn(5.dp.toPx(), size.width - 5.dp.toPx())
            val radius = (8f + 3f * lift).dp.toPx()
            drawCircle(p.background, radius + 2.dp.toPx(), Offset(thumbX, size.height - radius - 1.dp.toPx()))
            drawCircle(p.ink, radius, Offset(thumbX, size.height - radius - 1.dp.toPx()))
        }
        Row(Modifier.fillMaxWidth()) {
            Text(lowLabel, color = p.muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text(highLabel, color = p.muted, fontSize = 11.sp)
        }
    }
}
