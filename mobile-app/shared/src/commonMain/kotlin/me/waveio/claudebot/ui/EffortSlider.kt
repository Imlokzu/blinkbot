package me.waveio.claudebot.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlin.math.abs
import kotlin.math.roundToInt

/** Native counterpart of the PC effort picker, including its thumb-centred travel. */
@Composable
internal fun EffortSlider(
    effort: String,
    efforts: List<String>,
    modelLabel: String,
    modelBrand: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onChangeModel: () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = Color(0xFFE6532F)
    // Automatic inherits configuration; explicit off remains a separate stop.
    val levels = (listOf("none") + efforts).distinct()
    val current = levels.indexOf(effort).coerceAtLeast(0)
    val last = levels.lastIndex.coerceAtLeast(1)
    val canSelect = enabled && levels.size > 1
    var preview by remember(effort, levels, canSelect) { mutableStateOf<Float?>(null) }
    var focused by remember { mutableStateOf(false) }
    val index = ((preview ?: (current.toFloat() / last)) * last).roundToInt().coerceIn(levels.indices)
    val label = tr("model.${levels[index]}")
    val sliderLabel = tr("model.effort")
    val changeModelLabel = tr("model.change")
    val focusRequester = remember { FocusRequester() }
    val reduced = LocalReducedMotion.current
    val fraction by animateFloatAsState(
        preview ?: (current.toFloat() / last),
        tween(if (reduced || preview != null) 0 else 180), label = "effortPosition",
    )
    fun select(position: Int) {
        if (canSelect) {
            val next = levels[position.coerceIn(levels.indices)]
            if (next != effort) onSelect(next)
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(7.dp))
            .testTag("change-model").semantics { contentDescription = changeModelLabel }
            .clickable(role = Role.Button, onClick = onChangeModel).padding(horizontal = 8.dp, vertical = 7.dp)) {
            Text(label, color = palette.ink, fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                BrandMark(modelBrand, Modifier.size(14.dp).testTag("effort-model-brand"))
                Text(modelLabel, color = palette.muted, fontSize = 12.sp, lineHeight = 15.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Canvas(Modifier.size(10.dp)) {
                    val ink = palette.muted.copy(alpha = .55f)
                    drawLine(ink, Offset(size.width * .35f, size.height * .2f), Offset(size.width * .65f, size.height * .5f), 1.4.dp.toPx(), StrokeCap.Round)
                    drawLine(ink, Offset(size.width * .65f, size.height * .5f), Offset(size.width * .35f, size.height * .8f), 1.4.dp.toPx(), StrokeCap.Round)
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 4.dp)) {
            val travel = (maxWidth - 44.dp).coerceAtLeast(0.dp)
            Box(Modifier.fillMaxWidth().height(48.dp).testTag("effort-slider")
                .semantics {
                    contentDescription = sliderLabel
                    stateDescription = label
                    progressBarRangeInfo = ProgressBarRangeInfo(index.toFloat(), 0f..last.toFloat(), (levels.size - 2).coerceAtLeast(0))
                    if (!canSelect) disabled()
                    else setProgress { select(it.roundToInt()); true }
                }
                .focusRequester(focusRequester).onFocusChanged {
                    focused = it.isFocused
                    if (!focused) preview = null
                }
                .onKeyEvent { event ->
                    if (!canSelect || event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val next = when (event.key) {
                        Key.DirectionRight, Key.DirectionUp -> current + 1
                        Key.DirectionLeft, Key.DirectionDown -> current - 1
                        Key.MoveHome -> 0
                        Key.MoveEnd -> levels.lastIndex
                        else -> return@onKeyEvent false
                    }
                    select(next)
                    true
                }.focusable(canSelect)
                .pointerInput(effort, levels, canSelect) {
                    if (!canSelect) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        focusRequester.requestFocus()
                        val halfThumb = 22.dp.toPx()
                        val distance = (size.width - 2 * halfThumb).coerceAtLeast(1f)
                        val initial = current.toFloat() / last
                        val thumbCenter = halfThumb + distance * initial
                        val fromThumb = abs(down.position.x - thumbCenter) <= halfThumb
                        val grabOffset = if (fromThumb) down.position.x - thumbCenter else 0f
                        fun position(x: Float) = ((x - grabOffset - halfThumb) / distance).coerceIn(0f, 1f)
                        var next = if (fromThumb) initial else position(down.position.x)
                        preview = next
                        down.consume()
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!focused || change.isConsumed || event.changes.any { it.id != down.id && it.pressed }) break
                                next = position(change.position.x)
                                preview = next
                                change.consume()
                                if (!change.pressed) {
                                    select((next * last).roundToInt())
                                    break
                                }
                            }
                        } finally {
                            // Cancellation, dismissal and model/effort changes never save a preview.
                            preview = null
                        }
                    }
                }) {
                Canvas(Modifier.fillMaxSize()) {
                    val trackTop = (size.height - 28.dp.toPx()) / 2f
                    val corner = CornerRadius(14.dp.toPx())
                    val rail = Size(size.width, 28.dp.toPx())
                    val opacity = if (canSelect) 1f else .5f
                    drawRoundRect(Brush.verticalGradient(listOf(palette.ink.copy(alpha = .075f * opacity), palette.ink.copy(alpha = .04f * opacity))), Offset(0f, trackTop), rail, corner)
                    drawRoundRect(palette.ink.copy(alpha = .08f * opacity), Offset(.5.dp.toPx(), trackTop + .5.dp.toPx()), Size(rail.width - 1.dp.toPx(), rail.height - 1.dp.toPx()), corner, style = Stroke(1.dp.toPx()))
                    val fillWidth = (36.dp.toPx() + (size.width - 44.dp.toPx()) * fraction).coerceIn(0f, size.width)
                    drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFEC795D).copy(alpha = opacity), accent.copy(alpha = opacity)), startY = trackTop, endY = trackTop + rail.height), Offset(0f, trackTop), Size(fillWidth, rail.height), corner)
                    levels.forEachIndexed { stop, _ ->
                        val x = 22.dp.toPx() + (size.width - 44.dp.toPx()) * stop / last
                        drawCircle(if (stop <= index) Color.White.copy(alpha = .4f * opacity) else palette.muted.copy(alpha = .28f * opacity), 2.dp.toPx(), Offset(x, size.height / 2f))
                    }
                    if (focused || preview != null) {
                        drawRoundRect(accent.copy(alpha = if (preview != null) .3f else .8f),
                            Offset(travel.toPx() * fraction - 4.dp.toPx(), 4.dp.toPx()), Size(52.dp.toPx(), 40.dp.toPx()), CornerRadius(20.dp.toPx()), style = Stroke(1.dp.toPx()))
                    }
                }
                Box(Modifier.offset { IntOffset((travel.toPx() * fraction).roundToInt(), 8.dp.roundToPx()) }
                    .size(44.dp, 32.dp).shadow(2.dp, RoundedCornerShape(16.dp))
                    .background(Brush.verticalGradient(listOf(Color.White, Color(0xFFF6F6F6))), RoundedCornerShape(16.dp))
                    .border(1.dp, Color.Black.copy(alpha = .11f), RoundedCornerShape(16.dp)))
            }
        }
        if (levels.size == 1) Text(tr("model.effortUnavailable"), color = palette.muted, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
    }
}
