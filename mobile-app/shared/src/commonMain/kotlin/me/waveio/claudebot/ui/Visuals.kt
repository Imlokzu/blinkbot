package me.waveio.claudebot.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.jetbrains.compose.resources.Font
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.waveio.claudebot.resources.*
import me.waveio.claudebot.state.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

@Immutable
data class Palette(val background: Color, val surface: Color, val secondary: Color, val ink: Color, val muted: Color, val line: Color, val accent: Color, val dark: Boolean) {
    val userBubble: Color get() = if (dark) Color(0xFF40362E) else Color(0xFF29241F)
    val userInk: Color get() = Color(0xFFFFFDF8)
    val botBubble: Color get() = if (dark) Color(0xFF25211D) else Color(0xFFFFFDF8)
}
private val DayPalette = Palette(Color(0xFFF5F1EA), Color(0xFFFFFDF8), Color(0xFFEFE9DF), Color(0xFF231E19), Color(0xFF797066), Color(0xFFE1D7CA), Color(0xFFB95F3D), false)
private val NightPalette = Palette(Color(0xFF13110F), Color(0xFF1A1715), Color(0xFF221E1B), Color(0xFFF2ECE5), Color(0xFFABA096), Color(0xFF3A332C), Color(0xFFDA956E), true)
val LocalPalette = staticCompositionLocalOf { DayPalette }

@Composable
fun MobileTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) NightPalette else DayPalette
    val regular = Font(Res.font.manrope_regular)
    val semibold = Font(Res.font.manrope_semibold, FontWeight.SemiBold)
    val body = remember(regular, semibold) { FontFamily(regular, semibold) }
    val typography = remember(body) { val type = Typography(); type.copy(
        bodyLarge = type.bodyLarge.copy(fontFamily = body), bodyMedium = type.bodyMedium.copy(fontFamily = body), bodySmall = type.bodySmall.copy(fontFamily = body),
        titleLarge = type.titleLarge.copy(fontFamily = body), titleMedium = type.titleMedium.copy(fontFamily = body), titleSmall = type.titleSmall.copy(fontFamily = body),
        labelLarge = type.labelLarge.copy(fontFamily = body), labelMedium = type.labelMedium.copy(fontFamily = body), labelSmall = type.labelSmall.copy(fontFamily = body),
        headlineSmall = type.headlineSmall.copy(fontFamily = body), headlineMedium = type.headlineMedium.copy(fontFamily = body), headlineLarge = type.headlineLarge.copy(fontFamily = body),
    ) }
    val scheme = remember(colors) { (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = colors.accent, onPrimary = Color.White,
        secondary = colors.accent, secondaryContainer = colors.secondary,
        onSecondaryContainer = colors.ink, background = colors.background,
        surface = colors.surface, onSurface = colors.ink, onBackground = colors.ink,
        outline = colors.muted, outlineVariant = colors.line,
    ) }
    CompositionLocalProvider(LocalPalette provides colors, LocalContentColor provides colors.ink) { MaterialTheme(colorScheme = scheme, typography = typography, content = content) }
}

@Composable
fun Glyph(name: String, label: String? = null, modifier: Modifier = Modifier.size(21.dp), tint: Color = LocalPalette.current.ink) {
    val resource: DrawableResource = when (name) {
        "menu" -> Res.drawable.ic_sidebar_minimalistic_left
        "new" -> Res.drawable.ic_pen_new_square
        "attach" -> Res.drawable.ic_paperclip
        "camera" -> Res.drawable.ic_camera
        "photo" -> Res.drawable.ic_gallery
        "skills" -> Res.drawable.ic_magic_stick
        "effort" -> Res.drawable.ic_tuning
        "mic" -> Res.drawable.ic_microphone
        "send" -> Res.drawable.ic_arrow_up
        "back" -> Res.drawable.ic_arrow_left
        "down" -> Res.drawable.ic_alt_arrow_down
        "download" -> Res.drawable.ic_download_minimalistic
        "search" -> Res.drawable.ic_magnifer
        "folder" -> Res.drawable.ic_folder
        "file" -> Res.drawable.ic_document
        "settings" -> Res.drawable.ic_settings
        "close" -> Res.drawable.ic_close
        "stop" -> Res.drawable.ic_stop
        "time" -> Res.drawable.ic_clock_circle
        "check" -> Res.drawable.ic_check
        "share" -> Res.drawable.ic_share
        "select" -> Res.drawable.ic_select_text
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
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && !LocalReducedMotion.current) .9f else 1f, spring(stiffness = 850f), label = "iconPress")
    Box(modifier.size(48.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(RoundedCornerShape(15.dp))
        .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
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
fun Wallpaper(preferences: Preferences, wallpaper: ByteArray?, screen: Screen, connected: Boolean, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val visible = preferences.wallpaper && (screen.name in preferences.wallpaperScreens || "all" in preferences.wallpaperScreens || !connected)
    BoxWithConstraints(modifier.fillMaxSize().background(palette.background)) {
        if (visible) {
            val height = if (preferences.fullWallpaper) maxHeight else maxHeight * 0.56f
            val custom = remember(wallpaper) { wallpaper?.let { decodeImage(it) } }
            Box(Modifier.fillMaxWidth().height(height)) {
                WallpaperImage(custom, Modifier.fillMaxSize())
                if (preferences.wallpaperBlur > 0f) {
                    // Local cached layers do not track window coordinates while the
                    // foreground chat moves. Blend increasing radii down the image.
                    WallpaperImage(custom, Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithCache {
                            val mask = Brush.verticalGradient(listOf(Color.Transparent, Color.White), startY = size.height * .08f, endY = size.height * .57f)
                            onDrawWithContent { drawContent(); drawRect(mask, blendMode = BlendMode.DstIn) }
                        }.blur((preferences.wallpaperBlur * .45f).dp))
                    WallpaperImage(custom, Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithCache {
                            val mask = Brush.verticalGradient(listOf(Color.Transparent, Color.White), startY = size.height * .44f, endY = size.height * .9f)
                            onDrawWithContent { drawContent(); drawRect(mask, blendMode = BlendMode.DstIn) }
                        }.blur(preferences.wallpaperBlur.dp))
                }
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = (preferences.wallpaperDim + if (palette.dark) 0.2f else 0f).coerceIn(0f, 0.85f))))
            }
            Box(Modifier.fillMaxWidth().height(height).background(Brush.verticalGradient(listOf(Color.Transparent, palette.background.copy(alpha = 0.2f), palette.background), startY = 0f)))
        }
    }
}

@Composable
private fun WallpaperImage(custom: ImageBitmap?, modifier: Modifier) {
    if (custom == null) Image(painterResource(Res.drawable.wallpaper), null, modifier, contentScale = ContentScale.Crop)
    else Image(custom, null, modifier, contentScale = ContentScale.Crop)
}

expect fun decodeImage(bytes: ByteArray, maxDimension: Int = 2048): ImageBitmap?

/** Encode a bounded cache image; export always retains the original file. */
expect fun thumbnailBytes(bytes: ByteArray): ByteArray?

@Composable
fun GlassCard(modifier: Modifier = Modifier, radius: Dp = 24.dp, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Surface(modifier, shape = RoundedCornerShape(radius), color = p.surface, tonalElevation = 0.dp, shadowElevation = 2.dp, content = content)
}
