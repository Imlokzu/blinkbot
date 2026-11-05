package me.waveio.claudebot.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.CompositingStrategy as LayerCompositingStrategy
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/** Soften the complete backdrop beneath controls while keeping the reading area sharp. */
@Composable
fun Modifier.chatEdges(topPanel: Dp, bottomPanel: Dp, backdrop: GraphicsLayer, backdropOffset: () -> Offset): Modifier {
    val currentOffset = rememberUpdatedState(backdropOffset)
    val content = rememberGraphicsLayer()
    val topBlur = rememberGraphicsLayer()
    val topResult = rememberGraphicsLayer()
    val bottomBlur = rememberGraphicsLayer()
    val bottomResult = rememberGraphicsLayer()
    return then(remember(topPanel, bottomPanel, backdrop, content, topBlur, topResult, bottomBlur, bottomResult) {
        Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithCache {
            val top = topPanel.toPx().coerceAtMost(size.height * .4f)
            val bottom = bottomPanel.toPx().coerceAtMost(size.height * .5f)
            val topStart = (top - 36.dp.toPx()).coerceAtLeast(0f)
            val topEnd = top.coerceAtLeast(1f)
            val bottomStart = (size.height - bottom + 24.dp.toPx()).coerceIn(0f, (size.height - 1f).coerceAtLeast(0f))
            val bottomEnd = (bottomStart + 48.dp.toPx()).coerceAtMost(size.height).coerceAtLeast(bottomStart + 1f)
            fun ramp(reverse: Boolean, color: Color = Color.White, strength: Float = 1f) = Array(25) { index ->
                val t = index / 24f
                val smooth = t * t * t * (t * (t * 6f - 15f) + 10f)
                t to color.copy(alpha = strength * if (reverse) 1f - smooth else smooth)
            }
            val topSharp = Brush.verticalGradient(*ramp(false), startY = topStart, endY = topEnd)
            val topSoft = Brush.verticalGradient(*ramp(true), startY = topStart, endY = topEnd)
            val bottomSharp = Brush.verticalGradient(*ramp(true), startY = bottomStart, endY = bottomEnd)
            val topShade = Brush.verticalGradient(*ramp(true, Color.Black, .16f), startY = topStart, endY = topEnd)
            val bottomShade = Brush.verticalGradient(*ramp(false, Color.Black, .16f), startY = bottomStart, endY = bottomEnd)
            val radius = 8.dp.toPx()
            val effect = BlurEffect(radius, radius, TileMode.Clamp)
            val topSize = IntSize(ceil(size.width).toInt().coerceAtLeast(1), ceil((topEnd + radius * 2).coerceAtMost(size.height)).toInt().coerceAtLeast(1))
            val bottomY = (bottomStart - radius * 2).coerceAtLeast(0f)
            val bottomSize = IntSize(topSize.width, ceil(size.height - bottomY).toInt().coerceAtLeast(1))
            val bottomSoft = Brush.verticalGradient(*ramp(false), startY = bottomStart - bottomY, endY = bottomEnd - bottomY)
            topBlur.renderEffect = effect
            bottomBlur.renderEffect = effect
            topResult.compositingStrategy = LayerCompositingStrategy.Offscreen
            bottomResult.compositingStrategy = LayerCompositingStrategy.Offscreen
            // Composite the complete strip over transparent gaps between bubbles.
            topResult.blendMode = BlendMode.SrcOver
            bottomResult.blendMode = BlendMode.SrcOver
            onDrawWithContent {
                content.record { this@onDrawWithContent.drawContent() }
                drawLayer(content)
                if (effect.isSupported()) {
                    // Sample wallpaper only for the strips; the shell retains its
                    // full-window sizing when the keyboard reduces chat height.
                    val offset = currentOffset.value()
                    topBlur.record(size = topSize) {
                        translate(-offset.x, -offset.y) { drawLayer(backdrop) }
                        drawLayer(content)
                    }
                    topResult.record(size = topSize) {
                        drawLayer(topBlur)
                        drawRect(topSoft, blendMode = BlendMode.DstIn)
                    }
                    drawLayer(topResult)
                    bottomBlur.record(size = bottomSize) {
                        translate(top = -bottomY) {
                            translate(-offset.x, -offset.y) { drawLayer(backdrop) }
                            drawLayer(content)
                        }
                    }
                    bottomResult.record(size = bottomSize) {
                        drawLayer(bottomBlur)
                        drawRect(bottomSoft, blendMode = BlendMode.DstIn)
                    }
                    translate(top = bottomY) { drawLayer(bottomResult) }
                } else {
                    // Older renderers still keep the controls legible with a
                    // narrow fade entirely inside their panels.
                    drawRect(topSharp, blendMode = BlendMode.DstIn)
                    drawRect(bottomSharp, blendMode = BlendMode.DstIn)
                }
                drawRect(topShade)
                drawRect(bottomShade)
            }
        }
    })
}
