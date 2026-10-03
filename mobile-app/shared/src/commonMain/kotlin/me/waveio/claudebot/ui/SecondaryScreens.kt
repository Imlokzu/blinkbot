package me.waveio.claudebot.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.datetime.*
import me.waveio.claudebot.state.*
import kotlin.math.PI
import kotlin.math.sin

@Composable
fun FilesScreen(state: AppState, actions: AppActions) {
    val p = LocalPalette.current
    if (state.openFile != null) {
        Column(Modifier.fillMaxSize().padding(18.dp)) {
            Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr(if (state.fileEditable) "files.editor" else "files.readOnly"), fontSize = 12.sp, color = p.muted, modifier = Modifier.weight(1f))
                if (state.fileSaveState == "failed") TextButton(onClick = actions::retryFileSave) { Text(tr("files.failed"), fontSize = 12.sp) }
                else Text(tr("files.${state.fileSaveState}"), fontSize = 12.sp, color = p.muted)
            }
            if (state.fileEditable) BasicTextField(state.fileText, actions::fileText, modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()), textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, color = p.ink, lineHeight = 22.sp), cursorBrush = SolidColor(p.accent))
            else Text(state.fileText.ifBlank { tr("files.previewUnavailable") }, color = p.ink, modifier = Modifier.verticalScroll(rememberScrollState()))
        }
    } else Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.directory.isNotBlank()) IconAction("back", tr("nav.back"), { actions.openDirectory(state.directory.substringBeforeLast('/', "")) })
            Text(state.directory.ifBlank { tr("files.root") }, color = p.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            IconAction("retry", tr("action.retry"), { actions.openDirectory(state.directory) })
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn {
            items(state.files, key = { it.path }) { file ->
                Row(Modifier.fillMaxWidth().clickable { if (file.directory) actions.openDirectory(file.path) else actions.openFile(file.path) }.padding(vertical = 17.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph(if (file.directory) "folder" else "file", tint = if (file.directory) p.accent else p.muted)
                    Spacer(Modifier.width(13.dp)); Text(file.name, color = p.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
                }
                HorizontalDivider(color = p.line.copy(alpha = 0.5f))
            }
            if (!state.loading && state.files.isEmpty()) item { Text(tr("files.empty"), color = p.muted, modifier = Modifier.padding(vertical = 40.dp)) }
        }
    }
}

@Composable
fun SettingsScreen(state: AppState, actions: AppActions) {
    val preferences = state.preferences
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (state.screen) {
            Screen.Profile -> {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 20.dp)) {
                    BotMark(Modifier.size(52.dp)); Spacer(Modifier.width(13.dp))
                    Column { Text(state.profileName.ifBlank { tr("app.name") }, color = p.ink, fontSize = 21.sp); Text(state.baseUrl.removePrefix("https://"), color = p.muted, fontSize = 12.sp) }
                }
                SettingsLink("settings", "profile.appearance") { actions.navigate(Screen.Appearance) }
                SettingsLink("bot", "profile.models") { actions.navigate(Screen.Models) }
                SettingsLink("edit", "profile.personalization") { actions.navigate(Screen.Personalization) }
                SettingsLink("phone", "profile.notifications") { actions.navigate(Screen.Notifications) }
                SettingsLink("time", "queue.title") { actions.navigate(Screen.Queue) }
                HorizontalDivider(color = p.line)
                Text(tr("profile.language"), color = p.muted, fontSize = 12.sp)
                Choices(listOf("system" to "profile.system", "uk" to "profile.ukrainian", "en" to "profile.english"), preferences.language) { actions.preferences(preferences.copy(language = it)) }
                ToggleRow("profile.haptics", preferences.haptics) { actions.preferences(preferences.copy(haptics = it)) }
                ToggleRow("profile.answerHaptics", preferences.answerHaptics) { actions.preferences(preferences.copy(answerHaptics = it)) }
                TextButton(onClick = actions::disconnect) { Glyph("logout", modifier = Modifier.size(18.dp)); Spacer(Modifier.width(9.dp)); Text(tr("profile.disconnect")) }
                Text(tr("profile.credits"), color = p.muted, fontSize = 11.sp)
            }
            Screen.Appearance -> {
                Text(tr("appearance.deviceOnly"), color = p.muted, fontSize = 13.sp)
                Text(tr("appearance.theme"), color = p.ink)
                Choices(listOf("system" to "profile.system", "light" to "appearance.light", "dark" to "appearance.dark"), preferences.theme) { actions.preferences(preferences.copy(theme = it)) }
                ToggleRow("appearance.wallpaper", preferences.wallpaper) { actions.preferences(preferences.copy(wallpaper = it)) }
                Row { OutlinedButton(onClick = { actions.pickFile("wallpaper") }) { Text(tr("appearance.change")) }; Spacer(Modifier.width(8.dp)); TextButton(onClick = actions::resetWallpaper) { Text(tr("appearance.reset")) } }
                ToggleRow("appearance.full", preferences.fullWallpaper) { actions.preferences(preferences.copy(fullWallpaper = it)) }
                Text(tr("appearance.dim"), color = p.ink)
                Slider(preferences.wallpaperDim, { actions.preferences(preferences.copy(wallpaperDim = it)) }, valueRange = 0f..0.65f)
                Text(tr("appearance.blur"), color = p.ink)
                Slider(preferences.wallpaperBlur, { actions.preferences(preferences.copy(wallpaperBlur = it)) }, valueRange = 0f..48f)
                Text(tr("appearance.screens"), color = p.muted, fontSize = 12.sp)
                listOf(Screen.Chat, Screen.Search, Screen.Files, Screen.Agents, Screen.Profile).forEach { screen ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(screen.name in preferences.wallpaperScreens, { checked -> actions.preferences(preferences.copy(wallpaperScreens = if (checked) preferences.wallpaperScreens + screen.name else preferences.wallpaperScreens - screen.name)) })
                        Text(screenTitle(screen), color = p.ink)
                    }
                }
            }
            Screen.Models -> {
                Text(tr("defaults.help"), color = p.muted, fontSize = 13.sp)
                Choices(listOf("last" to "defaults.last", "fixed" to "defaults.fixed"), preferences.defaultModelMode) { actions.preferences(preferences.copy(defaultModelMode = it)) }
                state.models.forEach { model ->
                    Row(Modifier.fillMaxWidth().clickable { actions.preferences(preferences.copy(defaultModel = model.id, defaultModelMode = "fixed")) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(preferences.defaultModel == model.id, { actions.preferences(preferences.copy(defaultModel = model.id, defaultModelMode = "fixed")) })
                        BrandMark(model.brand); Spacer(Modifier.width(8.dp)); Text(model.label, color = p.ink)
                    }
                }
            }
            Screen.Notifications -> {
                Text(tr("notifications.permission"), color = p.muted)
                ToggleRow("notifications.enable", preferences.notifications) { if (it) actions.requestNotifications() else actions.preferences(preferences.copy(notifications = false)) }
                ToggleRow("notifications.preview", preferences.notificationPreview) { actions.preferences(preferences.copy(notificationPreview = it)) }
                Text(tr("notifications.limited"), color = p.muted, fontSize = 12.sp)
            }
            Screen.Personalization -> {
                OutlinedTextField(state.profileName, actions::profileName, label = { Text(tr("profile.name")) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
                OutlinedTextField(state.profilePersona, actions::profilePersona, label = { Text(tr("profile.persona")) }, modifier = Modifier.fillMaxWidth().heightIn(min = 170.dp), shape = RoundedCornerShape(16.dp))
                Button(onClick = actions::saveProfile, enabled = !state.loading) { Text(tr("profile.save")) }
            }
            Screen.Queue -> {
                if (state.allPending.isEmpty()) Text(tr("queue.empty"), color = p.muted)
                state.allPending.forEach { item ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(item.text, color = p.ink, maxLines = 4)
                        Text(tr("queue.state.${item.state}"), color = p.muted, fontSize = 12.sp)
                        item.scheduledAt?.let { Text(runCatching { Instant.parse(it).toLocalDateTime(TimeZone.currentSystemDefault()).toString().replace('T', ' ') }.getOrDefault(it), color = p.muted, fontSize = 12.sp) }
                        Row {
                            TextButton(onClick = { actions.openChat(item.sessionId) }) { Text(tr("action.open")) }
                            if (item.state in listOf("pending", "failed", "queued", "interrupted")) TextButton(onClick = { actions.retryPending(item.id) }) { Text(tr("action.retry")) }
                            TextButton(onClick = { actions.cancelPending(item.id) }) { Text(tr("input.cancel")) }
                        }
                        HorizontalDivider(color = p.line)
                    }
                }
            }
            else -> Unit
        }
    }
}

@Composable
private fun SettingsLink(icon: String, key: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) { Glyph(icon, tint = LocalPalette.current.muted); Spacer(Modifier.width(14.dp)); Text(tr(key), color = LocalPalette.current.ink, modifier = Modifier.weight(1f)); Glyph("down", modifier = Modifier.size(14.dp)) }
}

@Composable
private fun ToggleRow(key: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) { Text(tr(key), color = LocalPalette.current.ink, modifier = Modifier.weight(1f)); Switch(value, onChange) }
}

@Composable
private fun Choices(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) { options.forEach { (id, key) -> FilterChip(selected == id, { onSelect(id) }, label = { Text(tr(key), fontSize = 13.sp) }) } }
}

@Composable
fun DictationPopup(state: AppState, actions: AppActions) {
    val p = LocalPalette.current
    Dialog(onDismissRequest = actions::cancelDictation) {
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(17.dp)) {
                Text(tr(if (state.transcribing) "input.transcribing" else "input.listening"), color = p.ink, fontSize = 22.sp)
                Canvas(Modifier.fillMaxWidth().height(74.dp)) {
                    val path = Path(); val amplitude = 3.dp.toPx() + state.amplitude.coerceIn(0f, 1f) * size.height * 0.4f
                    for (x in 0..size.width.toInt() step 2) {
                        val envelope = sin(PI * x / size.width).toFloat()
                        val y = size.height / 2 + sin(x * 0.075).toFloat() * amplitude * envelope
                        if (x == 0) path.moveTo(x.toFloat(), y) else path.lineTo(x.toFloat(), y)
                    }
                    drawPath(path, p.accent, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
                }
                Text(state.transcript.ifBlank { tr("input.dictationHelp") }, color = if (state.transcript.isBlank()) p.muted else p.ink, fontSize = 15.sp, modifier = Modifier.heightIn(min = 50.dp, max = 180.dp).verticalScroll(rememberScrollState()))
                if (state.transcribing) CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
                else if (state.recording) FilledIconButton(onClick = actions::stopDictation, modifier = Modifier.size(58.dp)) { Glyph("stop", tr("chat.stop"), tint = MaterialTheme.colorScheme.onPrimary) }
                else if (state.transcript.isNotBlank()) Button(onClick = actions::useTranscript) { Text(tr("input.useText")) }
                TextButton(onClick = actions::cancelDictation) { Text(tr("input.cancel")) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchedulePopup(actions: AppActions) {
    val date = rememberDatePickerState()
    val time = rememberTimePickerState(is24Hour = true)
    var showTime by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = { actions.schedule(false) }) {
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(tr("queue.schedule"), fontSize = 20.sp, modifier = Modifier.padding(12.dp))
                if (showTime) TimePicker(time) else DatePicker(date, showModeToggle = false)
                Text(TimeZone.currentSystemDefault().id, color = LocalPalette.current.muted, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { actions.schedule(false) }) { Text(tr("input.cancel")) }
                    TextButton(enabled = date.selectedDateMillis != null, onClick = {
                        if (!showTime) showTime = true
                        else {
                            val day = Instant.fromEpochMilliseconds(date.selectedDateMillis!!).toLocalDateTime(TimeZone.UTC).date
                            val instant = LocalDateTime(day, LocalTime(time.hour, time.minute)).toInstant(TimeZone.currentSystemDefault())
                            actions.send("queue", instant.toString())
                        }
                    }) { Text(tr(if (showTime) "queue.schedule" else "queue.time")) }
                }
            }
        }
    }
}
