package me.waveio.claudebot.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.unit.dp

/** Custom controls supply their own motion; don't paint platform press rectangles. */
object QuietIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = object : Modifier.Node(), DrawModifierNode {
        override fun ContentDrawScope.draw() = drawContent()
    }
    override fun equals(other: Any?) = other === this
    override fun hashCode() = 719
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
    LaunchedEffect(Unit) { if (progress.value < 1f) progress.animateTo(1f, tween(210, easing = FastOutSlowInEasing)) }
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

/** A sent bubble lifts from the composer; restored history stays still. */
@Composable
fun MessageArrival(animate: Boolean, human: Boolean, content: @Composable (Modifier) -> Unit) {
    val reduced = LocalReducedMotion.current
    val progress = remember { Animatable(if (animate && !reduced) 0f else 1f) }
    val blurSteps = remember { (1..4).map { BlurEffect(it.toFloat(), it.toFloat()) } }
    LaunchedEffect(Unit) {
        if (progress.value < 1f) progress.animateTo(1f, tween(if (human) 360 else 280, easing = FastOutSlowInEasing))
    }
    content(Modifier.graphicsLayer {
        val value = if (reduced) 1f else progress.value
        alpha = value
        translationY = (1f - value) * (if (human) 34.dp else 14.dp).toPx()
        translationX = if (human) (1f - value) * 10.dp.toPx() else 0f
        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(if (human) 1f else 0f, 1f)
        scaleX = if (human) .72f + .28f * value else .96f + .04f * value
        scaleY = if (human) .72f + .28f * value else .96f + .04f * value
        val blur = ((1f - value) * 4f).toInt()
        renderEffect = if (human && blur > 0) blurSteps[blur - 1] else null
    })
}
