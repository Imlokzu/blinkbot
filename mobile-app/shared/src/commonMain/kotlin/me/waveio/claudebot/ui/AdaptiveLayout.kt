package me.waveio.claudebot.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal val ExpandedWindowMinWidth = 840.dp
internal val SidebarWidth = 280.dp
internal val ChatContentMaxWidth = 760.dp
internal val FormContentMaxWidth = 640.dp
internal val CompactPanelMaxWidth = 480.dp
internal val MediaContentMaxWidth = 1120.dp

/** Dimensions of the actual host, including when it occupies only part of a display. */
internal data class AdaptiveLayout(
    val width: Dp,
    val height: Dp,
    val connected: Boolean = true,
) {
    val persistentSidebar: Boolean get() = connected && width >= ExpandedWindowMinWidth
    val sidebarWidth: Dp get() = if (persistentSidebar) SidebarWidth else 0.dp
    val contentWidth: Dp get() = (width - sidebarWidth).coerceAtLeast(0.dp)
    val compactHeight: Boolean get() = height < 480.dp && width > height
    val insetPanels: Boolean get() = width >= 600.dp
}

internal val LocalAdaptiveLayout = compositionLocalOf { AdaptiveLayout(0.dp, 0.dp, false) }
