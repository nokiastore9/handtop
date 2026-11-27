package org.handtop.desktop.wayland

import org.handtop.desktop.linux.LinuxPaths

/** Dragged files as Linux programs exchange them: `text/uri-list` (RFC 2483). */
object UriList {
    const val MIME = "text/uri-list"

    /** [paths] (absolute Linux paths) as file:// URIs, one per line, UTF-8 percent-encoded. */
    fun encode(paths: List<String>): String = buildString {
        for (path in paths) {
            append("file://")
            for (byte in path.toByteArray(Charsets.UTF_8)) {
                val c = byte.toInt() and 0xFF
                if (c.toChar() in SAFE) append(c.toChar()) else append('%').append(HEX[c shr 4]).append(HEX[c and 15])
            }
            append("\r\n")
        }
    }

    /** The Linux paths of the file:// URIs on this machine in [text]; comments and other links are skipped. */
    fun parse(text: String): List<String> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.startsWith("file:", ignoreCase = true) }
            .mapNotNull { (LinuxPaths.target(it) as? LinuxPaths.Target.Path)?.path }
            .toList()

    private const val HEX = "0123456789ABCDEF"
    private val SAFE = ('A'..'Z').toSet() + ('a'..'z') + ('0'..'9') + setOf('-', '.', '_', '~', '/')
}

/** wl_data_device_manager.dnd_action: what a drop does with the data. */
object DndActions {
    const val NONE = 0
    const val COPY = 1
    const val MOVE = 2
    const val ASK = 4

    /**
     * The action of a drop: the target's [preferred] one when the [source]'s and the
     * [target]'s actions both allow it, else the first they share of copy, move and ask.
     */
    fun choose(source: Int, target: Int, preferred: Int): Int {
        val common = source and target
        if (preferred != NONE && common and preferred == preferred && Integer.bitCount(preferred) == 1) return preferred
        return listOf(COPY, MOVE, ASK).firstOrNull { common and it != 0 } ?: NONE
    }
}

/**
 * XDND, drag and drop between X11 programs (freedesktop.org XDND 5), as HandTop speaks it to
 * X11 windows: the data of its client messages, and which selection targets cross between X11
 * and the rest of the desktop (files and text).
 */
object Xdnd {
    const val VERSION = 5

    /** The version both sides speak with a target whose XdndAware says [targetVersion]; 0: none (below 3). */
    fun version(targetVersion: Int) = if (targetVersion < 3) 0 else minOf(targetVersion, VERSION)

    /** XdndEnter: the source, the version and whether more types than these three are in XdndTypeList. */
    fun enter(source: Int, version: Int, types: List<Int>) = intArrayOf(
        source, (version shl 24) or (if (types.size > 3) 1 else 0),
        types.getOrElse(0) { 0 }, types.getOrElse(1) { 0 }, types.getOrElse(2) { 0 },
    )

    /** XdndPosition: the point on the root window and the action asked for. */
    fun position(source: Int, rootX: Int, rootY: Int, time: Int, action: Int) =
        intArrayOf(source, 0, (rootX shl 16) or (rootY and 0xffff), time, action)

    fun drop(source: Int, time: Int) = intArrayOf(source, 0, time, 0, 0)

    fun leave(source: Int) = intArrayOf(source, 0, 0, 0, 0)

    /** XdndStatus: the target takes a drop at the last position ([flags], its second word). */
    fun accepts(flags: Int) = flags and 1 != 0

    /** The Wayland MIME type of an X11 selection target, for the ones that cross: files and text. */
    fun mimeOf(target: String): String? = when (target) {
        "text/uri-list" -> UriList.MIME
        "UTF8_STRING", "text/plain;charset=utf-8" -> TEXT_UTF8
        "text/plain" -> "text/plain"
        else -> null
    }

    /** The X11 targets of a drag carrying [mimes], files first. */
    fun targetsFor(mimes: Collection<String>): List<String> = buildList {
        if (UriList.MIME in mimes) add("text/uri-list")
        if (TEXT_UTF8 in mimes) {
            add("UTF8_STRING")
            add(TEXT_UTF8)
        }
        if ("text/plain" in mimes) add("text/plain")
    }

    /**
     * Which of an X11 program's [targets] HandTop fetches when it starts dragging: the file list,
     * and its text in the best encoding offered (one of them).
     */
    fun wanted(targets: List<String>): List<String> = buildList {
        if ("text/uri-list" in targets) add("text/uri-list")
        listOf("UTF8_STRING", TEXT_UTF8, "text/plain").firstOrNull { it in targets }?.let { add(it) }
    }

    private const val TEXT_UTF8 = "text/plain;charset=utf-8"
}
