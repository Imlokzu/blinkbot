package me.waveio.claudebot.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

/** One stable pair of panes, revealed on phones and placed side by side on wide hosts. */
@Composable
fun RevealDrawer(open: Boolean, onOpenChange: (Boolean) -> Unit, enabled: Boolean, menu: @Composable () -> Unit, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    val reduced = LocalReducedMotion.current
    val density = LocalDensity.current
    val menuTitle = tr("nav.menu")
    val dismissLabel = tr("nav.closeMenu")
    val blurSteps = remember { (1..4).map { BlurEffect(it.toFloat(), it.toFloat()) } }
    val persistent = LocalAdaptiveLayout.current.persistentSidebar
    val currentOpen by rememberUpdatedState(open && !persistent)
    val change by rememberUpdatedState(onOpenChange)
    BoxWithConstraints(Modifier.fillMaxSize().background(p.surface).clipToBounds()) {
        val menuWidth = if (persistent) SidebarWidth else maxWidth * .65f
        val reveal = with(density) { menuWidth.toPx() }.coerceAtLeast(1f)
        // Start inside the content, beyond Android's system-back edge zone.
        // Horizontal children (tables/editors) can still consume their own drag.
        val edge = with(density) { 128.dp.toPx() }
        var dragging by remember { mutableStateOf(false) }
        var offset by remember { mutableFloatStateOf(0f) }
        val position = animateFloatAsState(if (persistent) 0f else if (dragging) offset else if (open) reveal else 0f,
            if (persistent || dragging || reduced) snap() else spring(dampingRatio = .9f, stiffness = 460f), label = "drawerReveal")
        val flingThreshold = with(density) { 420.dp.toPx() }
        val minimumFlingTravel = with(density) { 24.dp.toPx() }
        LaunchedEffect(persistent, reveal) { dragging = false }
        val gestures = Modifier.pointerInput(enabled, persistent, reveal, edge, flingThreshold, minimumFlingTravel) {
            if (!enabled || persistent) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if ((!currentOpen && down.position.x > edge) || (currentOpen && down.position.x < position.value)) return@awaitEachGesture
                val tracker = VelocityTracker()
                tracker.addPosition(down.uptimeMillis, down.position)
                val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { event, amount ->
                    if (currentOpen || amount > 0f) {
                        event.consume(); dragging = true
                        offset = ((if (currentOpen) reveal else 0f) + amount).coerceIn(0f, reveal)
                    }
                }
                if (drag != null && dragging) {
                    tracker.addPosition(drag.uptimeMillis, drag.position)
                    var lastPosition = drag.position
                    val finished = try { horizontalDrag(drag.id) { event ->
                        tracker.addPosition(event.uptimeMillis, event.position)
                        lastPosition = event.position
                        offset = (offset + (event.position.x - event.previousPosition.x)).coerceIn(0f, reveal)
                        event.consume()
                    } } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        dragging = false
                        throw cancelled
                    }
                    val velocity = if (finished) tracker.calculateVelocity().x else 0f
                    val distance = lastPosition.x - down.position.x
                    change(when {
                        !finished -> currentOpen
                        velocity > flingThreshold && distance > minimumFlingTravel -> true
                        velocity < -flingThreshold && distance < -minimumFlingTravel -> false
                        else -> offset > reveal * .42f
                    })
                    dragging = false
                }
            }
        }
        val modalVisible by remember(persistent) { derivedStateOf { !persistent && (currentOpen || dragging || position.value > .5f) } }
        val visible = persistent || modalVisible
        Layout(modifier = Modifier.fillMaxSize().then(gestures), content = {
        // Keep the drawer composed but unplaced when closed. Resizing must not
        // discard its scroll position, or recreate the chat and its text field.
        Box(Modifier.fillMaxSize().graphicsLayer {
            val fraction = if (persistent) 1f else (position.value / reveal).coerceIn(0f, 1f)
            alpha = .5f + .5f * fraction
            translationX = if (reduced) 0f else -18.dp.toPx() * (1f - fraction)
            val blur = ((1f - fraction) * 4f).toInt()
            renderEffect = if (!reduced && fraction in .03f.. .96f && blur > 0) blurSteps[blur - 1] else null
        }.then(if (visible) Modifier.semantics { paneTitle = menuTitle } else Modifier.clearAndSetSemantics { })
            .then(if (persistent) Modifier.testTag("tablet-sidebar") else Modifier), content = { menu() })
        Box(Modifier.fillMaxSize().graphicsLayer {
            val fraction = if (persistent) 0f else (position.value / reveal).coerceIn(0f, 1f)
            translationX = if (persistent) 0f else position.value
            scaleX = 1f - .025f * fraction; scaleY = scaleX
            shape = RoundedCornerShape((26f * fraction).dp); clip = true
            shadowElevation = 12.dp.toPx() * fraction
        }) {
            Box(if (modalVisible) Modifier.fillMaxSize().clearAndSetSemantics { } else Modifier.fillMaxSize()) { content() }
            if (modalVisible) Box(Modifier.fillMaxSize().graphicsLayer { alpha = (position.value / reveal).coerceIn(0f, 1f) * .18f }
                .background(Color.Black).semantics { contentDescription = dismissLabel }.clickable(role = Role.Button) { change(false) })
        }
        }) { measurables, constraints ->
            val sidebar = if (persistent) menuWidth.roundToPx() else 0
            val menuPixels = menuWidth.roundToPx().coerceIn(0, constraints.maxWidth)
            val drawer = measurables[0].measure(constraints.copy(minWidth = menuPixels, maxWidth = menuPixels))
            val contentWidth = (constraints.maxWidth - sidebar).coerceAtLeast(0)
            val main = measurables[1].measure(constraints.copy(minWidth = contentWidth, maxWidth = contentWidth))
            layout(constraints.maxWidth, constraints.maxHeight) {
                if (visible) drawer.placeRelative(0, 0)
                main.placeRelative(sidebar, 0)
            }
        }
    }
}
