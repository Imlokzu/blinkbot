package me.waveio.claudebot.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp

/** Bars kept on screen; at [WaveSampleMillis] this is about two seconds of speech. */
internal const val WaveBars = 26
internal const val WaveSampleMillis = 75L

/**
 * Rolling history of the microphone level. New samples enter on the right and the
 * older ones travel left, so the bars trace the owner's actual speech over the last
 * couple of seconds instead of one level copied onto every bar. Silence leaves a
 * low baseline; once recording stops the history sinks back to it.
 */
internal class WaveHistory(size: Int = WaveBars) {
    val samples = FloatArray(size)
    var generation by mutableLongStateOf(0L)
        private set

    fun push(level: Float) {
        samples.copyInto(samples, 0, 1)
        samples[samples.lastIndex] = level.coerceIn(0f, 1f)
        generation++
    }

    /** Recording ended: let every bar fall a little each tick. */
    fun decay(factor: Float = .72f): Boolean {
        var moving = false
        for (index in samples.indices) {
            val next = samples[index] * factor
            samples[index] = if (next < .01f) 0f else next
            if (samples[index] > 0f) moving = true
        }
        generation++
        return moving
    }
}

@Composable
internal fun VoiceWave(amplitude: State<Float>, recording: Boolean, modifier: Modifier = Modifier) {
    val color = LocalPalette.current.accent
    val reduced = LocalReducedMotion.current
    val history = remember { WaveHistory() }
    // Progress from the last sample to the next one, so bars glide rather than step.
    val glide = remember { Animatable(0f) }
    LaunchedEffect(recording, reduced) {
        if (recording) {
            while (true) {
                history.push(amplitude.value)
                if (reduced) { kotlinx.coroutines.delay(WaveSampleMillis); continue }
                glide.snapTo(0f)
                glide.animateTo(1f, tween(WaveSampleMillis.toInt(), easing = LinearEasing))
            }
        } else {
            glide.snapTo(0f)
            while (history.decay()) kotlinx.coroutines.delay(40)
        }
    }
    Canvas(modifier.width(72.dp).height(26.dp)) {
        history.generation // redraw on every new sample
        val count = history.samples.size
        val step = size.width / count
        val shift = if (reduced) 0f else glide.value * step
        val stroke = 2.4.dp.toPx()
        val floor = 3.dp.toPx()
        for (index in 0 until count) {
            val level = history.samples[index]
            // Fade the oldest bars at the left edge so the history does not end in a hard cut.
            val age = index.toFloat() / (count - 1)
            val alpha = (.25f + .75f * age).coerceIn(0f, 1f)
            val height = floor + level * (size.height - floor)
            val x = index * step + step / 2 - shift
            if (x < 0f) continue
            drawLine(color.copy(alpha = alpha), Offset(x, (size.height - height) / 2), Offset(x, (size.height + height) / 2), stroke, StrokeCap.Round)
        }
    }
}

/** A record dot that breathes while listening and swells with the voice. */
@Composable
internal fun RecordingDot(amplitude: State<Float>, recording: Boolean, modifier: Modifier = Modifier) {
    val color = LocalPalette.current.accent
    val reduced = LocalReducedMotion.current
    val breath = if (recording && !reduced) rememberInfiniteTransition(label = "recordBreath")
        .animateFloat(.55f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath")
        else remember { mutableFloatStateOf(1f) }
    Canvas(modifier.size(10.dp)) {
        val radius = size.minDimension / 2f
        if (!recording) { drawCircle(color.copy(alpha = .35f), radius * .6f); return@Canvas }
        drawCircle(color.copy(alpha = .18f * breath.value), radius * (.75f + .25f * amplitude.value))
        drawCircle(color.copy(alpha = .7f + .3f * breath.value), radius * (.45f + .2f * amplitude.value))
    }
}
