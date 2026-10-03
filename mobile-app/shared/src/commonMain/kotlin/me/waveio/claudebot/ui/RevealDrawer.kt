package me.waveio.claudebot.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

/** The conversation stays on top and reveals the menu beneath it. */
@Composable
fun RevealDrawer(open: Boolean, onOpenChange: (Boolean) -> Unit, enabled: Boolean, menu: @Composable () -> Unit, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    val reduced = LocalReducedMotion.current
    val density = LocalDensity.current
    val menuTitle = tr("nav.menu")
    val dismissLabel = tr("nav.closeMenu")
    val blurSteps = remember { (1..4).map { BlurEffect(it.toFloat(), it.toFloat()) } }
    val currentOpen by rememberUpdatedState(open)
    val change by rememberUpdatedState(onOpenChange)
    BoxWithConstraints(Modifier.fillMaxSize().background(p.surface)) {
        val reveal = with(density) { maxWidth.toPx() * .65f }
        val edge = with(density) { 28.dp.toPx() }
        var dragging by remember { mutableStateOf(false) }
        var offset by remember { mutableFloatStateOf(0f) }
        val position = animateFloatAsState(if (dragging) offset else if (open) reveal else 0f,
            if (dragging || reduced) snap() else spring(dampingRatio = .9f, stiffness = 460f), label = "drawerReveal")
        val visible = open || dragging || position.value > .5f
        if (visible) Box(Modifier.fillMaxHeight().fillMaxWidth(.65f).graphicsLayer {
            val fraction = (position.value / reveal).coerceIn(0f, 1f)
            alpha = .5f + .5f * fraction
            translationX = if (reduced) 0f else -18.dp.toPx() * (1f - fraction)
            val blur = ((1f - fraction) * 4f).toInt()
            renderEffect = if (!reduced && fraction in .03f.. .96f && blur > 0) blurSteps[blur - 1] else null
        }.semantics { paneTitle = menuTitle }, content = { menu() })
        Box(Modifier.fillMaxSize().graphicsLayer {
            val fraction = (position.value / reveal).coerceIn(0f, 1f)
            translationX = position.value
            scaleX = 1f - .025f * fraction; scaleY = scaleX
            shape = RoundedCornerShape((26f * fraction).dp); clip = true
            shadowElevation = 12.dp.toPx() * fraction
        }.pointerInput(enabled, reveal) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (!currentOpen && down.position.x > edge) return@awaitEachGesture
                val tracker = VelocityTracker()
                tracker.addPosition(down.uptimeMillis, down.position)
                val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { event, amount ->
                    if (currentOpen || amount > 0f) {
                        event.consume(); dragging = true
                        offset = ((if (currentOpen) reveal else 0f) + amount).coerceIn(0f, reveal)
                    }
                }
                if (drag != null && dragging) {
                    val finished = horizontalDrag(drag.id) { event ->
                        tracker.addPosition(event.uptimeMillis, event.position)
                        offset = (offset + (event.position.x - event.previousPosition.x)).coerceIn(0f, reveal)
                        event.consume()
                    }
                    val velocity = if (finished) tracker.calculateVelocity().x else 0f
                    change(when { velocity > 650f -> true; velocity < -650f -> false; else -> offset > reveal * .42f })
                    dragging = false
                }
            }
        }) {
            Box(if (visible) Modifier.fillMaxSize().clearAndSetSemantics { } else Modifier.fillMaxSize()) { content() }
            if (visible) Box(Modifier.fillMaxSize().graphicsLayer { alpha = (position.value / reveal).coerceIn(0f, 1f) * .18f }
                .background(Color.Black).semantics { contentDescription = dismissLabel }.clickable(role = Role.Button) { change(false) })
        }
    }
}
