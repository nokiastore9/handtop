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

    /** The rules as they are now; the popup keeps them (the client may destroy this positioner). */
    fun rules() = PopupRules(
        width, height,
        Box(anchorRect.left, anchorRect.top, anchorRect.right, anchorRect.bottom),
        anchor, gravity, offsetX, offsetY, constraintAdjustment,
    )
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
                    XdgPopup(client, newId, version, this, parent, Placement(positioner.rules(), XdgPopup.boundsFor(parent))),
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

class XdgToplevel(client: Client, id: Int, version: Int, val xdg: XdgSurface) :
    Resource(client, id, version), Toplevel {
    override val pixelScale get() = client.server.scale
    override val iface = "xdg_toplevel"

    override val surface get() = xdg.surface
    override var title = ""
        private set
    override var appId = ""
        private set
    override var mapped = false
        private set

    /** Owner window for dialogs (xdg_toplevel.set_parent). */
    override var parent: XdgToplevel? = null
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

    override val fixedSize: Boolean
        get() = maxWidth > 0 && maxHeight > 0 && minWidth == maxWidth && minHeight == maxHeight

    /** Negotiated through xdg-decoration; otherwise the program draws its own title bar. */
    override var serverSideDecorations = false
        set(value) {
            if (field == value) return
            field = value
            view?.decorationsChanged()
        }
    override val popups = ArrayList<XdgPopup>()

    override var view: ToplevelView? = null

    override fun visibleGeometry() = xdg.visibleGeometry()

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
            3 -> { appId = r.string() ?: ""; view?.appIdChanged() }
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

    override fun requestState(width: Int, height: Int, activated: Boolean, maximized: Boolean) {
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

    override fun requestClose() {
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
    internal val parent: XdgSurface?,
    /** Its rules and its position relative to the parent's window geometry. */
    private val placement: Placement,
) : Resource(client, id, version), Popup {
    override val iface = "xdg_popup"

    override val surface get() = xdg.surface
    override fun visibleGeometry() = xdg.visibleGeometry()

    /** xdg_popup.grab: a menu. Without a grab (tooltips) a click elsewhere goes through. */
    private var grabbed = false
    override val closesOnOutsideClick get() = grabbed

    var mapped = false
        private set
    private var configureSent = false

    /** The toplevel this popup (possibly nested) belongs to. */
    val toplevel: XdgToplevel? = parent?.toplevel ?: parent?.popup?.toplevel

    override val originInToplevel: Rect
        get() {
            val parentOrigin = parent?.popup?.originInToplevel
            val dx = (parentOrigin?.left ?: 0)
            val dy = (parentOrigin?.top ?: 0)
            val p = placement.position
            return Rect(p.left + dx, p.top + dy, p.right + dx, p.bottom + dy)
        }

    override fun dispatch(opcode: Int, r: MessageReader) {
        when (opcode) {
            0 -> destroy()
            1 -> { // grab(seat, serial): menus take the keyboard (arrow keys, Escape)
                r.obj(); r.uint()
                grabbed = true
                client.server.seat.setKeyboardFocus(xdg.surface)
            }
            2 -> { // reposition(positioner, token)
                val positioner = client.require<XdgPositioner>(r.obj())
                val token = r.uint()
                placement.reposition(positioner.rules(), boundsFor(parent))
                event(2) { uint(token) }
                sendConfigure()
            }
            else -> super.dispatch(opcode, r)
        }
    }

    private fun sendConfigure() {
        val p = placement.position
        event(0) { int(p.left); int(p.top); int(p.width); int(p.height) }
        xdg.configure()
    }

    /**
     * The room around the popup changed (the on-screen keyboard hid, the window moved): place it
     * again from its rules and tell the program, as for a reactive positioner. Otherwise a menu
     * slid up while the keyboard was shown would stay up when it hides.
     */
    override fun reconstrain() {
        if (!configureSent) return
        if (!placement.reconstrain(boundsFor(parent))) return
        sendConfigure()
        client.flush()
        toplevel?.view?.contentChanged()
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

    /** The client destroys the popup in response. */
    override fun dismiss() {
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
        if (seat.keyboardFocus === xdg.surface) seat.setKeyboardFocus(openAncestorSurface())
    }

    /**
     * The nearest parent still shown: a menu, or the window. A click outside closes the menus
     * parent first, so a submenu closing after its menu must not give the keyboard to that
     * closed menu; the window would never get it back and typing would go nowhere.
     */
    private fun openAncestorSurface(): WlSurface? {
        var p = parent
        while (p != null) {
            val popup = p.popup ?: return p.surface // the window
            if (popup.mapped) return p.surface
            p = popup.parent
        }
        return null
    }

    override fun destroyed() = unmap()

    companion object {
        /** The desktop in the coordinates of the parent window geometry, for constraint adjustment. */
        fun boundsFor(parent: XdgSurface?): Box? {
            val toplevel = parent?.toplevel ?: parent?.popup?.toplevel ?: return null
            val bounds = toplevel.view?.constraintBounds() ?: return null
            parent?.popup?.originInToplevel?.let { bounds.offset(-it.left, -it.top) }
            return Box(bounds.left, bounds.top, bounds.right, bounds.bottom)
        }
    }
}

/** What the window manager provides for a mapped toplevel. */
interface ToplevelView {
    /** False while the window is hidden (minimized, HandTop not on screen). */
    val isShownOnScreen: Boolean get() = true
    fun titleChanged()
    /** The app id (xdg) / WM_CLASS (X11) arrived or changed: the icon may change. */
    fun appIdChanged() = Unit
    fun contentChanged()
    fun decorationsChanged()

    /** The desktop area in toplevel-geometry coordinates (logical), where popups must fit. */
    fun constraintBounds(): Rect?

    /** Interactive move / resize, requested while a pointer button is held (client title bar). */
    fun startMove()
    fun startResize(edges: Int)
    fun setMaximized(maximized: Boolean)
    fun minimize()

    /**
     * The program asks for a new size ([width] x [height] in its surface coordinates). False
     * when the window keeps its size: maximized or snapped, the desktop decides.
     */
    fun requestSize(width: Int, height: Int): Boolean = false
}
