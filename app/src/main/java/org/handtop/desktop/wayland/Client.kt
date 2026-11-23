package org.handtop.desktop.wayland

import android.os.Handler
import android.util.Log
import java.io.ByteArrayOutputStream

/** A protocol object owned by a client. */
abstract class Resource(val client: Client, val id: Int, val version: Int) {
    abstract val iface: String

    /** Handles request [opcode]; arguments are read from [r] in protocol order. */
    open fun dispatch(opcode: Int, r: MessageReader) {
        Log.w(WaylandServer.TAG, "$iface@$id: unhandled request $opcode")
    }

    /** Called once when the object goes away (destroy request or client disconnect). */
    open fun destroyed() {}

    fun event(opcode: Int, block: EventWriter.() -> Unit = {}) = client.event(id, opcode, block)

    /** The usual "destroy" request: forget the object and acknowledge the id. */
    fun destroy() = client.destroy(this)

    override fun toString() = "$iface@$id"
}

/**
 * One connected Wayland client. Reading happens on a background thread; everything else
 * (dispatching requests, sending events) runs on the main thread.
 */
class Client(val server: WaylandServer, private val fd: Int, private val main: Handler) {

    private val objects = HashMap<Int, Resource>()
    private var pending = ByteArray(0)
    private val inFds = ArrayDeque<Int>()
    private val out = ByteArrayOutputStream()
    private val outFds = ArrayList<Int>()

    /** Ids the client already destroyed: late requests to them are ignored, not errors. */
    private val zombies = HashSet<Int>()

    var connected = true
        private set

    val display = WlDisplay(this).also { objects[1] = it }

    fun start() {
        Thread({ readLoop() }, "wayland-client-$fd").start()
    }

    private fun readLoop() {
        val buf = ByteArray(16 * 1024)
        val fds = IntArray(32)
        while (true) {
            val n = Native.recv(fd, buf, 0, buf.size, fds)
            val fdCount = if (n >= 0) fds[0] else 0
            val received = fds.copyOfRange(1, 1 + fdCount)
            if (n < 0 || (n == 0 && fdCount == 0)) {
                main.post { disconnect() }
                return
            }
            val chunk = buf.copyOf(maxOf(n, 0))
            main.post { onData(chunk, received) }
        }
    }

    private fun onData(chunk: ByteArray, fds: IntArray) {
        if (!connected) {
            fds.forEach { Native.close(it) }
            return
        }
        fds.forEach { inFds.addLast(it) }
        pending = if (pending.isEmpty()) chunk else pending + chunk

        var pos = 0
        try {
            while (pending.size - pos >= 8) {
                val objectId = readInt(pending, pos)
                val sizeOpcode = readInt(pending, pos + 4)
                val size = sizeOpcode ushr 16
                val opcode = sizeOpcode and 0xFFFF
                if (size < 8) throw ProtocolError(1, 1, "bad message size")
                if (pending.size - pos < size) break
                val target = objects[objectId]
                if (target == null) {
                    if (objectId !in zombies) throw ProtocolError(1, 0, "invalid object $objectId")
                } else {
                    if (WaylandServer.DEBUG) Log.d(WaylandServer.TAG, "-> $target.$opcode")
                    target.dispatch(opcode, MessageReader(pending, pos + 8, pos + size, inFds, objectId))
                }
                pos += size
            }
        } catch (e: ProtocolError) {
            Log.w(WaylandServer.TAG, "protocol error: ${e.message}")
            display.error(e.objectId, e.code, e.message ?: "error")
            flush()
            disconnect()
            return
        } catch (e: RuntimeException) {
            Log.e(WaylandServer.TAG, "error handling request", e)
            display.error(1, 3, "compositor error: ${e.message}")
            flush()
            disconnect()
            return
        }
        pending = if (pos == pending.size) ByteArray(0) else pending.copyOfRange(pos, pending.size)
        flush()
    }

    // ---- objects ----

    fun <T : Resource> register(resource: T): T {
        objects[resource.id] = resource
        zombies.remove(resource.id)
        return resource
    }

    fun get(id: Int): Resource? = objects[id]

    inline fun <reified T : Resource> require(id: Int): T =
        get(id) as? T ?: throw ProtocolError(id, 0, "object $id is not a ${T::class.simpleName}")

    inline fun <reified T : Resource> optional(id: Int): T? = if (id == 0) null else require(id)

    fun destroy(resource: Resource) {
        if (objects.remove(resource.id) == null) return
        resource.destroyed()
        zombies += resource.id
        // Ids are unsigned: client-allocated ones lie below 0xff000000.
        if (Integer.compareUnsigned(resource.id, SERVER_ID_START) < 0) display.deleteId(resource.id)
    }

    fun resources(): Collection<Resource> = objects.values

    // ---- events ----

    fun event(objectId: Int, opcode: Int, block: EventWriter.() -> Unit) {
        if (!connected) return
        val w = EventWriter()
        w.block()
        w.finish(objectId, opcode)
        out.write(w.bytes, 0, w.size)
        outFds.addAll(w.fds)
        if (WaylandServer.DEBUG) Log.d(WaylandServer.TAG, "<- ${objects[objectId]}.$opcode")
    }

    fun flush() {
        if (!connected || out.size() == 0) return
        val bytes = out.toByteArray()
        out.reset()
        val fds = if (outFds.isEmpty()) null else outFds.toIntArray()
        outFds.clear()
        var sent = 0
        var first = true
        while (sent < bytes.size) {
            val n = Native.send(fd, bytes, sent, bytes.size - sent, if (first) fds else null)
            if (n <= 0) {
                Log.w(WaylandServer.TAG, "send failed: $n")
                disconnect()
                return
            }
            first = false
            sent += n
        }
        // Our copies of sent fds (keymaps) are not needed any more.
        fds?.forEach { Native.close(it) }
    }

    fun disconnect() {
        if (!connected) return
        connected = false
        val all = objects.values.toList()
        objects.clear()
        all.forEach {
            try {
                it.destroyed()
            } catch (e: RuntimeException) {
                Log.w(WaylandServer.TAG, "cleanup of $it failed", e)
            }
        }
        inFds.forEach { Native.close(it) }
        inFds.clear()
        Native.shutdown(fd)
        Native.close(fd)
        server.clientGone(this)
    }

    private fun readInt(b: ByteArray, p: Int) =
        (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or
            ((b[p + 2].toInt() and 0xFF) shl 16) or ((b[p + 3].toInt() and 0xFF) shl 24)

    companion object {
        const val SERVER_ID_START = 0xFF000000.toInt()
    }
}
