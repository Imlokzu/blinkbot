package me.waveio.claudebot.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.waveio.claudebot.state.AppState

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
            Text(tr("update.available", "version" to available.versionName), color = palette.ink,
                fontWeight = FontWeight.Medium, fontSize = 18.sp)
            Text(tr("update.changelog"), color = palette.ink, fontWeight = FontWeight.Medium)
            if (available.changelog.isEmpty()) Text(tr("update.noChanges"), color = palette.muted)
            available.changelog.forEach { change -> Text(change, color = palette.muted) }
            ActionButton(tr(if (state.updateInstalling) "update.downloading" else "update.install"), actions::installUpdate,
                Modifier.fillMaxWidth(), primary = true, icon = "download",
                enabled = !state.updateChecking && !state.updateInstalling)
        }
    }
}
