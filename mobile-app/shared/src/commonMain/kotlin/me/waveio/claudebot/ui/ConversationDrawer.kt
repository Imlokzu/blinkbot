package me.waveio.claudebot.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.*
import me.waveio.claudebot.state.*

fun conversationDay(updatedAt: Long?, now: Instant, zone: TimeZone): String {
    if (updatedAt == null || updatedAt <= 0) return "date.unknown"
    val today = now.toLocalDateTime(zone).date
    val day = runCatching { Instant.fromEpochSeconds(updatedAt).toLocalDateTime(zone).date }.getOrNull() ?: return "date.unknown"
    val age = day.daysUntil(today)
    return when { age <= 0 -> "date.today"; age == 1 -> "date.yesterday"; age < 7 -> "date.thisWeek"; else -> "date.older" }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationDrawer(state: AppState, actions: AppActions) {
    val p = LocalPalette.current
    val zone = remember { TimeZone.currentSystemDefault() }
    val now = remember(state.conversations) { Instant.fromEpochMilliseconds(kotlin.time.Clock.System.now().toEpochMilliseconds()) }
    var selected by remember { mutableStateOf<ConversationRow?>(null) }
    var editing by remember { mutableStateOf<ConversationRow?>(null) }
    var deleting by remember { mutableStateOf<ConversationRow?>(null) }
    var title by remember { mutableStateOf("") }
    val persistent = LocalAdaptiveLayout.current.persistentSidebar
    Column(Modifier.fillMaxSize().drawBehind {
        if (persistent) drawLine(p.line, Offset(size.width, 0f), Offset(size.width, size.height), 1.dp.toPx())
    }.windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 14.dp)) {
        LazyColumn(Modifier.weight(1f)) {
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    BotMark(Modifier.size(34.dp)); Spacer(Modifier.width(7.dp)); Text(tr("app.name"), fontSize = 17.sp, fontWeight = FontWeight.Medium)
                }
                DrawerLink("new", tr("nav.new"), actions::newChat)
                DrawerLink("search", tr("nav.search")) { actions.navigate(Screen.Search) }
                DrawerLink("bot", tr("nav.agents")) { actions.navigate(Screen.Agents) }
                DrawerLink("folder", tr("nav.files")) { actions.navigate(Screen.Files) }
            }
            val groups = state.conversations.sortedByDescending { it.updatedAt ?: 0 }.groupBy { conversationDay(it.updatedAt, now, zone) }
            for ((day, chats) in groups) {
                item(key = day) { Text(tr(day), fontSize = 11.sp, color = p.muted, modifier = Modifier.padding(start = 10.dp, top = 22.dp, bottom = 6.dp)) }
                items(chats, key = { it.id }) { chat ->
                    Text(chat.title, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (chat.id == state.sessionId) p.secondary else Color.Transparent)
                        .combinedClickable(onClick = { actions.openChat(chat.id) }, onLongClick = { selected = chat })
                        .padding(10.dp, 13.dp), color = p.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                }
            }
            if (state.conversations.isEmpty()) item { Text(tr("chat.historyEmpty"), color = p.muted, fontSize = 12.sp, modifier = Modifier.padding(10.dp)) }
        }
        Hairline()
        DrawerLink("settings", tr("nav.profile")) { actions.navigate(Screen.Profile) }
    }
    MotionPopup(selected != null, { selected = null }, Modifier.width(240.dp), alignment = Alignment.Center) {
        MenuRow("edit", tr("chat.rename"), { editing = selected; title = selected?.title.orEmpty(); selected = null })
        MenuRow("delete", tr("chat.delete"), { deleting = selected; selected = null })
    }
    editing?.let { chat -> BotDialog({ editing = null }) {
        Text(tr("chat.renameTitle"), fontWeight = FontWeight.SemiBold, color = p.ink)
        Spacer(Modifier.height(14.dp))
        BotField(title, { title = it.take(200) }, placeholder = tr("chat.renamePlaceholder"))
        Spacer(Modifier.height(14.dp))
        ActionButton(tr("chat.renameSave"), { actions.renameChat(chat.id, title); editing = null }, Modifier.fillMaxWidth(), primary = true, enabled = title.isNotBlank())
        QuietAction(tr("input.cancel"), { editing = null }, Modifier.fillMaxWidth())
    } }
    deleting?.let { chat -> BotDialog({ deleting = null }) {
        Text(tr("chat.deleteTitle"), fontWeight = FontWeight.SemiBold, color = p.ink)
        Text(chat.title, color = p.muted, modifier = Modifier.padding(vertical = 14.dp))
        ActionButton(tr("chat.deleteConfirm"), { actions.deleteChat(chat.id); deleting = null }, Modifier.fillMaxWidth(), primary = true)
        QuietAction(tr("input.cancel"), { deleting = null }, Modifier.fillMaxWidth())
    } }
}

@Composable
private fun DrawerLink(icon: String, text: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Glyph(icon, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(11.dp)); Text(text, fontSize = 14.sp)
    }
}
