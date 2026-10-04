package me.waveio.claudebot.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.waveio.claudebot.state.IntelligenceRow

/** The web picker’s four-zone intelligence speedometer, reduced for touch rows. */
@Composable
fun IntelGauge(value: Float, coverage: Int, total: Int, modifier: Modifier = Modifier, large: Boolean = false) {
    val p = LocalPalette.current
    val zones = listOf(
        Color(0xFFD06A61), Color(0xFFD59A55), Color(0xFF8FAF96), Color(0xFF5F9B77),
    )
    val width = if (large) 104.dp else 45.dp
    val height = if (large) 58.dp else 28.dp
    val stroke = if (large) 7.dp else 4.dp
    val score = value.coerceIn(0f, 100f)
    val description = tr("model.intelligence", "score" to score.toInt(), "coverage" to coverage, "total" to total)
    Row(modifier.semantics { contentDescription = description }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.width(width).height(height)) {
            val inset = stroke.toPx() / 2f
            val diameter = size.width - inset * 2f
            val radius = diameter / 2f
            val top = size.height - inset
            val left = (size.width - diameter) / 2f
            zones.forEachIndexed { index, color ->
                val start = 180f + index * 45f + 1.2f
                val sweep = 42.6f
                drawArc(color.copy(alpha = .16f), start, sweep, false, Offset(left, top - diameter), Size(diameter, diameter), style = Stroke(stroke.toPx(), cap = StrokeCap.Round))
                val zoneStart = index * 25f
                val reached = ((score - zoneStart) / 25f).coerceIn(0f, 1f)
                if (reached > 0f) drawArc(color, start, sweep * reached, false, Offset(left, top - diameter), Size(diameter, diameter), style = Stroke(stroke.toPx(), cap = StrokeCap.Round))
            }
        }
        if (large) Text(score.toInt().toString(), color = p.ink, fontSize = 18.sp, lineHeight = 20.sp)
    }
}

@Composable
fun IntelGauge(row: IntelligenceRow, modifier: Modifier = Modifier, large: Boolean = false) =
    IntelGauge(row.index, row.coverage, row.total, modifier, large)
