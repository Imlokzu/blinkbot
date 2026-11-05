package me.waveio.claudebot.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.*
import me.waveio.claudebot.state.*
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun FilesScreen(state: AppState, actions: AppActions,
    navigation: WorkspaceEditorNavigation = rememberWorkspaceEditorNavigation(state.fileEditorGeneration.toString())) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = if (state.openFile != null) MediaContentMaxWidth else ChatContentMaxWidth).fillMaxSize()) {
            FileWorkspace(state, actions, navigation)
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
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(tr("app.name"), color = p.ink, fontSize = 21.sp)
                        // The profile header sits on the wallpaper's busiest part (the sky), so the
                        // host name gets its own opaque backing instead of muted ink on the image.
                        Text(state.baseUrl.removePrefix("https://"), color = p.ink.copy(alpha = .78f), fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(p.surface.copy(alpha = .86f)).padding(horizontal = 7.dp, vertical = 2.dp))
                    }
                }
                SettingsLink("settings", "profile.appearance") { actions.navigate(Screen.Appearance) }
                SettingsLink("bot", "profile.models") { actions.navigate(Screen.Models) }
                SettingsLink("edit", "profile.personalization") { actions.navigate(Screen.Personalization) }
                SettingsLink("phone", "profile.notifications") { actions.navigate(Screen.Notifications) }
                SettingsLink("phone", "profile.haptics") { actions.navigate(Screen.Haptics) }
                SettingsLink("time", "queue.title") { actions.navigate(Screen.Queue) }
                SettingsLink("download", "profile.update") { actions.navigate(Screen.Updates) }
                Hairline()
                Text(tr("profile.language"), color = p.muted, fontSize = 12.sp)
                Choices(listOf("system" to "profile.system", "uk" to "profile.ukrainian", "en" to "profile.english"), preferences.language) { actions.preferences(preferences.copy(language = it)) }
                ActionButton(tr("profile.disconnect"), actions::disconnect, icon = "logout")
                Text(tr("profile.credits"), color = p.muted, fontSize = 11.sp)
            }
            Screen.Appearance -> {
                Text(tr("appearance.deviceOnly"), color = p.muted, fontSize = 13.sp)
                Text(tr("appearance.theme"), color = p.ink)
                Choices(listOf("system" to "profile.system", "light" to "appearance.light", "dark" to "appearance.dark"), preferences.theme) { actions.preferences(preferences.copy(theme = it)) }
                Text(tr("appearance.font"), color = p.ink)
                FontChoices(appFont(preferences.font).id) { actions.preferences(preferences.copy(font = it)) }
                ToggleRow("appearance.wallpaper", preferences.wallpaper) { actions.preferences(preferences.copy(wallpaper = it)) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton(tr("appearance.change"), { actions.pickFile("wallpaper") }, icon = "photo")
                    QuietAction(tr("appearance.reset"), actions::resetWallpaper)
                }
                ToggleRow("appearance.full", preferences.fullWallpaper) { actions.preferences(preferences.copy(fullWallpaper = it)) }
                BotSlider(tr("appearance.dim"), preferences.wallpaperDim, 0f..0.65f) { actions.preferences(preferences.copy(wallpaperDim = it)) }
                BotSlider(tr("appearance.blur"), preferences.wallpaperBlur, 0f..48f) { actions.preferences(preferences.copy(wallpaperBlur = it)) }
                Text(tr("appearance.screens"), color = p.muted, fontSize = 12.sp)
                val everywhere = "all" in preferences.wallpaperScreens
                BotToggle(tr("appearance.everywhere"), everywhere) { enabled -> actions.preferences(preferences.copy(wallpaperScreens = if (enabled) setOf("all") else setOf("Chat"))) }
                // "all" also covers the settings pages, which have no toggle of their own below.
                Text(tr(if (everywhere) "appearance.everywhereOn" else "appearance.everywhereOff"), color = p.muted, fontSize = 12.sp)
                if ("all" !in preferences.wallpaperScreens) listOf(Screen.Chat, Screen.Search, Screen.Files, Screen.Agents, Screen.Profile).forEach { screen ->
                    BotToggle(screenTitle(screen), screen.name in preferences.wallpaperScreens) { checked -> actions.preferences(preferences.copy(wallpaperScreens = if (checked) preferences.wallpaperScreens + screen.name else preferences.wallpaperScreens - screen.name)) }
                }
            }
            Screen.Models -> {
                Text(tr("defaults.help"), color = p.muted, fontSize = 13.sp)
                Choices(listOf("last" to "defaults.last", "fixed" to "defaults.fixed"), preferences.defaultModelMode) { actions.preferences(preferences.copy(defaultModelMode = it)) }
                // Separate cards with real gaps: back-to-back surfaces used to merge into one
                // ragged block, and the selected card could not be told apart from its neighbours.
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.models.forEach { model ->
                        val picked = preferences.defaultModelMode == "fixed" && preferences.defaultModel == model.id
                        val shape = RoundedCornerShape(18.dp)
                        Row(Modifier.fillMaxWidth().clip(shape).background(if (picked) p.secondary else p.surface)
                            .then(if (picked) Modifier.border(1.dp, p.ink.copy(alpha = .16f), shape) else Modifier)
                            .selectable(selected = picked, enabled = model.available, role = Role.RadioButton) {
                                actions.preferences(preferences.copy(defaultModel = model.id, defaultModelMode = "fixed"))
                            }.heightIn(min = 60.dp).padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                            BrandMark(model.brand); Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(model.label, color = if (model.available) p.ink else p.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val facts = if (model.available) modelFacts(model, state.intelligence[model.id]) else listOf(tr("model.unavailable"))
                                if (facts.isNotEmpty()) Text(facts.joinToString(" · "), color = p.muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Box(Modifier.size(28.dp), contentAlignment = Alignment.CenterEnd) { if (picked) Glyph("check", modifier = Modifier.size(18.dp), tint = p.accent) }
                        }
                    }
                }
            }
            Screen.Notifications -> {
                Text(tr("notifications.permission"), color = p.muted)
                ToggleRow("notifications.enable", preferences.notifications) { if (it) actions.requestNotifications() else actions.preferences(preferences.copy(notifications = false)) }
                ToggleRow("notifications.preview", preferences.notificationPreview) { actions.preferences(preferences.copy(notificationPreview = it)) }
                // Say what will actually happen. Delivery is local: it works while Blink runs in the
                // background; no push reaches a closed app, so that limit is stated plainly.
                val readiness = when {
                    state.notificationsDenied -> "notifications.state.denied"
                    preferences.notifications -> "notifications.state.ready"
                    else -> "notifications.state.off"
                }
                Text(tr(readiness), color = p.ink.copy(alpha = .78f), fontSize = 12.sp)
            }
            Screen.Haptics -> {
                Text(tr("haptics.help"), color = p.muted, fontSize = 13.sp)
                ToggleRow("profile.haptics", preferences.haptics) { actions.preferences(preferences.copy(haptics = it)) }
                if (preferences.haptics) {
                    ToggleRow("haptics.agent", preferences.answerHaptics) { actions.preferences(preferences.copy(answerHaptics = it)) }
                    if (preferences.answerHaptics) {
                        // Agent activity really gates these three: off, none of them plays. The indent
                        // and guide line show that; strength and frequency apply to all three.
                        Row(Modifier.height(IntrinsicSize.Min)) {
                            Box(Modifier.padding(start = 6.dp, end = 14.dp).width(2.dp).fillMaxHeight().clip(RoundedCornerShape(1.dp)).background(p.line))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(tr("haptics.agentGroup"), color = p.muted, fontSize = 12.sp)
                                ToggleRow("haptics.thinking", preferences.thinkingHaptics) { actions.preferences(preferences.copy(thinkingHaptics = it)) }
                                Text(tr("haptics.thinkingHint"), color = p.muted, fontSize = 12.sp)
                                ToggleRow("haptics.tools", preferences.toolHaptics) { actions.preferences(preferences.copy(toolHaptics = it)) }
                                Text(tr("haptics.toolsHint"), color = p.muted, fontSize = 12.sp)
                                ToggleRow("haptics.streaming", preferences.streamingHaptics) { actions.preferences(preferences.copy(streamingHaptics = it)) }
                                Text(tr("haptics.streamingHint"), color = p.muted, fontSize = 12.sp)
                            }
                        }
                        Hairline()
                        HapticStrengthSlider(tr("haptics.strength"), preferences.hapticStrengthLevel,
                            onChange = { actions.preferences(preferences.copy(hapticLevel = it)) },
                            onPreview = actions::previewHapticStrength,
                            lowLabel = tr("haptics.light"), highLabel = tr("haptics.strong"),
                            previewEnabled = state.agentHapticsSupported)
                        if (state.agentHapticsSupported) Text(tr("haptics.strengthHint"), color = p.muted, fontSize = 12.sp)
                        Text(tr("haptics.cadence"), color = p.ink)
                        Choices(listOf("calm" to "haptics.calm", "balanced" to "haptics.balanced", "lively" to "haptics.lively"), preferences.hapticCadence) { actions.preferences(preferences.copy(hapticCadence = it)) }
                        Text(tr("haptics.preview"), color = p.muted, fontSize = 12.sp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(AgentHaptic.Thinking to "haptics.tryThinking", AgentHaptic.Tool to "haptics.tryTools", AgentHaptic.Streaming to "haptics.tryStreaming").forEach { (kind, key) ->
                                ActionButton(tr(key), { actions.previewHaptic(kind) }, enabled = state.agentHapticsSupported)
                            }
                        }
                    }
                }
                Text(tr(if (state.agentHapticsSupported) "haptics.system" else "haptics.unsupported"), color = p.muted, fontSize = 12.sp)
            }
            Screen.Updates -> UpdateSettingsContent(state, actions)
            Screen.Personalization -> {
                BotField(state.profileName, actions::profileName, label = tr("profile.name"), modifier = Modifier.fillMaxWidth())
                BotField(state.profilePersona, actions::profilePersona, label = tr("profile.persona"), modifier = Modifier.fillMaxWidth().heightIn(min = 170.dp), singleLine = false)
                ActionButton(tr("profile.save"), actions::saveProfile, enabled = !state.loading, primary = true)
            }
            Screen.Queue -> {
                if (state.allPending.isEmpty()) Text(tr("queue.empty"), color = p.muted)
                state.allPending.forEach { item ->
                    val advice = queueAdvice(item, chatActive = state.allPending.any { it.sessionId == item.sessionId && it.id != item.id && it.state in listOf("running", "stopping") })
                    val attention = item.state in listOf("failed", "interrupted", "unsupported")
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(item.text, color = p.ink, maxLines = 4)
                        Text(tr("queue.state.${item.state}"), color = if (attention) p.accent else p.muted, fontSize = 12.sp, fontWeight = if (attention) FontWeight.SemiBold else null)
                        advice.reason?.let { Text(tr(it), color = p.ink.copy(alpha = .78f), fontSize = 12.sp) }
                        item.scheduledAt?.let { Text(runCatching { Instant.parse(it).toLocalDateTime(TimeZone.currentSystemDefault()).toString().replace('T', ' ') }.getOrDefault(it), color = p.muted, fontSize = 12.sp) }
                        // The step that fixes this item's actual problem leads; the others stay quiet.
                        val retryLabel = tr(when { advice.recommended == QueueStep.Continue -> "queue.resume"; advice.recommended == QueueStep.SendNow || item.state == "pending" -> "queue.sendNow"; else -> "action.retry" })
                        FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            val open = { actions.openChat(item.sessionId) }
                            val retry = { actions.retryPending(item.id) }
                            when (advice.recommended) {
                                QueueStep.Open -> ActionButton(tr("action.open"), open)
                                QueueStep.Retry, QueueStep.SendNow, QueueStep.Continue -> ActionButton(retryLabel, retry)
                                QueueStep.None -> Unit
                            }
                            if (advice.recommended != QueueStep.Open) QuietAction(tr("action.open"), open)
                            if (advice.canRetry && advice.recommended !in listOf(QueueStep.Retry, QueueStep.SendNow, QueueStep.Continue)) QuietAction(retryLabel, retry)
                            QuietAction(tr(advice.cancelLabel), { actions.cancelPending(item.id) })
                        }
                        Text(tr(advice.cancelHint), color = p.muted, fontSize = 11.sp)
                        Hairline(Modifier.padding(top = 6.dp))
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
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = onClick).heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        // Thin line icons vanish on a busy wallpaper; an opaque tile keeps them as legible as the labels.
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(p.secondary), contentAlignment = Alignment.Center) {
            Glyph(icon, modifier = Modifier.size(19.dp), tint = p.ink)
        }
        Spacer(Modifier.width(12.dp)); Text(tr(key), color = p.ink, modifier = Modifier.weight(1f)); Glyph("down", modifier = Modifier.size(14.dp).rotate(-90f), tint = p.muted)
    }
}

/**
 * Only facts the host reported: unknown capabilities, speeds or scores are left out
 * rather than guessed, so two rows may show different amounts of detail.
 */
@Composable
private fun modelFacts(model: ModelRow, intelligence: IntelligenceRow?): List<String> = buildList {
    intelligence?.let { add(tr("model.fact.overall", "score" to it.index.roundToInt())) }
    model.context?.takeIf { it > 0 }?.let { add(tr("model.fact.context", "size" to compactTokens(it))) }
    model.seconds?.takeIf { it > 0 }?.let { add(tr("model.fact.seconds", "value" to formatSeconds(it))) }
    if (model.vision == true) add(tr("model.images"))
}

internal fun compactTokens(tokens: Long): String = when {
    tokens >= 1_000_000 -> ((tokens / 100_000) / 10.0).let { if (it % 1.0 == 0.0) "${it.toInt()}M" else "${it}M" }
    tokens >= 1_000 -> "${(tokens + 500) / 1_000}K"
    else -> tokens.toString()
}

private fun formatSeconds(value: Double): String = ((value * 10).roundToInt() / 10.0).let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }

@Composable
private fun ToggleRow(key: String, value: Boolean, onChange: (Boolean) -> Unit) = BotToggle(tr(key), value, onChange)

@Composable
private fun Choices(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) { options.forEach { (id, key) -> ChoicePill(tr(key), selected == id, { onSelect(id) }) } }
}

/** Each option previews itself: its name is drawn in that font. */
@Composable
private fun FontChoices(selected: String, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        AppFonts.forEach { font -> ChoicePill(tr("font.${font.id}"), selected == font.id, { onSelect(font.id) }, fontFamily = rememberFontFamily(font)) }
    }
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
