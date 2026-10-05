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
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    Box(Modifier.widthIn(max = if (state.openFile != null) MediaContentMaxWidth else ChatContentMaxWidth).fillMaxSize()) {
    if (state.openFile != null) {
        Column(Modifier.fillMaxSize().padding(18.dp)) {
            Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr(if (state.fileEditable) "files.editor" else "files.readOnly"), fontSize = 12.sp, color = p.muted, modifier = Modifier.weight(1f))
                if (state.fileSaveState == "failed") QuietAction(tr("files.failed"), actions::retryFileSave)
                else Text(tr("files.${state.fileSaveState}"), fontSize = 12.sp, color = p.muted)
            }
            if (state.loading && state.fileText.isEmpty()) LoadingDots(Modifier.padding(16.dp))
            else if (state.fileEditable) BasicTextField(state.fileText, actions::fileText, modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()), textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, color = p.ink, lineHeight = 22.sp), cursorBrush = SolidColor(p.accent))
            else Text(state.fileText.ifBlank { tr("files.previewUnavailable") }, color = p.ink, modifier = Modifier.verticalScroll(rememberScrollState()))
        }
    } else Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.directory.isNotBlank()) IconAction("back", tr("nav.back"), { actions.openDirectory(state.directory.substringBeforeLast('/', "")) })
            Text(state.directory.ifBlank { tr("files.root") }, color = p.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            IconAction("retry", tr("action.retry"), { actions.openDirectory(state.directory) })
        }
        if (state.loading) LoadingDots(Modifier.padding(vertical = 10.dp))
        LazyColumn {
            items(state.files, key = { it.path }) { file ->
                Row(Modifier.fillMaxWidth().clickable { if (file.directory) actions.openDirectory(file.path) else actions.openFile(file.path) }.padding(vertical = 17.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph(if (file.directory) "folder" else "file", tint = if (file.directory) p.accent else p.muted)
                    Spacer(Modifier.width(13.dp)); Text(file.name, color = p.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
                }
                Hairline()
            }
            if (!state.loading && state.files.isEmpty()) item { Text(tr("files.empty"), color = p.muted, modifier = Modifier.padding(vertical = 40.dp)) }
        }
    }
    }
    }
}

@Composable
fun SettingsScreen(state: AppState, actions: AppActions) {
    val preferences = state.preferences
    val p = LocalPalette.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    Column(Modifier.widthIn(max = FormContentMaxWidth).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                SettingsLink(if (state.updateChecking) "time" else "download", "profile.update", actions::checkForUpdate)
                Hairline()
                Text(tr("profile.language"), color = p.muted, fontSize = 12.sp)
                Choices(listOf("system" to "profile.system", "uk" to "profile.ukrainian", "en" to "profile.english"), preferences.language) { actions.preferences(preferences.copy(language = it)) }
                ToggleRow("profile.haptics", preferences.haptics) { actions.preferences(preferences.copy(haptics = it)) }
                ToggleRow("profile.answerHaptics", preferences.answerHaptics) { actions.preferences(preferences.copy(answerHaptics = it)) }
                ActionButton(tr("profile.disconnect"), actions::disconnect, icon = "logout")
                Text(tr("profile.credits"), color = p.muted, fontSize = 11.sp)
            }
            Screen.Appearance -> {
                Text(tr("appearance.deviceOnly"), color = p.muted, fontSize = 13.sp)
                Text(tr("appearance.theme"), color = p.ink)
                Choices(listOf("system" to "profile.system", "light" to "appearance.light", "dark" to "appearance.dark"), preferences.theme) { actions.preferences(preferences.copy(theme = it)) }
                ToggleRow("appearance.wallpaper", preferences.wallpaper) { actions.preferences(preferences.copy(wallpaper = it)) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton(tr("appearance.change"), { actions.pickFile("wallpaper") }, icon = "photo")
                    QuietAction(tr("appearance.reset"), actions::resetWallpaper)
                }
                ToggleRow("appearance.full", preferences.fullWallpaper) { actions.preferences(preferences.copy(fullWallpaper = it)) }
                BotSlider(tr("appearance.dim"), preferences.wallpaperDim, 0f..0.65f) { actions.preferences(preferences.copy(wallpaperDim = it)) }
                BotSlider(tr("appearance.blur"), preferences.wallpaperBlur, 0f..48f) { actions.preferences(preferences.copy(wallpaperBlur = it)) }
                Text(tr("appearance.screens"), color = p.muted, fontSize = 12.sp)
                BotToggle(tr("appearance.everywhere"), "all" in preferences.wallpaperScreens) { enabled -> actions.preferences(preferences.copy(wallpaperScreens = if (enabled) setOf("all") else setOf("Chat"))) }
                if ("all" !in preferences.wallpaperScreens) listOf(Screen.Chat, Screen.Search, Screen.Files, Screen.Agents, Screen.Profile).forEach { screen ->
                    BotToggle(screenTitle(screen), screen.name in preferences.wallpaperScreens) { checked -> actions.preferences(preferences.copy(wallpaperScreens = if (checked) preferences.wallpaperScreens + screen.name else preferences.wallpaperScreens - screen.name)) }
                }
            }
            Screen.Models -> {
                Text(tr("defaults.help"), color = p.muted, fontSize = 13.sp)
                Choices(listOf("last" to "defaults.last", "fixed" to "defaults.fixed"), preferences.defaultModelMode) { actions.preferences(preferences.copy(defaultModelMode = it)) }
                state.models.forEach { model ->
                    Row(Modifier.fillMaxWidth().clickable { actions.preferences(preferences.copy(defaultModel = model.id, defaultModelMode = "fixed")) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { if (preferences.defaultModelMode == "fixed" && preferences.defaultModel == model.id) Glyph("check", modifier = Modifier.size(18.dp), tint = p.accent) }
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
                BotField(state.profileName, actions::profileName, label = tr("profile.name"), modifier = Modifier.fillMaxWidth())
                BotField(state.profilePersona, actions::profilePersona, label = tr("profile.persona"), modifier = Modifier.fillMaxWidth().heightIn(min = 170.dp), singleLine = false)
                ActionButton(tr("profile.save"), actions::saveProfile, enabled = !state.loading, primary = true)
            }
            Screen.Queue -> {
                if (state.allPending.isEmpty()) Text(tr("queue.empty"), color = p.muted)
                state.allPending.forEach { item ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(item.text, color = p.ink, maxLines = 4)
                        Text(tr("queue.state.${item.state}"), color = p.muted, fontSize = 12.sp)
                        item.scheduledAt?.let { Text(runCatching { Instant.parse(it).toLocalDateTime(TimeZone.currentSystemDefault()).toString().replace('T', ' ') }.getOrDefault(it), color = p.muted, fontSize = 12.sp) }
                        Row {
                            QuietAction(tr("action.open"), { actions.openChat(item.sessionId) })
                            if (item.state in listOf("pending", "failed", "queued", "interrupted")) QuietAction(tr("action.retry"), { actions.retryPending(item.id) })
                            QuietAction(tr("input.cancel"), { actions.cancelPending(item.id) })
                        }
                        Hairline()
                    }
                }
            }
            else -> Unit
        }
    }
    }
}

@Composable
private fun SettingsLink(icon: String, key: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) { Glyph(icon, tint = LocalPalette.current.muted); Spacer(Modifier.width(14.dp)); Text(tr(key), color = LocalPalette.current.ink, modifier = Modifier.weight(1f)); Glyph("down", modifier = Modifier.size(14.dp)) }
}

@Composable
private fun ToggleRow(key: String, value: Boolean, onChange: (Boolean) -> Unit) = BotToggle(tr(key), value, onChange)

@Composable
private fun Choices(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) { options.forEach { (id, key) -> ChoicePill(tr(key), selected == id, { onSelect(id) }) } }
}


@Composable
fun SkillsScreen(state: AppState, actions: AppActions) {
    val p = LocalPalette.current
    var query by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    Column(Modifier.widthIn(max = FormContentMaxWidth).fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(tr("skills.help"), color = p.muted, fontSize = 13.sp)
        BotField(query, { query = it }, placeholder = tr("skills.search"), icon = "search")
        if (state.skillsLoading) LoadingDots(Modifier.padding(16.dp))
        if (state.skillsError) {
            Text(tr("skills.unavailable"), color = p.muted, fontSize = 13.sp)
            ActionButton(tr("action.retry"), actions::refresh, icon = "retry")
        }
        val matches = state.skills.filter { it.name.contains(query, true) || it.description.contains(query, true) }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(matches, key = { it.name + ":" + it.source }) { skill ->
                MenuRow("skills", skill.name, { actions.useSkill(skill.name) },
                    subtitle = if (skill.selectable) skill.description else tr("skills.disabled"), enabled = skill.selectable,
                    trailing = { if (skill.selectable) Glyph("new", modifier = Modifier.size(16.dp), tint = p.muted) })
                Hairline()
            }
            if (!state.skillsLoading && !state.skillsError && matches.isEmpty()) item { Text(tr("skills.empty"), color = p.muted, modifier = Modifier.padding(vertical = 22.dp)) }
        }
    }
    }
}
