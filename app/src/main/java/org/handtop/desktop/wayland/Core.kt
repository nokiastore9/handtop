package org.handtop.desktop.wayland

import android.graphics.Rect
import android.util.Log

// Core Wayland protocol objects (wayland.xml). Request/event numbers follow the XML order.

class WlDisplay(client: Client) : Resource(client, 1, 1) {
    override val iface = "wl_display"

    override fun dispatch(opcode: Int, r: MessageReader) {
        when (opcode) {
            0 -> { // sync(callback)
                val cb = client.register(WlCallback(client, r.newId()))
                cb.done(client.server.nextSerial())
            }
            1 -> { // get_registry(registry)
                val registry = client.register(WlRegistry(client, r.newId()))
                client.server.globals.forEach { registry.announce(it) }
            }
            else -> super.dispatch(opcode, r)
        }
    }

    fun error(objectId: Int, code: Int, message: String) = event(0) { obj(objectId); uint(code); string(message) }

    fun deleteId(id: Int) = event(1) { uint(id) }
}

class WlRegistry(client: Client, id: Int) : Resource(client, id, 1) {
    override val iface = "wl_registry"

    fun announce(g: Global) = event(0) { uint(g.name); string(g.iface); uint(g.version) }

    override fun dispatch(opcode: Int, r: MessageReader) {
        if (opcode != 0) return super.dispatch(opcode, r)
        // bind(name, new_id of an interface chosen by the client)
        val name = r.uint()
        val iface = r.string()
        val version = r.uint()
        val newId = r.newId()
        val global = client.server.globals.firstOrNull { it.name == name && it.iface == iface }
            ?: throw ProtocolError(id, 0, "no global $name ($iface)")
        if (version < 1 || version > global.version) throw ProtocolError(id, 0, "bad version $version for $iface")
        global.bind(client, newId, version)
    }
}

class WlCallback(client: Client, id: Int) : Resource(client, id, 1) {
    override val iface = "wl_callback"

    /** Fires the callback; the object dies with it. */
    fun done(data: Int) {
        event(0) { uint(data) }
        destroy()
    }
}

class WlCompositor(client: Client, id: Int, version: Int) : Resource(client, id, version) {
    override val iface = "wl_compositor"

    override fun dispatch(opcode: Int, r: MessageReader) {
        when (opcode) {
            0 -> client.register(WlSurface(client, r.newId(), version))
            1 -> client.register(WlRegion(client, r.newId()))
            else -> super.dispatch(opcode, r)
        }
    }
}

/** Opaque / input regions: accepted and ignored for now. */
/**
 * A set of rectangles built by adding and subtracting: a point is inside if the last
 * operation whose rectangle holds it was an add.
 */
class Region(private val ops: List<Pair<Boolean, Rect>>) {
    fun contains(x: Double, y: Double): Boolean {
        var inside = false
        for ((add, rect) in ops) {
            if (x >= rect.left && x < rect.right && y >= rect.top && y < rect.bottom) inside = add
        }
        return inside
    }
}

class WlRegion(client: Client, id: Int) : Resource(client, id, 1) {
    override val iface = "wl_region"

    private val ops = ArrayList<Pair<Boolean, Rect>>()

    /** A copy: the surface keeps it even if the client changes or destroys the region. */
    fun snapshot() = Region(ops.toList())

    override fun dispatch(opcode: Int, r: MessageReader) {
        when (opcode) {
            0 -> destroy()
            1, 2 -> { // add / subtract(x, y, width, height)
                val x = r.int(); val y = r.int(); val w = r.int(); val h = r.int()
                if (w > 0 && h > 0) ops.add((opcode == 1) to Rect(x, y, x + w, y + h))
            }
            else -> super.dispatch(opcode, r)
        }
    }
}

class WlShm(client: Client, id: Int, version: Int) : Resource(client, id, version) {
    override val iface = "wl_shm"

    init {
        event(0) { uint(FORMAT_ARGB8888) }
        event(0) { uint(FORMAT_XRGB8888) }
    }

    override fun dispatch(opcode: Int, r: MessageReader) {
        when (opcode) {
            0 -> { // create_pool(id, fd, size)
                val newId = r.newId()
                val fd = r.fd()
                val size = r.int()
                client.register(WlShmPool(client, newId, fd, size))
            }
            1 -> destroy() // release (v2)
            else -> super.dispatch(opcode, r)
        }
    }

    companion object {
        const val FORMAT_ARGB8888 = 0
        const val FORMAT_XRGB8888 = 1
    }
}

/** A client's shared-memory file, mapped read-only while it or any of its buffers live. */
class WlShmPool(client: Client, id: Int, private val fd: Int, size: Int) : Resource(client, id, 1) {
    override val iface = "wl_shm_pool"

    var size = size
        private set
    var addr = Native.mmap(fd, size)
        private set
    private var buffers = 0
    private var poolDestroyed = false

    init {
        if (addr == 0L) throw ProtocolError(id, 0, "cannot map pool of $size bytes")
    }

    override fun dispatch(opcode: Int, r: MessageReader) {
        when (opcode) {
            0 -> { // create_buffer(id, offset, width, height, stride, format)
                val newId = r.newId()
                val buffer = WlBuffer(client, newId, this, r.int(), r.int(), r.int(), r.int(), r.uint())
                if (buffer.format != WlShm.FORMAT_ARGB8888 && buffer.format != WlShm.FORMAT_XRGB8888) {
                    throw ProtocolError(id, 0, "unsupported format ${buffer.format}")
                }
                buffers++
                client.register(buffer)
            }
            1 -> destroy()
            2 -> { // resize(size): pools only grow
                val newSize = r.int()
                if (newSize > size) {
                    Native.munmap(addr, size)
                    addr = Native.mmap(fd, newSize)
                    size = newSize
                    if (addr == 0L) throw ProtocolError(id, 0, "cannot remap pool")
                }
            }
            else -> super.dispatch(opcode, r)
        }
    }

    override fun destroyed() {
        poolDestroyed = true
        releaseIfUnused()
    }

    fun bufferGone() {
        buffers--
        releaseIfUnused()
    }

    private fun releaseIfUnused() {
        if (poolDestroyed && buffers <= 0 && addr != 0L) {
            Native.munmap(addr, size)
            Native.close(fd)
            addr = 0
        }
    }
}

class WlBuffer(
    client: Client, id: Int,
    val pool: WlShmPool,
    val offset: Int, val width: Int, val height: Int, val stride: Int, val format: Int,
) : Resource(client, id, 1) {
    override val iface = "wl_buffer"

    override fun dispatch(opcode: Int, r: MessageReader) {
        if (opcode == 0) destroy() else super.dispatch(opcode, r)
    }

    override fun destroyed() = pool.bufferGone()

    fun release() = event(0)
}

/** The single output: the HandTop desktop area. */
class WlOutput(client: Client, id: Int, version: Int) : Resource(client, id, version) {
    override val iface = "wl_output"

    init {
        sendInfo()
    }

    fun sendInfo() {
        val s = client.server
        event(0) { // geometry
            int(0); int(0); int(s.outputWidthMm); int(s.outputHeightMm)
            int(0); string("HandTop"); string("Android"); int(0)
        }
        event(1) { uint(3); int(s.outputWidth); int(s.outputHeight); int(60_000) } // mode: current|preferred
        if (version >= 2) {
            event(3) { int(if (client.isXwayland) s.x11PixelScale else s.scale) }
            event(2) // done
        }
    }

    override fun dispatch(opcode: Int, r: MessageReader) {
        if (opcode == 0) destroy() else super.dispatch(opcode, r)
    }
}

/** Server-side decorations: HandTop always draws the window frame itself. */
class XdgDecorationManager(client: Client, id: Int) : Resource(client, id, 1) {
    override val iface = "zxdg_decoration_manager_v1"

    override fun dispatch(opcode: Int, r: MessageReader) {
        when (opcode) {
            0 -> destroy()
            1 -> { // get_toplevel_decoration(id, toplevel)
                val newId = r.newId()
                val toplevel = client.require<XdgToplevel>(r.obj())
                client.register(XdgToplevelDecoration(client, newId, toplevel)).configure()
            }
            else -> super.dispatch(opcode, r)
        }
    }
}

class XdgToplevelDecoration(client: Client, id: Int, private val toplevel: XdgToplevel) : Resource(client, id, 1) {
    override val iface = "zxdg_toplevel_decoration_v1"

    fun configure() {
        event(0) { uint(2) } // server_side
        toplevel.serverSideDecorations = true
        toplevel.scheduleConfigure()
    }

    override fun dispatch(opcode: Int, r: MessageReader) {
        when (opcode) {
            0 -> destroy()
            1 -> { r.uint(); configure() } // set_mode: we always answer server_side
            2 -> configure()                // unset_mode
            else -> super.dispatch(opcode, r)
        }
    }
}

/** An object whose requests are all ignored, except the one that destroys it. */
class Stub(client: Client, id: Int, version: Int, override val iface: String, private val destroyOpcode: Int) :
    Resource(client, id, version) {
    override fun dispatch(opcode: Int, r: MessageReader) {
        if (opcode == destroyOpcode) destroy()
        else if (WaylandServer.DEBUG) Log.d(WaylandServer.TAG, "$iface: ignoring request $opcode")
    }
}
