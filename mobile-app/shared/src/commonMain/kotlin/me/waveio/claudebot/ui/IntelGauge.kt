package me.waveio.claudebot.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.min
import me.waveio.claudebot.data.IntelligenceBenchmark
import me.waveio.claudebot.data.IntelligenceSource
import me.waveio.claudebot.state.AppState
import me.waveio.claudebot.state.IntelligenceRow

/*
 * The intelligence index as a speedometer, mirroring the dashboard's
 * IntelGauge: a half-circle split into four coloured zones, dim where the
 * model does not reach and full where it does. Zones are separate arcs with
 * a hairline gap, not a gradient. The needle and readout appear in the large
 * dial; the row size shows the number next to the arcs instead, the same
 * pairing the web picker uses at mini size. Tapping either opens the detail
 * card — the mobile equivalent of the web hover card.
 */

private val ZoneColors = listOf(
    Color(0xFFD06A61), Color(0xFFD59A55), Color(0xFF8FAF96), Color(0xFF5F9B77),
)

/** 0 basic, 1 capable, 2 strong, 3 frontier — mirrors the web zone cut points (30/50/65). */
private fun tierOf(index: Float): Int = when {
    index < 30f -> 0
    index < 50f -> 1
    index < 65f -> 2
    else -> 3
}

private val AREA_KEYS = mapOf(
    "gpqa" to "model.intelAreaGpqa",
    "aime" to "model.intelAreaAime",
    "scicode" to "model.intelAreaScicode",
    "arc_agi_2" to "model.intelAreaArc",
    "simpleqa" to "model.intelAreaSimpleqa",
    "critpt" to "model.intelAreaCritpt",
)

@Composable
private fun IntelDial(score: Float, width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp, stroke: androidx.compose.ui.unit.Dp, needle: Boolean) {
    val p = LocalPalette.current
    Canvas(Modifier.width(width).height(height)) {
        val inset = stroke.toPx() / 2f
        val bottomInset = if (needle) maxOf(inset, 4.5.dp.toPx()) else inset
        val radius = min(size.width / 2f - inset, size.height - inset - bottomInset).coerceAtLeast(0f)
        val diameter = radius * 2f
        val center = Offset(size.width / 2f, inset + radius)
        // The arc and needle share a center; its upper half fits inside the canvas.
        val arcOrigin = Offset(center.x - radius, center.y - radius)
        ZoneColors.forEachIndexed { index, color ->
            val start = 180f + index * 45f + 1.2f
            val sweep = 42.6f
            drawArc(color.copy(alpha = .16f), start, sweep, false, arcOrigin, Size(diameter, diameter), style = Stroke(stroke.toPx(), cap = StrokeCap.Round))
            val zoneStart = index * 25f
            val reached = ((score - zoneStart) / 25f).coerceIn(0f, 1f)
            if (reached > 0f) drawArc(color, start, sweep * reached, false, arcOrigin, Size(diameter, diameter), style = Stroke(stroke.toPx(), cap = StrokeCap.Round))
        }
        if (needle) {
            val angle = PI * (1 - score.coerceIn(0f, 100f) / 100f)
            val tip = Offset(
                center.x + (diameter / 2f - stroke.toPx() - 3.dp.toPx()) * cos(angle).toFloat(),
                center.y - (diameter / 2f - stroke.toPx() - 3.dp.toPx()) * sin(angle).toFloat(),
            )
            // A short counterweight behind the pivot and a hollow hub read as a finished gauge
            // rather than a line on a dot.
            val tail = Offset(center.x - 5.dp.toPx() * cos(angle).toFloat(), center.y + 5.dp.toPx() * sin(angle).toFloat())
            drawLine(p.ink, tail, tip, strokeWidth = 2.2.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(p.ink, radius = 4.5.dp.toPx(), center = center)
            drawCircle(p.surface, radius = 1.8.dp.toPx(), center = center)
        }
    }
}

/** Compact speedometer and score for model rows. */
@Composable
private fun IntelGauge(value: Float, coverage: Int, total: Int, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val score = value.coerceIn(0f, 100f)
    val description = tr("model.intelligence", "score" to score.toInt(), "coverage" to coverage, "total" to total)
    Row(modifier.semantics { contentDescription = description }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        IntelDial(score, 28.dp, 18.dp, 3.dp, needle = false)
        Text(score.toInt().toString(), color = p.muted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * Tappable row gauge: shows the arcs + number and opens the detail card,
 * which mirrors the web IntelCard (dial, tier, coverage, per-area bars,
 * source). Hidden when the app has no entry detail for the row.
 */
@Composable
fun IntelGaugeButton(state: AppState, modelId: String, modifier: Modifier = Modifier) {
    val row = state.intelligence[modelId] ?: return
    var open by remember { mutableStateOf(false) }
    Box(modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clip(RoundedCornerShape(8.dp)).clickable(role = androidx.compose.ui.semantics.Role.Button) { open = true }.padding(2.dp), contentAlignment = Alignment.Center) {
        IntelGauge(row.index, row.coverage, row.total)
    }
    if (open) IntelCard(state, modelId, row) { open = false }
}

/** The detail card behind a tap: the full readout the web picker shows on hover. */
@Composable
private fun IntelCard(state: AppState, modelId: String, row: IntelligenceRow, onClose: () -> Unit) {
    val p = LocalPalette.current
    val uriHandler = LocalUriHandler.current
    val label = state.models.firstOrNull { it.id == modelId }?.label ?: modelId
    val tier = tierOf(row.index)
    val entry = row.entry
    BotDialog(onClose) {
        // One scroll for the whole card: a capped inner list used to end mid-row (Facts)
        // right above the source note, which looked like the rest was missing.
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Text(label, color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        // The dial is a summary; it sits beside its readout so the per-area list gets the room.
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IntelDial(row.index, 96.dp, 56.dp, 8.dp, needle = true)
            Column {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(row.index.toInt().toString(), color = p.ink, fontSize = 24.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
                    Text("/100", color = p.muted, fontSize = 12.sp, lineHeight = 18.sp)
                }
                Text(tr("model.intelTier$tier"), color = ZoneColors[tier], fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp))
                Text(tr("model.intelCoverage", "count" to row.coverage, "total" to row.total), color = p.muted, fontSize = 12.sp)
            }
        }
        if (row.coverage < 4) Text(tr("model.intelPartial"), color = p.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        if (state.intelligenceBenchmarks.isNotEmpty()) {
            Text(tr("model.intelByArea"), color = p.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
            state.intelligenceBenchmarks.forEach { bench -> IntelAreaRow(bench, entry.scores[bench.key]) }
            Text(tr("model.intelBarHint"), color = p.muted, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
        }
        IntelSourceLine(state.intelligenceSource, state.intelligenceBenchmarks.size) { url -> uriHandler.openUri(url) }
        }
    }
}

@Composable
private fun IntelAreaRow(bench: IntelligenceBenchmark, score: Double?) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(tr(AREA_KEYS[bench.key] ?: ""), color = if (score != null) p.ink else p.muted, fontSize = 12.sp, maxLines = 1)
            Text(bench.name, color = p.muted, fontSize = 10.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        // The bar is the same number as the label (score out of 100%), so 55% fills about half.
        // It used to fill score ÷ best score, which made 55% look like 70–80%. The best
        // model's score is still shown, as a tick, rather than folded into the fill.
        val fill = benchmarkFill(score)
        val best = benchmarkFill(bench.top.takeIf { it > 0 })
        Canvas(Modifier.width(72.dp).height(12.dp)) {
            val y = size.height / 2f
            val thickness = 5.dp.toPx()
            drawLine(p.line, Offset(thickness / 2, y), Offset(size.width - thickness / 2, y), thickness, StrokeCap.Round)
            if (fill > 0f) drawLine(p.ink.copy(alpha = .62f), Offset(thickness / 2, y), Offset(thickness / 2 + (size.width - thickness) * fill, y), thickness, StrokeCap.Round)
            if (best > 0f) {
                val x = thickness / 2 + (size.width - thickness) * best
                drawLine(p.ink, Offset(x, 0f), Offset(x, size.height), 1.5.dp.toPx(), StrokeCap.Round)
            }
        }
        Text(
            if (score != null) "${(score * 100).toInt()}%" else tr("model.intelNotTaken"),
            color = if (score != null) p.ink else p.muted,
            fontSize = 11.sp,
            modifier = Modifier.width(44.dp).padding(start = 8.dp),
        )
    }
}

/** Benchmark scores arrive as 0..1 fractions; the bar and the printed percent both use this. */
internal fun benchmarkFill(score: Double?): Float = (score ?: 0.0).coerceIn(0.0, 1.0).toFloat()

@Composable
private fun IntelSourceLine(source: IntelligenceSource, benchmarkCount: Int, openUrl: (String) -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Hairline()
        Text(tr("model.intelWhy", "count" to benchmarkCount), color = p.muted, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
        if (source.url.isNotBlank()) {
            Text(
                tr("model.intelMore") + " " + source.name + if (source.license.isNotBlank()) " (" + source.license + ")" else "",
                color = p.accent, fontSize = 10.sp,
                modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(4.dp)).clickable { openUrl(source.url) },
            )
        }
    }
}
