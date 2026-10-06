package me.waveio.claudebot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.waveio.claudebot.state.AppState
import me.waveio.claudebot.state.MobileUpdate

@Composable
internal fun UpdateSettingsContent(state: AppState, actions: AppActions) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxWidth().testTag("app-updates"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            BotMark(Modifier.size(48.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(tr("app.name"), color = palette.ink, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                Text(tr("update.installed", "version" to state.installedVersion), color = palette.muted, fontSize = 13.sp)
            }
        }
        Text(tr(if (state.updateChecking) "update.checking" else state.updateStatus), color = palette.muted,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        BotToggle(tr("update.betaChannel"), state.updateBeta, actions::updateBeta)
        Text(tr(if (state.updateBeta) "update.channelBeta" else "update.channelStable"), color = palette.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 2.dp))
        state.updateError?.let { Text(tr(it), color = MaterialTheme.colorScheme.error) }
        ActionButton(tr(if (state.updateChecking) "update.checking" else "update.check"), actions::checkForUpdate,
            Modifier.fillMaxWidth(), icon = "retry", enabled = !state.updateChecking && !state.updateInstalling)

        state.update?.let { available ->
            Hairline()
            UpdateVersionRow(available, palette)
            GroupedChangelog(available, palette)
            ActionButton(tr(if (state.updateInstalling) "update.downloading" else "update.install"), actions::installUpdate,
                Modifier.fillMaxWidth(), primary = true, icon = "download",
                enabled = !state.updateChecking && !state.updateInstalling)
        }
    }
}

@Composable
private fun UpdateVersionRow(update: MobileUpdate, palette: Palette) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(tr("update.available", "version" to update.versionName), color = palette.ink,
            fontWeight = FontWeight.Medium, fontSize = 18.sp, modifier = Modifier.weight(1f))
        ChannelBadge(update.channel, palette)
    }
}

@Composable
private fun ChannelBadge(channel: String, palette: Palette) {
    val isBeta = channel == "beta"
    val background = if (isBeta) palette.accent.copy(alpha = .16f) else palette.secondary
    val ink = if (isBeta) palette.accent else palette.muted
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(background).padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(if (isBeta) "BETA" else "STABLE", color = ink, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun GroupedChangelog(update: MobileUpdate, palette: Palette) {
    if (update.changelog.isEmpty()) { Text(tr("update.noChanges"), color = palette.muted); return }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (update.security.isNotEmpty()) {
            ChangelogSection(tr("update.security"), update.security, palette, highlight = true)
        }
        if (update.added.isNotEmpty()) ChangelogSection(tr("update.added"), update.added, palette)
        if (update.fixed.isNotEmpty()) ChangelogSection(tr("update.fixed"), update.fixed, palette)
        if (update.other.isNotEmpty()) ChangelogSection(tr("update.changelog"), update.other, palette)
    }
}

@Composable
private fun ChangelogSection(title: String, items: List<String>, palette: Palette, highlight: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, color = if (highlight) MaterialTheme.colorScheme.error else palette.ink,
            fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        items.forEach { item -> Text(item, color = palette.muted, fontSize = 13.sp, lineHeight = 19.sp) }
    }
}
