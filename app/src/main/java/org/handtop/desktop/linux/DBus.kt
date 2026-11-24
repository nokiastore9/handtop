package org.handtop.desktop.linux

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream

/** A D-Bus message with the header fields HandTop uses; the body holds simple values only. */
class DBusMessage(
    val type: Int,
    val flags: Int,
    val serial: Int,
    val path: String?,
    val iface: String?,
    val member: String?,
    val replySerial: Int,
    val sender: String?,
    val signature: String,
    /** String (s, o, g), Int (u, i), Boolean (b) per signature character. */
    val body: List<Any>,
) {
    companion object {
        const val METHOD_CALL = 1
        const val METHOD_RETURN = 2
        const val ERROR = 3
        const val SIGNAL = 4
        const val NO_REPLY_EXPECTED = 1
    }
}

/**
 * A minimal D-Bus client (little-endian wire format, ANONYMOUS authentication): enough for
 * HandTop to offer simple services on the Linux session bus. Calls are not thread safe:
 * one thread reads and replies.
 */
class DBusConnection private constructor(private val socket: LocalSocket) {

    private val out: OutputStream = socket.outputStream
    private val input = DataInputStream(socket.inputStream.buffered())
    private var serial = 0

    fun close() {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }

    fun call(destination: String, path: String, iface: String, member: String, signature: String = "", vararg body: Any): Int {
        val s = ++serial
        send(encode(DBusMessage.METHOD_CALL, s, path = path, iface = iface, member = member, destination = destination, signature = signature, body = body.toList()))
        return s
    }

    fun reply(to: DBusMessage, signature: String = "", vararg body: Any) {
        if (to.flags and DBusMessage.NO_REPLY_EXPECTED != 0) return
        send(encode(DBusMessage.METHOD_RETURN, ++serial, replySerial = to.serial, destination = to.sender, signature = signature, body = body.toList()))
    }

    fun error(to: DBusMessage, name: String, text: String) {
        if (to.flags and DBusMessage.NO_REPLY_EXPECTED != 0) return
        send(encode(DBusMessage.ERROR, ++serial, errorName = name, replySerial = to.serial, destination = to.sender, signature = "s", body = listOf(text)))
    }

    private fun send(bytes: ByteArray) {
        out.write(bytes)
        out.flush()
    }

    /** The next message (blocking); throws IOException when the bus goes away. */
    fun read(): DBusMessage {
        while (true) {
            val head = ByteArray(16)
            input.readFully(head)
            val r = Reader(head)
            val fieldsLength = r.u32At(12)
            val headerEnd = align(16 + fieldsLength, 8)
            val bodyLength = r.u32At(4)
            val all = head.copyOf(headerEnd + bodyLength)
            input.readFully(all, 16, headerEnd - 16 + bodyLength)
            if (head[0] != 'l'.code.toByte()) continue // big-endian peers: not on this machine
            parse(all, fieldsLength, headerEnd)?.let { return it }
        }
    }

    // ---- wire format ----

    private class Writer {
        var bytes = ByteArray(256)
            private set
        var size = 0
            private set

        fun byte(v: Int) {
            if (size == bytes.size) bytes = bytes.copyOf(bytes.size * 2)
            bytes[size++] = v.toByte()
        }

        fun align(n: Int) {
            while (size % n != 0) byte(0)
        }

        fun u32(v: Int) {
            align(4)
            for (i in 0 until 4) byte(v ushr (8 * i))
        }

        fun string(s: String) {
            val b = s.toByteArray()
            u32(b.size)
            b.forEach { byte(it.toInt()) }
            byte(0)
        }

        fun signature(s: String) {
            val b = s.toByteArray()
            byte(b.size)
            b.forEach { byte(it.toInt()) }
            byte(0)
        }

        fun patchU32(at: Int, v: Int) {
            for (i in 0 until 4) bytes[at + i] = (v ushr (8 * i)).toByte()
        }
    }

    private class Reader(val b: ByteArray) {
        var pos = 0

        fun u32At(at: Int) = (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8) or
            ((b[at + 2].toInt() and 0xff) shl 16) or ((b[at + 3].toInt() and 0xff) shl 24)

        fun align(n: Int) {
            pos = align(pos, n)
        }

        fun byte() = b[pos++].toInt() and 0xff

        fun u32(): Int {
            align(4)
            return u32At(pos).also { pos += 4 }
        }

        fun string(): String {
            val n = u32()
            return String(b, pos, n).also { pos += n + 1 }
        }

        fun signature(): String {
            val n = byte()
            return String(b, pos, n).also { pos += n + 1 }
        }

        /** A value of a basic type, or null for types HandTop does not read. */
        fun value(type: Char): Any? = when (type) {
            's', 'o' -> string()
            'g' -> signature()
            'u', 'i' -> u32()
            'b' -> u32() != 0
            'y' -> byte()
            else -> null
        }
    }

    private fun encode(
        type: Int, serial: Int, path: String? = null, iface: String? = null, member: String? = null,
        errorName: String? = null, replySerial: Int = 0, destination: String? = null,
        signature: String = "", body: List<Any> = emptyList(),
    ): ByteArray {
        val w = Writer()
        w.byte('l'.code); w.byte(type); w.byte(0); w.byte(1)
        w.u32(0) // body length, patched below
        w.u32(serial)
        w.u32(0) // header fields length, patched below
        w.align(8)
        val fieldsStart = w.size
        fun field(code: Int, sig: String, value: Any) {
            w.align(8)
            w.byte(code)
            w.signature(sig)
            when (sig) {
                "s", "o" -> w.string(value as String)
                "u" -> w.u32(value as Int)
                "g" -> w.signature(value as String)
            }
        }
        path?.let { field(1, "o", it) }
        iface?.let { field(2, "s", it) }
        member?.let { field(3, "s", it) }
        errorName?.let { field(4, "s", it) }
        if (replySerial != 0) field(5, "u", replySerial)
        destination?.let { field(6, "s", it) }
        if (signature.isNotEmpty()) field(8, "g", signature)
        w.patchU32(12, w.size - fieldsStart)
        w.align(8)
        val bodyStart = w.size
        signature.forEachIndexed { i, type ->
            when (type) {
                's', 'o' -> w.string(body[i] as String)
                'u', 'i' -> w.u32(body[i] as Int)
                'b' -> w.u32(if (body[i] as Boolean) 1 else 0)
                else -> throw IllegalArgumentException("unsupported type $type")
            }
        }
        w.patchU32(4, w.size - bodyStart)
        return w.bytes.copyOf(w.size)
    }

    private fun parse(all: ByteArray, fieldsLength: Int, headerEnd: Int): DBusMessage? {
        val r = Reader(all)
        val type = all[1].toInt() and 0xff
        val flags = all[2].toInt() and 0xff
        val serial = r.u32At(8)
        var path: String? = null
        var iface: String? = null
        var member: String? = null
        var replySerial = 0
        var sender: String? = null
        var signature = ""
        r.pos = 16
        val end = 16 + fieldsLength
        while (r.pos < end) {
            r.align(8)
            if (r.pos >= end) break
            val code = r.byte()
            val sig = r.signature()
            val value = r.value(sig.firstOrNull() ?: return null) ?: return null
            when (code) {
                1 -> path = value as String
                2 -> iface = value as String
                3 -> member = value as String
                5 -> replySerial = value as Int
                7 -> sender = value as String
                8 -> signature = value as String
            }
        }
        r.pos = headerEnd
        val body = ArrayList<Any>()
        for (t in signature) body += r.value(t) ?: break // stop at the first type HandTop does not read
        return DBusMessage(type, flags, serial, path, iface, member, replySerial, sender, signature, body)
    }

    companion object {
        private fun align(n: Int, to: Int) = (n + to - 1) / to * to

        /** Connects to the bus at the Unix socket [path] and authenticates (ANONYMOUS). Blocking. */
        fun connect(path: String): DBusConnection {
            val socket = LocalSocket(LocalSocket.SOCKET_STREAM)
            socket.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
            val out = socket.outputStream
            val input = socket.inputStream
            out.write(0)
            out.write("AUTH ANONYMOUS 68616e64746f70\r\n".toByteArray()) // trace: "handtop"
            out.flush()
            val line = StringBuilder()
            while (!line.endsWith("\r\n")) {
                val c = input.read()
                if (c < 0) throw IOException("bus closed during authentication")
                line.append(c.toChar())
            }
            if (!line.startsWith("OK")) {
                socket.close()
                throw IOException("bus refused: ${line.trim()}")
            }
            out.write("BEGIN\r\n".toByteArray())
            out.flush()
            return DBusConnection(socket)
        }
    }
}
