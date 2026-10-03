package me.waveio.claudebot

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import me.waveio.claudebot.resources.Res
import me.waveio.claudebot.resources.app_name
import org.jetbrains.compose.resources.stringResource

@Composable
fun App() {
    MaterialTheme { Text(stringResource(Res.string.app_name)) }
}
