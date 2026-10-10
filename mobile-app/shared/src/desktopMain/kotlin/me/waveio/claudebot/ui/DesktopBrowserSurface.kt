package me.waveio.claudebot.ui

import com.jetbrains.cef.SharedMemoryCache
import org.cef.browser.CefBrowser
import org.cef.handler.CefNativeRenderHandler
import org.cef.handler.CefRenderHandlerAdapter
import org.cef.handler.CefScreenInfo
import java.awt.*
import java.awt.event.*
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.nio.ByteOrder
import javax.swing.JPanel
import javax.swing.SwingUtilities

/** Remote JCEF paints shared-memory frames into an ordinary Swing surface. */
internal class DesktopBrowserSurface : JPanel() {
    var browser: CefBrowser? = null
    @Volatile private var image: BufferedImage? = null
    private val frames = SharedMemoryCache()
    @Volatile private var popupBounds = Rectangle()
    @Volatile private var popupImage: BufferedImage? = null
    @Volatile private var alive = true
    @Volatile private var viewBounds = Rectangle(0, 0, 800, 600)
    @Volatile private var screenBounds = Rectangle(0, 0, 1920, 1080)
    @Volatile private var screenPosition = Point()
    @Volatile private var scale = 1.0
    internal val paintedFrame: BufferedImage? get() = image
    val renderer: CefNativeRenderHandler = object : CefRenderHandlerAdapter(), CefNativeRenderHandler {
        override fun getViewRect(browser: CefBrowser) = Rectangle(viewBounds)
        override fun getDeviceScaleFactor(browser: CefBrowser) = scale
        override fun getScreenInfo(browser: CefBrowser, info: CefScreenInfo): Boolean {
            info.Set(scale, 32, 4, false, Rectangle(screenBounds), Rectangle(screenBounds))
            return true
        }
        override fun getScreenPoint(browser: CefBrowser, point: Point) =
            Point(screenPosition.x + point.x, screenBounds.height - screenPosition.y - point.y)
        override fun onPopupSize(browser: CefBrowser, size: Rectangle) { popupBounds = Rectangle(size) }
        override fun onPopupShow(browser: CefBrowser, show: Boolean) {
            if (!show) popupImage = null
            schedulePaint()
        }
        override fun onPaintWithSharedMem(browser: CefBrowser, popup: Boolean, dirtyRects: Int, name: String,
            handle: Long, rasterWidth: Int, rasterHeight: Int) {
            if (!alive || rasterWidth <= 0 || rasterHeight <= 0 || rasterWidth.toLong() * rasterHeight > 16_777_216) return
            val memory = frames.get(name, handle)
            memory.width = rasterWidth; memory.height = rasterHeight; memory.dirtyRectsCount = dirtyRects
            val frame = BufferedImage(rasterWidth, rasterHeight, BufferedImage.TYPE_INT_ARGB_PRE)
            val target = (frame.raster.dataBuffer as DataBufferInt).data
            memory.wrapRaster().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(target)
            if (popup) popupImage = frame else image = frame
            schedulePaint()
        }
        override fun disposeNativeResources() { alive = false; image = null; popupImage = null }
        override fun onCursorChange(browser: CefBrowser, type: Int): Boolean {
            SwingUtilities.invokeLater { cursor = runCatching { Cursor.getPredefinedCursor(type) }.getOrDefault(Cursor.getDefaultCursor()) }
            return true
        }
    }
    init {
        preferredSize = Dimension(800, 600)
        setSize(800, 600)
        background = Color.WHITE
        isFocusable = true
        focusTraversalKeysEnabled = false
        enableEvents(AWTEvent.KEY_EVENT_MASK or AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_MOTION_EVENT_MASK or AWTEvent.MOUSE_WHEEL_EVENT_MASK)
        addFocusListener(object : FocusAdapter() {
            override fun focusGained(event: FocusEvent) { browser?.setFocus(true) }
            override fun focusLost(event: FocusEvent) { browser?.setFocus(false) }
        })
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) { updateGeometry(); browser?.wasResized(viewBounds.width, viewBounds.height) }
            override fun componentMoved(event: ComponentEvent) { updateGeometry() }
        })
        addHierarchyBoundsListener(object : HierarchyBoundsAdapter() {
            override fun ancestorMoved(event: HierarchyEvent) { updateGeometry(); browser?.notifyScreenInfoChanged() }
            override fun ancestorResized(event: HierarchyEvent) { updateGeometry() }
        })
    }
    private fun updateGeometry() {
        viewBounds = Rectangle(0, 0, width.coerceAtLeast(1), height.coerceAtLeast(1))
        graphicsConfiguration?.let { config -> scale = config.defaultTransform.scaleX; screenBounds = Rectangle(config.bounds) }
        if (isShowing) screenPosition = runCatching { locationOnScreen }.getOrDefault(screenPosition)
    }
    private fun schedulePaint() { SwingUtilities.invokeLater { if (alive) repaint() } }
    override fun addNotify() {
        super.addNotify()
        SwingUtilities.invokeLater { updateGeometry(); browser?.notifyScreenInfoChanged(); browser?.createImmediately() }
    }
    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        // Chromium's default page canvas is transparent in remote OSR.
        // Composite it over the ordinary white browser page, even in dark app chrome.
        graphics.color = background
        graphics.fillRect(0, 0, width, height)
        image?.let { graphics.drawImage(it, 0, 0, width, height, null) }
        popupImage?.let { graphics.drawImage(it, popupBounds.x, popupBounds.y, popupBounds.width, popupBounds.height, null) }
    }
    override fun processKeyEvent(event: KeyEvent) { browser?.sendKeyEvent(event); event.consume() }
    override fun processMouseEvent(event: MouseEvent) {
        if (event.id == MouseEvent.MOUSE_PRESSED) requestFocusInWindow()
        browser?.sendMouseEvent(event)
    }
    override fun processMouseMotionEvent(event: MouseEvent) { browser?.sendMouseEvent(event) }
    override fun processMouseWheelEvent(event: MouseWheelEvent) {
        browser?.sendMouseWheelEvent(MouseWheelEvent(this, event.id, event.`when`, event.modifiersEx, event.x, event.y,
            event.xOnScreen, event.yOnScreen, event.clickCount, event.isPopupTrigger, event.scrollType,
            event.scrollAmount, -event.wheelRotation, -event.preciseWheelRotation))
    }
}
