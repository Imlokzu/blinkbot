package me.waveio.claudebot.ui

import androidx.compose.runtime.Composable

/** iOS uses the shared back controls and drawer gesture; it has no system back key. */
@Composable
actual fun NativeBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
