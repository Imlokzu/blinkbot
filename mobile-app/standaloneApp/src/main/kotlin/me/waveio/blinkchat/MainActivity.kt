package me.waveio.blinkchat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.waveio.claudebot.ui.*

class MainActivity : ComponentActivity() {
    private lateinit var platform: ChatPlatform
    private lateinit var controller: ChatController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val storage = ChatStorage(applicationContext)
        platform = ChatPlatform(this, storage)
        controller = ChatController(platform, storage)
        setContent { StandaloneApp(controller, platform) { darkStatusIcons, darkNavigationIcons ->
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = darkStatusIcons
                isAppearanceLightNavigationBars = darkNavigationIcons
            }
        } }
    }

    override fun onDestroy() {
        controller.close()
        platform.cleanup()
        super.onDestroy()
    }
}

@Composable
private fun StandaloneApp(controller: ChatController, platform: ChatPlatform, systemBars: (Boolean, Boolean) -> Unit) {
    val context = LocalContext.current
    val state by controller.state.collectAsState()
    val language = state.preferences.language.takeUnless { it == "system" } ?: platform.systemLanguage
    val text by produceState<LocaleText?>(null, language) {
        val base = LocaleText.load(language)
        val overrides = withContext(Dispatchers.IO) {
            fun read(code: String): Map<String, String> = context.assets.open("locales/$code.json").use { stream ->
                Json.parseToJsonElement(stream.readBytes().decodeToString()).jsonObject.mapValues { it.value.jsonPrimitive.content }
            }
            val english = read("en")
            if (language.substringBefore('-').substringBefore('_') == "uk") english + read("uk") else english
        }
        value = base.withOverrides(overrides)
    }
    val dark = when (state.preferences.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val wallpaper = state.preferences.wallpaper && !state.menuOpen && (state.screen.name in state.preferences.wallpaperScreens || "all" in state.preferences.wallpaperScreens)
    SideEffect { systemBars(!dark && !wallpaper, !dark) }
    MobileTheme(dark, state.preferences.font) {
        val strings = text
        if (strings == null || state.loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingDots() }
        else CompositionLocalProvider(LocalText provides strings, LocalChatFeatures provides ChatFeatures(dictation = false, scheduling = false)) {
            MobileShell(state, controller, platform.reducedMotion) { screen -> StandaloneScreen(screen, state, controller) }
        }
    }
}
