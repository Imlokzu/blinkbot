package me.waveio.claudebot.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.waveio.claudebot.resources.Res
import me.waveio.claudebot.resources.ic_arrow_up
import org.jetbrains.compose.resources.painterResource

/**
 * The send button's flight into the bubble it becomes. The composer launches
 * it from the button's bounds; the first new human message claims it and
 * reports its bubble's live bounds; the chat draws the button in between.
 * All bounds are in root coordinates.
 */
@Stable
internal class SendFlight {
    var token by mutableIntStateOf(0)
        private set
    var start by mutableStateOf<Rect?>(null)
        private set
    /** Presentation key of the message that claimed this flight. */
    var targetKey by mutableStateOf<String?>(null)
    var target by mutableStateOf<Rect?>(null)
    /** True from launch until the bubble has taken over. */
    var active by mutableStateOf(false)
        internal set
    /** The key whose bubble should now reveal its content. */
    var landedKey by mutableStateOf<String?>(null)
        internal set

    fun launch(from: Rect) {
        start = from
        targetKey = null
        target = null
        landedKey = null
        active = true
        token++
    }

    /** Whether the message keyed [key] is, or may become, this flight's destination. */
    fun claims(key: String): Boolean = active && (targetKey == null || targetKey == key)

    internal fun finish() {
        active = false
        start = null
    }
}

internal val LocalSendFlight = staticCompositionLocalOf<SendFlight?> { null }

// A send overshoots slightly as it lifts, then decelerates into place.
private val FlightEasing = CubicBezierEasing(.3f, 0f, .12f, 1f)
private const val LiftMillis = 110
private const val TravelMillis = 400

/** Draws the flying button above the chat; touches pass straight through. */
@Composable
internal fun SendFlightOverlay(flight: SendFlight, origin: Offset, buttonColor: Color, bubbleColor: Color, arrowTint: Color) {
    val lift = remember { Animatable(0f) }
    val travel = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    var velocity by remember { mutableStateOf(Offset.Zero) }
    val blur = rememberMotionBlur(9.dp)
    val arrow = painterResource(Res.drawable.ic_arrow_up)
    LaunchedEffect(flight.token) {
        if (!flight.active) return@LaunchedEffect
        lift.snapTo(0f); travel.snapTo(0f); fade.snapTo(1f)
        // Anticipation: the button shrinks and hops before it flies.
        launch { lift.animateTo(1f, tween(LiftMillis, easing = FastOutSlowInEasing)) }
        val claimed = withTimeoutOrNull(600) { snapshotFlow { flight.target }.first { it != null } }
        // Take off from the top of the hop rather than pausing on it.
        snapshotFlow { lift.value }.first { it >= .6f }
        if (claimed == null) {
            // No bubble appeared (a rejected or attachment-only send): dissolve in place.
            fade.animateTo(0f, tween(140))
            flight.finish(); return@LaunchedEffect
        }
        travel.animateTo(1f, tween(TravelMillis, easing = FlightEasing))
        flight.landedKey = flight.targetKey
        // The bubble is now drawn underneath in the same place and colour.
        fade.animateTo(0f, tween(90, easing = LinearEasing))
        flight.finish()
        velocity = Offset.Zero
    }
    if (!flight.active) return
    val start = flight.start ?: return
    val last = remember { arrayOfNulls<Rect>(1) }
    Box(Modifier.fillMaxSize().graphicsLayer {
        alpha = fade.value
        renderEffect = blur.effect(speedX = velocity.x, speedY = velocity.y)
    }.drawBehind {
        val hopScale = 1f - .18f * lift.value
        val hopRise = 8.dp.toPx() * lift.value
        val t = travel.value
        val goal = flight.target ?: start
        val launch = Rect(Offset(start.center.x, start.center.y - hopRise) - Offset(start.width * hopScale / 2f, start.height * hopScale / 2f),
            Size(start.width * hopScale, start.height * hopScale))
        // Up first, then across: the control point sits above the start.
        val control = Offset(launch.center.x, goal.center.y + (launch.center.y - goal.center.y) * .15f)
        val u = 1f - t
        val center = Offset(u * u * launch.center.x + 2 * u * t * control.x + t * t * goal.center.x,
            u * u * launch.center.y + 2 * u * t * control.y + t * t * goal.center.y)
        // The body widens late, so it reads as a button flying, then a bubble opening.
        val grow = t * t * (3f - 2f * t)
        val width = launch.width + (goal.width - launch.width) * grow
        val height = launch.height + (goal.height - launch.height) * grow
        val rect = Rect(center - Offset(width / 2f, height / 2f) - origin, Size(width, height))
        // Per-frame displacement drives the motion blur on the next frame.
        last[0]?.let { previous -> velocity = (rect.center - previous.center) * 60f }
        last[0] = rect
        val round = height / 2f
        val big = 21.dp.toPx(); val tail = 6.dp.toPx()
        fun r(target: Float) = CornerRadius(round + (target - round) * grow)
        drawPath(Path().apply { addRoundRect(RoundRect(rect, r(big), r(big), r(tail), r(big))) }, lerp(buttonColor, bubbleColor, grow))
        val glyph = 20.dp.toPx() * hopScale
        val arrowAlpha = (1f - t * 2.5f).coerceIn(0f, 1f)
        if (arrowAlpha > 0f) translate(rect.center.x - glyph / 2f, rect.center.y - glyph / 2f - t * 12.dp.toPx()) {
            with(arrow) { draw(Size(glyph, glyph), alpha = arrowAlpha, colorFilter = ColorFilter.tint(arrowTint)) }
        }
    })
}
