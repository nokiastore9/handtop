package org.handtop.desktop.linux

import android.system.ErrnoException
import android.system.Os
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

/**
 * Streaming tar extractor for container image layers (ustar, GNU long names, PAX headers),
 * adapted to the Android app sandbox:
 *  - hard links become copies (link(2) is denied to apps),
 *  - device nodes and FIFOs are skipped (mknod is denied; proot binds the host /dev),
 *  - ownership is ignored (proot fakes root), the owner always keeps read/write access,
 *  - entries can't escape [root] ("../" or absolute names are rejected),
 *  - OCI whiteouts (".wh.name", ".wh..wh..opq") remove content of lower layers.
 *
 * Use one instance per layer, applying layers bottom to top.
 */
class TarExtractor(private val root: File, private val onProgress: (entries: Int) -> Unit) {

    private val directoryModes = ArrayList<Pair<File, Int>>()
    private var entries = 0

    /** Paths written by this layer: an opaque whiteout must not remove them. */
    private val layerPaths = HashSet<String>()

    fun extract(input: InputStream) {
        val header = ByteArray(BLOCK)
        var longName: String? = null
        var longLink: String? = null
        var pax: Map<String, String> = emptyMap()

        while (true) {
            if (!readBlock(input, header)) break
            if (header.all { it == 0.toByte() }) break // end-of-archive marker

            val type = header[156].toInt().toChar()
            val headerSize = parseNumber(header, 124, 12)
            when (type) {
                'L' -> { longName = readText(input, headerSize); continue }
                'K' -> { longLink = readText(input, headerSize); continue }
                'x' -> { pax = parsePax(readBytes(input, headerSize)); continue }
                'g' -> { skip(input, padded(headerSize)); continue }
            }

            val name = pax["path"] ?: longName ?: ustarName(header)
            val link = pax["linkpath"] ?: longLink ?: cString(header, 157, 100)
            val size = pax["size"]?.toLongOrNull() ?: headerSize
            val mode = parseNumber(header, 100, 8).toInt() and 0xFFF
            longName = null
            longLink = null
            pax = emptyMap()

            extractEntry(type, name, link, mode, size, input)
            onProgress(++entries)
        }

        // Directory permissions last, so a read-only directory doesn't block its own content.
        for ((dir, mode) in directoryModes.asReversed()) chmod(dir, (mode and 0x1FF) or OWNER_RWX)
    }

    private fun extractEntry(type: Char, name: String, link: String, mode: Int, size: Long, input: InputStream) {
        val target = resolve(name)
        if (target == null) {
            skip(input, padded(size))
            return
        }
        val base = target.name
        if (base.startsWith(WHITEOUT)) {
            applyWhiteout(target.parentFile ?: root, base)
            skip(input, padded(size))
            return
        }
        layerPaths += target.path
        when (type) {
            '5' -> { // directory
                if (isSymlink(target)) target.delete()
                target.mkdirs()
                directoryModes += target to mode
            }
            '0', '\u0000', '7' -> { // regular file
                target.parentFile?.mkdirs()
                removeExisting(target)
                FileOutputStream(target).use { copy(input, it, size) }
                skip(input, padded(size) - size)
                chmod(target, (mode and 0x1FF) or OWNER_RW)
            }
            '2' -> { // symbolic link
                target.parentFile?.mkdirs()
                removeExisting(target)
                Os.symlink(link, target.path)
            }
            '1' -> { // hard link -> copy
                val source = resolve(link)
                if (source != null && source.isFile) {
                    target.parentFile?.mkdirs()
                    removeExisting(target)
                    source.copyTo(target, overwrite = true)
                    chmod(target, (mode and 0x1FF) or OWNER_RW)
                }
            }
            else -> skip(input, padded(size)) // devices, FIFOs, unknown
        }
    }

    /** ".wh.name" deletes name from lower layers; ".wh..wh..opq" empties the directory of them. */
    private fun applyWhiteout(dir: File, marker: String) {
        if (marker == OPAQUE_WHITEOUT) {
            dir.listFiles()?.forEach { if (it.path !in layerPaths) FileTrees.delete(it) }
        } else {
            FileTrees.delete(File(dir, marker.removePrefix(WHITEOUT)))
        }
    }

    /** [name] inside [root], or null if it would escape it. Purely lexical: no symlink resolving. */
    private fun resolve(name: String): File? {
        val parts = ArrayList<String>()
        for (part in name.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.size - 1)
                else -> parts += part
            }
        }
        return if (parts.isEmpty()) root else File(root, parts.joinToString("/"))
    }

    private fun isSymlink(f: File) = Files.isSymbolicLink(f.toPath())

    /** Whatever a lower layer left at [f] (file, link or whole directory) gets replaced. */
    private fun removeExisting(f: File) {
        if (isSymlink(f) || f.exists()) FileTrees.delete(f)
    }

    private fun chmod(f: File, mode: Int) {
        try {
            Os.chmod(f.path, mode)
        } catch (_: ErrnoException) {
            // Not fatal: proot reports fake permissions to the guest anyway.
        }
    }

    // ---- low-level tar parsing ----

    private fun ustarName(h: ByteArray): String {
        val name = cString(h, 0, 100)
        val magic = cString(h, 257, 6)
        val prefix = if (magic.startsWith("ustar")) cString(h, 345, 155) else ""
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    private fun cString(h: ByteArray, offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && h[end] != 0.toByte()) end++
        return String(h, offset, end - offset, Charsets.UTF_8)
    }

    /** Octal ASCII, or GNU base-256 when the high bit of the first byte is set. */
    private fun parseNumber(h: ByteArray, offset: Int, length: Int): Long {
        if (h[offset].toInt() and 0x80 != 0) {
            var value = (h[offset].toLong() and 0x7F)
            for (i in offset + 1 until offset + length) value = (value shl 8) or (h[i].toLong() and 0xFF)
            return value
        }
        val text = cString(h, offset, length).trim()
        return if (text.isEmpty()) 0 else text.toLong(8)
    }

    /** PAX records: "<length> <key>=<value>\n", length counted in bytes. */
    private fun parsePax(data: ByteArray): Map<String, String> {
        val result = HashMap<String, String>()
        var pos = 0
        while (pos < data.size) {
            var space = pos
            while (space < data.size && data[space] != ' '.code.toByte()) space++
            if (space >= data.size) break
            val length = String(data, pos, space - pos, Charsets.US_ASCII).toIntOrNull() ?: break
            if (length <= 0 || pos + length > data.size) break
            val record = String(data, space + 1, pos + length - space - 2, Charsets.UTF_8) // drop '\n'
            val eq = record.indexOf('=')
            if (eq > 0) result[record.substring(0, eq)] = record.substring(eq + 1)
            pos += length
        }
        return result
    }

    private fun readText(input: InputStream, size: Long): String =
        String(readBytes(input, size), Charsets.UTF_8).trimEnd('\u0000')

    private fun readBytes(input: InputStream, size: Long): ByteArray {
        if (size > MAX_HEADER_DATA) throw IOException("tar header too large: $size")
        val data = ByteArray(size.toInt())
        readFully(input, data, data.size)
        skip(input, padded(size) - size)
        return data
    }

    private fun readBlock(input: InputStream, block: ByteArray): Boolean {
        var read = 0
        while (read < block.size) {
            val n = input.read(block, read, block.size - read)
            if (n < 0) {
                if (read == 0) return false
                throw EOFException("truncated tar header")
            }
            read += n
        }
        return true
    }

    private fun readFully(input: InputStream, buffer: ByteArray, length: Int) {
        var read = 0
        while (read < length) {
            val n = input.read(buffer, read, length - read)
            if (n < 0) throw EOFException("truncated tar entry")
            read += n
        }
    }

    private fun copy(input: InputStream, out: FileOutputStream, size: Long) {
        val buffer = ByteArray(64 * 1024)
        var left = size
        while (left > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n < 0) throw EOFException("truncated tar entry")
            out.write(buffer, 0, n)
            left -= n
        }
    }

    private fun skip(input: InputStream, count: Long) {
        var left = count
        val buffer = ByteArray(BLOCK)
        while (left > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n < 0) throw EOFException("truncated tar entry")
            left -= n
        }
    }

    private fun padded(size: Long) = (size + BLOCK - 1) / BLOCK * BLOCK

    private companion object {
        const val BLOCK = 512
        const val MAX_HEADER_DATA = 1L shl 20
        const val OWNER_RW = 0x180   // 0600
        const val OWNER_RWX = 0x1C0  // 0700
        const val WHITEOUT = ".wh."
        const val OPAQUE_WHITEOUT = ".wh..wh..opq"
    }
}
