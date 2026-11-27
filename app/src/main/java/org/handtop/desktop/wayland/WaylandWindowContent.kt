package org.handtop.desktop.wayland

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.DragEvent
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.handtop.desktop.R
import org.handtop.desktop.wm.DesktopWindow.Companion.EDGE_BOTTOM
import org.handtop.desktop.wm.DesktopWindow.Companion.EDGE_LEFT
import org.handtop.desktop.wm.DesktopWindow.Companion.EDGE_RIGHT
import org.handtop.desktop.wm.DesktopWindow.Companion.EDGE_TOP
import org.handtop.desktop.wm.WindowContent
import org.handtop.desktop.wm.WindowHost

/**
 * Shows a Linux toplevel (and its popups) inside a desktop window, and feeds it pointer and
 * keyboard input. 1 unit of the program's surface = [scale] view pixels.
 */
@SuppressLint("ViewConstructor")
class WaylandWindowContent(
    context: Context,
    val toplevel: Toplevel,
    private val windowControl: WindowControl,
    /** Draws this window's popups above every window (see [PopupLayer]). */
    private val popupLayer: PopupLayer,
) : View(context), WindowContent, ToplevelView {

    // Not attached yet: the program's first frames must not wait.
    override val isShownOnScreen get() = !isAttachedToWindow || isShown

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) toplevel.client.server.wakeHiddenFrames()
    }

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
        /** Fit the desktop window around content of [width] x [height] pixels; false if it keeps its size. */
        fun resizeContent(width: Int, height: Int): Boolean
        /** Look up the program's icon again (its app id changed). */
        fun appIdChanged()
    }

    /** A move or resize the program started from its own title bar / border. */
    private enum class Grab { NONE, MOVE, RESIZE }

    private var grab = Grab.NONE
    private var pressed = false
    private var lastRawX = 0f
    private var lastRawY = 0f

    override val view: View get() = this

    private val server = toplevel.client.server
    /**
     * View pixels per surface unit ([Toplevel.pixelScale]); follows the server, the interface
     * scale can change while the window is open.
     */
    val scale get() = toplevel.pixelScale
    private var host: WindowHost? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val src = Rect()
    private val dst = Rect()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        // Android's focus highlight would veil the program's pixels in white (16%) whenever
        // the window takes focus after a key (DeX, a keyboard): colours looked washed out.
        defaultFocusHighlightEnabled = false
        setBackgroundColor(Color.BLACK)
        toplevel.view = this
        toplevel.surface.onCommit = { invalidate() }
    }

    // ---- WindowContent ----

    override fun attach(host: WindowHost) {
        this.host = host
        titleChanged()
    }

    override fun onActivated() {
        activatedAt = SystemClock.uptimeMillis()
        requestFocus()
        server.textInputs.addListener(textInputListener)
        server.textInputs.addCommitListener(textInputCommitListener)
        server.seat.setKeyboardFocus(toplevel.surface)
        sendState()
    }

    // ---- on-screen keyboard (zwp_text_input_v3 / keys) ----

    private val imm get() = context.getSystemService(InputMethodManager::class.java)
    private var keyboardAutoShown = false
    /** The keyboard was asked for with the taskbar button: type into any program (as keys). */
    private var keyboardRequested = false
    private var lastTextInput: TextInput? = null
    /** A text field (not a menu) had the focus at the last change. */
    private var lastField = false
    /** The focused text field lost the focus, and whether the keyboard was up then. */
    private var fieldLost = false
    private var keyboardUpWhenFieldLost = false
    /** A menu opened since the field lost the focus (see [KeyboardPolicy.showsByItself]). */
    private var menuSinceFieldLost = false
    private val textInputListener: () -> Unit = { textInputChanged() }
    private val textInputCommitListener: () -> Unit = { textInputCommitted() }

    // The last finger tap, to tell whether the program answered it with a text field.
    private var tapAt = 0L
    private var tapDownX = 0f
    private var tapDownY = 0f
    private var tapInput: TextInput? = null
    private var tapCommits = 0
    private var tapOnCaretLine = false
    private var awaitingTapAnswer = false
    /** The surface the finger went down on, and the row on it (surface units). */
    private var tapSurface: WlSurface? = null
    private var tapSurfaceY = 0.0
    /** When the window last became the active one, and whether a field of it had the focus when it stopped being. */
    private var activatedAt = 0L
    private var fieldWhenDeactivated = false
    /** The tap activated the window; [tapRestored]: the window's field came back since. */
    private var tapActivated = false
    private var tapRestored = false

    /**
     * An editor for Android only while the program has a text field focused (or the user asked
     * for the keyboard): otherwise Android could bring the keyboard up on its own, e.g. when
     * HandTop's window gets the focus back.
     */
    override fun onCheckIsTextEditor() = keyboardRequested || ownTextInput() != null

    private fun ownTextInput() = server.textInputs.active()?.takeIf { it.client === toplevel.client }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        WaylandInputConnection.editorInfo(outAttrs, server.textInputs.active())
        return WaylandInputConnection(this, server)
    }

    /** Taskbar keyboard button. */
    override fun requestKeyboard() {
        keyboardRequested = true
        requestFocus()
        imm?.restartInput(this)
        imm?.showSoftInput(this, 0)
    }

    /**
     * A text field of this program got or lost focus (or changed type): refresh what the
     * keyboard sends. The keyboard does not come up for the field by itself (see
     * [KeyboardPolicy]): only for a tap into it, or back after a menu.
     */
    private fun textInputChanged() {
        val input = ownTextInput()
        imm?.restartInput(this)
        val onMenu = (input?.focused?.role as? SurfaceRole.Xdg)?.xdg?.popup != null
        val field = input != null && !onMenu
        if (lastField && !field) {
            // A menu takes the field's focus before it shows: whether it was a menu is known later.
            fieldLost = true
            keyboardUpWhenFieldLost = keyboardVisible()
            menuSinceFieldLost = false
        }
        if (fieldLost && (onMenu || toplevel.popups.isNotEmpty())) menuSinceFieldLost = true
        if (KeyboardPolicy.showsByItself(
                returningFromMenu = field && lastTextInput == null && menuSinceFieldLost,
                keyboardWasUp = keyboardUpWhenFieldLost,
                hardwareKeyboard = hasHardwareKeyboard(),
            )
        ) {
            requestFocus()
            imm?.showSoftInput(this, 0)
            keyboardAutoShown = true
        } else if (input == null && keyboardAutoShown) {
            hideKeyboard()
        }
        if (field) {
            fieldLost = false
            menuSinceFieldLost = false
        }
        lastField = field
        lastTextInput = input
    }

    private fun keyboardVisible() =
        ViewCompat.getRootWindowInsets(this)?.isVisible(WindowInsetsCompat.Type.ime()) == true

    /**
     * A finger went down: which field had the focus, how far its state had got, and whether
     * the finger is on the line of its caret.
     */
    private fun pressedField(event: MotionEvent) {
        tapDownX = event.x
        tapDownY = event.y
        val input = ownTextInput()
        tapInput = input
        tapCommits = input?.commits ?: 0
        val (surface, at) = hit(event.x, event.y) ?: (null to null)
        tapSurface = surface
        tapSurfaceY = at?.get(1) ?: 0.0
        tapOnCaretLine = onCaretLine(input)
        // The press made the window active (DesktopArea raises it before the window sees the press).
        tapActivated = activatedAt >= event.downTime
        tapRestored = false
    }

    private fun onCaretLine(input: TextInput?) =
        input != null && tapSurface != null && input.focused === tapSurface &&
            KeyboardPolicy.onCaretLine(input.cursorY, input.cursorHeight, tapSurfaceY)

    /**
     * A finger came up: if it was a tap (not a drag that scrolled), see what the program makes
     * of it. Its answer may come before the finger is lifted, or a little after.
     */
    private fun tapped(event: MotionEvent) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        if (Math.hypot((event.x - tapDownX).toDouble(), (event.y - tapDownY).toDouble()) > slop) return
        tapAt = SystemClock.uptimeMillis()
        awaitingTapAnswer = true
        removeCallbacks(checkTapAnswer)
        // The program first answers the tap: a menu it opens takes the text field's focus.
        postDelayed(checkTapAnswer, TAP_KEYBOARD_DELAY_MS)
    }

    /** The program answered something soon after a tap: maybe its field, a moment later. */
    private fun textInputCommitted() {
        val input = ownTextInput()
        if (KeyboardPolicy.restoresField(tapActivated, fieldWhenDeactivated, tapRestored, input)) {
            // The window's own field came back with the focus: the tap is measured from here.
            tapRestored = true
            tapInput = input
            tapCommits = input?.commits ?: 0
            tapOnCaretLine = onCaretLine(input)
            return
        }
        if (!awaitingTapAnswer) return
        if (SystemClock.uptimeMillis() - tapAt > KeyboardPolicy.TAP_ANSWER_MS) {
            awaitingTapAnswer = false
            return
        }
        removeCallbacks(checkTapAnswer)
        postDelayed(checkTapAnswer, ANSWER_SETTLE_MS)
    }

    private val checkTapAnswer = Runnable {
        if (!awaitingTapAnswer) return@Runnable
        val input = ownTextInput()
        val onMenu = (input?.focused?.role as? SurfaceRole.Xdg)?.xdg?.popup != null
        val answered = !onMenu && KeyboardPolicy.fieldAnswered(
            tapInput, tapCommits, input, input?.commits ?: 0, tapOnCaretLine && input === tapInput,
        )
        if (KeyboardPolicy.showsOnTap(answered, hasHardwareKeyboard(), isActiveWindow(), toplevel.popups.isNotEmpty())) {
            awaitingTapAnswer = false
            if (!keyboardVisible()) imm?.showSoftInput(this, 0)
            keyboardAutoShown = true
        } else if (SystemClock.uptimeMillis() - tapAt > KeyboardPolicy.TAP_ANSWER_MS) {
            awaitingTapAnswer = false
        }
    }

    private fun isActiveWindow() = windowControl.isActive()

    private fun hideKeyboard() {
        keyboardAutoShown = false
        imm?.hideSoftInputFromWindow(windowToken, 0)
    }

    private fun hasHardwareKeyboard() =
        resources.configuration.hardKeyboardHidden == android.content.res.Configuration.HARDKEYBOARDHIDDEN_NO

    override fun onDeactivated() {
        fieldWhenDeactivated = ownTextInput() != null
        server.textInputs.removeListener(textInputListener)
        server.textInputs.removeCommitListener(textInputCommitListener)
        removeCallbacks(checkTapAnswer)
        awaitingTapAnswer = false
        keyboardRequested = false
        if (keyboardAutoShown) hideKeyboard()
        // Menus close when their window loses focus, as on a desktop.
        toplevel.popups.toList().forEach { it.dismiss() }
        if (server.seat.keyboardFocus === toplevel.surface) server.seat.setKeyboardFocus(null)
        sendState()
    }

    override fun onCloseRequest(): Boolean {
        toplevel.requestClose()
        return true // the window goes away when the client unmaps it
    }

    override fun onResizeEnded() = toplevel.resizeEnded()

    override fun onClosed() {
        server.textInputs.removeListener(textInputListener)
        popupLayer.unregister(this)
        if (toplevel.view === this) toplevel.view = null
        toplevel.surface.onCommit = null
        if (server.seat.keyboardFocus === toplevel.surface) server.seat.setKeyboardFocus(null)
    }

    // ---- ToplevelView ----

    override fun titleChanged() {
        host?.setTitle(toplevel.title.ifEmpty { toplevel.appId.ifEmpty { "Linux" } })
    }

    override fun appIdChanged() = windowControl.appIdChanged()

    override fun contentChanged() {
        // A menu shown while a text field is without focus: see textInputChanged.
        if (fieldLost && toplevel.popups.isNotEmpty()) menuSinceFieldLost = true
        invalidate()
        popupLayer.invalidate()
    }

    override fun constraintBounds(): Rect? {
        val origin = popupLayer.originOf(this) ?: return null
        val s = scale
        return Rect(
            Math.floorDiv(-origin.x, s), Math.floorDiv(-origin.y, s),
            (popupLayer.width - origin.x) / s, (popupLayer.height - origin.y) / s,
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        popupLayer.register(this)
    }

    override fun onDetachedFromWindow() {
        popupLayer.unregister(this)
        super.onDetachedFromWindow()
    }
    override fun setMaximized(maximized: Boolean) = windowControl.setMaximized(maximized)
    override fun minimize() = windowControl.minimize()
    override fun requestSize(width: Int, height: Int) = windowControl.resizeContent(width * scale, height * scale)

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
        drawSurface(canvas, toplevel.surface, toplevel.visibleGeometry(), 0, 0) // popups: the PopupLayer
    }

    /**
     * Draws the [geometry] part of [surface] (with its sub-surfaces) with its top-left at
     * canvas pixel ([x], [y]).
     */
    fun drawSurface(canvas: Canvas, surface: WlSurface, geometry: Rect, x: Int, y: Int) {
        val g = geometry
        val s = scale
        canvas.save()
        canvas.clipRect(x, y, x + g.width() * s, y + g.height() * s)
        drawTree(canvas, surface, x - g.left * s, y - g.top * s)
        canvas.restore()
    }

    /** Draws [surface] and its sub-surfaces, its origin at canvas pixel ([ox], [oy]). */
    private fun drawTree(canvas: Canvas, surface: WlSurface, ox: Int, oy: Int, depth: Int = 0) {
        if (depth > MAX_DEPTH) return
        val s = scale
        for (part in surface.drawOrder) {
            if (part === surface) {
                val bitmap = surface.bitmap ?: continue
                surface.sourceInBuffer()?.let { src.set(it) } ?: src.set(0, 0, bitmap.width, bitmap.height)
                dst.set(ox, oy, ox + surface.width * s, oy + surface.height * s)
                canvas.drawBitmap(bitmap, src, dst, paint)
            } else {
                val sub = part.subsurface ?: continue
                drawTree(canvas, part, ox + sub.x * s, oy + sub.y * s, depth + 1)
            }
        }
    }

    // ---- input ----

    /** The surface under view pixel (px, py) and the point in its surface coordinates. */
    private fun hit(px: Float, py: Float): Pair<WlSurface, DoubleArray>? {
        val lx = px / scale.toDouble()
        val ly = py / scale.toDouble()
        val g = toplevel.visibleGeometry()
        val x = lx + g.left
        val y = ly + g.top
        return toplevel.surface.surfaceAt(x, y) ?: (toplevel.surface to doubleArrayOf(x, y))
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        lastRawX = event.rawX
        lastRawY = event.rawY
        // The program's drag has the finger: nothing reaches the program (not even the release,
        // which would end its drag) until a new touch.
        if (linuxDragging) {
            if (event.actionMasked != MotionEvent.ACTION_DOWN) return true
            linuxDragging = false
        }
        if (handleGrab(event)) return true
        // An X11 program drags something and the finger leaves its window: HandTop carries it on.
        if (event.actionMasked == MotionEvent.ACTION_MOVE && pressed && toplevel is XwaylandToplevel &&
            (event.x < 0 || event.y < 0 || event.x >= width || event.y >= height) && startX11Drag()
        ) return true
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
                // Programs move a field's caret on the press: note the field state before it.
                if (!event.isFromSource(InputDevice.SOURCE_MOUSE)) pressedField(event)
                motion(event)
                seat.pointerButton(buttonOf(event), true)
            }
            MotionEvent.ACTION_MOVE -> motion(event)
            MotionEvent.ACTION_UP -> {
                pressed = false
                motion(event)
                seat.pointerButton(lastButton, false)
                // A finger tap into a text field brings the keyboard up, like on Android.
                if (!event.isFromSource(InputDevice.SOURCE_MOUSE)) tapped(event)
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = false
                seat.pointerButton(lastButton, false)
            }
        }
        return true
    }

    private var lastButton = BTN_LEFT

    // ---- drag and drop (see DragAndDrop) ----

    /** A drag this window's program started is going on. */
    private var linuxDragging = false

    /** The program started a drag (start_drag) while the finger or a button is down here. */
    fun startLinuxDrag(drag: LinuxDrag): Boolean {
        if (!pressed || grab != Grab.NONE) return false
        val started = startDragAndDrop(ClipData.newPlainText("Linux", ""), LinuxDragShadow(drag.icon), drag, 0)
        if (started) {
            linuxDragging = true
            pressed = false
            server.seat.pointerLeave() // the drag has the pointer until it ends
        }
        return started
    }

    /** The program drew (or changed) its drag icon. */
    fun updateLinuxDragShadow(drag: LinuxDrag) = updateDragShadow(LinuxDragShadow(drag.icon))

    /**
     * The finger left this X11 window while its program drags: the drag goes on as HandTop's,
     * with what the program carries (fetched when it began, see Xwm). The program sees the
     * button go up outside its window, which ends its own drag.
     */
    private fun startX11Drag(): Boolean {
        val drag = server.xwm?.takeX11Drag(this) ?: return false
        if (!startDragAndDrop(ClipData.newPlainText("Linux", ""), LinuxDragShadow(null), drag, 0)) return false
        // The program's own drag must end without a drop: the button going up where the finger
        // last was in its window would drop the files there (onto a folder of its sidebar: a
        // move). Escape cancels a drag in GTK, Qt and Chromium; the button then goes up far
        // outside every window.
        val seat = server.seat
        seat.key(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))
        seat.key(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE))
        seat.pointerFocus?.let { seat.pointerMotion(it, OUTSIDE, OUTSIDE) }
        seat.pointerButton(lastButton, false)
        seat.pointerLeave()
        linuxDragging = true
        pressed = false
        return true
    }

    override fun onDragEvent(event: DragEvent): Boolean {
        val dnd = server.dnd
        val payload = event.localState
        (toplevel as? XwaylandToplevel)?.let { return onX11DragEvent(event, payload, it) }
        when (event.action) {
            // The window a drag started in hears its end in any case.
            DragEvent.ACTION_DRAG_STARTED ->
                return dnd.accepts(payload, toplevel.client) || (payload as? ProgramDrag)?.view === this
            DragEvent.ACTION_DRAG_LOCATION -> hit(event.x, event.y)?.let { (surface, p) -> dnd.over(payload, surface, p[0], p[1]) }
            DragEvent.ACTION_DRAG_EXITED -> dnd.left(payload)
            DragEvent.ACTION_DROP -> {
                val (surface, p) = hit(event.x, event.y) ?: return false
                return dnd.drop(payload, surface, p[0], p[1])
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                if ((payload as? ProgramDrag)?.view === this) linuxDragging = false
                dnd.ended(payload, event.result)
            }
        }
        return true
    }

    /** A drag over an X11 window: XDND, HandTop being the source (see Xwm). */
    private fun onX11DragEvent(event: DragEvent, payload: Any?, top: XwaylandToplevel): Boolean {
        val xwm = server.xwm
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED ->
                return xwm?.dragAccepts(payload) == true || (payload as? ProgramDrag)?.view === this
            DragEvent.ACTION_DRAG_LOCATION -> hit(event.x, event.y)?.let { (_, p) ->
                xwm?.dragOver(payload, top.window, p[0].toInt(), p[1].toInt())
            }
            DragEvent.ACTION_DRAG_EXITED -> xwm?.dragLeft(payload)
            DragEvent.ACTION_DROP -> {
                val (_, p) = hit(event.x, event.y) ?: return false
                return xwm?.dragDrop(payload, top.window, p[0].toInt(), p[1].toInt()) == true
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                if ((payload as? ProgramDrag)?.view === this) linuxDragging = false
                xwm?.dragEnded(payload)
                server.dnd.ended(payload, event.result) // a Wayland program's drag hears how it ended
            }
        }
        return true
    }

    /** Under the finger: the program's drag icon, or a file icon until it draws one. */
    private inner class LinuxDragShadow(iconSurface: WlSurface?) : DragShadowBuilder() {
        private val icon = iconSurface?.bitmap?.takeIf { it.width > 0 && it.height > 0 }
        private val iconScale = scale.toFloat() / (iconSurface?.bufferScale ?: 1).coerceAtLeast(1)
        private val chip = (resources.displayMetrics.density * 48).toInt()
        private var w = chip
        private var h = chip

        override fun onProvideShadowMetrics(outShadowSize: Point, outShadowTouchPoint: Point) {
            if (icon != null) {
                w = (icon.width * iconScale).toInt().coerceIn(1, chip * 4)
                h = (icon.height * iconScale).toInt().coerceIn(1, chip * 4)
            }
            outShadowSize.set(w, h)
            outShadowTouchPoint.set(w / 2, h / 2)
        }

        override fun onDrawShadow(canvas: Canvas) {
            if (icon != null) {
                canvas.drawBitmap(icon, null, Rect(0, 0, w, h), paint)
                return
            }
            val back = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC202833.toInt() }
            canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), w / 5f, w / 5f, back)
            context.getDrawable(R.drawable.ic_file)?.apply {
                setTint(Color.WHITE)
                setBounds(w / 5, h / 5, w - w / 5, h - h / 5)
                draw(canvas)
            }
        }
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

    private fun motion(event: MotionEvent) {
        val (surface, p) = hit(event.x, event.y) ?: return
        server.seat.cursorView = this
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
        const val TAP_KEYBOARD_DELAY_MS = 150L

        /** After the program's answer, a moment for a menu it opens to appear. */
        const val ANSWER_SETTLE_MS = 50L

        /** A point far outside every X11 window (surface units), where nothing takes a drop. */
        const val OUTSIDE = -100000.0

        // xdg_toplevel.resize_edge bits
        const val XDG_EDGE_TOP = 1
        const val XDG_EDGE_BOTTOM = 2
        const val XDG_EDGE_LEFT = 4
        const val XDG_EDGE_RIGHT = 8

        const val BTN_LEFT = 0x110
        const val BTN_RIGHT = 0x111
        const val BTN_MIDDLE = 0x112
        const val SCROLL_STEP = 15.0
        const val MAX_DEPTH = 16
    }
}
