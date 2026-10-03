package me.waveio.claudebot.ui

import androidx.compose.runtime.Composable

@Composable
expect fun NativeBackHandler(enabled: Boolean, onBack: () -> Unit)
