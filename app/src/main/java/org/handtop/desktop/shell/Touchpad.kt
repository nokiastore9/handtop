package org.handtop.desktop.shell

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.PopupWindow
import org.handtop.desktop.ui.Popups
import org.handtop.desktop.ui.dp
import org.handtop.desktop.ui.hideSystemBarsOfWindow
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The touch screen as a touchpad, for a desktop that is easier with a mouse (small targets,
 * hovering, right clicks), when no mouse is at hand.
 *
 * A transparent layer covers the screen, above the desktop and its menus, draws a pointer and
 * turns gestures into mouse events where the pointer is: into a menu when one is open there,
 * otherwise into the desktop. Linux windows take them as a mouse, as they would a real one.
 *  - one finger moves the pointer (faster when moved faster);
 *  - a tap clicks, two quick taps double-click;
 *  - holding the finger still grabs (a short vibration): let go for a right click, or move to
 *    drag (windows, icons, selections);
 *  - a tap with two fingers right-clicks, two fingers moving scroll.
 * [passThrough] marks where the screen stays a touch screen (the taskbar's keyboard button,
 * whose long press turns the touchpad off). A real mouse works as usual meanwhile.
 */
class Touchpad(
    private val root: View,
    /** Screen points that keep reaching the desktop as touches. */
    private val passThrough: (Float, Float) -> Boolean,
) {

    private val context: Context get() = root.context
    private var window: PopupWindow? = null
    private var pad: Pad? = null

    val isOn get() = window?.isShowing == true

    fun start() {
        if (isOn || root.width == 0) return
        val view = Pad(context)
        val popup = PopupWindow(view, root.width, root.height, false).apply {
            isTouchable = true
            isClippingEnabled = false
            // Above the desktop's own popups (menus are panels), so the pointer is drawn over them.
            windowLayoutType = WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL
            setBackgroundDrawable(null)
        }
        popup.showAtLocation(root, Gravity.TOP or Gravity.START, 0, 0)
        view.hideSystemBarsOfWindow()
        view.cursorX = root.width / 2f
        view.cursorY = root.height / 2f
        window = popup
        pad = view
    }

    fun stop() {
        pad?.releaseButtons()
        window?.dismiss()
        window = null
        pad = null
    }

    /** Follows the desktop's size (a rotation, DeX). */
    fun resize() {
        val popup = window ?: return
        if (popup.width != root.width || popup.height != root.height) popup.update(0, 0, root.width, root.height)
    }

    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    private inner class Pad(context: Context) : View(context) {

        var cursorX = 0f
        var cursorY = 0f
        private var cursorShown = true

        private val main = Handler(Looper.getMainLooper())
        private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        private val notch = dp(22f) // finger travel per scroll step

        // The gesture in progress.
        private var downTime = 0L
        private var startX = 0f
        private var startY = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var moved = false
        private var fingers = 1
        private var twoFingerMoved = false
        /** The finger was held still: a right click when let go, a drag when moved. */
        private var held = false
        private var dragging = false
        private var dragDownTime = 0L
        private var scrollX = 0f
        private var scrollY = 0f
        /** This gesture goes to the desktop untouched (see [passThrough]). */
        private var passing = false

        private val hold = Runnable {
            if (!moved && fingers == 1) {
                held = true
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                invalidate()
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (!e.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) {
                cursorShown = false // a real mouse: its own pointer is on screen
                invalidate()
                forward(e)
                return true
            }
            if (e.actionMasked == MotionEvent.ACTION_DOWN) passing = passThrough(e.rawX, e.rawY)
            if (passing) {
                forward(e)
                return true
            }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cursorShown = true
                    downTime = e.eventTime
                    startX = e.x; startY = e.y; lastX = e.x; lastY = e.y
                    moved = false; fingers = 1; twoFingerMoved = false; held = false
                    scrollX = 0f; scrollY = 0f
                    main.postDelayed(hold, HOLD_MS)
                    invalidate()
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    fingers = max(fingers, e.pointerCount)
                    main.removeCallbacks(hold)
                    lastX = averageX(e); lastY = averageY(e)
                }
                MotionEvent.ACTION_MOVE -> move(e)
                MotionEvent.ACTION_POINTER_UP -> {
                    // The fingers left one by one: follow the ones still down, without a jump.
                    val left = e.actionIndex
                    lastX = averageX(e, skip = left); lastY = averageY(e, skip = left)
                }
                MotionEvent.ACTION_UP -> up(e)
                MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(hold)
                    releaseButtons()
                }
            }
            return true
        }

        private fun move(e: MotionEvent) {
            if (fingers >= 2 && e.pointerCount >= 2) {
                val x = averageX(e)
                val y = averageY(e)
                scrollX += x - lastX
                scrollY += y - lastY
                lastX = x; lastY = y
                if (abs(x - startX) > slop || abs(y - startY) > slop) twoFingerMoved = true
                // Whole steps, as a wheel gives them: the fingers move the content (natural scrolling).
                val v = (scrollY / notch).toInt()
                val h = (scrollX / notch).toInt()
                if (v != 0 || h != 0) {
                    scrollY -= v * notch
                    scrollX -= h * notch
                    scroll(v.toFloat(), -h.toFloat())
                }
                return
            }
            if (fingers >= 2) return // the last finger of a two-finger gesture: nothing to do
            val dx = e.x - lastX
            val dy = e.y - lastY
            lastX = e.x; lastY = e.y
            if (!moved && hypot(e.x - startX, e.y - startY) > slop) {
                moved = true
                main.removeCallbacks(hold)
            }
            if (!moved) return
            if (held && !dragging) {
                dragging = true
                dragDownTime = SystemClock.uptimeMillis()
                send(MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_PRIMARY, dragDownTime)
            }
            // Slow moves stay precise; fast ones cross the screen.
            val speed = hypot(dx, dy) / dp(1f)
            val gain = BASE_GAIN + min(MAX_EXTRA_GAIN, speed * ACCELERATION)
            cursorX = (cursorX + dx * gain).coerceIn(0f, width - 1f)
            cursorY = (cursorY + dy * gain).coerceIn(0f, height - 1f)
            invalidate()
            if (dragging) send(MotionEvent.ACTION_MOVE, MotionEvent.BUTTON_PRIMARY, dragDownTime)
            else send(MotionEvent.ACTION_HOVER_MOVE, 0, SystemClock.uptimeMillis())
        }

        private fun up(e: MotionEvent) {
            main.removeCallbacks(hold)
            when {
                dragging -> {
                    send(MotionEvent.ACTION_UP, 0, dragDownTime)
                    dragging = false
                }
                fingers >= 2 -> if (!twoFingerMoved && e.eventTime - downTime < TAP_MS) click(MotionEvent.BUTTON_SECONDARY)
                held -> click(MotionEvent.BUTTON_SECONDARY)
                !moved -> click(MotionEvent.BUTTON_PRIMARY)
            }
            held = false
            invalidate()
        }

        fun releaseButtons() {
            if (dragging) send(MotionEvent.ACTION_UP, 0, dragDownTime)
            dragging = false
            held = false
            main.removeCallbacks(hold)
        }

        private fun averageX(e: MotionEvent, skip: Int = -1): Float {
            var sum = 0f; var n = 0
            for (i in 0 until e.pointerCount) if (i != skip) { sum += e.getX(i); n++ }
            return if (n == 0) e.x else sum / n
        }

        private fun averageY(e: MotionEvent, skip: Int = -1): Float {
            var sum = 0f; var n = 0
            for (i in 0 until e.pointerCount) if (i != skip) { sum += e.getY(i); n++ }
            return if (n == 0) e.y else sum / n
        }

        // ---- mouse events where the pointer is ----

        private fun click(button: Int) {
            val down = SystemClock.uptimeMillis()
            send(MotionEvent.ACTION_DOWN, button, down)
            send(MotionEvent.ACTION_UP, 0, down)
        }

        private fun scroll(vertical: Float, horizontal: Float) {
            val now = SystemClock.uptimeMillis()
            deliver(mouse(MotionEvent.ACTION_SCROLL, 0, now, now, vertical, horizontal), generic = true)
        }

        private fun send(action: Int, buttons: Int, down: Long) {
            val generic = action == MotionEvent.ACTION_HOVER_MOVE
            deliver(mouse(action, buttons, down, SystemClock.uptimeMillis(), 0f, 0f), generic)
        }

        /** A mouse event at the pointer, in screen coordinates. */
        private fun mouse(action: Int, buttons: Int, down: Long, time: Long, v: Float, h: Float): MotionEvent {
            val origin = IntArray(2).also { getLocationOnScreen(it) }
            val properties = arrayOf(MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_MOUSE
            })
            val coords = arrayOf(MotionEvent.PointerCoords().apply {
                x = origin[0] + cursorX
                y = origin[1] + cursorY
                pressure = if (buttons != 0) 1f else 0f
                size = 1f
                setAxisValue(MotionEvent.AXIS_VSCROLL, v)
                setAxisValue(MotionEvent.AXIS_HSCROLL, h)
            })
            return MotionEvent.obtain(down, time, action, 1, properties, coords, 0, buttons, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        }

        /**
         * Hands [event] (screen coordinates) to the window under it: the topmost popup that
         * contains it, otherwise the desktop. A press outside an open popup closes the popup
         * instead, as a tap beside a menu does.
         */
        private fun deliver(event: MotionEvent, generic: Boolean) {
            val x = event.rawX
            val y = event.rawY
            val popups = Popups.showing()
            val target = popups.lastOrNull { contains(it.contentView.rootView, x, y) }?.contentView?.rootView
            if (target == null && popups.isNotEmpty() && event.actionMasked == MotionEvent.ACTION_DOWN) {
                popups.last().dismiss()
                event.recycle()
                return
            }
            val view = target ?: root
            val at = IntArray(2).also { view.getLocationOnScreen(it) }
            event.offsetLocation(-at[0].toFloat(), -at[1].toFloat())
            if (generic) view.dispatchGenericMotionEvent(event) else view.dispatchTouchEvent(event)
            event.recycle()
        }

        /** A real mouse's or a pass-through touch's event, unchanged, to the window under it. */
        private fun forward(e: MotionEvent) {
            val copy = MotionEvent.obtain(e)
            val origin = IntArray(2).also { getLocationOnScreen(it) }
            copy.offsetLocation(origin[0].toFloat(), origin[1].toFloat()) // into screen coordinates
            val popups = Popups.showing()
            val target = popups.lastOrNull { contains(it.contentView.rootView, e.rawX, e.rawY) }?.contentView?.rootView ?: root
            val at = IntArray(2).also { target.getLocationOnScreen(it) }
            copy.offsetLocation(-at[0].toFloat(), -at[1].toFloat())
            target.dispatchTouchEvent(copy)
            copy.recycle()
        }

        override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
            // A real mouse hovering or scrolling over the layer.
            val copy = MotionEvent.obtain(e)
            val origin = IntArray(2).also { getLocationOnScreen(it) }
            copy.offsetLocation(origin[0].toFloat(), origin[1].toFloat())
            val at = IntArray(2).also { root.getLocationOnScreen(it) }
            copy.offsetLocation(-at[0].toFloat(), -at[1].toFloat())
            root.dispatchGenericMotionEvent(copy)
            copy.recycle()
            return true
        }

        private fun contains(view: View, x: Float, y: Float): Boolean {
            val at = IntArray(2).also { view.getLocationOnScreen(it) }
            return x >= at[0] && x < at[0] + view.width && y >= at[1] && y < at[1] + view.height
        }

        // ---- the pointer ----

        private val arrow = Path().apply {
            // A classic arrow, its tip at (0, 0); 1 unit = 1 dp.
            moveTo(0f, 0f); lineTo(0f, 17f); lineTo(4.2f, 13.2f); lineTo(7f, 19.4f)
            lineTo(9.6f, 18.3f); lineTo(6.9f, 12.2f); lineTo(12.4f, 12.2f); close()
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 1.3f
            strokeJoin = Paint.Join.ROUND
        }

        private val grab = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x664F8CFF }

        override fun onDraw(canvas: Canvas) {
            if (!cursorShown) return
            // Held or dragging: a ring at the tip says what the next move does.
            if (held || dragging) canvas.drawCircle(cursorX, cursorY, dp(14f), grab)
            val scale = dp(1.2f)
            canvas.save()
            canvas.translate(cursorX, cursorY)
            canvas.scale(scale, scale)
            canvas.drawPath(arrow, fill)
            canvas.drawPath(arrow, outline)
            canvas.restore()
        }
    }

    private companion object {
        const val TAP_MS = 250L
        const val HOLD_MS = 450L
        const val BASE_GAIN = 1.3f
        const val ACCELERATION = 0.12f
        const val MAX_EXTRA_GAIN = 2.2f
    }
}
