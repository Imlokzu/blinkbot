package me.waveio.claudebot.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Fade the scrolling content itself; never paint a rectangle over the wallpaper. */
fun Modifier.chatEdges(topPanel: Dp, bottomPanel: Dp): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithCache {
        val top = topPanel.toPx().coerceAtMost(size.height * .4f)
        val bottom = bottomPanel.toPx().coerceAtMost(size.height * .5f)
        val soften = 16.dp.toPx()
        val topMask = Brush.verticalGradient(
            0f to Color.Transparent, .65f to Color.White.copy(alpha = .12f), 1f to Color.White,
            startY = 0f, endY = top + soften,
        )
        val bottomMask = Brush.verticalGradient(
            0f to Color.White, .55f to Color.White.copy(alpha = .16f), 1f to Color.Transparent,
            startY = (size.height - bottom - soften).coerceAtLeast(0f),
            endY = (size.height - bottom * .35f).coerceAtLeast(1f),
        )
        onDrawWithContent {
            drawContent()
            drawRect(topMask, blendMode = BlendMode.DstIn)
            drawRect(bottomMask, blendMode = BlendMode.DstIn)
        }
    }
