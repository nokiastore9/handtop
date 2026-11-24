package org.handtop.desktop.wayland

import android.os.SystemClock
import android.view.KeyEvent

/** The single seat: routes Android input to the focused client's pointer and keyboard. */
class Seat(private val server: WaylandServer) {

    val pointers = ArrayList<WlPointer>()
    val keyboards = ArrayList<WlKeyboard>()

    var pointerFocus: WlSurface? = null
        private set
    var keyboardFocus: WlSurface? = null
        private set
    private var modifiers = 0

    private fun time() = SystemClock.uptimeMillis().toInt()

    // ---- pointer ----

    fun pointerMotion(surface: WlSurface, x: Double, y: Double) {
        if (pointerFocus !== surface) {
            pointerLeave()
            pointerFocus = surface
            val serial = server.nextSerial()
            forPointers(surface) { p ->
                p.event(0) { uint(serial); obj(surface.id); fixed(x); fixed(y) }
                p.frame()
            }
        }
        forPointers(surface) { p ->
            p.event(2) { uint(time()); fixed(x); fixed(y) }
            p.frame()
        }
        surface.client.flush()
    }

    fun pointerLeave() {
        val old = pointerFocus ?: return
        pointerFocus = null
        val serial = server.nextSerial()
        forPointers(old) { p ->
            p.event(1) { uint(serial); obj(old.id) }
            p.frame()
        }
        old.client.flush()
    }

    fun pointerButton(button: Int, pressed: Boolean) {
        val surface = pointerFocus ?: return
        val serial = server.nextSerial()
        forPointers(surface) { p ->
            p.event(3) { uint(serial); uint(time()); uint(button); uint(if (pressed) 1 else 0) }
            p.frame()
        }
        surface.client.flush()
    }

    /** Scroll by [dy]/[dx] in surface units (positive = down / right). */
    fun pointerAxis(dx: Double, dy: Double) {
        val surface = pointerFocus ?: return
        forPointers(surface) { p ->
            if (p.version >= 5) p.event(6) { uint(0) } // axis_source: wheel
            if (dy != 0.0) p.event(4) { uint(time()); uint(0); fixed(dy) }
            if (dx != 0.0) p.event(4) { uint(time()); uint(1); fixed(dx) }
            p.frame()
        }
        surface.client.flush()
    }

    private inline fun forPointers(surface: WlSurface, action: (WlPointer) -> Unit) {
        pointers.filter { it.client === surface.client }.forEach(action)
    }

    // ---- keyboard ----

    fun setKeyboardFocus(surface: WlSurface?) {
        if (keyboardFocus === surface) return
        keyboardFocus?.let { old ->
            val serial = server.nextSerial()
            keyboards.filter { it.client === old.client }.forEach { k -> k.event(2) { uint(serial); obj(old.id) } }
            old.client.flush()
        }
        keyboardFocus = surface
        if (surface != null) {
            // The selection is announced before the keyboard enters (wl_data_device spec).
            server.clipboard.sendSelection(surface.client)
            val serial = server.nextSerial()
            keyboards.filter { it.client === surface.client }.forEach { k ->
                k.event(1) { uint(serial); obj(surface.id); array(ByteArray(0)) }
                k.event(4) { uint(serial); uint(modifiers); uint(0); uint(0); uint(0) }
            }
            surface.client.flush()
        }
    }

    /** Sends an Android key event to the focused surface; false if it has no Linux key code. */
    fun key(event: KeyEvent): Boolean {
        val surface = keyboardFocus ?: return false
        val code = Keycodes.evdev(event)
        if (code == 0) return false
        val pressed = event.action == KeyEvent.ACTION_DOWN
        if (pressed && event.repeatCount > 0) return true // the client repeats keys itself
        val serial = server.nextSerial()
        val clientKeyboards = keyboards.filter { it.client === surface.client }
        // Clients keep modifier state only from wl_keyboard.modifiers, so it must be current
        // before the key arrives. A modifier key's own event may not carry its meta bit yet
        // (injected keys, some keyboards): account for it here.
        var newModifiers = Keycodes.modifierMask(event.metaState)
        val own = Keycodes.modifierOf(event.keyCode)
        if (own != 0) newModifiers = if (pressed) newModifiers or own else newModifiers and own.inv()
        if (newModifiers != modifiers) {
            modifiers = newModifiers
            clientKeyboards.forEach { k -> k.event(4) { uint(serial); uint(modifiers); uint(0); uint(0); uint(0) } }
        }
        clientKeyboards.forEach { k ->
            k.event(3) { uint(serial); uint(time()); uint(code); uint(if (pressed) 1 else 0) }
        }
        surface.client.flush()
        return true
    }

    fun surfaceGone(surface: WlSurface) {
        if (pointerFocus === surface) pointerFocus = null
        if (keyboardFocus === surface) keyboardFocus = null
    }
}

class WlSeat(client: Client, id: Int, version: Int) : Resource(client, id, version) {
    override val iface = "wl_seat"

    init {
        event(0) { uint(CAP_POINTER or CAP_KEYBOARD) }
        if (version >= 2) event(1) { string("seat0") }
    }

    override fun dispatch(opcode: Int, r: MessageReader) {
        val seat = client.server.seat
        when (opcode) {
            0 -> seat.pointers += client.register(WlPointer(client, r.newId(), version))
            1 -> {
                val keyboard = client.register(WlKeyboard(client, r.newId(), version))
                seat.keyboards += keyboard
                keyboard.sendKeymap()
            }
            2 -> client.register(Stub(client, r.newId(), version, "wl_touch", destroyOpcode = 0))
            3 -> destroy()
            else -> super.dispatch(opcode, r)
        }
    }

    private companion object {
        const val CAP_POINTER = 1
        const val CAP_KEYBOARD = 2
    }
}

class WlPointer(client: Client, id: Int, version: Int) : Resource(client, id, version) {
    override val iface = "wl_pointer"

    override fun dispatch(opcode: Int, r: MessageReader) {
        when (opcode) {
            0 -> { // set_cursor(serial, surface, hotspot x, y): Android draws the pointer
                r.uint()
                client.optional<WlSurface>(r.obj())?.role = SurfaceRole.Cursor
                r.int(); r.int()
            }
            1 -> destroy()
            else -> super.dispatch(opcode, r)
        }
    }

    fun frame() {
        if (version >= 5) event(5)
    }

    override fun destroyed() {
        client.server.seat.pointers.remove(this)
    }
}

class WlKeyboard(client: Client, id: Int, version: Int) : Resource(client, id, version) {
    override val iface = "wl_keyboard"

    fun sendKeymap() {
        val keymap = client.server.keymap
        val fd = Native.memfdWithData(keymap)
        if (fd >= 0) event(0) { uint(1); fd(fd); uint(keymap.size) } // format xkb_v1
        if (version >= 4) event(5) { int(30); int(500) } // repeat_info: 30/s after 500 ms
    }

    override fun dispatch(opcode: Int, r: MessageReader) {
        if (opcode == 0) destroy() else super.dispatch(opcode, r)
    }

    override fun destroyed() {
        client.server.seat.keyboards.remove(this)
    }
}
