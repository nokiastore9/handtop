package org.handtop.desktop.wayland

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Handler
import android.util.Log
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream

/** A reply or event from the X server (little-endian), read with absolute offsets. */
class XMessage(val bytes: ByteArray) {
    /** Event code without the "sent by SendEvent" bit. */
    val code get() = bytes[0].toInt() and 0x7f
    fun u8(at: Int) = bytes[at].toInt() and 0xff
    fun u16(at: Int) = u8(at) or (u8(at + 1) shl 8)
    fun s16(at: Int) = u16(at).toShort().toInt()
    fun u32(at: Int) = u16(at) or (u16(at + 2) shl 16)
}

/** A property value (GetProperty reply). */
class XProperty(val type: Int, val format: Int, val data: ByteArray) {
    /** Format-32 values (atoms, windows, cardinals). */
    fun ints(): IntArray {
        if (format != 32) return IntArray(0)
        val m = XMessage(data)
        return IntArray(data.size / 4) { m.u32(it * 4) }
    }
}

/** Request body writer. */
class XOut {
    var bytes = ByteArray(64)
        private set
    var size = 0
        private set

    private fun ensure(n: Int) {
        if (size + n > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, size + n))
    }

    fun u8(v: Int) { ensure(1); bytes[size++] = v.toByte() }
    fun u16(v: Int) { u8(v); u8(v shr 8) }
    fun u32(v: Int) { u16(v); u16(v shr 16) }
    fun data(b: ByteArray) { ensure(b.size); b.copyInto(bytes, size); size += b.size }
    fun pad() { while (size % 4 != 0) u8(0) }
    fun patch16(at: Int, v: Int) { bytes[at] = v.toByte(); bytes[at + 1] = (v shr 8).toByte() }
}

/**
 * A minimal X11 protocol client: just what HandTop's window manager needs. Requests are
 * sent from the main thread; replies and events are read on a background thread and handed
 * to the main thread in order.
 */
class X11Connection private constructor(
    private val socket: LocalSocket,
    private val main: Handler,
    setup: XMessage,
) {
    val root: Int
    val rootWidth: Int
    val rootHeight: Int
    private val idBase: Int
    private val idMask: Int
    private val idShift: Int
    private var idNext = 1

    private val out: OutputStream = socket.outputStream
    private val lock = Any()
    private var sequence = 0
    private val pending = HashMap<Int, (XMessage?) -> Unit>()

    /** Events, on the main thread. */
    var onEvent: ((XMessage) -> Unit)? = null
    /** Errors of requests without a reply callback, on the main thread. */
    var onError: ((XMessage) -> Unit)? = null
    /** The server went away, on the main thread. */
    var onClosed: (() -> Unit)? = null

    init {
        idBase = setup.u32(12)
        idMask = setup.u32(16)
        idShift = Integer.numberOfTrailingZeros(idMask)
        val vendorLength = setup.u16(24)
        val formats = setup.u8(29)
        val screen = 40 + (vendorLength + 3) / 4 * 4 + 8 * formats
        root = setup.u32(screen)
        rootWidth = setup.u16(screen + 20)
        rootHeight = setup.u16(screen + 22)
    }

    fun newId(): Int = idBase or ((idNext++ shl idShift) and idMask)

    /**
     * Sends a request: [opcode], the header's data byte, then [body]. With [reply], the
     * reply (or null after an error) is delivered on the main thread.
     */
    fun request(opcode: Int, data: Int = 0, reply: ((XMessage?) -> Unit)? = null, body: XOut.() -> Unit = {}) {
        val w = XOut()
        w.u8(opcode); w.u8(data); w.u16(0)
        w.body()
        w.pad()
        w.patch16(2, w.size / 4)
        synchronized(lock) {
            sequence = (sequence + 1) and 0xffff
            if (reply != null) pending[sequence] = reply
            try {
                out.write(w.bytes, 0, w.size)
                out.flush()
            } catch (e: IOException) {
                Log.w(TAG, "X11 write: ${e.message}")
            }
        }
    }

    fun close() {
        try {
            socket.shutdownInput()
        } catch (_: IOException) {
        }
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }

    private fun readLoop(input: DataInputStream) {
        val head = ByteArray(32)
        try {
            while (true) {
                input.readFully(head)
                val type = head[0].toInt() and 0x7f
                val extra = if (type == 1 || type == GENERIC_EVENT) XMessage(head).u32(4) * 4 else 0
                val bytes = if (extra > 0) head.copyOf(32 + extra).also { input.readFully(it, 32, extra) } else head.copyOf()
                val message = XMessage(bytes)
                val seq = message.u16(2)
                when (type) {
                    1 -> { // reply
                        val handler = synchronized(lock) { pending.remove(seq) }
                        if (handler != null) main.post { handler(message) }
                    }
                    0 -> { // error
                        val handler = synchronized(lock) { pending.remove(seq) }
                        main.post { if (handler != null) handler(null) else onError?.invoke(message) }
                    }
                    else -> main.post { onEvent?.invoke(message) }
                }
            }
        } catch (e: IOException) {
            Log.i(TAG, "X11 connection closed: ${e.message}")
        }
        main.post { onClosed?.invoke() }
    }

    // ---- requests the window manager uses ----

    /** The extension's major opcode, or 0 if the server does not have it. */
    fun queryExtension(name: String, reply: (Int) -> Unit) {
        val bytes = name.toByteArray(Charsets.ISO_8859_1)
        request(98, 0, { m -> reply(if (m != null && m.u8(8) != 0) m.u8(9) else 0) }) { u16(bytes.size); u16(0); data(bytes) }
    }

    /**
     * Composite: redirect the root's children manually. Rootless Xwayland gives a window its
     * own wl_surface only when it is redirected this way (by the window manager).
     */
    fun redirectSubwindowsManually(composite: Int, window: Int, done: (Boolean) -> Unit) {
        request(composite, 0, { m ->
            if (m == null) {
                done(false)
                return@request
            }
            request(composite, 2) { u32(window); u8(1); u8(0); u16(0) } // RedirectSubwindows, Manual
            done(true)
        }) { u32(0); u32(4) } // QueryVersion 0.4
    }

    fun internAtom(name: String, reply: (Int) -> Unit) {
        val bytes = name.toByteArray(Charsets.ISO_8859_1)
        request(16, 0, { reply(it?.u32(8) ?: 0) }) { u16(bytes.size); u16(0); data(bytes) }
    }

    fun changeWindowAttributes(window: Int, mask: Int, vararg values: Int) =
        request(2) { u32(window); u32(mask); values.forEach { u32(it) } }

    fun createWindow(window: Int, parent: Int, x: Int, y: Int, width: Int, height: Int, inputOnly: Boolean, mask: Int, vararg values: Int) =
        request(1, 0) {
            u32(window); u32(parent); u16(x); u16(y); u16(width); u16(height); u16(0)
            u16(if (inputOnly) 2 else 0); u32(0); u32(mask); values.forEach { u32(it) }
        }

    fun mapWindow(window: Int) = request(8) { u32(window) }

    /** [mask]: CONFIG_* bits; [values] in bit order. */
    fun configureWindow(window: Int, mask: Int, vararg values: Int) =
        request(12) { u32(window); u16(mask); u16(0); values.forEach { u32(it) } }

    fun changeProperty(window: Int, property: Int, type: Int, format: Int, data: ByteArray, units: Int) =
        request(18, 0) { u32(window); u32(property); u32(type); u8(format); u8(0); u16(0); u32(units); data(data) }

    fun changeProperty32(window: Int, property: Int, type: Int, vararg values: Int) {
        val w = XOut()
        values.forEach { w.u32(it) }
        changeProperty(window, property, type, 32, w.bytes.copyOf(w.size), values.size)
    }

    fun changePropertyUtf8(window: Int, property: Int, type: Int, text: String) {
        val bytes = text.toByteArray()
        changeProperty(window, property, type, 8, bytes, bytes.size)
    }

    fun getProperty(window: Int, property: Int, reply: (XProperty?) -> Unit) =
        request(20, 0, { m ->
            if (m == null || m.u32(8) == 0) {
                reply(null)
            } else {
                val format = m.u8(1)
                val count = m.u32(16) * (format / 8)
                reply(XProperty(m.u32(8), format, m.bytes.copyOfRange(32, minOf(32 + count, m.bytes.size))))
            }
        }) { u32(window); u32(property); u32(0); u32(0); u32(MAX_PROPERTY_LONGS) }

    /** A 32-byte [event] built by [build] (after its code byte), sent to [window]. */
    fun sendEvent(window: Int, eventMask: Int, build: XOut.() -> Unit) {
        val e = XOut().apply(build)
        val bytes = e.bytes.copyOf(32)
        request(25, 0) { u32(window); u32(eventMask); data(bytes) }
    }

    /** ClientMessage of format 32 to [window] (e.g. WM_PROTOCOLS / WM_DELETE_WINDOW). */
    fun clientMessage(window: Int, type: Int, vararg data: Int) = sendEvent(window, 0) {
        u8(EV_CLIENT_MESSAGE); u8(32); u16(0); u32(window); u32(type)
        for (i in 0 until 5) u32(data.getOrElse(i) { 0 })
    }

    fun setInputFocus(window: Int) = request(42, REVERT_TO_POINTER_ROOT) { u32(window); u32(0) }

    fun killClient(resource: Int) = request(113) { u32(resource) }

    companion object {
        private const val TAG = "HandTopX11"
        private const val GENERIC_EVENT = 35
        private const val MAX_PROPERTY_LONGS = 0x4000
        private const val REVERT_TO_POINTER_ROOT = 1

        const val EV_KEY_PRESS = 2
        const val EV_FOCUS_IN = 9
        const val EV_CREATE_NOTIFY = 16
        const val EV_DESTROY_NOTIFY = 17
        const val EV_UNMAP_NOTIFY = 18
        const val EV_MAP_NOTIFY = 19
        const val EV_MAP_REQUEST = 20
        const val EV_CONFIGURE_NOTIFY = 22
        const val EV_CONFIGURE_REQUEST = 23
        const val EV_PROPERTY_NOTIFY = 28
        const val EV_CLIENT_MESSAGE = 33

        const val CW_OVERRIDE_REDIRECT = 0x200
        const val CW_EVENT_MASK = 0x800

        const val STRUCTURE_NOTIFY = 0x20000
        const val SUBSTRUCTURE_NOTIFY = 0x80000
        const val SUBSTRUCTURE_REDIRECT = 0x100000
        const val FOCUS_CHANGE = 0x200000
        const val PROPERTY_CHANGE = 0x400000

        const val CONFIG_X = 1
        const val CONFIG_Y = 2
        const val CONFIG_WIDTH = 4
        const val CONFIG_HEIGHT = 8
        const val CONFIG_BORDER = 16
        const val CONFIG_STACK_MODE = 64
        const val STACK_ABOVE = 0

        // Predefined atoms.
        const val ATOM_ATOM = 4
        const val ATOM_CARDINAL = 6
        const val ATOM_STRING = 31
        const val ATOM_WINDOW = 33
        const val ATOM_WM_NAME = 39
        const val ATOM_WM_NORMAL_HINTS = 40
        const val ATOM_WM_CLASS = 67
        const val ATOM_WM_TRANSIENT_FOR = 68

        /**
         * Connects to the X server listening on the Unix socket [path] (no authorization:
         * Xwayland started by HandTop). Blocking: call it off the main thread.
         */
        fun connect(path: String, main: Handler): X11Connection {
            val socket = LocalSocket(LocalSocket.SOCKET_STREAM)
            socket.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
            val out = socket.outputStream
            // Little-endian, protocol 11.0, no authorization.
            out.write(byteArrayOf(0x6c, 0, 11, 0, 0, 0, 0, 0, 0, 0, 0, 0))
            out.flush()
            val input = DataInputStream(socket.inputStream.buffered())
            val head = ByteArray(8)
            input.readFully(head)
            val extra = XMessage(head).u16(6) * 4
            val setup = head.copyOf(8 + extra)
            input.readFully(setup, 8, extra)
            if (head[0].toInt() != 1) {
                socket.close()
                throw IOException("X server refused the connection (status ${head[0]})")
            }
            val connection = X11Connection(socket, main, XMessage(setup))
            Thread({ connection.readLoop(input) }, "x11-reader").apply { isDaemon = true; start() }
            return connection
        }
    }
}
