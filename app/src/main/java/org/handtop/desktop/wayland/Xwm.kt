package org.handtop.desktop.wayland

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.handtop.desktop.wayland.X11Connection.Companion.ATOM_ATOM
import org.handtop.desktop.wayland.X11Connection.Companion.ATOM_CARDINAL
import org.handtop.desktop.wayland.X11Connection.Companion.ATOM_STRING
import org.handtop.desktop.wayland.X11Connection.Companion.ATOM_WINDOW
import org.handtop.desktop.wayland.X11Connection.Companion.ATOM_WM_CLASS
import org.handtop.desktop.wayland.X11Connection.Companion.ATOM_WM_NAME
import org.handtop.desktop.wayland.X11Connection.Companion.ATOM_WM_NORMAL_HINTS
import org.handtop.desktop.wayland.X11Connection.Companion.ATOM_WM_TRANSIENT_FOR
import org.handtop.desktop.wayland.X11Connection.Companion.CONFIG_HEIGHT
import org.handtop.desktop.wayland.X11Connection.Companion.CONFIG_STACK_MODE
import org.handtop.desktop.wayland.X11Connection.Companion.CONFIG_WIDTH
import org.handtop.desktop.wayland.X11Connection.Companion.CONFIG_X
import org.handtop.desktop.wayland.X11Connection.Companion.CONFIG_Y
import org.handtop.desktop.wayland.X11Connection.Companion.CW_EVENT_MASK
import org.handtop.desktop.wayland.X11Connection.Companion.CW_OVERRIDE_REDIRECT
import org.handtop.desktop.wayland.X11Connection.Companion.EV_CLIENT_MESSAGE
import org.handtop.desktop.wayland.X11Connection.Companion.EV_CONFIGURE_NOTIFY
import org.handtop.desktop.wayland.X11Connection.Companion.EV_CONFIGURE_REQUEST
import org.handtop.desktop.wayland.X11Connection.Companion.EV_CREATE_NOTIFY
import org.handtop.desktop.wayland.X11Connection.Companion.EV_DESTROY_NOTIFY
import org.handtop.desktop.wayland.X11Connection.Companion.EV_MAP_NOTIFY
import org.handtop.desktop.wayland.X11Connection.Companion.EV_MAP_REQUEST
import org.handtop.desktop.wayland.X11Connection.Companion.EV_PROPERTY_NOTIFY
import org.handtop.desktop.wayland.X11Connection.Companion.EV_UNMAP_NOTIFY
import org.handtop.desktop.wayland.X11Connection.Companion.FOCUS_CHANGE
import org.handtop.desktop.wayland.X11Connection.Companion.PROPERTY_CHANGE
import org.handtop.desktop.wayland.X11Connection.Companion.STACK_ABOVE
import org.handtop.desktop.wayland.X11Connection.Companion.STRUCTURE_NOTIFY
import org.handtop.desktop.wayland.X11Connection.Companion.SUBSTRUCTURE_NOTIFY
import org.handtop.desktop.wayland.X11Connection.Companion.SUBSTRUCTURE_REDIRECT

/** What HandTop knows about one X11 window (a child of the root window). */
class XWindow(val id: Int, var overrideRedirect: Boolean, var x: Int, var y: Int, var width: Int, var height: Int) {
    /** The X server mapped the window (MapNotify). */
    var mapped = false
    var surface: WlSurface? = null

    var wmName: String? = null
    var netName: String? = null
    /** WM_CLASS class part, e.g. "XEyes". */
    var wmClass = ""
    var transientFor = 0
    var protocols: Set<Int> = emptySet()
    var types: List<Int> = emptyList()
    var minWidth = 0
    var minHeight = 0
    var maxWidth = 0
    var maxHeight = 0
    /** _MOTIF_WM_HINTS did not turn decorations off. */
    var decorations = true
    var netState: List<Int> = emptyList()

    var toplevel: XwaylandToplevel? = null
    var popup: XwaylandPopup? = null
}

/** An X11 window shown as a desktop window. */
class XwaylandToplevel(private val xwm: Xwm, val window: XWindow, override val surface: WlSurface) : Toplevel {
    override val client get() = surface.client
    override val title get() = window.netName ?: window.wmName ?: ""
    override val appId get() = window.wmClass
    override val parent get() = xwm.toplevelOf(window.transientFor)
    override val fixedSize
        get() = window.maxWidth > 0 && window.maxHeight > 0 &&
            window.minWidth == window.maxWidth && window.minHeight == window.maxHeight
    override val serverSideDecorations get() = window.decorations
    override val popups = ArrayList<XwaylandPopup>()
    override var mapped = true
    override var view: ToplevelView? = null

    override fun visibleGeometry() = Rect(0, 0, surface.width, surface.height)
    override fun requestState(width: Int, height: Int, activated: Boolean, maximized: Boolean) =
        xwm.requestState(this, width, height, activated, maximized)
    override fun requestClose() = xwm.close(window)
    override fun moved(x: Int, y: Int) = xwm.move(window, x, y)
}

/** An override-redirect X11 window (menu, tooltip…) drawn over the window it belongs to. */
class XwaylandPopup(
    private val xwm: Xwm,
    val window: XWindow,
    val owner: XwaylandToplevel,
    override val surface: WlSurface,
) : Popup {
    override val originInToplevel: Rect
        get() {
            val x = window.x - owner.window.x
            val y = window.y - owner.window.y
            return Rect(x, y, x + surface.width, y + surface.height)
        }
    override val takesInput get() = !xwm.isPassive(window)
    override fun visibleGeometry() = Rect(0, 0, surface.width, surface.height)
    override fun dismiss() = xwm.clickOutside(this)
}

/**
 * HandTop as the X11 window manager of a rootless Xwayland: every X11 program window becomes a
 * desktop window like a Wayland one, menus and tooltips (override-redirect windows) are drawn
 * by the [PopupLayer]. X11 windows are tied to their wl_surfaces through xwayland_shell_v1
 * serials (or the older WL_SURFACE_ID message). X root coordinates are desktop logical pixels.
 */
class Xwm(private val server: WaylandServer, private val x: X11Connection) {

    private val main = Handler(Looper.getMainLooper())
    private val atoms = HashMap<String, Int>()
    private val windows = HashMap<Int, XWindow>()
    private var wmWindow = 0
    private var focused: XWindow? = null
    @Volatile
    var ready = false
        private set

    /** Surfaces Xwayland announced with a serial, waiting for (or tied to) their X window. */
    private val surfacesBySerial = HashMap<Long, XwaylandSurface>()
    private val windowsBySerial = HashMap<Long, XWindow>()

    /** Called on the main thread when the X server goes away. */
    var onClosed: (() -> Unit)? = null

    private fun a(name: String) = atoms[name] ?: -1

    fun start() {
        x.onEvent = ::handleEvent
        x.onError = { e -> Log.d(TAG, "X error ${e.u8(1)} (request ${e.u8(10)}, value 0x${Integer.toHexString(e.u32(4))})") }
        x.onClosed = { shutdown() }
        var left = ATOM_NAMES.size
        for (name in ATOM_NAMES) {
            x.internAtom(name) {
                atoms[name] = it
                if (--left == 0) becomeWindowManager()
            }
        }
    }

    private fun becomeWindowManager() {
        x.changeWindowAttributes(x.root, CW_EVENT_MASK, SUBSTRUCTURE_REDIRECT or SUBSTRUCTURE_NOTIFY or PROPERTY_CHANGE)
        wmWindow = x.newId()
        x.createWindow(wmWindow, x.root, -100, -100, 1, 1, true, CW_OVERRIDE_REDIRECT, 1)
        x.changeProperty32(wmWindow, a("_NET_SUPPORTING_WM_CHECK"), ATOM_WINDOW, wmWindow)
        x.changeProperty32(x.root, a("_NET_SUPPORTING_WM_CHECK"), ATOM_WINDOW, wmWindow)
        x.changePropertyUtf8(wmWindow, a("_NET_WM_NAME"), a("UTF8_STRING"), "HandTop")
        x.changeProperty32(x.root, a("_NET_SUPPORTED"), ATOM_ATOM, *SUPPORTED.map { a(it) }.toIntArray())
        x.queryExtension("Composite") { composite ->
            if (composite == 0) {
                Log.e(TAG, "the X server has no Composite extension: X11 windows cannot be shown")
                return@queryExtension
            }
            x.redirectSubwindowsManually(composite, x.root) { ok ->
                if (!ok) {
                    Log.e(TAG, "Composite redirection failed")
                    return@redirectSubwindowsManually
                }
                ready = true
                Log.i(TAG, "X11 window manager ready (root ${x.rootWidth}x${x.rootHeight})")
            }
        }
    }

    private fun shutdown() {
        ready = false
        for (win in windows.values.toList()) hide(win)
        windows.clear()
        surfacesBySerial.clear()
        windowsBySerial.clear()
        Log.i(TAG, "X server gone")
        onClosed?.invoke()
    }

    /** Shown X11 windows, e.g. to re-create their desktop windows after an activity restart. */
    fun toplevels(): List<Toplevel> = windows.values.mapNotNull { it.toplevel }

    fun toplevelOf(id: Int): XwaylandToplevel? = if (id == 0) null else windows[id]?.toplevel

    // ---- events ----

    private fun handleEvent(e: XMessage) {
        if (DEBUG) Log.d(TAG, "event ${e.code} window 0x${Integer.toHexString(e.u32(if (e.code == EV_PROPERTY_NOTIFY || e.code == EV_CLIENT_MESSAGE) 4 else 8))}")
        when (e.code) {
            EV_CREATE_NOTIFY -> {
                val id = e.u32(8)
                if (id == wmWindow) return
                windows[id] = XWindow(id, e.u8(22) != 0, e.s16(12), e.s16(14), e.u16(16), e.u16(18))
                x.changeWindowAttributes(id, CW_EVENT_MASK, PROPERTY_CHANGE or FOCUS_CHANGE)
            }
            EV_MAP_REQUEST -> windows[e.u32(8)]?.let { manage(it) }
            EV_MAP_NOTIFY -> windows[e.u32(8)]?.let { win ->
                win.mapped = true
                win.overrideRedirect = e.u8(12) != 0
                // Menus and tooltips are never managed: read what they are when they appear.
                if (win.overrideRedirect) readProperties(win) { update(win) } else update(win)
            }
            EV_UNMAP_NOTIFY -> windows[e.u32(8)]?.let { unmapped(it) }
            EV_DESTROY_NOTIFY -> windows.remove(e.u32(8))?.let { win ->
                win.mapped = false
                hide(win)
                windowsBySerial.values.remove(win)
                if (focused === win) focused = null
            }
            EV_CONFIGURE_REQUEST -> configureRequest(e)
            EV_CONFIGURE_NOTIFY -> windows[e.u32(8)]?.let { win ->
                win.x = e.s16(16); win.y = e.s16(18)
                win.width = e.u16(20); win.height = e.u16(22)
                win.popup?.owner?.view?.contentChanged() // a menu moved
            }
            EV_PROPERTY_NOTIFY -> windows[e.u32(4)]?.let { propertyChanged(it, e.u32(8)) }
            EV_CLIENT_MESSAGE -> clientMessage(e)
        }
    }

    /** A program wants to show a window (MapRequest): read its properties, then map it. */
    private fun manage(win: XWindow) {
        readProperties(win) {
            x.changeProperty32(win.id, a("WM_STATE"), a("WM_STATE"), WM_STATE_NORMAL, 0)
            x.changeProperty32(win.id, a("_NET_FRAME_EXTENTS"), ATOM_CARDINAL, 0, 0, 0, 0)
            x.mapWindow(win.id)
        }
    }

    private fun unmapped(win: XWindow) {
        win.mapped = false
        if (!win.overrideRedirect) x.changeProperty32(win.id, a("WM_STATE"), a("WM_STATE"), WM_STATE_WITHDRAWN, 0)
        hide(win)
        if (focused === win) focused = null
    }

    private fun configureRequest(e: XMessage) {
        val win = windows[e.u32(8)] ?: return
        val mask = e.u16(26)
        if (win.toplevel != null) {
            // On the desktop, HandTop decides the geometry; tell the program where it is (ICCCM 4.1.5).
            sendConfigureNotify(win)
            return
        }
        // Not shown yet: grant position and size (stacking requests are ignored).
        val values = ArrayList<Int>()
        var granted = 0
        if (mask and CONFIG_X != 0) { granted = granted or CONFIG_X; values += e.s16(16); win.x = e.s16(16) }
        if (mask and CONFIG_Y != 0) { granted = granted or CONFIG_Y; values += e.s16(18); win.y = e.s16(18) }
        if (mask and CONFIG_WIDTH != 0) { granted = granted or CONFIG_WIDTH; values += e.u16(20); win.width = e.u16(20) }
        if (mask and CONFIG_HEIGHT != 0) { granted = granted or CONFIG_HEIGHT; values += e.u16(22); win.height = e.u16(22) }
        if (granted != 0) x.configureWindow(win.id, granted, *values.toIntArray())
    }

    private fun sendConfigureNotify(win: XWindow) = x.sendEvent(win.id, STRUCTURE_NOTIFY) {
        u8(EV_CONFIGURE_NOTIFY); u8(0); u16(0)
        u32(win.id); u32(win.id); u32(0)
        u16(win.x); u16(win.y); u16(win.width); u16(win.height); u16(0); u8(0)
    }

    private fun clientMessage(e: XMessage) {
        val type = e.u32(8)
        val win = windows[e.u32(4)] ?: return
        when (type) {
            a("WL_SURFACE_SERIAL") -> serialFor(win, (e.u32(12).toLong() and 0xffffffffL) or (e.u32(16).toLong() shl 32))
            a("WL_SURFACE_ID") -> surfaceIdFor(win, e.u32(12), 0)
            a("_NET_WM_STATE") -> {
                val view = win.toplevel?.view ?: return
                val changed = setOf(e.u32(16), e.u32(20))
                if (changed.any { it == a("_NET_WM_STATE_MAXIMIZED_VERT") || it == a("_NET_WM_STATE_MAXIMIZED_HORZ") || it == a("_NET_WM_STATE_FULLSCREEN") }) {
                    val now = a("_NET_WM_STATE_MAXIMIZED_VERT") in win.netState
                    view.setMaximized(when (e.u32(12)) { 0 -> false; 1 -> true; else -> !now })
                }
            }
            a("_NET_WM_MOVERESIZE") -> {
                val view = win.toplevel?.view ?: return
                when (val direction = e.u32(20)) {
                    MOVERESIZE_MOVE -> view.startMove()
                    in 0..7 -> view.startResize(RESIZE_EDGES[direction])
                }
            }
            a("WM_CHANGE_STATE") -> if (e.u32(12) == ICONIC_STATE) win.toplevel?.view?.minimize()
        }
    }

    private fun propertyChanged(win: XWindow, atom: Int) {
        if (atom !in watchedProperties()) return
        x.getProperty(win.id, atom) { value ->
            applyProperty(win, atom, value)
            val view = win.toplevel?.view ?: return@getProperty
            when (atom) {
                ATOM_WM_NAME, a("_NET_WM_NAME") -> view.titleChanged()
                ATOM_WM_CLASS -> view.appIdChanged()
                a("_MOTIF_WM_HINTS") -> view.decorationsChanged()
            }
        }
    }

    private fun watchedProperties() = listOf(
        ATOM_WM_NAME, a("_NET_WM_NAME"), ATOM_WM_CLASS, ATOM_WM_TRANSIENT_FOR, a("WM_PROTOCOLS"),
        ATOM_WM_NORMAL_HINTS, a("_NET_WM_WINDOW_TYPE"), a("_MOTIF_WM_HINTS"),
    )

    private fun readProperties(win: XWindow, done: () -> Unit) {
        val wanted = watchedProperties()
        var left = wanted.size
        for (atom in wanted) {
            x.getProperty(win.id, atom) { value ->
                applyProperty(win, atom, value)
                if (--left == 0) done()
            }
        }
    }

    private fun applyProperty(win: XWindow, atom: Int, p: XProperty?) {
        when (atom) {
            ATOM_WM_NAME -> win.wmName = p?.let { text(it) }
            a("_NET_WM_NAME") -> win.netName = p?.let { text(it) }
            ATOM_WM_CLASS -> win.wmClass = p?.let { String(it.data, Charsets.ISO_8859_1).split('\u0000').getOrNull(1) } ?: ""
            ATOM_WM_TRANSIENT_FOR -> win.transientFor = p?.ints()?.firstOrNull() ?: 0
            a("WM_PROTOCOLS") -> win.protocols = p?.ints()?.toSet() ?: emptySet()
            ATOM_WM_NORMAL_HINTS -> {
                val h = p?.ints() ?: IntArray(0)
                val flags = h.getOrElse(0) { 0 }
                win.minWidth = if (flags and P_MIN_SIZE != 0) h.getOrElse(5) { 0 } else 0
                win.minHeight = if (flags and P_MIN_SIZE != 0) h.getOrElse(6) { 0 } else 0
                win.maxWidth = if (flags and P_MAX_SIZE != 0) h.getOrElse(7) { 0 } else 0
                win.maxHeight = if (flags and P_MAX_SIZE != 0) h.getOrElse(8) { 0 } else 0
            }
            a("_NET_WM_WINDOW_TYPE") -> win.types = p?.ints()?.toList() ?: emptyList()
            a("_MOTIF_WM_HINTS") -> {
                val h = p?.ints() ?: IntArray(0)
                win.decorations = !(h.size >= 3 && h[0] and MWM_HINTS_DECORATIONS != 0 && h[2] == 0)
            }
        }
    }

    private fun text(p: XProperty): String =
        String(p.data, if (p.type == ATOM_STRING) Charsets.ISO_8859_1 else Charsets.UTF_8).trimEnd('\u0000')

    /** Tooltips and notifications: shown, but they take no input and do not block clicks. */
    fun isPassive(win: XWindow) = win.types.any {
        it == a("_NET_WM_WINDOW_TYPE_TOOLTIP") || it == a("_NET_WM_WINDOW_TYPE_NOTIFICATION") || it == a("_NET_WM_WINDOW_TYPE_DND")
    }

    // ---- X window ↔ wl_surface ----

    /** xwayland_surface_v1.set_serial. */
    fun surfaceSerial(surface: XwaylandSurface) {
        if (DEBUG) Log.d(TAG, "${surface.surface} serial ${surface.serial}")
        surfacesBySerial[surface.serial] = surface
        windowsBySerial.remove(surface.serial)?.let { associate(it, surface) }
    }

    private fun serialFor(win: XWindow, serial: Long) {
        if (DEBUG) Log.d(TAG, "window 0x${Integer.toHexString(win.id)} serial $serial")
        val surface = surfacesBySerial[serial]
        if (surface != null) associate(win, surface) else windowsBySerial[serial] = win
    }

    /** Older Xwayland: the wl_surface object id. The surface may not have reached us yet. */
    private fun surfaceIdFor(win: XWindow, id: Int, attempt: Int) {
        val surface = server.findXwaylandSurface(id)
        when {
            surface != null -> {
                val role = XwaylandSurface.forLegacy(surface, this)
                associate(win, role)
            }
            attempt < 40 -> main.postDelayed({ if (windows[win.id] === win) surfaceIdFor(win, id, attempt + 1) }, 25)
            else -> Log.w(TAG, "no wl_surface $id for X window 0x${Integer.toHexString(win.id)}")
        }
    }

    private fun associate(win: XWindow, surface: XwaylandSurface) {
        if (DEBUG) Log.d(TAG, "window 0x${Integer.toHexString(win.id)} ↔ ${surface.surface}")
        surface.window = win
        win.surface = surface.surface
        update(win)
    }

    /** The surface got new content. */
    fun surfaceCommitted(surface: XwaylandSurface) {
        val win = surface.window ?: return
        update(win)
        win.popup?.owner?.view?.contentChanged()
    }

    fun surfaceGone(surface: XwaylandSurface) {
        surfacesBySerial.remove(surface.serial)
        val win = surface.window ?: return
        surface.window = null
        win.surface = null
        hide(win)
    }

    // ---- desktop windows ----

    /** Shows or hides [win] to match its X state and surface content. */
    private fun update(win: XWindow) {
        val surface = win.surface
        if (!win.mapped || surface == null || surface.bitmap == null) {
            hide(win)
            return
        }
        if (win.overrideRedirect) showPopup(win, surface) else showToplevel(win, surface)
    }

    private fun showToplevel(win: XWindow, surface: WlSurface) {
        if (win.toplevel != null) return
        val toplevel = XwaylandToplevel(this, win, surface)
        win.toplevel = toplevel
        surface.enterOutputs()
        server.host?.toplevelMapped(toplevel)
    }

    private fun showPopup(win: XWindow, surface: WlSurface) {
        if (win.popup != null) return
        val owner = ownerFor(win) ?: return
        val popup = XwaylandPopup(this, win, owner, surface)
        win.popup = popup
        owner.popups += popup
        surface.enterOutputs()
        owner.view?.contentChanged()
    }

    /** The window a menu belongs to: its transient-for window, else the active X11 window. */
    private fun ownerFor(win: XWindow): XwaylandToplevel? =
        toplevelOf(win.transientFor) ?: focused?.toplevel ?: windows.values.firstNotNullOfOrNull { it.toplevel }

    private fun hide(win: XWindow) {
        win.toplevel?.let { toplevel ->
            win.toplevel = null
            toplevel.mapped = false
            for (popup in toplevel.popups.toList()) popup.window.popup = null
            toplevel.popups.clear()
            server.host?.toplevelUnmapped(toplevel)
        }
        win.popup?.let { popup ->
            win.popup = null
            popup.owner.popups.remove(popup)
            popup.owner.view?.contentChanged()
        }
    }

    fun requestState(toplevel: XwaylandToplevel, width: Int, height: Int, activated: Boolean, maximized: Boolean) {
        val win = toplevel.window
        if (width > 0 && height > 0 && (width != win.width || height != win.height) && !toplevel.fixedSize) {
            win.width = width
            win.height = height
            x.configureWindow(win.id, CONFIG_WIDTH or CONFIG_HEIGHT, width, height)
        }
        if (activated && focused !== win) focus(win)
        if (!activated && focused === win) focused = null
        val state = ArrayList<Int>()
        if (maximized) state += listOf(a("_NET_WM_STATE_MAXIMIZED_VERT"), a("_NET_WM_STATE_MAXIMIZED_HORZ"))
        if (activated) state += a("_NET_WM_STATE_FOCUSED")
        if (state != win.netState) {
            win.netState = state
            x.changeProperty32(win.id, a("_NET_WM_STATE"), ATOM_ATOM, *state.toIntArray())
        }
    }

    private fun focus(win: XWindow) {
        focused = win
        x.configureWindow(win.id, CONFIG_STACK_MODE, STACK_ABOVE)
        x.setInputFocus(win.id)
        if (a("WM_TAKE_FOCUS") in win.protocols) x.clientMessage(win.id, a("WM_PROTOCOLS"), a("WM_TAKE_FOCUS"), 0)
        x.changeProperty32(x.root, a("_NET_ACTIVE_WINDOW"), ATOM_WINDOW, win.id)
    }

    fun close(win: XWindow) {
        if (a("WM_DELETE_WINDOW") in win.protocols) x.clientMessage(win.id, a("WM_PROTOCOLS"), a("WM_DELETE_WINDOW"), 0)
        else x.killClient(win.id)
    }

    fun move(win: XWindow, x: Int, y: Int) {
        if (win.x == x && win.y == y) return
        win.x = x
        win.y = y
        this.x.configureWindow(win.id, CONFIG_X or CONFIG_Y, x, y)
    }

    /**
     * A click outside an X11 menu: X11 programs grab the pointer while a menu is open, so a
     * click just outside the menu surface reaches the program, which closes the menu.
     */
    fun clickOutside(popup: XwaylandPopup) {
        val seat = server.seat
        seat.pointerMotion(popup.surface, -1.0, -1.0)
        seat.pointerButton(BTN_LEFT, true)
        seat.pointerButton(BTN_LEFT, false)
        seat.pointerLeave()
    }

    private companion object {
        const val TAG = "HandTopXwm"
        const val DEBUG = false
        const val BTN_LEFT = 0x110
        const val WM_STATE_WITHDRAWN = 0
        const val WM_STATE_NORMAL = 1
        const val ICONIC_STATE = 3
        const val P_MIN_SIZE = 16
        const val P_MAX_SIZE = 32
        const val MWM_HINTS_DECORATIONS = 2
        const val MOVERESIZE_MOVE = 8

        /** _NET_WM_MOVERESIZE directions 0..7 (top-left clockwise) as xdg resize edges. */
        val RESIZE_EDGES = intArrayOf(5, 1, 9, 8, 10, 2, 6, 4)

        val SUPPORTED = listOf(
            "_NET_SUPPORTED", "_NET_SUPPORTING_WM_CHECK", "_NET_ACTIVE_WINDOW", "_NET_WM_NAME",
            "_NET_WM_STATE", "_NET_WM_STATE_MAXIMIZED_VERT", "_NET_WM_STATE_MAXIMIZED_HORZ",
            "_NET_WM_STATE_FULLSCREEN", "_NET_WM_STATE_FOCUSED", "_NET_WM_MOVERESIZE",
            "_NET_WM_WINDOW_TYPE", "_NET_FRAME_EXTENTS",
        )

        val ATOM_NAMES = SUPPORTED + listOf(
            "UTF8_STRING", "WM_PROTOCOLS", "WM_DELETE_WINDOW", "WM_TAKE_FOCUS", "WM_STATE", "WM_CHANGE_STATE",
            "_NET_WM_WINDOW_TYPE_TOOLTIP", "_NET_WM_WINDOW_TYPE_NOTIFICATION", "_NET_WM_WINDOW_TYPE_DND",
            "_MOTIF_WM_HINTS", "WL_SURFACE_ID", "WL_SURFACE_SERIAL",
        )
    }
}

// ---- Wayland side: xwayland_shell_v1 ----

/** Lets Xwayland tie its wl_surfaces to X11 windows by serial (Xwayland 23.1+). */
class XwaylandShell(client: Client, id: Int) : Resource(client, id, 1) {
    override val iface = "xwayland_shell_v1"

    override fun dispatch(opcode: Int, r: MessageReader) {
        when (opcode) {
            0 -> destroy()
            1 -> { // get_xwayland_surface(id, surface)
                val newId = r.newId()
                val surface = client.require<WlSurface>(r.obj())
                val role = client.register(XwaylandSurface(client, newId, surface, client.server.xwm))
                surface.role = SurfaceRole.Xwayland(role)
            }
            else -> super.dispatch(opcode, r)
        }
    }
}

/** The X11 window role of a wl_surface. Also used without the protocol for older Xwayland. */
class XwaylandSurface(client: Client, id: Int, val surface: WlSurface, private val xwm: Xwm?) : Resource(client, id, 1) {
    override val iface = "xwayland_surface_v1"

    var serial = 0L
        private set
    var window: XWindow? = null

    override fun dispatch(opcode: Int, r: MessageReader) {
        when (opcode) {
            0 -> { // set_serial(lo, hi)
                serial = (r.uint().toLong() and 0xffffffffL) or (r.uint().toLong() shl 32)
                xwm?.surfaceSerial(this)
            }
            1 -> destroy()
            else -> super.dispatch(opcode, r)
        }
    }

    fun committed() = xwm?.surfaceCommitted(this)

    fun surfaceGone() = xwm?.surfaceGone(this)

    companion object {
        /** A role object for a surface found by WL_SURFACE_ID (not registered with the client). */
        fun forLegacy(surface: WlSurface, xwm: Xwm): XwaylandSurface {
            (surface.role as? SurfaceRole.Xwayland)?.let { return it.surface }
            val role = XwaylandSurface(surface.client, 0, surface, xwm)
            surface.role = SurfaceRole.Xwayland(role)
            return role
        }
    }
}

// ---- zxdg_output_manager_v1: the logical screen size (Xwayland sizes its root window by it) ----

class XdgOutputManager(client: Client, id: Int, version: Int) : Resource(client, id, version) {
    override val iface = "zxdg_output_manager_v1"

    override fun dispatch(opcode: Int, r: MessageReader) {
        when (opcode) {
            0 -> destroy()
            1 -> { // get_xdg_output(id, output)
                val newId = r.newId()
                val output = client.require<WlOutput>(r.obj())
                client.register(XdgOutput(client, newId, version, output)).sendInfo(true)
            }
            else -> super.dispatch(opcode, r)
        }
    }
}

class XdgOutput(client: Client, id: Int, version: Int, private val output: WlOutput) : Resource(client, id, version) {
    override val iface = "zxdg_output_v1"

    fun sendInfo(first: Boolean) {
        val s = client.server
        event(0) { int(0); int(0) } // logical_position
        event(1) { int(s.outputWidth / s.scale); int(s.outputHeight / s.scale) } // logical_size
        if (version >= 2 && first) {
            event(3) { string("HandTop-1") } // name
            event(4) { string("HandTop desktop") } // description
        }
        if (version < 3) event(2) // done (version 3: wl_output.done follows)
        else if (output.version >= 2) output.event(2)
    }

    override fun dispatch(opcode: Int, r: MessageReader) {
        if (opcode == 0) destroy() else super.dispatch(opcode, r)
    }
}
