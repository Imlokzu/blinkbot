package me.waveio.claudebot.ui

import androidx.compose.ui.window.DialogProperties

/** Android uses edge-to-edge content; desktop keeps ordinary window placement. */
expect fun mediaPreviewDialogProperties(): DialogProperties
