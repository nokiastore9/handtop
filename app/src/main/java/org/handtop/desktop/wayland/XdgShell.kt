package org.handtop.desktop.wayland

import android.graphics.Rect

// xdg-shell (stable): windows and popups.

class XdgWmBase(client: Client, id: Int, version: Int) : Resource(client, id, version) {
    override val iface = "xdg_wm_base"

    override fun dispatch(opcode: Int, r: MessageReader) {
        when (opcode) {
            0 -> destroy()
            1 -> client.register(XdgPositioner(client, r.newId(), version))
            2 -> { // get_xdg_surface(id, surface)
                val newId = r.newId()
                val surface = client.require<WlSurface>(r.obj())
                val xdg = client.register(XdgSurface(client, newId, version, surface))
                surface.role = SurfaceRole.Xdg(xdg)
            }
            3 -> r.uint() // pong
            else -> super.dispatch(opcode, r)
        }
    }
}

class XdgPositioner(client: Client, id: Int, version: Int) : Resource(client, id, version) {
    override val iface = "xdg_positioner"

    var width = 1
    var height = 1
    val anchorRect = Rect()
    var anchor = 0
    var gravity = 0
    var offsetX = 0
    var offsetY = 0
    var constraintAdjustment = 0

    override fun dispatch(opcode: Int, r: MessageReader) {
        when (opcode) {
            0 -> destroy()
            1 -> { width = r.int(); height = r.int() }
            2 -> { val x = r.int(); val y = r.int(); anchorRect.set(x, y, x + r.int(), y + r.int()) }
            3 -> anchor = r.uint()
            4 -> gravity = r.uint()
            5 -> constraintAdjustment = r.uint()
            6 -> { offsetX = r.int(); offsetY = r.int() }
            7 -> Unit // set_reactive
            8 -> { r.int(); r.int() } // set_parent_size
            9 -> r.uint() // set_parent_configure
            else -> super.dispatch(opcode, r)
        }
    }

    /**
     * Popup position relative to the parent's window geometry. With [bounds] (the desktop in the
     * same coordinates) the client's constraint adjustment is applied: flip, then slide, then
     * resize, per axis, so menus stay on screen.
     */
    fun place(bounds: Rect? = null): Rect {
        val rect = Rect(xFor(anchor, gravity), yFor(anchor, gravity), 0, 0)
        rect.right = rect.left + width
        rect.bottom = rect.top + height
        if (bounds == null || bounds.isEmpty) return rect

        if (rect.left < bounds.left || rect.right > bounds.right) {
            if (constraintAdjustment and FLIP_X != 0) {
                val x = xFor(flipX(anchor), flipX(gravity))
                if (x >= bounds.left && x + width <= bounds.right) rect.offsetTo(x, rect.top)
            }
            if (constraintAdjustment and SLIDE_X != 0) {
                if (rect.right > bounds.right) rect.offset(bounds.right - rect.right, 0)
                if (rect.left < bounds.left) rect.offset(bounds.left - rect.left, 0)
            }
            if (constraintAdjustment and RESIZE_X != 0) {
                rect.left = rect.left.coerceAtLeast(bounds.left)
                rect.right = rect.right.coerceAtMost(bounds.right)
            }
        }
        if (rect.top < bounds.top || rect.bottom > bounds.bottom) {
            if (constraintAdjustment and FLIP_Y != 0) {
                val y = yFor(flipY(anchor), flipY(gravity))
                if (y >= bounds.top && y + height <= bounds.bottom) rect.offsetTo(rect.left, y)
            }
            if (constraintAdjustment and SLIDE_Y != 0) {
                if (rect.bottom > bounds.bottom) rect.offset(0, bounds.bottom - rect.bottom)
                if (rect.top < bounds.top) rect.offset(0, bounds.top - rect.top)
            }
            if (constraintAdjustment and RESIZE_Y != 0) {
                rect.top = rect.top.coerceAtLeast(bounds.top)
                rect.bottom = rect.bottom.coerceAtMost(bounds.bottom)
            }
        }
        if (rect.width() <= 0 || rect.height() <= 0) rect.set(rect.left, rect.top, rect.left + 1, rect.top + 1)
        return rect
    }

    private fun xFor(anchor: Int, gravity: Int): Int {
        val ax = when (anchor) {
            LEFT, TOP_LEFT, BOTTOM_LEFT -> anchorRect.left
            RIGHT, TOP_RIGHT, BOTTOM_RIGHT -> anchorRect.right
            else -> anchorRect.centerX()
        }
        return when (gravity) {
            LEFT, TOP_LEFT, BOTTOM_LEFT -> ax - width
            RIGHT, TOP_RIGHT, BOTTOM_RIGHT -> ax
            else -> ax - width / 2
        } + offsetX
    }

    private fun yFor(anchor: Int, gravity: Int): Int {
        val ay = when (anchor) {
            TOP, TOP_LEFT, TOP_RIGHT -> anchorRect.top
            BOTTOM, BOTTOM_LEFT, BOTTOM_RIGHT -> anchorRect.bottom
            else -> anchorRect.centerY()
        }
        return when (gravity) {
            TOP, TOP_LEFT, TOP_RIGHT -> ay - height
            BOTTOM, BOTTOM_LEFT, BOTTOM_RIGHT -> ay
            else -> ay - height / 2
        } + offsetY
    }

    private fun flipX(edge: Int) = when (edge) {
        LEFT -> RIGHT
        RIGHT -> LEFT
        TOP_LEFT -> TOP_RIGHT
        TOP_RIGHT -> TOP_LEFT
        BOTTOM_LEFT -> BOTTOM_RIGHT
        BOTTOM_RIGHT -> BOTTOM_LEFT
        else -> edge
    }

    private fun flipY(edge: Int) = when (edge) {
        TOP -> BOTTOM
        BOTTOM -> TOP
        TOP_LEFT -> BOTTOM_LEFT
        BOTTOM_LEFT -> TOP_LEFT
        TOP_RIGHT -> BOTTOM_RIGHT
        BOTTOM_RIGHT -> TOP_RIGHT
        else -> edge
    }

    private companion object {
        const val TOP = 1
        const val BOTTOM = 2
        const val LEFT = 3
        const val RIGHT = 4
        const val TOP_LEFT = 5
        const val BOTTOM_LEFT = 6
        const val TOP_RIGHT = 7
        const val BOTTOM_RIGHT = 8

        // constraint_adjustment bits
        const val SLIDE_X = 1
        const val SLIDE_Y = 2
        const val FLIP_X = 4
        const val FLIP_Y = 8
        const val RESIZE_X = 16
        const val RESIZE_Y = 32
    }
}

class XdgSurface(client: Client, id: Int, version: Int, val surface: WlSurface) : Resource(client, id, version) {
    override val iface = "xdg_surface"

    var toplevel: XdgToplevel? = null
        private set
    var popup: XdgPopup? = null
        private set

    /** Window geometry in surface coordinates (excludes client-side shadows). */
    val geometry = Rect()
    private var geometrySet = false
    private var configured = false

    override fun dispatch(opcode: Int, r: MessageReader) {
        when (opcode) {
            0 -> destroy()
            1 -> toplevel = client.register(XdgToplevel(client, r.newId(), version, this))
            2 -> { // get_popup(id, parent, positioner)
                val newId = r.newId()
                val parent = client.optional<XdgSurface>(r.obj())
                val positioner = client.require<XdgPositioner>(r.obj())
                popup = client.register(
                    XdgPopup(client, newId, version, this, parent, positioner.place(XdgPopup.boundsFor(parent))),
                )
            }
            3 -> { // set_window_geometry
                val x = r.int(); val y = r.int()
                geometry.set(x, y, x + r.int(), y + r.int())
                geometrySet = true
            }
            4 -> { r.uint(); configured = true } // ack_configure
            else -> super.dispatch(opcode, r)
        }
    }

    /** Geometry to show: the declared window geometry, else the whole surface. */
    fun visibleGeometry(): Rect =
        if (geometrySet && !geometry.isEmpty) geometry else Rect(0, 0, surface.width, surface.height)

    fun configure() = event(0) { uint(client.server.nextSerial()) }

    fun surfaceCommitted() {
        toplevel?.committed(configured)
        popup?.committed(configured)
    }

    fun surfaceGone() {
        toplevel?.unmap()
        popup?.unmap()
    }

    override fun destroyed() {
        toplevel?.unmap()
        popup?.unmap()
    }
}

class XdgToplevel(client: Client, id: Int, version: Int, val xdg: XdgSurface) : Resource(client, id, version) {
    override val iface = "xdg_toplevel"

    var title = ""
        private set
    var appId = ""
        private set
    var mapped = false
        private set

    /** Owner window for dialogs (xdg_toplevel.set_parent). */
    var parent: XdgToplevel? = null
        private set

    /** Size limits in logical pixels; 0 = no limit. */
    var minWidth = 0
        private set
    var minHeight = 0
        private set
    var maxWidth = 0
        private set
    var maxHeight = 0
        private set

    /** Dialogs and the like: the program allows exactly one size. */
    val fixedSize: Boolean
        get() = maxWidth > 0 && maxHeight > 0 && minWidth == maxWidth && minHeight == maxHeight

    /** Negotiated through xdg-decoration; otherwise the program draws its own title bar. */
    var serverSideDecorations = false
        set(value) {
            if (field == value) return
            field = value
            view?.decorationsChanged()
        }
    val popups = ArrayList<XdgPopup>()

    /** Window shown on the desktop; set by the host when it maps the toplevel. */
    var view: ToplevelView? = null

    private var initialConfigureSent = false
    private var configuredWidth = 0
    private var configuredHeight = 0
    private var activated = false
    private var maximized = false

    override fun dispatch(opcode: Int, r: MessageReader) {
        when (opcode) {
            0 -> destroy()
            1 -> parent = client.optional<XdgToplevel>(r.obj())
            2 -> { title = r.string() ?: ""; view?.titleChanged() }
            3 -> appId = r.string() ?: ""
            4 -> { r.obj(); r.uint(); r.int(); r.int() } // show_window_menu
            5 -> { r.obj(); r.uint(); view?.startMove() } // move(seat, serial)
            6 -> { r.obj(); r.uint(); view?.startResize(r.uint()) } // resize(seat, serial, edges)
            7 -> { maxWidth = r.int(); maxHeight = r.int() }
            8 -> { minWidth = r.int(); minHeight = r.int() }
            9 -> view?.setMaximized(true)
            10 -> view?.setMaximized(false)
            11 -> r.obj() // set_fullscreen
            12 -> Unit
            13 -> view?.minimize()
            else -> super.dispatch(opcode, r)
        }
    }

    fun committed(configured: Boolean) {
        if (!initialConfigureSent) {
            // First commit (no buffer yet): let the client pick its size.
            initialConfigureSent = true
            sendConfigure()
            return
        }
        if (!mapped && configured && xdg.surface.bitmap != null) {
            mapped = true
            xdg.surface.enterOutputs()
            client.server.host?.toplevelMapped(this)
        } else if (mapped && xdg.surface.bitmap == null) {
            unmap()
        }
    }

    /** New size (logical pixels) and state requested by the window manager. */
    fun requestState(width: Int, height: Int, activated: Boolean, maximized: Boolean) {
        if (width == configuredWidth && height == configuredHeight &&
            activated == this.activated && maximized == this.maximized
        ) return
        configuredWidth = width
        configuredHeight = height
        this.activated = activated
        this.maximized = maximized
        if (initialConfigureSent) {
            sendConfigure()
            client.flush()
        }
    }

    fun scheduleConfigure() {
        if (initialConfigureSent) sendConfigure()
    }

    private fun sendConfigure() {
        val states = ArrayList<Int>()
        if (maximized) states += STATE_MAXIMIZED
        if (activated) states += STATE_ACTIVATED
        event(0) { int(configuredWidth); int(configuredHeight); intArray(states.toIntArray()) }
        xdg.configure()
    }

    /** The user pressed the window's close button. */
    fun requestClose() {
        event(1)
        client.flush()
    }

    fun unmap() {
        if (!mapped) return
        mapped = false
        popups.toList().forEach { it.dismiss() }
        client.server.host?.toplevelUnmapped(this)
    }

    override fun destroyed() = unmap()

    private companion object {
        const val STATE_MAXIMIZED = 1
        const val STATE_ACTIVATED = 4
    }
}

class XdgPopup(
    client: Client, id: Int, version: Int,
    val xdg: XdgSurface,
    private val parent: XdgSurface?,
    /** Position relative to the parent's window geometry. */
    val position: Rect,
) : Resource(client, id, version) {
    override val iface = "xdg_popup"

    var mapped = false
        private set
    private var configureSent = false

    /** The toplevel this popup (possibly nested) belongs to. */
    val toplevel: XdgToplevel? = parent?.toplevel ?: parent?.popup?.toplevel

    /** Offset of the popup's geometry origin from the toplevel's geometry origin. */
    val originInToplevel: Rect
        get() {
            val parentOrigin = parent?.popup?.originInToplevel
            val dx = (parentOrigin?.left ?: 0)
            val dy = (parentOrigin?.top ?: 0)
            return Rect(position).apply { offset(dx, dy) }
        }

    override fun dispatch(opcode: Int, r: MessageReader) {
        when (opcode) {
            0 -> destroy()
            1 -> { // grab(seat, serial): menus take the keyboard (arrow keys, Escape)
                r.obj(); r.uint()
                client.server.seat.setKeyboardFocus(xdg.surface)
            }
            2 -> { // reposition(positioner, token)
                val positioner = client.require<XdgPositioner>(r.obj())
                val token = r.uint()
                position.set(positioner.place(boundsFor(parent)))
                event(2) { uint(token) }
                sendConfigure()
            }
            else -> super.dispatch(opcode, r)
        }
    }

    private fun sendConfigure() {
        event(0) { int(position.left); int(position.top); int(position.width()); int(position.height()) }
        xdg.configure()
    }

    fun committed(configured: Boolean) {
        if (!configureSent) {
            configureSent = true
            sendConfigure()
            return
        }
        if (!mapped && configured && xdg.surface.bitmap != null) {
            mapped = true
            toplevel?.popups?.add(this)
            xdg.surface.enterOutputs()
        }
        toplevel?.view?.contentChanged()
    }

    /** Closes the popup (click outside it): the client destroys it in response. */
    fun dismiss() {
        event(1)
        unmap()
        client.flush()
    }

    fun unmap() {
        if (!mapped) return
        mapped = false
        toplevel?.popups?.remove(this)
        toplevel?.view?.contentChanged()
        // A grabbing menu had the keyboard: give it back to the menu or window below.
        val seat = client.server.seat
        if (seat.keyboardFocus === xdg.surface) seat.setKeyboardFocus(parent?.surface)
    }

    override fun destroyed() = unmap()

    companion object {
        /** The desktop in the coordinates of the parent window geometry, for constraint adjustment. */
        fun boundsFor(parent: XdgSurface?): Rect? {
            val toplevel = parent?.toplevel ?: parent?.popup?.toplevel ?: return null
            val bounds = toplevel.view?.constraintBounds() ?: return null
            parent?.popup?.originInToplevel?.let { bounds.offset(-it.left, -it.top) }
            return bounds
        }
    }
}

/** What the window manager provides for a mapped toplevel. */
interface ToplevelView {
    fun titleChanged()
    fun contentChanged()
    fun decorationsChanged()

    /** The desktop area in toplevel-geometry coordinates (logical), where popups must fit. */
    fun constraintBounds(): Rect?

    /** Interactive move / resize, requested while a pointer button is held (client title bar). */
    fun startMove()
    fun startResize(edges: Int)
    fun setMaximized(maximized: Boolean)
    fun minimize()
}
