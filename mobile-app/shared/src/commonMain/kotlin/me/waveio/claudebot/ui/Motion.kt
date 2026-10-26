package me.waveio.claudebot.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Shared timings keep controls responsive without delaying their actions. */
object MotionTiming {
    const val Press = 70
    const val Release = 120
    const val Exit = 100
    const val Panel = 180
    const val ReplyFocus = 180
    const val Ghost = 240
}

/** Exiting content keeps its pixels, but cannot retain an interactive surface. */
internal fun Modifier.transitionInput(active: Boolean): Modifier = if (active) this else {
    clearAndSetSemantics { }
        .focusProperties { canFocus = false }
        .onPreviewKeyEvent { true }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }
}

/** Draw-only feedback reaches every clickable row, including keyboard focus. */
data class PressIndication(val color: Color, val reducedMotion: Boolean) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        PressIndicationNode(interactionSource, color, reducedMotion)
}

private class PressIndicationNode(
    private val source: InteractionSource,
    private val color: Color,
    private val reduced: Boolean,
) : Modifier.Node(), DrawModifierNode {
    private val pressure = Animatable(0f)
    private var focused by mutableStateOf(false)
    private var hovered by mutableStateOf(false)

    override fun onAttach() {
        focused = false
        hovered = false
        coroutineScope.launch {
            pressure.snapTo(0f)
            val presses = mutableSetOf<PressInteraction.Press>()
            var pressAnimation: Job? = null
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        presses.add(interaction)
                        pressAnimation?.cancel()
                        pressAnimation = launch {
                            if (reduced) pressure.snapTo(1f)
                            else pressure.animateTo(1f, tween(MotionTiming.Press))
                        }
                    }
                    is PressInteraction.Release -> {
                        presses.remove(interaction.press)
                        if (presses.isEmpty()) {
                            pressAnimation?.cancel()
                            pressAnimation = launch {
                                if (reduced) pressure.snapTo(0f) else {
                                    // Even a quick tap receives feedback. The click itself
                                    // has already fired and never waits for this animation.
                                    if (pressure.value < .5f) pressure.animateTo(.5f, tween(35))
                                    pressure.animateTo(0f, tween(MotionTiming.Release))
                                }
                            }
                        }
                    }
                    is PressInteraction.Cancel -> {
                        presses.remove(interaction.press)
                        if (presses.isEmpty()) {
                            pressAnimation?.cancel()
                            pressAnimation = launch { pressure.snapTo(0f) }
                        }
                    }
                    is FocusInteraction.Focus -> focused = true
                    is FocusInteraction.Unfocus -> focused = false
                    is HoverInteraction.Enter -> hovered = true
                    is HoverInteraction.Exit -> hovered = false
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        val amount = pressure.value
        val factor = if (reduced) 1f else 1f - .018f * amount
        scale(factor) {
            this@draw.drawContent()
            val opacity = maxOf(amount * .09f, if (focused) .12f else if (hovered) .045f else 0f)
            if (opacity > 0f) drawRoundRect(color.copy(alpha = opacity), cornerRadius = CornerRadius(12.dp.toPx()))
        }
    }
}

@Composable
fun EnterMotion(
    animate: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val progress = remember { Animatable(if (animate && !reduced) 0f else 1f) }
    val blurSteps = remember { (1..4).map { BlurEffect(it.toFloat(), it.toFloat()) } }
    LaunchedEffect(reduced) {
        if (reduced) progress.snapTo(1f)
        else if (progress.value < 1f) progress.animateTo(1f, tween(MotionTiming.Panel, easing = FastOutSlowInEasing))
    }
    content(modifier.graphicsLayer {
        val value = if (reduced) 1f else progress.value
        alpha = value
        translationY = (1f - value) * 10.dp.toPx()
        scaleX = .985f + .015f * value; scaleY = scaleX
        val step = ((1f - value) * 4f).toInt()
        renderEffect = when {
            step > 0 -> blurSteps[step - 1]
            else -> null
        }
    })
}

/**
 * Shutter-style blur. Moving content smears along its own path in proportion
 * to its on-screen speed, as a camera exposure would, and is sharp the moment
 * it stops. Effects are cached per half-dp step, so a whole flight allocates a
 * handful of render effects rather than one per frame.
 */
internal class MotionBlur(private val quantum: Float, private val maxRadius: Float) {
    private val cache = HashMap<Long, BlurEffect>()

    /** [speedX]/[speedY] are px per second; [focus] adds a uniform defocus in px. */
    fun effect(speedX: Float = 0f, speedY: Float = 0f, focus: Float = 0f): BlurEffect? {
        fun radius(speed: Float) = kotlin.math.hypot(abs(speed) * ShutterSeconds, focus).coerceAtMost(maxRadius)
        val x = (radius(speedX) / quantum).roundToInt()
        val y = (radius(speedY) / quantum).roundToInt()
        if (x == 0 && y == 0) return null
        return cache.getOrPut((x.toLong() shl 32) or y.toLong()) { BlurEffect(x * quantum, y * quantum, TileMode.Decal) }
    }

    private companion object { const val ShutterSeconds = 1f / 120f }
}

@Composable
internal fun rememberMotionBlur(maxRadius: Dp = 18.dp): MotionBlur {
    val density = LocalDensity.current
    return remember(density, maxRadius) { with(density) { MotionBlur(.5.dp.toPx(), maxRadius.toPx()) } }
}

/**
 * A sent bubble is thrown up out of the composer: a quick spring with a slight
 * overshoot, stretched and smeared along its path while it is fast. A reply
 * lands from just below, coming out of a brief defocus. Restored history stays
 * still. Opacity is reached within a few frames, so nothing lingers as a pale
 * ghost; the sense of motion comes from speed, not from a slow fade.
 */
@Composable
fun MessageArrival(animate: Boolean, human: Boolean, content: @Composable (Modifier) -> Unit) {
    val reduced = LocalReducedMotion.current
    val start = if (animate && !reduced) 0f else 1f
    val rise = remember { Animatable(start) }
    val shown = remember { Animatable(start) }
    val focus = remember { Animatable(start) }
    val blur = rememberMotionBlur()
    LaunchedEffect(reduced) {
        if (reduced) { rise.snapTo(1f); shown.snapTo(1f); focus.snapTo(1f); return@LaunchedEffect }
        launch { if (shown.value < 1f) shown.animateTo(1f, tween(if (human) 60 else 110, easing = LinearEasing)) }
        launch { if (focus.value < 1f) focus.animateTo(1f, tween(MotionTiming.ReplyFocus, easing = FastOutSlowInEasing)) }
        if (rise.value < 1f) rise.animateTo(1f,
            if (human) spring(dampingRatio = .74f, stiffness = 520f) else spring(dampingRatio = .86f, stiffness = 700f))
    }
    content(Modifier.graphicsLayer {
        if (reduced) return@graphicsLayer
        val travel = (if (human) 76.dp else 16.dp).toPx()
        val lift = 1f - rise.value
        val speed = rise.velocity * travel
        // Normalised speed drives a slight stretch along the path.
        val pace = (abs(speed) / 2400.dp.toPx()).coerceAtMost(1f)
        // A reply takes the indicator's place in the same frame, so it starts
        // partly visible: a transparent first frame would read as a flicker.
        alpha = shown.value.coerceIn(0f, 1f).let { if (human) it else .4f + .6f * it }
        translationY = lift * travel
        translationX = if (human) lift * 10.dp.toPx() else 0f
        transformOrigin = TransformOrigin(if (human) 1f else 0f, 1f)
        val settle = 1f - lift * (if (human) .06f else .04f)
        scaleY = settle * (1f + pace * .07f)
        scaleX = settle * (1f - pace * .03f)
        val defocus = if (human) 0f else (1f - focus.value.coerceIn(0f, 1f)) * 5.dp.toPx()
        renderEffect = blur.effect(speedX = if (human) rise.velocity * 10.dp.toPx() else 0f, speedY = speed, focus = defocus)
    })
}
