package org.handtop.desktop.wayland

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import org.handtop.desktop.wm.DesktopWindow.Companion.EDGE_BOTTOM
import org.handtop.desktop.wm.DesktopWindow.Companion.EDGE_LEFT
import org.handtop.desktop.wm.DesktopWindow.Companion.EDGE_RIGHT
import org.handtop.desktop.wm.DesktopWindow.Companion.EDGE_TOP
import org.handtop.desktop.wm.WindowContent
import org.handtop.desktop.wm.WindowHost

/**
 * Shows a Linux toplevel (and its popups) inside a desktop window, and feeds it pointer and
 * keyboard input. 1 logical Wayland unit = [WaylandServer.scale] view pixels.
 */
@SuppressLint("ViewConstructor")
class WaylandWindowContent(
    context: Context,
    val toplevel: XdgToplevel,
    private val windowControl: WindowControl,
) : View(context), WindowContent, ToplevelView {

    /** What the content may ask from its desktop window beyond [WindowHost]. Raw = screen pixels. */
    interface WindowControl {
        fun beginMove(rawX: Float, rawY: Float)
        fun moveTo(rawX: Float, rawY: Float)
        fun endMove(rawX: Float, rawY: Float, cancelled: Boolean)
        /** [edges]: DesktopWindow.EDGE_* flags. */
        fun beginResize(edges: Int, rawX: Float, rawY: Float)
        fun resizeTo(rawX: Float, rawY: Float)
        fun endResize()
        fun setDecorated(decorated: Boolean)
        fun setMaximized(maximized: Boolean)
        fun minimize()
        fun isMaximized(): Boolean
        fun isActive(): Boolean
    }

    /** A move or resize the program started from its own title bar / border. */
    private enum class Grab { NONE, MOVE, RESIZE }

    private var grab = Grab.NONE
    private var pressed = false
    private var lastRawX = 0f
    private var lastRawY = 0f

    override val view: View get() = this

    private val server = toplevel.client.server
    /** Follows the server: the interface scale can change while the window is open. */
    private val scale get() = server.scale
    private var host: WindowHost? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val src = Rect()
    private val dst = Rect()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.BLACK)
        toplevel.view = this
        toplevel.xdg.surface.onCommit = { invalidate() }
    }

    // ---- WindowContent ----

    override fun attach(host: WindowHost) {
        this.host = host
        titleChanged()
    }

    override fun onActivated() {
        requestFocus()
        server.seat.setKeyboardFocus(toplevel.xdg.surface)
        sendState()
    }

    override fun onDeactivated() {
        if (server.seat.keyboardFocus === toplevel.xdg.surface) server.seat.setKeyboardFocus(null)
        sendState()
    }

    override fun onCloseRequest(): Boolean {
        toplevel.requestClose()
        return true // the window goes away when the client unmaps it
    }

    override fun onClosed() {
        if (toplevel.view === this) toplevel.view = null
        toplevel.xdg.surface.onCommit = null
        if (server.seat.keyboardFocus === toplevel.xdg.surface) server.seat.setKeyboardFocus(null)
    }

    // ---- ToplevelView ----

    override fun titleChanged() {
        host?.setTitle(toplevel.title.ifEmpty { toplevel.appId.ifEmpty { "Linux" } })
    }

    override fun contentChanged() = invalidate()
    override fun setMaximized(maximized: Boolean) = windowControl.setMaximized(maximized)
    override fun minimize() = windowControl.minimize()

    override fun decorationsChanged() = windowControl.setDecorated(toplevel.serverSideDecorations)

    override fun startMove() {
        if (!pressed || grab != Grab.NONE) return
        grab = Grab.MOVE
        server.seat.pointerLeave() // the window manager owns the pointer until release
        windowControl.beginMove(lastRawX, lastRawY)
    }

    override fun startResize(edges: Int) {
        if (!pressed || grab != Grab.NONE) return
        var flags = 0
        if (edges and XDG_EDGE_TOP != 0) flags = flags or EDGE_TOP
        if (edges and XDG_EDGE_BOTTOM != 0) flags = flags or EDGE_BOTTOM
        if (edges and XDG_EDGE_LEFT != 0) flags = flags or EDGE_LEFT
        if (edges and XDG_EDGE_RIGHT != 0) flags = flags or EDGE_RIGHT
        if (flags == 0) return
        grab = Grab.RESIZE
        server.seat.pointerLeave()
        windowControl.beginResize(flags, lastRawX, lastRawY)
    }

    /** Feeds a grabbed gesture to the window manager; true while a grab is active. */
    private fun handleGrab(event: MotionEvent): Boolean {
        if (grab == Grab.NONE) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE ->
                if (grab == Grab.MOVE) windowControl.moveTo(event.rawX, event.rawY)
                else windowControl.resizeTo(event.rawX, event.rawY)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // CANCEL: e.g. the system took the touch for the notification shade.
                val cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL
                if (grab == Grab.MOVE) windowControl.endMove(event.rawX, event.rawY, cancelled) else windowControl.endResize()
                grab = Grab.NONE
                pressed = false
            }
        }
        return true
    }

    /** Tells the client our size and state (after layout changes or activation). */
    fun sendState() {
        if (width == 0 || height == 0) return
        toplevel.requestState(width / scale, height / scale, windowControl.isActive(), windowControl.isMaximized())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        sendState()
    }

    // ---- drawing ----

    override fun onDraw(canvas: Canvas) {
        drawSurface(canvas, toplevel.xdg, 0, 0)
        for (popup in toplevel.popups) {
            val origin = popup.originInToplevel
            drawSurface(canvas, popup.xdg, origin.left * scale, origin.top * scale)
        }
    }

    /** Draws the window-geometry part of [xdg] with its top-left at view pixel ([x], [y]). */
    private fun drawSurface(canvas: Canvas, xdg: XdgSurface, x: Int, y: Int) {
        val surface = xdg.surface
        val bitmap = surface.bitmap ?: return
        val g = xdg.visibleGeometry()
        val bs = surface.bufferScale
        src.set(g.left * bs, g.top * bs, g.right * bs, g.bottom * bs)
        dst.set(x, y, x + g.width() * scale, y + g.height() * scale)
        canvas.drawBitmap(bitmap, src, dst, paint)
    }

    // ---- input ----

    /** The surface under view pixel (px, py) and the point in its surface coordinates. */
    private fun hit(px: Float, py: Float): Pair<WlSurface, DoubleArray>? {
        val lx = px / scale.toDouble()
        val ly = py / scale.toDouble()
        for (popup in toplevel.popups.asReversed()) {
            val o = popup.originInToplevel
            if (lx >= o.left && lx < o.right && ly >= o.top && ly < o.bottom) {
                val g = popup.xdg.visibleGeometry()
                return popup.xdg.surface to doubleArrayOf(lx - o.left + g.left, ly - o.top + g.top)
            }
        }
        val g = toplevel.xdg.visibleGeometry()
        return toplevel.xdg.surface to doubleArrayOf(lx + g.left, ly + g.top)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        lastRawX = event.rawX
        lastRawY = event.rawY
        if (handleGrab(event)) return true
        val seat = server.seat
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = true
                requestFocus()
                // Alt + drag moves any Linux window, even one without a title bar.
                if (event.metaState and KeyEvent.META_ALT_ON != 0) {
                    startMove()
                    return true
                }
                // A click outside the open popups closes them, like on a desktop.
                val target = hit(event.x, event.y)
                if (target != null && target.first === toplevel.xdg.surface && toplevel.popups.isNotEmpty()) {
                    toplevel.popups.toList().forEach { it.dismiss() }
                }
                motion(event)
                seat.pointerButton(buttonOf(event), true)
            }
            MotionEvent.ACTION_MOVE -> motion(event)
            MotionEvent.ACTION_UP -> {
                pressed = false
                motion(event)
                seat.pointerButton(lastButton, false)
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = false
                seat.pointerButton(lastButton, false)
            }
        }
        return true
    }

    private var lastButton = BTN_LEFT

    private fun buttonOf(event: MotionEvent): Int {
        lastButton = when {
            !event.isFromSource(InputDevice.SOURCE_MOUSE) -> BTN_LEFT
            event.buttonState and MotionEvent.BUTTON_SECONDARY != 0 -> BTN_RIGHT
            event.buttonState and MotionEvent.BUTTON_TERTIARY != 0 -> BTN_MIDDLE
            else -> BTN_LEFT
        }
        return lastButton
    }

    private fun motion(event: MotionEvent) {
        val (surface, p) = hit(event.x, event.y) ?: return
        server.seat.pointerMotion(surface, p[0], p[1])
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> motion(event)
            MotionEvent.ACTION_HOVER_EXIT -> server.seat.pointerLeave()
            MotionEvent.ACTION_SCROLL -> {
                motion(event)
                server.seat.pointerAxis(
                    -event.getAxisValue(MotionEvent.AXIS_HSCROLL) * SCROLL_STEP,
                    -event.getAxisValue(MotionEvent.AXIS_VSCROLL) * SCROLL_STEP,
                )
            }
            else -> return super.onGenericMotionEvent(event)
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        server.seat.key(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        server.seat.key(event) || super.onKeyUp(keyCode, event)

    private companion object {
        // xdg_toplevel.resize_edge bits
        const val XDG_EDGE_TOP = 1
        const val XDG_EDGE_BOTTOM = 2
        const val XDG_EDGE_LEFT = 4
        const val XDG_EDGE_RIGHT = 8

        const val BTN_LEFT = 0x110
        const val BTN_RIGHT = 0x111
        const val BTN_MIDDLE = 0x112
        const val SCROLL_STEP = 15.0
    }
}
