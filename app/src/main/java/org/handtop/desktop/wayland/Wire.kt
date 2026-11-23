package org.handtop.desktop.wayland

import java.io.IOException

/** A protocol violation: the client gets wl_display.error and is disconnected. */
class ProtocolError(val objectId: Int, val code: Int, message: String) : IOException(message)

/** Decodes the arguments of one request (little-endian wire format, 32-bit aligned). */
class MessageReader(
    private val data: ByteArray,
    private var pos: Int,
    private val end: Int,
    private val fds: ArrayDeque<Int>,
    private val objectId: Int,
) {
    fun int(): Int {
        if (pos + 4 > end) throw ProtocolError(objectId, 1, "message too short")
        val v = (data[pos].toInt() and 0xFF) or
            ((data[pos + 1].toInt() and 0xFF) shl 8) or
            ((data[pos + 2].toInt() and 0xFF) shl 16) or
            ((data[pos + 3].toInt() and 0xFF) shl 24)
        pos += 4
        return v
    }

    fun uint() = int()
    fun obj() = int()
    fun newId() = int()

    /** wl_fixed_t: signed 24.8. */
    fun fixed(): Double = int() / 256.0

    fun string(): String? {
        val len = int()
        if (len == 0) return null
        if (len < 0 || pos + len > end) throw ProtocolError(objectId, 1, "bad string")
        val s = String(data, pos, len - 1, Charsets.UTF_8) // drop the NUL terminator
        pos += (len + 3) and 3.inv()
        return s
    }

    fun array(): ByteArray {
        val len = int()
        if (len < 0 || pos + len > end) throw ProtocolError(objectId, 1, "bad array")
        val a = data.copyOfRange(pos, pos + len)
        pos += (len + 3) and 3.inv()
        return a
    }

    fun fd(): Int = fds.removeFirstOrNull() ?: throw ProtocolError(objectId, 1, "missing file descriptor")
}

/** Encodes one event; [Client.event] appends it to the client's output buffer. */
class EventWriter {
    var bytes = ByteArray(64)
    var size = 8 // room for the header
    val fds = ArrayList<Int>()

    private fun ensure(extra: Int) {
        if (size + extra > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, size + extra))
    }

    fun int(v: Int) {
        ensure(4)
        bytes[size] = v.toByte()
        bytes[size + 1] = (v shr 8).toByte()
        bytes[size + 2] = (v shr 16).toByte()
        bytes[size + 3] = (v shr 24).toByte()
        size += 4
    }

    fun uint(v: Int) = int(v)
    fun obj(id: Int) = int(id)
    fun fixed(v: Double) = int(Math.round(v * 256.0).toInt())

    fun string(s: String?) {
        if (s == null) {
            int(0)
            return
        }
        val b = s.toByteArray(Charsets.UTF_8)
        int(b.size + 1)
        ensure(b.size + 4)
        b.copyInto(bytes, size)
        size += b.size
        bytes[size++] = 0
        while (size and 3 != 0) bytes[size++] = 0
    }

    fun array(a: ByteArray) {
        int(a.size)
        ensure(a.size + 3)
        a.copyInto(bytes, size)
        size += a.size
        while (size and 3 != 0) bytes[size++] = 0
    }

    fun intArray(values: IntArray) {
        val a = ByteArray(values.size * 4)
        values.forEachIndexed { i, v ->
            a[i * 4] = v.toByte(); a[i * 4 + 1] = (v shr 8).toByte()
            a[i * 4 + 2] = (v shr 16).toByte(); a[i * 4 + 3] = (v shr 24).toByte()
        }
        array(a)
    }

    fun fd(fd: Int) {
        fds += fd
    }

    fun finish(objectId: Int, opcode: Int) {
        val header = size
        size = 0
        int(objectId)
        int((header shl 16) or opcode)
        size = header
    }
}
