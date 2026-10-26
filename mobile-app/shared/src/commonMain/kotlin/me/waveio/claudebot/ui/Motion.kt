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
import kotlin.math.ceil

/** Shared timings keep controls responsive without delaying their actions. */
object MotionTiming {
    const val Press = 70
    const val Release = 120
    const val Exit = 100
    const val Panel = 180
    const val MessageFocus = 420
    const val ReplyFocus = 620
    const val Ghost = 380
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
 * Fine blur ladder for entrances. Twenty-four cached radii read as a continuous
 * focus pull, while the render effects are still allocated only once.
 */
@Composable
internal fun rememberBlurLadder(maxRadius: Dp, steps: Int = 24): List<BlurEffect> {
    val radius = with(LocalDensity.current) { maxRadius.toPx() }
    return remember(radius, steps) { (1..steps).map { BlurEffect(radius * it / steps, radius * it / steps, TileMode.Decal) } }
}

internal fun List<BlurEffect>.at(amount: Float): BlurEffect? {
    val step = ceil(amount.coerceIn(0f, 1f) * size).toInt()
    return if (step == 0) null else this[step - 1]
}

/**
 * A sent bubble springs up out of the composer and comes into focus; a reply
 * condenses out of a heavier blur. Restored history stays still.
 *
 * Position and scale use a spring, so a sent bubble overshoots slightly and
 * settles. Focus and opacity use a separate eased track that never overshoots,
 * because a negative blur or an alpha above one has no meaning.
 */
@Composable
fun MessageArrival(animate: Boolean, human: Boolean, content: @Composable (Modifier) -> Unit) {
    val reduced = LocalReducedMotion.current
    val rise = remember { Animatable(if (animate && !reduced) 0f else 1f) }
    val focus = remember { Animatable(if (animate && !reduced) 0f else 1f) }
    val blur = rememberBlurLadder(if (human) 14.dp else 20.dp)
    LaunchedEffect(reduced) {
        if (reduced) { rise.snapTo(1f); focus.snapTo(1f); return@LaunchedEffect }
        launch {
            if (rise.value < 1f) rise.animateTo(1f,
                if (human) spring(dampingRatio = .62f, stiffness = 360f) else spring(dampingRatio = .82f, stiffness = 240f))
        }
        if (focus.value < 1f) focus.animateTo(1f, tween(if (human) MotionTiming.MessageFocus else MotionTiming.ReplyFocus, easing = LinearOutSlowInEasing))
    }
    content(Modifier.graphicsLayer {
        if (reduced) return@graphicsLayer
        val lift = 1f - rise.value
        val clear = focus.value.coerceIn(0f, 1f)
        alpha = (clear * 1.8f).coerceAtMost(1f)
        translationY = lift * (if (human) 72.dp else 30.dp).toPx()
        translationX = if (human) lift * 14.dp.toPx() else 0f
        transformOrigin = TransformOrigin(if (human) 1f else 0f, 1f)
        scaleX = 1f - lift * (if (human) .2f else .1f)
        scaleY = scaleX
        renderEffect = blur.at(1f - clear)
    })
}
