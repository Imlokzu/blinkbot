package me.waveio.claudebot.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

@Stable
internal class PreviewImageZoomState {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    private var viewport = IntSize.Zero
    private var image = IntSize.Zero

    fun updateGeometry(viewport: IntSize, image: IntSize) {
        this.viewport = viewport
        this.image = image
        offset = bounded(offset, scale)
    }

    fun transform(centroid: Offset, pan: Offset, zoom: Float) {
        if (!centroid.x.isFinite() || !centroid.y.isFinite() || !zoom.isFinite() || zoom <= 0f) return
        val nextScale = (scale * zoom).coerceIn(1f, 5f)
        val focus = centroid - Offset(viewport.width / 2f, viewport.height / 2f)
        // Keep the image point under the fingers stationary while scaling.
        offset = bounded((offset - focus) * (nextScale / scale) + focus + pan, nextScale)
        scale = nextScale
    }

    fun zoomTo(value: Float) = transform(Offset(viewport.width / 2f, viewport.height / 2f), Offset.Zero, value / scale)

    fun toggleZoom(position: Offset) {
        if (scale > 1f) zoomTo(1f) else transform(position, Offset.Zero, 3f)
    }

    private fun bounded(value: Offset, scale: Float): Offset {
        if (scale <= 1f || viewport.width <= 0 || viewport.height <= 0 || image.width <= 0 || image.height <= 0) return Offset.Zero
        val fit = min(viewport.width.toFloat() / image.width, viewport.height.toFloat() / image.height)
        // Constrain the actual fitted image, including portrait/landscape letterboxing.
        val x = ((image.width * fit * scale - viewport.width) / 2f).coerceAtLeast(0f)
        val y = ((image.height * fit * scale - viewport.height) / 2f).coerceAtLeast(0f)
        return Offset(value.x.coerceIn(-x, x), value.y.coerceIn(-y, y))
    }
}

@Composable
internal fun ZoomablePreviewImage(bitmap: ImageBitmap, description: String, zoom: PreviewImageZoomState, modifier: Modifier = Modifier) {
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    SideEffect { zoom.updateGeometry(viewport, IntSize(bitmap.width, bitmap.height)) }
    val zoomDescription = tr("media.zoomLevel", "percent" to (zoom.scale * 100).roundToInt())
    val zoomIn = tr("media.zoomIn")
    val zoomOut = tr("media.zoomOut")
    val resetZoom = tr("media.resetZoom")
    Box(modifier.clipToBounds().onSizeChanged { viewport = it }
        .semantics(mergeDescendants = true) {
            stateDescription = zoomDescription
            customActions = buildList {
                if (zoom.scale < 5f) add(CustomAccessibilityAction(zoomIn) { zoom.zoomTo(zoom.scale * 2f); true })
                if (zoom.scale > 1f) {
                    add(CustomAccessibilityAction(zoomOut) { zoom.zoomTo(zoom.scale / 2f); true })
                    add(CustomAccessibilityAction(resetZoom) { zoom.zoomTo(1f); true })
                }
            }
        }
        .pointerInput(zoom) { detectPreviewTransforms(zoom) }
        .pointerInput(zoom) { detectTapGestures(onDoubleTap = zoom::toggleZoom) }) {
        Image(bitmap, description, Modifier.fillMaxSize().graphicsLayer {
            scaleX = zoom.scale
            scaleY = zoom.scale
            translationX = zoom.offset.x
            translationY = zoom.offset.y
        }, contentScale = ContentScale.Fit)
    }
}

private suspend fun PointerInputScope.detectPreviewTransforms(zoom: PreviewImageZoomState) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var claimed = false
        var accumulatedZoom = 1f
        var accumulatedPan = Offset.Zero
        do {
            val event = awaitPointerEvent()
            if (event.changes.any { it.isConsumed }) break
            val zoomChange = event.calculateZoom()
            val pan = event.calculatePan()
            if (!claimed) {
                accumulatedZoom *= zoomChange
                accumulatedPan += pan
                val zoomMotion = abs(1f - accumulatedZoom) * event.calculateCentroidSize(useCurrent = false)
                val panMotion = accumulatedPan.getDistance()
                val multiplePointers = event.changes.count { it.pressed } > 1
                // A fitted image leaves one-finger swipes to the gallery. Once a
                // pinch owns the gesture, keep it until all fingers are lifted.
                claimed = (multiplePointers && (zoomMotion > viewConfiguration.touchSlop || panMotion > viewConfiguration.touchSlop)) ||
                    (zoom.scale > 1f && panMotion > viewConfiguration.touchSlop)
            }
            if (claimed) {
                zoom.transform(event.calculateCentroid(useCurrent = false), pan, zoomChange)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        } while (event.changes.any { it.pressed })
    }
}
