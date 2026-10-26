package me.waveio.claudebot.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.max

private const val StreamRevealGraphemes = 2
/** Everything queued is visible this long after it starts to flow, however much arrives. */
internal const val StreamRevealDeadlineMillis = 280L
/** A burst larger than this shows its older part at once; only the rest flows. */
internal const val StreamRevealWindow = 480
private const val StreamRevealMaxTextLength = 4096
private const val StreamRevealFrameMillis = 16L
/** Above this length each reveal step re-parses a sizeable document, so steps are spaced out. */
private const val StreamRevealDenseLength = 1024
private const val StreamRevealSparseMillis = 48L

internal expect fun isStreamCombiningMark(codePoint: Int): Boolean

/** Bound the softened suffix independently of the short text reveal window. */
internal fun changedStreamTail(previous: String?, current: String, limit: Int = 16): IntRange? {
    require(limit > 0)
    if (current.isEmpty() || current == previous || previous?.startsWith(current) == true) return null
    val end = current.indexOfLast { !it.isWhitespace() } + 1
    var prefix = 0
    if (previous != null) while (prefix < minOf(previous.length, current.length) && previous[prefix] == current[prefix]) prefix++
    if (prefix >= end) return null
    return streamGraphemeStart(current, max(prefix, end - limit)) until end
}

/** Round a visual boundary back to the start of its joined glyph. */
private fun streamGraphemeStart(current: String, offset: Int): Int {
    var start = offset
    if (start == current.length) return start
    fun previousPoint(at: Int): Int = (at - 1).let { if (it > 0 && current[it].isLowSurrogate() && current[it - 1].isHighSurrogate()) it - 1 else it }
    if (start > 0 && current[start].isLowSurrogate() && current[start - 1].isHighSurrogate()) start--
    while (start > 0) {
        val code = streamPoint(current, start)
        val before = previousPoint(start)
        val previous = streamPoint(current, before)
        var precedingIndicators = 0
        if (code in 0x1F1E6..0x1F1FF) {
            var index = start
            while (index > 0 && streamPoint(current, previousPoint(index)) in 0x1F1E6..0x1F1FF) {
                precedingIndicators++; index = previousPoint(index)
            }
        }
        if (streamExtendsGlyph(code) || code == 0x200D || previous == 0x200D ||
            (previous == 0x0D && code == 0x0A) || streamHangulJoins(previous, code) ||
            precedingIndicators % 2 == 1) start = before else break
    }
    return start
}

private fun streamExtendsGlyph(point: Int): Boolean = isStreamCombiningMark(point) ||
    point in 0xFE00..0xFE0F || point in 0x1F3FB..0x1F3FF || point in 0xE0020..0xE007F || point in 0xE0100..0xE01EF

private fun streamHangulJoins(before: Int, after: Int): Boolean {
    fun type(point: Int): Int = when (point) {
        in 0x1100..0x115F, in 0xA960..0xA97C -> 1 // L
        in 0x1160..0x11A7, in 0xD7B0..0xD7C6 -> 2 // V
        in 0x11A8..0x11FF, in 0xD7CB..0xD7FB -> 3 // T
        in 0xAC00..0xD7A3 -> if ((point - 0xAC00) % 28 == 0) 4 else 5 // LV / LVT
        else -> 0
    }
    val left = type(before); val right = type(after)
    return (left == 1 && (right == 1 || right == 2 || right == 4 || right == 5)) ||
        ((left == 2 || left == 4) && (right == 2 || right == 3)) || ((left == 3 || left == 5) && right == 3)
}

internal class StreamingTail(val modifier: Modifier, val onTextLayout: (TextLayoutResult) -> Unit)

private fun streamPoint(text: String, index: Int): Int {
    val first = text[index]
    return if (first.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate())
        0x10000 + ((first.code - 0xD800) shl 10) + text[index + 1].code - 0xDC00 else first.code
}

private fun streamPointEnd(text: String, index: Int): Int =
    index + if (streamPoint(text, index) > 0xFFFF) 2 else 1

/** Small fragments preserve accents, emoji modifiers, flags, tags and joined glyphs. */
internal fun nextStreamRevealIndex(text: String, start: Int, graphemes: Int = StreamRevealGraphemes): Int {
    var index = start.coerceIn(0, text.length)
    index = streamGraphemeStart(text, index)
    repeat(graphemes.coerceAtLeast(1)) {
        if (index >= text.length) return index
        var previous = streamPoint(text, index)
        var indicators = if (previous in 0x1F1E6..0x1F1FF) 1 else 0
        index = streamPointEnd(text, index)
        while (index < text.length) {
            val point = streamPoint(text, index)
            val joins = streamExtendsGlyph(point) || point == 0x200D || previous == 0x200D ||
                (previous == 0x0D && point == 0x0A) || streamHangulJoins(previous, point) ||
                (point in 0x1F1E6..0x1F1FF && indicators % 2 == 1)
            if (!joins) break
            indicators = if (point in 0x1F1E6..0x1F1FF) indicators + 1 else 0
            previous = point
            index = streamPointEnd(text, index)
        }
    }
    return index
}

/**
 * Latest snapshots own the text, but an appended chunk flows in like typing
 * instead of appearing as a block. The pending text is spread evenly over the
 * frames left before a deadline that starts when it begins to flow; later
 * chunks speed the flow up rather than extend that deadline, so the visible
 * text never lags the provider by more than [StreamRevealDeadlineMillis].
 */
internal class StreamTextReveal(initial: String, enabled: Boolean) {
    var target = initial
        private set
    var rendered = initial
        private set
    var animateTail = false
        private set
    var revision = 0
        private set
    private var wasEnabled = enabled
    private var deadlineMillis: Long? = null
    private var lastFrameMillis = 0L
    val pending: Boolean get() = rendered != target

    fun update(text: String, enabled: Boolean) {
        if (text == target && enabled == wasEnabled) return
        val append = enabled && wasEnabled && text.length > target.length && text.startsWith(target)
        target = text
        wasEnabled = enabled
        animateTail = append
        if (!append) {
            // Compare with the last target, not the delayed prefix: a truncation
            // can still start with everything we have drawn so far.
            // Mode changes alone must preserve parsed Markdown and table scroll.
            if (rendered != text) revision++
            rendered = text
            deadlineMillis = null
        } else {
            if (text.length > StreamRevealMaxTextLength || hasTableRow(text)) {
                // Avoid extra full-document Markdown parses for tables and very
                // large replies; their real chunks still receive suffix blur.
                rendered = text
                deadlineMillis = null
                return
            }
            // Huge bursts catch up at once except for a bounded window.
            // Never move an already visible prefix backwards.
            val keepFrom = (text.length - StreamRevealWindow).coerceAtLeast(rendered.length)
            var boundary = rendered.length
            while (boundary < keepFrom) boundary = nextStreamRevealIndex(text, boundary, 1)
            rendered = text.substring(0, boundary)
        }
    }

    fun advance(frameMillis: Long): String {
        if (!pending) return rendered
        val deadline = deadlineMillis ?: (frameMillis + StreamRevealDeadlineMillis).also {
            deadlineMillis = it
            lastFrameMillis = frameMillis - StreamRevealFrameMillis
        }
        // Longer replies take fewer, larger steps; the tail blur hides the difference.
        if (target.length > StreamRevealDenseLength && frameMillis < deadline &&
            frameMillis - lastFrameMillis < StreamRevealSparseMillis) return rendered
        val elapsed = (frameMillis - lastFrameMillis).coerceAtLeast(1L)
        lastFrameMillis = frameMillis
        val remaining = deadline - frameMillis
        val end = if (remaining <= 0) target.length else {
            // This frame's even share of what is left before the deadline.
            val backlog = target.length - rendered.length
            val goal = rendered.length + (backlog * elapsed + remaining + elapsed - 1) / (remaining + elapsed)
            var index = nextStreamRevealIndex(target, rendered.length)
            while (index < goal) index = nextStreamRevealIndex(target, index, 1)
            index
        }
        rendered = target.substring(0, end.toInt())
        if (!pending) deadlineMillis = null
        return rendered
    }
}

/** Tables re-parse expensively and already arrive row by row. */
internal fun hasTableRow(text: String): Boolean = text.lineSequence().any { it.trimStart().startsWith("|") }

internal data class StreamingText(val text: String, val animateTail: Boolean, val revision: Int)

/** A fixed flow deadline survives new chunks; terminal/corrected text is synchronous. */
@Composable
internal fun rememberStreamingText(text: String, streaming: Boolean, flowFromStart: Boolean = false): StreamingText {
    val enabled = streaming && !LocalReducedMotion.current
    // A bubble that just opened lets its first chunk flow in as well.
    val reveal = remember { StreamTextReveal(if (flowFromStart && enabled) "" else text, enabled) }
    var frame by remember { mutableIntStateOf(0) }
    // Snapshot changes are authoritative in this composition, before effects run.
    remember(text, enabled) { reveal.update(text, enabled) }
    LaunchedEffect(text, enabled) {
        while (reveal.pending) {
            withFrameNanos { reveal.advance(it / 1_000_000) }
            frame++
        }
    }
    @Suppress("UNUSED_VARIABLE") val observedFrame = frame
    return StreamingText(reveal.rendered, reveal.animateTail && enabled, reveal.revision)
}

private const val StreamTailBands = 4

private class TailChunk(val start: Int, val end: Int) {
    /** Frame time of the first draw; negative until the frame clock reaches it. */
    var born = -1L
}

/**
 * Appended fragments, oldest first. Each keeps its own age, so a steady stream
 * draws a soft gradient: the newest glyphs are the most blurred and earlier
 * ones keep sharpening instead of snapping into focus when the next chunk lands.
 */
private class TailFade(private var previous: String) {
    val chunks = ArrayList<TailChunk>()

    fun update(text: String, enabled: Boolean) {
        val before = previous
        previous = text
        if (!enabled || !text.startsWith(before)) { chunks.clear(); return }
        val changed = changedStreamTail(before, text) ?: return
        chunks += TailChunk(changed.first, changed.last + 1)
    }

    fun tick(now: Long, durationMillis: Int) {
        chunks.forEach { if (it.born < 0) it.born = now }
        chunks.removeAll { now - it.born >= durationMillis }
    }
}

/** Blur only fresh glyphs, then go completely idle even if the provider is still running. */
@Composable
internal fun rememberStreamingTail(text: String, live: Boolean, maxBlur: Dp = 4.dp, durationMillis: Int = 320,
                                   fromEmpty: Boolean = false): StreamingTail {
    val enabled = live && !LocalReducedMotion.current
    // A paragraph that first appears mid-stream blurs in from its first glyph.
    val fade = remember { TailFade(if (fromEmpty && enabled) "" else text) }
    var clock by remember { mutableLongStateOf(0L) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    // Chunks are recorded in composition, so a new glyph is never drawn sharp
    // for a frame before its blur starts.
    remember(text, enabled) { fade.update(text, enabled) }
    LaunchedEffect(text, enabled, durationMillis) {
        while (fade.chunks.isNotEmpty()) withFrameMillis { now -> fade.tick(now, durationMillis); clock = now }
    }
    if (!enabled) return StreamingTail(Modifier) { layout = it }
    val layers = listOf(rememberGraphicsLayer(), rememberGraphicsLayer(), rememberGraphicsLayer(), rememberGraphicsLayer())
    val density = LocalDensity.current
    val radius = with(density) { maxBlur.toPx() }
    val rise = with(density) { 3.dp.toPx() }
    val effects = remember(radius) { (1..16).map { step -> BlurEffect(radius * step / 16f, radius * step / 16f, TileMode.Decal) } }
    val modifier = Modifier.drawWithContent {
        val now = clock
        val measured = layout
        val chunks = fade.chunks
        // Parsing/measurement may lag a snapshot. Never apply offsets to an old layout.
        // A text field taller than its viewport scrolls its glyphs away from
        // layout coordinates; leave that text sharp rather than blur the wrong line.
        if (chunks.isEmpty() || measured == null || measured.layoutInput.text.text != text || chunks.last().end > text.length ||
            measured.size.height > size.height + 1f) {
            drawContent(); return@drawWithContent
        }
        // Group chunks into contiguous runs of similar age: at most one
        // offscreen layer per band, however fast the provider streams.
        val runStarts = IntArray(StreamTailBands); val runAges = FloatArray(StreamTailBands); var runs = 0; var lastBand = -1
        for (chunk in chunks) {
            val age = if (chunk.born < 0) 0f else ((now - chunk.born).toFloat() / durationMillis).coerceIn(0f, 1f)
            val band = (age * StreamTailBands).toInt().coerceAtMost(StreamTailBands - 1)
            if (band == lastBand) continue
            val start = if (runs == 0) chunk.start else maxOf(chunk.start, runStarts[runs - 1])
            if (runs == StreamTailBands) break
            runStarts[runs] = start; runAges[runs] = age; runs++; lastBand = band
        }
        val end = chunks.maxOf { it.end }
        val padding = radius * 2f
        clipPath(measured.getPathForRange(runStarts[0], end), clipOp = ClipOp.Difference) { this@drawWithContent.drawContent() }
        for (index in 0 until runs) {
            val runEnd = if (index + 1 < runs) runStarts[index + 1] else end
            if (runEnd <= runStarts[index]) continue
            val path = measured.getPathForRange(runStarts[index], runEnd)
            val bounds = path.getBounds()
            val crop = IntSize(ceil(bounds.width + padding * 2).toInt().coerceAtLeast(1),
                ceil(bounds.height + padding * 2).toInt().coerceAtLeast(1))
            // Unusually long joined glyphs or extreme font sizes stay sharp instead
            // of allocating an unbounded blur texture (at most 2 MiB of RGBA pixels).
            if (bounds.isEmpty || crop.width.toLong() * crop.height > 512L * 1024) {
                clipPath(path) { this@drawWithContent.drawContent() }
                continue
            }
            val focus = 1f - (1f - runAges[index]).let { it * it }
            val step = ceil((1f - focus) * effects.size).toInt().coerceIn(0, effects.size)
            val layer = layers[index]
            // Cache only the small suffix rectangle, not a full growing paragraph.
            layer.renderEffect = if (step > 0 && effects[step - 1].isSupported()) effects[step - 1] else null
            layer.alpha = .15f + focus * .85f
            layer.record(size = crop) {
                translate(padding - bounds.left, padding - bounds.top) { this@drawWithContent.drawContent() }
            }
            clipPath(path) {
                translate(bounds.left - padding, bounds.top - padding + (1f - focus) * rise) { drawLayer(layer) }
            }
        }
    }
    return StreamingTail(modifier) { layout = it }
}
