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
