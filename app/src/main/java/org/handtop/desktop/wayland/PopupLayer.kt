package org.handtop.desktop.wayland

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Point
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver

/**
 * Draws the popups (menus, tooltips, combo lists) of every Linux window above all desktop
 * windows, so a menu can reach past the edge of its window, and routes their input.
 *
 * The layer covers the whole desktop but only takes input that lands on a popup — or, while
 * popups are open, a click elsewhere, which closes them like on a desktop. Everything else
 * falls through to the windows below.
 */
@SuppressLint("ViewConstructor")
class PopupLayer(context: Context, private val server: WaylandServer) : View(context) {

    private val windows = ArrayList<WaylandWindowContent>()
    private val here = IntArray(2)
    private val there = IntArray(2)
    private val lastOrigins = HashMap<WaylandWindowContent, Point>()
    /** Last position reported to each toplevel (logical), see [Toplevel.moved]. */
    private val reported = HashMap<WaylandWindowContent, Point>()

    /** Popup surface the current touch / button press belongs to. */
    private var pressTarget: WlSurface? = null
    private var hovering = false
    private var lastButton = BTN_LEFT

    // Follow window moves: open popups move along, X11 windows learn their new position.
    private val follow = ViewTreeObserver.OnPreDrawListener {
        reconstrainPopups()
        if (windows.any { it.toplevel.popups.isNotEmpty() && originOf(it) != lastOrigins[it] }) invalidate()
        reportPositions()
        true
    }

    /**
     * Open popups check their place before every frame: when the room around them changed
     * (the on-screen keyboard hid or showed, the window moved), they are placed again, so a
     * menu slid up to clear the keyboard goes back under its button once the keyboard is gone.
     * The keyboard often hides before the menu is first drawn, so this cannot wait for a change
     * seen here. A popup whose place stays the same costs one comparison.
     */
    private fun reconstrainPopups() {
        for (window in windows) {
            // Parents come first in the list, so a submenu is placed after the menu it hangs from.
            if (window.toplevel.popups.isNotEmpty()) window.toplevel.popups.toList().forEach { it.reconstrain() }
        }
    }

    private fun reportPositions() {
        val scale = server.scale
        for (window in windows) {
            val origin = originOf(window) ?: continue
            val logical = Point(Math.floorDiv(origin.x, scale), Math.floorDiv(origin.y, scale))
            if (reported[window] == logical) continue
            reported[window] = logical
            window.toplevel.moved(logical.x, logical.y)
        }
    }

    fun register(window: WaylandWindowContent) {
        if (window !in windows) windows += window
    }

    fun unregister(window: WaylandWindowContent) {
        windows -= window
        lastOrigins -= window
        reported -= window
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(follow)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(follow)
        super.onDetachedFromWindow()
    }

    /** Top-left of [window]'s content (its toplevel geometry origin) in this layer, or null if hidden. */
    fun originOf(window: WaylandWindowContent): Point? {
        if (!window.isAttachedToWindow || !window.isShown || !isAttachedToWindow) return null
        window.getLocationInWindow(there)
        getLocationInWindow(here)
        return Point(there[0] - here[0], there[1] - here[1])
    }

    // ---- drawing ----

    override fun onDraw(canvas: Canvas) {
        for (window in windows) {
            val origin = originOf(window) ?: continue
            lastOrigins[window] = origin
            val scale = server.scale
            for (popup in window.toplevel.popups) {
                val at = popup.originInToplevel
                window.drawSurface(canvas, popup.surface, popup.visibleGeometry(), origin.x + at.left * scale, origin.y + at.top * scale)
            }
        }
    }

    // ---- input ----

    private class Hit(val surface: WlSurface, val x: Double, val y: Double)

    /** The topmost popup under layer pixel (px, py), with the point in its surface coordinates. */
    private fun popupAt(px: Float, py: Float): Hit? {
        val scale = server.scale.toDouble()
        for (window in windows.asReversed()) {
            val origin = originOf(window) ?: continue
            val lx = (px - origin.x) / scale
            val ly = (py - origin.y) / scale
            for (popup in window.toplevel.popups.asReversed()) {
                if (!popup.takesInput) continue
                val o = popup.originInToplevel
                if (lx >= o.left && lx < o.right && ly >= o.top && ly < o.bottom) {
                    val g = popup.visibleGeometry()
                    val x = lx - o.left + g.left
                    val y = ly - o.top + g.top
                    val (surface, p) = popup.surface.surfaceAt(x, y) ?: (popup.surface to doubleArrayOf(x, y))
                    return Hit(surface, p[0], p[1])
                }
            }
        }
        return null
    }

    private fun anyPopups() = windows.any { w -> w.toplevel.popups.any { it.closesOnOutsideClick } }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val seat = server.seat
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = popupAt(event.x, event.y)
                if (hit == null) {
                    if (!anyPopups()) return false // not ours: the windows below get it
                    // A click outside the menus closes them (and is not passed on).
                    windows.forEach { w -> w.toplevel.popups.filter { it.closesOnOutsideClick }.forEach { it.dismiss() } }
                    return true
                }
                pressTarget = hit.surface
                seat.pointerMotion(hit.surface, hit.x, hit.y)
                seat.pointerButton(buttonOf(event), true)
            }
            MotionEvent.ACTION_MOVE -> pressTarget?.let { motion(it, event) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pressTarget?.let { motion(it, event) }
                if (pressTarget != null) seat.pointerButton(lastButton, false)
                pressTarget = null
            }
        }
        return true
    }

    /** While pressed, motion stays with the popup it started on (drag across a menu). */
    private fun motion(surface: WlSurface, event: MotionEvent) {
        val hit = popupAt(event.x, event.y)
        if (hit != null) server.seat.pointerMotion(hit.surface, hit.x, hit.y)
        else if (server.seat.pointerFocus === surface) Unit // keep focus; position outside is not reported
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        val hit = popupAt(event.x, event.y)
        if (hit == null || event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) {
            if (hovering) {
                hovering = false
                server.seat.pointerLeave()
            }
            return false // the window below handles its own hover
        }
        hovering = true
        server.seat.cursorView = this
        server.seat.pointerMotion(hit.surface, hit.x, hit.y)
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_SCROLL) return false
        val hit = popupAt(event.x, event.y) ?: return false
        server.seat.pointerMotion(hit.surface, hit.x, hit.y)
        server.seat.pointerAxis(
            -event.getAxisValue(MotionEvent.AXIS_HSCROLL) * SCROLL_STEP,
            -event.getAxisValue(MotionEvent.AXIS_VSCROLL) * SCROLL_STEP,
        )
        return true
    }

    /**
     * The pointer shape. ViewGroup asks only the topmost child under the pointer, and this layer
     * covers the whole desktop: outside the popups, answer for the views below (the windows,
     * their resize edges, the programs' own cursors), as if the layer were not there.
     */
    override fun onResolvePointerIcon(event: MotionEvent, pointerIndex: Int): PointerIcon? {
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)
        if (popupAt(x, y) != null) return pointerIcon // set by the Seat for the popup's program
        val parent = parent as? ViewGroup ?: return null
        for (i in parent.indexOfChild(this) - 1 downTo 0) {
            val below = parent.getChildAt(i)
            if (below.visibility != VISIBLE) continue
            val dx = left - below.left.toFloat()
            val dy = top - below.top.toFloat()
            if (x + dx < 0 || y + dy < 0 || x + dx >= below.width || y + dy >= below.height) continue
            val moved = MotionEvent.obtain(event)
            moved.offsetLocation(dx, dy)
            try {
                return below.onResolvePointerIcon(moved, pointerIndex)
            } finally {
                moved.recycle()
            }
        }
        return null
    }

    private fun buttonOf(event: MotionEvent): Int {
        lastButton = when {
            !event.isFromSource(InputDevice.SOURCE_MOUSE) -> BTN_LEFT
            event.buttonState and MotionEvent.BUTTON_SECONDARY != 0 -> BTN_RIGHT
            event.buttonState and MotionEvent.BUTTON_TERTIARY != 0 -> BTN_MIDDLE
            else -> BTN_LEFT
        }
        return lastButton
    }

    private companion object {
        const val BTN_LEFT = 0x110
        const val BTN_RIGHT = 0x111
        const val BTN_MIDDLE = 0x112
        const val SCROLL_STEP = 15.0
    }
}
