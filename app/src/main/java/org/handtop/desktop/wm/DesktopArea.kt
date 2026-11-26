package org.handtop.desktop.wm

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import org.handtop.desktop.ui.Palette
import org.handtop.desktop.ui.Themes
import org.handtop.desktop.ui.dp
import org.handtop.desktop.ui.dpi
import org.handtop.desktop.wm.DesktopWindow.Companion.EDGE_BOTTOM
import org.handtop.desktop.wm.DesktopWindow.Companion.EDGE_LEFT
import org.handtop.desktop.wm.DesktopWindow.Companion.EDGE_RIGHT
import org.handtop.desktop.wm.DesktopWindow.Companion.EDGE_TOP
import org.handtop.desktop.wm.DesktopWindow.Mode
import kotlin.math.max
import kotlin.math.min

/**
 * The window manager. Children are [DesktopWindow]s; child order is the z-order
 * (last child = top). Empty space is transparent to touches so the desktop icons
 * underneath stay clickable.
 */
class DesktopArea(context: Context) : ViewGroup(context) {

    fun interface Listener {
        fun onWindowsChanged()
    }

    var listener: Listener? = null

    /**
     * How far from the left new windows open so they don't cover the desktop's icons, for an
     * area of the given height (the icons fill more columns on a shorter screen).
     */
    var iconsWidth: (height: Int) -> Int = { 0 }

    /**
     * How much of the desktop the on-screen keyboard hides now (pixels). New windows are
     * sized for the desktop without it: the keyboard goes away, the window stays.
     */
    var keyboardCover = 0

    private val openOrder = ArrayList<DesktopWindow>()

    /** Windows in the order they were opened (taskbar order). */
    val windows: List<DesktopWindow> get() = openOrder

    var activeWindow: DesktopWindow? = null
        private set

    /** Phone-sized area: windows open maximized and side snapping is off. */
    val isCompact: Boolean
        get() {
            val w = if (width > 0) width else resources.displayMetrics.widthPixels
            return w / resources.displayMetrics.density < 600
        }

    private var cascadeStep = 0
    private val frame = Rect()
    private val location = IntArray(2)

    private val minWidth = dpi(240)
    private val minHeight = dpi(150)
    private val minVisible = dpi(72)
    private val snapEdge = dpi(10)

    // Gesture state (one move / resize at a time).
    private val gestureStart = Rect()
    private var grabDx = 0f
    private var grabDy = 0f
    private var startRawX = 0f
    private var startRawY = 0f
    private var resizeEdges = 0

    private var snapPreview: Rect? = null
    private val previewFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.SNAP_PREVIEW_FILL
        style = Paint.Style.FILL
    }
    private val previewHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.SNAP_PREVIEW_HALO
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
    }
    private val previewStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.SNAP_PREVIEW_STROKE
        style = Paint.Style.STROKE
        strokeWidth = if (Themes.current.roundedCorners) dp(2f) else dp(3f)
    }

    // ---- window lifecycle ----

    private class Placement(
        val window: DesktopWindow,
        val contentWidth: Int,
        val contentHeight: Int,
        val parent: DesktopWindow?,
        val cascade: Int,
    )

    /** Windows added before the area had a size (see [addWindow]). */
    private val unplaced = ArrayList<Placement>()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && unplaced.isNotEmpty()) {
            unplaced.forEach { if (it.window.parent === this) place(it) }
            unplaced.clear()
        }
    }

    /**
     * The first size and position of a window: sized for the desktop without the on-screen
     * keyboard, cascading right of the desktop's icons; a dialog centered over its parent.
     */
    private fun place(p: Placement) {
        val window = p.window
        val aw = if (width > 0) width else resources.displayMetrics.widthPixels
        val ah = if (height > 0) height + keyboardCover else resources.displayMetrics.heightPixels
        var (ww, wh) = WindowSizing.defaultSize(aw, ah, resources.displayMetrics.density).toList()
        if (p.contentWidth > 0 && p.contentHeight > 0) {
            val (fw, fh) = window.frameSizeFor(p.contentWidth, p.contentHeight)
            // Room for the title bar's buttons (a small program, e.g. a clock, gets a wider window).
            ww = min(if (window.decorated) max(fw, minWidth) else fw, aw)
            wh = min(fh, ah)
        }
        val offset = dpi(32) * (p.cascade % 6)
        // Start right of the desktop's icons so new windows don't cover them.
        var left = iconsWidth(ah) + offset
        var top = dpi(24) + offset
        if (left + ww > aw) left = max(0, aw - ww)
        if (top + wh > ah) top = max(0, ah - wh)
        if (p.parent != null || !window.resizable) {
            val over = Rect()
            if (p.parent != null && !p.parent.minimized) frameOf(p.parent, aw, ah, over) else over.set(0, 0, aw, ah)
            left = (over.centerX() - ww / 2).coerceIn(0, max(0, aw - ww))
            top = (over.centerY() - wh / 2).coerceIn(0, max(0, ah - wh))
        }
        window.bounds.set(left, top, left + ww, top + wh)
    }

    /**
     * Shows [window]. With [contentWidth] / [contentHeight] (pixels) the floating window is
     * sized around that content, e.g. the size a Linux program asked for. A window with a
     * [parent] (a dialog) opens centered over it and stays floating even on a phone.
     */
    fun addWindow(
        window: DesktopWindow,
        contentWidth: Int = 0,
        contentHeight: Int = 0,
        parent: DesktopWindow? = null,
    ) {
        val dialog = parent != null || !window.resizable
        val placement = Placement(window, contentWidth, contentHeight, parent, if (dialog) 0 else cascadeStep++)
        place(placement)
        // Before the desktop's first layout (the activity is being created) its size is only
        // guessed and the icons aren't laid out yet: place the window again once they are.
        if (width == 0 || height == 0) unplaced += placement
        if (isCompact && !dialog) window.setMode(Mode.MAXIMIZED)

        openOrder += window
        addView(window)
        activate(window)
    }

    fun activate(window: DesktopWindow?) {
        var changed = false
        if (window != null) {
            if (window.minimized) {
                window.minimized = false
                window.visibility = View.VISIBLE
                changed = true
            }
            if (indexOfChild(window) != childCount - 1) {
                bringChildToFront(window)
                invalidate()
            }
        }
        if (activeWindow !== window) {
            val previous = activeWindow
            previous?.isActiveWindow = false
            activeWindow = window
            window?.isActiveWindow = true
            previous?.content?.onDeactivated()
            changed = true
        }
        window?.content?.onActivated()
        // Keyboard input must follow the active window, even if its content has nothing
        // focusable — otherwise keys would keep going to a terminal in the background.
        if (window != null) {
            setDesktopFocusable(false)
            if (!window.hasFocus()) window.requestFocus()
        } else {
            focusDesktop()
        }
        if (changed) notifyWindowsChanged()
    }

    /**
     * No active window: the desktop itself holds the focus, so keys and the on-screen keyboard
     * no longer go to the window that was active before, and the keyboard goes away.
     */
    private fun focusDesktop() {
        setDesktopFocusable(true)
        if (findFocus() !== this) requestFocus()
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
    }

    /** Focusable only while no window is active: it must not take the focus from windows. */
    private fun setDesktopFocusable(focusable: Boolean) {
        isFocusable = focusable
        isFocusableInTouchMode = focusable
    }

    fun minimize(window: DesktopWindow) {
        window.minimized = true
        window.visibility = View.GONE
        if (activeWindow === window) {
            window.isActiveWindow = false
            activeWindow = null
            window.content.onDeactivated()
            activate(topmostVisible())
        }
        notifyWindowsChanged()
    }

    fun toggleMaximize(window: DesktopWindow) {
        if (!window.resizable) return
        window.setMode(if (window.mode == Mode.MAXIMIZED) Mode.NORMAL else Mode.MAXIMIZED)
    }

    fun closeWindow(window: DesktopWindow) {
        if (!openOrder.remove(window)) return
        removeView(window)
        // Release the content first (e.g. its terminal session), so the window activated
        // next already sees the new state.
        window.content.onClosed()
        if (activeWindow === window) {
            activeWindow = null
            activate(topmostVisible())
        }
        notifyWindowsChanged()
    }

    fun notifyWindowsChanged() {
        listener?.onWindowsChanged()
    }

    private fun topmostVisible(): DesktopWindow? {
        for (i in childCount - 1 downTo 0) {
            val c = getChildAt(i)
            if (c is DesktopWindow && c.visibility == View.VISIBLE) return c
        }
        return null
    }

    private fun windowAt(x: Float, y: Float): DesktopWindow? {
        for (i in childCount - 1 downTo 0) {
            val c = getChildAt(i)
            if (c is DesktopWindow && c.visibility == View.VISIBLE &&
                x >= c.left && x < c.right && y >= c.top && y < c.bottom
            ) return c
        }
        return null
    }

    // ---- focus follows click ----

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // Raise the touched window *before* children get the event, so z-order is
        // already correct while the touch is dispatched.
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val window = windowAt(ev.x, ev.y)
            when {
                window != null -> if (window !== activeWindow || indexOfChild(window) != childCount - 1) activate(window)
                // A click on the wallpaper deactivates the active window, as on a desktop.
                activeWindow != null -> activate(null)
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = false

    // ---- geometry ----

    /** The on-screen frame of a window for an area of [aw] x [ah]. */
    private fun frameOf(window: DesktopWindow, aw: Int, ah: Int, out: Rect) {
        val w = max(1, aw)
        val h = max(1, ah)
        when (window.mode) {
            Mode.MAXIMIZED -> out.set(0, 0, w, h)
            Mode.SNAP_LEFT -> out.set(0, 0, w / 2, h)
            Mode.SNAP_RIGHT -> out.set(w / 2, 0, w, h)
            Mode.NORMAL -> {
                val ww = window.bounds.width().coerceIn(1, w)
                val wh = window.bounds.height().coerceIn(1, h)
                val minX = minVisible - ww
                val maxX = max(minX, w - minVisible)
                val x = window.bounds.left.coerceIn(minX, maxX)
                val y = window.bounds.top.coerceIn(0, max(0, h - wh))
                out.set(x, y, x + ww, y + wh)
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        for (i in 0 until childCount) {
            val c = getChildAt(i) as? DesktopWindow ?: continue
            if (c.visibility == View.GONE) continue
            frameOf(c, w, h, frame)
            c.measure(
                MeasureSpec.makeMeasureSpec(frame.width(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(frame.height(), MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val c = getChildAt(i) as? DesktopWindow ?: continue
            if (c.visibility == View.GONE) continue
            frameOf(c, r - l, b - t, frame)
            c.layout(frame.left, frame.top, frame.right, frame.bottom)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        snapPreview?.let {
            val inset = dp(6f)
            val r = RectF(it).apply { inset(inset, inset) }
            // Classic: just an outline, nothing translucent, no rounding.
            val radius = if (Themes.current.roundedCorners) dp(12f) else 0f
            if (Palette.SNAP_PREVIEW_FILL != 0) canvas.drawRoundRect(r, radius, radius, previewFill)
            canvas.drawRoundRect(r, radius, radius, previewStroke)
            if (Palette.SNAP_PREVIEW_HALO != 0) {
                // Classic: a light line just inside the dark one, so the outline shows on any wallpaper.
                val inner = previewStroke.strokeWidth
                r.inset(inner, inner)
                canvas.drawRoundRect(r, radius, radius, previewHalo)
            }
        }
    }

    private fun localX(rawX: Float): Float {
        getLocationOnScreen(location)
        return rawX - location[0]
    }

    private fun localY(rawY: Float): Float {
        getLocationOnScreen(location)
        return rawY - location[1]
    }

    // ---- moving ----

    fun beginMove(window: DesktopWindow, rawX: Float, rawY: Float) {
        val x = localX(rawX)
        val y = localY(rawY)
        frameOf(window, width, height, frame)
        if (window.mode != Mode.NORMAL) {
            // Un-maximize under the pointer, keeping the grab point proportionally in place.
            val ratio = ((x - frame.left) / frame.width().toFloat()).coerceIn(0f, 1f)
            val fw = window.bounds.width()
            val fh = window.bounds.height()
            val left = (x - ratio * fw).toInt()
            window.bounds.set(left, frame.top, left + fw, frame.top + fh)
            window.setMode(Mode.NORMAL)
        } else {
            window.bounds.set(frame)
        }
        grabDx = x - window.bounds.left
        grabDy = y - window.bounds.top
        requestLayout()
    }

    fun moveTo(window: DesktopWindow, rawX: Float, rawY: Float) {
        val x = localX(rawX)
        val y = localY(rawY)
        window.bounds.offsetTo((x - grabDx).toInt(), (y - grabDy).toInt())
        setSnapPreview(snapModeAt(x, y))
        requestLayout()
    }

    fun endMove(window: DesktopWindow, rawX: Float, rawY: Float, cancelled: Boolean = false) {
        val snap = if (cancelled) null else snapModeAt(localX(rawX), localY(rawY))
        setSnapPreview(null)
        if (snap != null) {
            window.setMode(snap)
        } else {
            frameOf(window, width, height, frame)
            window.bounds.set(frame)
        }
        requestLayout()
    }

    private fun snapModeAt(x: Float, y: Float): Mode? = when {
        y <= snapEdge -> Mode.MAXIMIZED
        isCompact -> null
        x <= snapEdge -> Mode.SNAP_LEFT
        x >= width - snapEdge -> Mode.SNAP_RIGHT
        else -> null
    }

    private fun setSnapPreview(mode: Mode?) {
        val rect = when (mode) {
            Mode.MAXIMIZED -> Rect(0, 0, width, height)
            Mode.SNAP_LEFT -> Rect(0, 0, width / 2, height)
            Mode.SNAP_RIGHT -> Rect(width / 2, 0, width, height)
            else -> null
        }
        if (rect != snapPreview) {
            snapPreview = rect
            invalidate()
        }
    }

    // ---- resizing ----

    fun beginResize(window: DesktopWindow, edges: Int, rawX: Float, rawY: Float) {
        frameOf(window, width, height, frame)
        window.bounds.set(frame)
        gestureStart.set(frame)
        resizeEdges = edges
        startRawX = rawX
        startRawY = rawY
    }

    fun resizeTo(window: DesktopWindow, rawX: Float, rawY: Float) {
        val dx = (rawX - startRawX).toInt()
        val dy = (rawY - startRawY).toInt()
        val s = gestureStart
        var l = s.left
        var t = s.top
        var r = s.right
        var b = s.bottom
        if (resizeEdges and EDGE_LEFT != 0) l = min(s.left + dx, s.right - minWidth)
        if (resizeEdges and EDGE_RIGHT != 0) r = max(s.right + dx, s.left + minWidth)
        if (resizeEdges and EDGE_TOP != 0) t = min(max(0, s.top + dy), s.bottom - minHeight)
        if (resizeEdges and EDGE_BOTTOM != 0) b = max(s.bottom + dy, s.top + minHeight)
        window.bounds.set(l, t, r, b)
        requestLayout()
    }

    /**
     * A program asks for a new size of its content ([contentWidth] x [contentHeight] pixels):
     * the floating window keeps its top-left corner and fits around it, within the desktop.
     * False for a maximized or snapped window: the desktop decides its size.
     */
    fun resizeContent(window: DesktopWindow, contentWidth: Int, contentHeight: Int): Boolean {
        if (window.mode != DesktopWindow.Mode.NORMAL) return false
        if (resizeEdges != 0) return false // the user is resizing a window: the hand decides
        val (fw, fh) = window.frameSizeFor(contentWidth, contentHeight)
        frameOf(window, width, height, frame)
        val w = if (width > 0) min(fw, width) else fw
        val h = if (height > 0) min(fh, height) else fh
        window.bounds.set(frame.left, frame.top, frame.left + w, frame.top + h)
        requestLayout()
        return true
    }

    fun endResize(window: DesktopWindow) {
        frameOf(window, width, height, frame)
        window.bounds.set(frame)
        resizeEdges = 0
        requestLayout()
        window.content.onResizeEnded()
    }
}
