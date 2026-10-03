package me.waveio.claudebot.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.*
import me.waveio.claudebot.resources.*
import me.waveio.claudebot.state.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

data class Palette(val background: Color, val surface: Color, val ink: Color, val muted: Color, val line: Color, val accent: Color, val dark: Boolean)
val LocalPalette = staticCompositionLocalOf { Palette(Color(0xFFF7F5F2), Color.White, Color(0xFF232326), Color(0xFF77757C), Color(0xFFE6E2DD), Color(0xFFC06C4E), false) }

@Composable
fun MobileTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) Palette(Color(0xFF171720), Color(0xFF24242F), Color(0xFFF4F0EB), Color(0xFFAAA6B0), Color(0xFF383743), Color(0xFFE39879), true) else LocalPalette.current
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = colors.accent, onPrimary = Color.White,
        secondary = colors.accent, secondaryContainer = lerp(colors.surface, colors.accent, 0.16f),
        onSecondaryContainer = colors.ink, background = colors.background,
        surface = colors.surface, onSurface = colors.ink, onBackground = colors.ink,
        outline = colors.muted, outlineVariant = colors.line,
    )
    CompositionLocalProvider(LocalPalette provides colors) { MaterialTheme(colorScheme = scheme, content = content) }
}

@Composable
fun Glyph(name: String, label: String? = null, modifier: Modifier = Modifier.size(21.dp), tint: Color = LocalPalette.current.ink) {
    val resource: DrawableResource = when (name) {
        "menu" -> Res.drawable.ic_sidebar_minimalistic_left
        "new" -> Res.drawable.ic_pen_new_square
        "attach" -> Res.drawable.ic_paperclip
        "mic" -> Res.drawable.ic_microphone
        "send" -> Res.drawable.ic_arrow_up
        "back" -> Res.drawable.ic_arrow_left
        "down" -> Res.drawable.ic_alt_arrow_down
        "search" -> Res.drawable.ic_magnifer
        "folder" -> Res.drawable.ic_folder
        "file" -> Res.drawable.ic_document
        "settings" -> Res.drawable.ic_settings
        "close" -> Res.drawable.ic_close
        "stop" -> Res.drawable.ic_stop
        "time" -> Res.drawable.ic_clock_circle
        "check" -> Res.drawable.ic_check
        "copy" -> Res.drawable.ic_copy
        "retry" -> Res.drawable.ic_refresh
        "phone" -> Res.drawable.ic_smartphone
        "logout" -> Res.drawable.ic_logout_2
        "edit" -> Res.drawable.ic_pen
        "delete" -> Res.drawable.ic_trash_bin_minimalistic
        "chat" -> Res.drawable.ic_chat_round_line
        "calendar" -> Res.drawable.ic_calendar
        else -> Res.drawable.ic_bot
    }
    Icon(painterResource(resource), label, modifier, tint)
}

@Composable
fun IconAction(name: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(48.dp)) {
        Glyph(name, label, tint = LocalPalette.current.ink.copy(alpha = if (enabled) 1f else 0.35f))
    }
}

@Composable
fun BotMark(modifier: Modifier = Modifier.size(44.dp)) {
    val palette = LocalPalette.current
    Canvas(modifier) {
        val sx = size.width / 64f; val sy = size.height / 64f
        drawRoundRect(palette.accent, Offset(8 * sx, 15 * sy), Size(48 * sx, 34 * sy), CornerRadius(8 * sx))
        drawRoundRect(palette.background, Offset(20 * sx, 26 * sy), Size(7 * sx, 11 * sy), CornerRadius(1.5f * sx))
        drawRoundRect(palette.background, Offset(37 * sx, 26 * sy), Size(7 * sx, 11 * sy), CornerRadius(1.5f * sx))
    }
}

@Composable
fun BrandMark(brand: String, modifier: Modifier = Modifier.size(20.dp)) {
    val key = brand.lowercase()
    val icon = when (key) {
        "anthropic", "claude" -> Res.drawable.brand_anthropic
        "openai" -> Res.drawable.brand_openai
        "google", "gemini" -> Res.drawable.brand_google
        "deepseek" -> Res.drawable.brand_deepseek
        "moonshot", "kimi" -> Res.drawable.brand_moonshot
        "qwen", "alibaba" -> Res.drawable.brand_qwen
        "mistral" -> Res.drawable.brand_mistral
        "meta" -> Res.drawable.brand_meta
        "nvidia" -> Res.drawable.brand_nvidia
        "xai" -> Res.drawable.brand_xai
        "minimax" -> Res.drawable.brand_minimax
        "zhipu" -> Res.drawable.brand_zhipu
        else -> null
    }
    val dark = LocalPalette.current.dark
    val color = when (key) {
        "anthropic", "claude" -> if (dark) Color(0xFFE59A81) else Color(0xFFB85A40)
        "openai" -> if (dark) Color(0xFF65D6B6) else Color(0xFF0F886B)
        "google", "gemini", "deepseek", "meta", "zhipu" -> if (dark) Color(0xFF8EB1FF) else Color(0xFF396DE1)
        "qwen", "alibaba" -> if (dark) Color(0xFFBC9AFF) else Color(0xFF7446DC)
        "mistral" -> Color(0xFFE99636)
        "nvidia" -> if (dark) Color(0xFFA3D747) else Color(0xFF568000)
        "minimax" -> if (dark) Color(0xFFF58DAF) else Color(0xFFCF436F)
        else -> LocalPalette.current.ink
    }
    if (icon == null) BotMark(modifier) else Icon(painterResource(icon), null, modifier, color)
}

@Composable
fun Wallpaper(state: AppState, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val preferences = state.preferences
    val visible = preferences.wallpaper && (state.screen.name in preferences.wallpaperScreens || "all" in preferences.wallpaperScreens || !state.connected)
    BoxWithConstraints(modifier.fillMaxSize().background(palette.background)) {
        if (visible) {
            val height = if (preferences.fullWallpaper) maxHeight else maxHeight * 0.56f
            val source = rememberHazeState()
            val custom = remember(state.customWallpaper) { state.customWallpaper?.let(::decodeImage) }
            Box(Modifier.fillMaxWidth().height(height).hazeSource(source)) {
                if (custom == null) Image(painterResource(Res.drawable.wallpaper), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Image(custom, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = (preferences.wallpaperDim + if (palette.dark) 0.2f else 0f).coerceIn(0f, 0.85f))))
            }
            Box(Modifier.fillMaxWidth().height(height).hazeEffect(source) {
                blurRadius = preferences.wallpaperBlur.dp
                progressive = HazeProgressive.verticalGradient(startIntensity = 0f, endIntensity = 1f)
                backgroundColor = palette.background
                tints = emptyList()
                noiseFactor = 0f
            })
            Box(Modifier.fillMaxWidth().height(height).background(Brush.verticalGradient(listOf(Color.Transparent, palette.background.copy(alpha = 0.2f), palette.background), startY = 0f)))
        }
    }
}

expect fun decodeImage(bytes: ByteArray): ImageBitmap?

@Composable
fun GlassCard(modifier: Modifier = Modifier, radius: Dp = 24.dp, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Surface(modifier, shape = RoundedCornerShape(radius), color = p.surface, tonalElevation = 0.dp, shadowElevation = 2.dp, content = content)
}
