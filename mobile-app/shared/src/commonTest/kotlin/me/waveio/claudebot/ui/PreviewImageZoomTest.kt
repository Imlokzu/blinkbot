package me.waveio.claudebot.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.test.*

class PreviewImageZoomTest {
    private fun state(viewport: IntSize = IntSize(400, 600), image: IntSize = viewport) = PreviewImageZoomState().apply {
        updateGeometry(viewport, image)
    }

    @Test fun pinchKeepsTheOffCenterImagePointUnderTheFingers() {
        val zoom = state()
        zoom.transform(Offset(250f, 400f), Offset(20f, -10f), 2f)
        assertEquals(2f, zoom.scale)
        assertEquals(Offset(-30f, -110f), zoom.offset)
    }

    @Test fun zoomLimitsAndResetKeepTheWholeImageRecoverable() {
        val zoom = state()
        zoom.transform(Offset(250f, 400f), Offset(10_000f, -10_000f), 100f)
        assertEquals(5f, zoom.scale)
        assertEquals(Offset(800f, -1200f), zoom.offset)
        zoom.zoomTo(.1f)
        assertEquals(1f, zoom.scale)
        assertEquals(Offset.Zero, zoom.offset)
    }

    @Test fun wideImageCannotBeDraggedIntoItsVerticalLetterbox() {
        val zoom = state(image = IntSize(1200, 400))
        zoom.transform(Offset(200f, 300f), Offset(10_000f, 10_000f), 2f)
        assertEquals(Offset(200f, 0f), zoom.offset)
    }

    @Test fun portraitImageCannotBeDraggedIntoItsHorizontalLetterbox() {
        val zoom = state(IntSize(600, 400), IntSize(400, 1200))
        zoom.transform(Offset(300f, 200f), Offset(-10_000f, -10_000f), 2f)
        assertEquals(0f, zoom.offset.x, .001f)
        assertEquals(-200f, zoom.offset.y, .001f)
    }

    @Test fun doubleTapZoomsAtTheTapThenRestoresFit() {
        val zoom = state()
        zoom.toggleZoom(Offset(250f, 400f))
        assertEquals(3f, zoom.scale)
        assertEquals(Offset(-100f, -200f), zoom.offset)
        zoom.toggleZoom(Offset(10f, 10f))
        assertEquals(1f, zoom.scale)
        assertEquals(Offset.Zero, zoom.offset)
    }

    @Test fun resizingTheViewportReclampsThePanToTheNewImageBounds() {
        val zoom = state()
        zoom.transform(Offset(200f, 300f), Offset(10_000f, 10_000f), 2f)
        zoom.updateGeometry(IntSize(800, 400), IntSize(400, 600))
        assertEquals(Offset(0f, 200f), zoom.offset)
        assertEquals(2f, zoom.scale)
    }
}
