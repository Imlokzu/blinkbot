package me.waveio.claudebot

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import me.waveio.claudebot.platform.PlatformBridge
import me.waveio.claudebot.state.AppController
import me.waveio.claudebot.ui.*

@Composable
fun App(platform: PlatformBridge, createController: (PlatformBridge) -> AppController = { AppController(it) }) {
    val controller = remember(platform) { createController(platform) }
    val state by controller.state.collectAsState()
    val language = state.preferences.language.takeUnless { it == "system" } ?: platform.systemLanguage
    val strings by produceState<LocaleText?>(null, language) { value = LocaleText.load(language) }
    DisposableEffect(controller) { onDispose { controller.close() } }
    val dark = when (state.preferences.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    MobileTheme(dark) {
        val local = strings
        if (local == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingDots() }
        else CompositionLocalProvider(LocalText provides local) {
            SideEffect { controller.strings(local) }
            MobileShell(state, controller, platform.reducedMotion)
        }
    }
}
