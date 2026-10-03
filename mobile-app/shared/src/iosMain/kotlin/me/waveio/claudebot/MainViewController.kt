package me.waveio.claudebot

import androidx.compose.ui.window.ComposeUIViewController
import me.waveio.claudebot.platform.IosBridge

fun MainViewController(bridge: IosBridge) = ComposeUIViewController { App(bridge) }
