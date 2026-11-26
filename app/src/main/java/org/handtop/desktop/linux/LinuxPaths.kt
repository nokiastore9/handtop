package org.handtop.desktop.linux

import java.io.File
import java.net.URI
import java.net.URISyntaxException

/** Where a path a Linux program names is on Android (the reverse of FilePlaces.linuxPath). */
object LinuxPaths {

    /**
     * The Android file for absolute Linux [path]: /sdcard is the phone ([phone]) and /storage
     * its cards (only with "All files access", [storageAccess]); everything else is inside the
     * root filesystem [rootfs]. "." and ".." are resolved first and cannot climb out of Linux.
     * Null for a relative path, or the phone without access.
     */
    fun toAndroid(path: String, rootfs: File, phone: File, storageAccess: Boolean): File? {
        if (!path.startsWith("/")) return null
        val parts = ArrayList<String>()
        for (part in path.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts += part
            }
        }
        return when (parts.firstOrNull()) {
            "sdcard" -> if (storageAccess) join(phone, parts.drop(1)) else null
            "storage" -> if (storageAccess) File("/" + parts.joinToString("/")) else null
            else -> join(rootfs, parts)
        }
    }

    /** What a Linux program asked HandTop to open: a file (its Linux path) or a link. */
    sealed class Target {
        data class Path(val path: String) : Target()
        data class Link(val uri: String) : Target()
    }

    private val scheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

    /**
     * [text] as handtop-open passes it: an absolute path, a file:// URI on this machine
     * (decoded), or a link of any other scheme. Null for anything else (a relative path).
     */
    fun target(text: String): Target? {
        if (text.startsWith("/")) return Target.Path(text)
        if (!scheme.containsMatchIn(text)) return null
        if (!text.startsWith("file:", ignoreCase = true)) return Target.Link(text)
        val uri = try {
            URI(text)
        } catch (_: URISyntaxException) {
            return null
        }
        val host = uri.host
        if (!host.isNullOrEmpty() && host != "localhost") return null
        return uri.path?.takeIf { it.startsWith("/") }?.let { Target.Path(it) }
    }

    private fun join(base: File, parts: List<String>) = if (parts.isEmpty()) base else File(base, parts.joinToString("/"))
}
