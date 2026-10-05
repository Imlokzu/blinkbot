package me.waveio.claudebot.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.max

private const val StreamRevealCodePoints = 4
private const val StreamRevealDelayMillis = 12L

internal expect fun isStreamCombiningMark(codePoint: Int): Boolean

/** A bounded visual suffix, never an alternate or delayed copy of the reply. */
internal fun changedStreamTail(previous: String?, current: String, limit: Int = 16): IntRange? {
    require(limit > 0)
    if (current.isEmpty() || current == previous || previous?.startsWith(current) == true) return null
    val end = current.indexOfLast { !it.isWhitespace() } + 1
    var prefix = 0
    if (previous != null) while (prefix < minOf(previous.length, current.length) && previous[prefix] == current[prefix]) prefix++
    if (prefix >= end) return null
    var start = max(prefix, end - limit)
    fun previousPoint(at: Int): Int = (at - 1).let { if (it > 0 && current[it].isLowSurrogate() && current[it - 1].isHighSurrogate()) it - 1 else it }
    fun point(at: Int): Int {
        val first = current[at]
        return if (first.isHighSurrogate() && at + 1 < current.length && current[at + 1].isLowSurrogate())
            0x10000 + ((first.code - 0xD800) shl 10) + current[at + 1].code - 0xDC00 else first.code
    }
    if (start > 0 && current[start].isLowSurrogate() && current[start - 1].isHighSurrogate()) start--
    // Keep a visible glyph together when a chunk splits an accent, emoji or ZWJ sequence.
    while (start > 0) {
        val code = point(start)
        val before = previousPoint(start)
        var precedingIndicators = 0
        if (code in 0x1F1E6..0x1F1FF) {
            var offset = start
            while (offset > 0 && point(previousPoint(offset)) in 0x1F1E6..0x1F1FF) {
                precedingIndicators++; offset = previousPoint(offset)
            }
        }
        if (isStreamCombiningMark(code) ||
            code == 0x200D || point(before) == 0x200D || code in 0x1F3FB..0x1F3FF || code in 0xE0100..0xE01EF ||
            precedingIndicators % 2 == 1) start = before else break
    }
    return start until end
}

internal class StreamingTail(val modifier: Modifier, val onTextLayout: (TextLayoutResult) -> Unit)

/** Advance by a few Unicode code points, deliberately ignoring word boundaries. */
internal fun nextStreamRevealIndex(text: String, start: Int, codePoints: Int = StreamRevealCodePoints): Int {
    var index = start.coerceIn(0, text.length)
    repeat(codePoints.coerceAtLeast(1)) {
        if (index >= text.length) return index
        index += if (text[index].isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) 2 else 1
    }
    return index
}

/** Reveal provider chunks in short code-point frames while Markdown parses them. */
@Composable
internal fun rememberStreamingText(text: String, streaming: Boolean): String {
    val reduced = LocalReducedMotion.current
    var rendered by remember { mutableStateOf("") }
    LaunchedEffect(text, streaming, reduced) {
        if (!streaming || reduced) {
            rendered = text
            return@LaunchedEffect
        }
        val prefix = rendered.takeIf { text.startsWith(it) } ?: ""
        if (prefix.length == text.length) return@LaunchedEffect
        rendered = prefix
        var index = prefix.length
        while (index < text.length) {
            index = nextStreamRevealIndex(text, index)
            rendered = text.substring(0, index)
            delay(StreamRevealDelayMillis)
        }
    }
    return rendered
}

/** Blur only fresh glyphs, then go completely idle even if the provider is still running. */
@Composable
internal fun rememberStreamingTail(text: String, live: Boolean): StreamingTail {
    val enabled = live && !LocalReducedMotion.current
    var previous by remember { mutableStateOf<String?>(null) }
    var range by remember { mutableStateOf<IntRange?>(null) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(text, enabled) {
        val changed = if (enabled) changedStreamTail(previous, text) else null
        previous = text
        range = changed
        if (changed == null) progress.snapTo(1f)
        else {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
            range = null
        }
    }
    if (!enabled) return StreamingTail(Modifier) { layout = it }
    val layer = rememberGraphicsLayer()
    val radius = with(LocalDensity.current) { 2.4.dp.toPx() }
    val effects = remember(radius) { (1..12).map { step -> BlurEffect(radius * step / 12f, radius * step / 12f, TileMode.Decal) } }
    val modifier = Modifier.drawWithCache {
        val measured = layout
        val currentRange = range
        // Parsing/measurement may lag a snapshot. Never apply offsets to an old layout.
        val valid = measured != null && measured.layoutInput.text.text == text && currentRange != null && currentRange.last < text.length
        val path = if (valid) measured!!.getPathForRange(currentRange!!.first, currentRange.last + 1) else null
        val bounds = path?.getBounds() ?: Rect.Zero
        val padding = radius * 2f
        val crop = IntSize(ceil(bounds.width + padding * 2).toInt().coerceAtLeast(1),
            ceil(bounds.height + padding * 2).toInt().coerceAtLeast(1))
        // Unusually long joined glyphs or extreme font sizes stay sharp instead
        // of allocating an unbounded blur texture (at most 2 MiB of RGBA pixels).
        val bounded = crop.width.toLong() * crop.height <= 512L * 1024
        onDrawWithContent {
            val value = progress.value
            val step = ceil((1f - value) * effects.size).toInt().coerceIn(0, effects.size)
            if (path == null || bounds.isEmpty || !bounded || step == 0 || !effects[step - 1].isSupported()) {
                drawContent()
            } else {
                // Cache only the small suffix rectangle, not a full growing paragraph.
                layer.renderEffect = effects[step - 1]
                layer.alpha = .65f + value * .35f
                layer.record(size = crop) {
                    translate(padding - bounds.left, padding - bounds.top) { this@onDrawWithContent.drawContent() }
                }
                clipPath(path, clipOp = ClipOp.Difference) { this@onDrawWithContent.drawContent() }
                clipPath(path) {
                    translate(bounds.left - padding, bounds.top - padding) { drawLayer(layer) }
                }
            }
        }
    }
    return StreamingTail(modifier) { layout = it }
}
