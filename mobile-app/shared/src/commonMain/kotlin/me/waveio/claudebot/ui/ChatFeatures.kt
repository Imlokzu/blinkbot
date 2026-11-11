package me.waveio.claudebot.ui

import androidx.compose.runtime.staticCompositionLocalOf

/** Defaults preserve Blink's host features; standalone clients expose what they implement. */
data class ChatFeatures(val dictation: Boolean = true, val scheduling: Boolean = true)
val LocalChatFeatures = staticCompositionLocalOf { ChatFeatures() }
