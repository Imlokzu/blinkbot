package me.waveio.claudebot.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import kotlinx.datetime.*

@Composable
fun SchedulePopup(actions: AppActions) {
    val zone = remember { TimeZone.currentSystemDefault() }
    val initial = remember { Instant.fromEpochMilliseconds(kotlin.time.Clock.System.now().toEpochMilliseconds() + 300_000).toLocalDateTime(zone) }
    val today = remember { Instant.fromEpochMilliseconds(kotlin.time.Clock.System.now().toEpochMilliseconds()).toLocalDateTime(zone).date }
    var selected by remember { mutableStateOf(initial.date) }
    var month by remember { mutableStateOf(LocalDate(selected.year, selected.monthNumber, 1)) }
    var hour by remember { mutableIntStateOf(initial.hour) }
    var minute by remember { mutableIntStateOf(initial.minute) }
    val p = LocalPalette.current
    BotDialog({ actions.schedule(false) }) {
        Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tr("queue.schedule"), color = p.ink, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconAction("back", tr("calendar.previous"), { month = month.minus(DatePeriod(months = 1)) }, enabled = month > LocalDate(today.year, today.monthNumber, 1))
                Text(tr("calendar.month.${month.monthNumber}") + " " + month.year, color = p.ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconAction("back", tr("calendar.next"), { month = month.plus(DatePeriod(months = 1)) }, Modifier.rotate(180f))
            }
            Row(Modifier.fillMaxWidth()) { tr("calendar.weekdaysCompact").split('|').forEachIndexed { index, day ->
                val fullDay = tr("calendar.weekdays").split('|')[index]
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(day, fontSize = 11.sp, lineHeight = 16.sp, maxLines = 1, color = p.muted, modifier = Modifier.clearAndSetSemantics { contentDescription = fullDay })
                }
            } }
            val start = month.dayOfWeek.ordinal
            val count = month.plus(DatePeriod(months = 1)).minus(DatePeriod(days = 1)).dayOfMonth
            repeat((start + count + 6) / 7) { week ->
                Row(Modifier.fillMaxWidth()) {
                    repeat(7) { day ->
                        val number = week * 7 + day - start + 1
                        if (number !in 1..count) Spacer(Modifier.weight(1f).height(43.dp))
                        else {
                            val date = LocalDate(month.year, month.monthNumber, number)
                            val active = date == selected
                            Box(Modifier.weight(1f).heightIn(min = 43.dp).padding(2.dp).clip(RoundedCornerShape(12.dp))
                                .background(if (active) p.ink else p.surface).semantics { this.selected = active; contentDescription = date.toString() }
                                .clickable(enabled = date >= today, role = Role.RadioButton) { selected = date }, contentAlignment = Alignment.Center) {
                                Text(number.toString(), color = if (active) p.background else p.ink.copy(alpha = if (date >= today) 1f else .28f), fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
            Hairline(Modifier.padding(vertical = 4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TimeValue(tr("queue.hours"), hour, { hour = (hour + 1) % 24 }, { hour = (hour + 23) % 24 }, Modifier.weight(1f))
                TimeValue(tr("queue.minutes"), minute, { minute = (minute + 1) % 60 }, { minute = (minute + 59) % 60 }, Modifier.weight(1f))
            }
            Text(zone.id, color = p.muted, fontSize = 11.sp)
            ActionButton(tr("queue.schedule"), { actions.send("queue", LocalDateTime(selected, LocalTime(hour, minute)).toInstant(zone).toString()) }, Modifier.fillMaxWidth(), primary = true, icon = "calendar")
            QuietAction(tr("input.cancel"), { actions.schedule(false) }, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun TimeValue(label: String, value: Int, up: () -> Unit, down: () -> Unit, modifier: Modifier) {
    val p = LocalPalette.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = p.muted, fontSize = 11.sp)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stacked = maxWidth < 80.dp + with(LocalDensity.current) { 44.sp.toDp() }
            if (stacked) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(p.secondary), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(value.toString().padStart(2, '0'), color = p.ink, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, maxLines = 1, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    IconAction("down", tr("calendar.decrease", "field" to label), down)
                    IconAction("down", tr("calendar.increase", "field" to label), up, Modifier.rotate(180f))
                }
            } else {
                Row(Modifier.clip(RoundedCornerShape(15.dp)).background(p.secondary), verticalAlignment = Alignment.CenterVertically) {
                    IconAction("down", tr("calendar.decrease", "field" to label), down, Modifier.size(40.dp))
                    Text(value.toString().padStart(2, '0'), color = p.ink, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, maxLines = 1, modifier = Modifier.weight(1f))
                    IconAction("down", tr("calendar.increase", "field" to label), up, Modifier.size(40.dp).rotate(180f))
                }
            }
        }
    }
}
