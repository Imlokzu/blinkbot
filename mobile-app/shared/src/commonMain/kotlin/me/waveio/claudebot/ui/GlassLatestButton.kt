package me.waveio.claudebot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/** Sample only the small area beneath this control from the history recording. */
@Composable
internal fun GlassLatestButton(source: GraphicsLayer, sourceOrigin: Offset, modifier: Modifier, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val label = tr("chat.latest")
    val blurred = rememberGraphicsLayer()
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(modifier.size(48.dp).testTag("chat-latest")
        .onGloballyPositioned { origin = it.positionInRoot() }
        .clip(CircleShape).drawWithCache {
            val radius = 10.dp.toPx()
            val padding = ceil(radius * 2).toInt()
            val effect = BlurEffect(radius, radius, TileMode.Clamp)
            val offset = origin - sourceOrigin
            blurred.renderEffect = effect
            onDrawBehind {
                if (effect.isSupported()) {
                    blurred.record(size = IntSize(ceil(size.width).toInt() + padding * 2, ceil(size.height).toInt() + padding * 2)) {
                        translate(padding - offset.x, padding - offset.y) { drawLayer(source) }
                    }
                    translate(-padding.toFloat(), -padding.toFloat()) { drawLayer(blurred) }
                }
            }
        }.background(palette.surface.copy(alpha = .82f)).border(1.dp, palette.ink.copy(alpha = .10f), CircleShape)
        .semantics { contentDescription = label }
        .clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Glyph("send", modifier = Modifier.size(22.dp).graphicsLayer { rotationZ = 180f }, tint = palette.ink)
    }
}
