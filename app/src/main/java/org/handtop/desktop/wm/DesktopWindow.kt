package org.handtop.desktop.wm

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.ViewConfiguration
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.handtop.desktop.R
import org.handtop.desktop.ui.Palette
import org.handtop.desktop.ui.dp
import org.handtop.desktop.ui.dpi
import org.handtop.desktop.ui.selectableBackground
import kotlin.math.hypot

/**
 * A desktop window: frame + title bar (icon, title, minimize / maximize / close) + content.
 * Geometry and z-order are owned by [DesktopArea]; this class handles its own gestures
 * (dragging by the title bar, resizing by the frame edges) and forwards them to the area.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class DesktopWindow(
    context: Context,
    private val area: DesktopArea,
    val content: WindowContent,
    title: CharSequence,
    @param:DrawableRes val iconRes: Int,
) : LinearLayout(context), WindowHost {

    enum class Mode { NORMAL, MAXIMIZED, SNAP_LEFT, SNAP_RIGHT }

    companion object {
        const val EDGE_LEFT = 1
        const val EDGE_TOP = 2
        const val EDGE_RIGHT = 4
        const val EDGE_BOTTOM = 8
        private const val TITLE_HEIGHT_DP = 36
    }

    var mode = Mode.NORMAL
        private set
    var minimized = false
        internal set

    /** Floating ("restored") geometry in [DesktopArea] coordinates. */
    val bounds = Rect()

    var title: CharSequence = title
        private set

    /** A picture icon (a Linux app's own) instead of [iconRes]; shown in the title and taskbar. */
    var iconBitmap: android.graphics.Bitmap? = null
        set(value) {
            field = value
            if (value != null) iconView.setImageBitmap(value) else iconView.setImageResource(iconRes)
            area.notifyWindowsChanged()
        }

    var isActiveWindow = false
        set(value) {
            field = value
            applyStyle()
        }

    private val border = dpi(3)
    private val cornerRadius = dp(10f)
    private val frameBackground = GradientDrawable()
    private val titleBar = LinearLayout(context)
    private val iconView = ImageView(context)
    private val titleView = TextView(context)
    private val maximizeButton: ImageButton
    private var resizeEdges = 0

    init {
        orientation = VERTICAL
        background = frameBackground
        clipToOutline = true
        // The window itself can hold focus when its content has no focusable views.
        isFocusable = true
        isFocusableInTouchMode = true
        descendantFocusability = FOCUS_AFTER_DESCENDANTS

        titleBar.orientation = HORIZONTAL
        titleBar.gravity = Gravity.CENTER_VERTICAL

        iconView.setImageResource(iconRes)
        titleBar.addView(iconView, LayoutParams(dpi(18), dpi(18)).apply {
            marginStart = dpi(10)
            marginEnd = dpi(8)
        })

        titleView.text = title
        titleView.textSize = 13f
        titleView.isSingleLine = true
        titleView.ellipsize = TextUtils.TruncateAt.END
        titleBar.addView(titleView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        titleBar.addView(makeButton(R.drawable.ic_win_minimize, R.string.action_minimize, false) {
            area.minimize(this)
        })
        maximizeButton = makeButton(R.drawable.ic_win_maximize, R.string.action_maximize, false) {
            area.toggleMaximize(this)
        }
        titleBar.addView(maximizeButton)
        titleBar.addView(makeButton(R.drawable.ic_win_close, R.string.action_close, true) { requestClose() })

        addView(titleBar, LayoutParams(LayoutParams.MATCH_PARENT, dpi(TITLE_HEIGHT_DP)))
        addView(content.view, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        setupTitleBarGestures()
        applyStyle()
        content.attach(this)
    }

    // ---- WindowHost ----

    override fun setTitle(title: CharSequence) {
        this.title = title
        titleView.text = title
        area.notifyWindowsChanged()
    }

    override fun close() = area.closeWindow(this)

    /** Close button / Alt+F4: the content may intercept it (see [WindowContent.onCloseRequest]). */
    fun requestClose() {
        if (!content.onCloseRequest()) close()
    }

    /** Outer window size for a content area of [contentWidth] x [contentHeight] pixels. */
    fun frameSizeFor(contentWidth: Int, contentHeight: Int): Pair<Int, Int> {
        val title = if (decorated) dpi(TITLE_HEIGHT_DP) else 0
        return (contentWidth + 2 * border) to (contentHeight + title + 2 * border)
    }

    /**
     * Whether HandTop draws the title bar. Off for programs that draw their own
     * (client-side decorations); the thin frame stays for resizing.
     */
    var decorated = true
        set(value) {
            if (field == value) return
            field = value
            titleBar.visibility = if (value) VISIBLE else GONE
            area.requestLayout()
        }

    /**
     * Off for fixed-size windows (dialogs): no resizing by the frame, no maximizing.
     */
    var resizable = true
        set(value) {
            field = value
            maximizeButton.visibility = if (value) VISIBLE else GONE
            if (!value && mode != Mode.NORMAL) setMode(Mode.NORMAL)
        }

    // ---- state ----

    fun setMode(newMode: Mode) {
        if (mode == newMode) return
        mode = newMode
        applyStyle()
        area.requestLayout()
    }

    private fun applyStyle() {
        val floating = mode == Mode.NORMAL
        frameBackground.setColor(if (isActiveWindow) Palette.TITLE_ACTIVE else Palette.TITLE_INACTIVE)
        frameBackground.cornerRadius = if (floating) cornerRadius else 0f
        val pad = if (floating) border else 0
        setPadding(pad, pad, pad, pad)
        titleView.setTextColor(if (isActiveWindow) Palette.TITLE_TEXT_ACTIVE else Palette.TITLE_TEXT_INACTIVE)
        iconView.alpha = if (isActiveWindow) 1f else 0.6f
        elevation = if (isActiveWindow) dp(18f) else dp(6f)
        maximizeButton.setImageResource(if (mode == Mode.MAXIMIZED) R.drawable.ic_win_restore else R.drawable.ic_win_maximize)
        invalidateOutline()
    }

    private fun makeButton(@DrawableRes icon: Int, @StringRes desc: Int, danger: Boolean, onClick: () -> Unit) =
        ImageButton(context).apply {
            setImageResource(icon)
            contentDescription = context.getString(desc)
            tooltipText = contentDescription
            scaleType = ImageView.ScaleType.CENTER
            background = if (danger) closeButtonBackground() else context.selectableBackground()
            isFocusable = false
            setOnClickListener { onClick() }
            layoutParams = LayoutParams(dpi(44), LayoutParams.MATCH_PARENT)
        }

    private fun closeButtonBackground() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(Palette.CLOSE_PRESSED))
        addState(intArrayOf(android.R.attr.state_hovered), ColorDrawable(Palette.CLOSE_HOVER))
        addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
    }

    // ---- dragging by the title bar ----

    private fun setupTitleBarGestures() {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        val doubleTap = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                area.toggleMaximize(this@DesktopWindow)
                return true
            }
        })
        var downX = 0f
        var downY = 0f
        var dragging = false
        titleBar.setOnTouchListener { _, ev ->
            doubleTap.onTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && hypot(ev.rawX - downX, ev.rawY - downY) > slop) {
                        dragging = true
                        area.beginMove(this, downX, downY)
                    }
                    if (dragging) area.moveTo(this, ev.rawX, ev.rawY)
                }
                MotionEvent.ACTION_UP -> if (dragging) area.endMove(this, ev.rawX, ev.rawY)
                MotionEvent.ACTION_CANCEL -> if (dragging) area.endMove(this, ev.rawX, ev.rawY, cancelled = true)
            }
            true
        }
    }

    // ---- resizing by the frame ----

    /** Which frame edges are under (x, y). Thin zones for a mouse, wider ones for fingers. */
    private fun edgesAt(x: Float, y: Float, mouse: Boolean): Int {
        if (mode != Mode.NORMAL || !resizable) return 0
        val w = width
        val h = height
        val zone = if (mouse) dpi(6) else dpi(12)
        val topZone = if (mouse) dpi(5) else border
        val corner = if (mouse) dpi(14) else dpi(28)
        var edges = 0
        if (x < zone) edges = edges or EDGE_LEFT
        if (x > w - zone) edges = edges or EDGE_RIGHT
        if (y < topZone) edges = edges or EDGE_TOP
        if (y > h - zone) edges = edges or EDGE_BOTTOM
        // Generous corner grips at the bottom (top corners would cover the title bar buttons).
        if (y > h - corner) {
            if (x < corner) edges = EDGE_LEFT or EDGE_BOTTOM
            if (x > w - corner) edges = EDGE_RIGHT or EDGE_BOTTOM
        }
        return edges
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val edges = edgesAt(ev.x, ev.y, ev.isFromSource(InputDevice.SOURCE_MOUSE))
            if (edges != 0) {
                resizeEdges = edges
                area.beginResize(this, edges, ev.rawX, ev.rawY)
                return true
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (resizeEdges != 0) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> area.resizeTo(this, ev.rawX, ev.rawY)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    area.endResize(this)
                    resizeEdges = 0
                }
            }
        }
        // Always consume: touches on a window must never fall through to the desktop below.
        return true
    }

    override fun onResolvePointerIcon(event: MotionEvent, pointerIndex: Int): PointerIcon? {
        val type = when (edgesAt(event.getX(pointerIndex), event.getY(pointerIndex), mouse = true)) {
            EDGE_LEFT, EDGE_RIGHT -> PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW
            EDGE_TOP, EDGE_BOTTOM -> PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW
            EDGE_LEFT or EDGE_TOP, EDGE_RIGHT or EDGE_BOTTOM -> PointerIcon.TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW
            EDGE_RIGHT or EDGE_TOP, EDGE_LEFT or EDGE_BOTTOM -> PointerIcon.TYPE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW
            else -> null
        }
        return if (type != null) PointerIcon.getSystemIcon(context, type)
        else super.onResolvePointerIcon(event, pointerIndex)
    }
}
