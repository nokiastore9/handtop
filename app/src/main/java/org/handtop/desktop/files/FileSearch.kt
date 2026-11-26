package org.handtop.desktop.files

import java.io.File
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.Locale

/** Finds files and folders by name under a folder (the file manager's search). */
object FileSearch {

    /** Every word of [query] is in [name], ignoring case: "rep 2024" finds "Report_2024.pdf". */
    fun matches(name: String, query: String): Boolean {
        val words = words(query)
        if (words.isEmpty()) return false
        val lower = name.lowercase(Locale.ROOT)
        return words.all { it in lower }
    }

    private fun words(query: String) = query.lowercase(Locale.ROOT).split(' ', '\t').filter { it.isNotEmpty() }

    /**
     * The files and folders under [root] whose names match [query], nearer ones first (folder by
     * folder, names in order). Hidden ones (a dot first) only with [hidden]. Linked folders are
     * not entered: no loops, no leaving [root]. Stops at [limit] results or when [cancelled].
     */
    fun search(
        root: File,
        query: String,
        hidden: Boolean = false,
        limit: Int = 500,
        cancelled: () -> Boolean = { false },
    ): List<File> {
        val found = ArrayList<File>()
        if (words(query).isEmpty()) return found
        val folders = ArrayDeque<File>().apply { add(root) }
        while (folders.isNotEmpty()) {
            if (cancelled()) return emptyList()
            val children = folders.removeFirst().listFiles() ?: continue
            children.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            for (child in children) {
                if (!hidden && child.name.startsWith(".")) continue
                if (matches(child.name, query)) {
                    found += child
                    if (found.size >= limit) return found
                }
                if (child.isDirectory && !Files.isSymbolicLink(child.toPath())) folders.add(child)
            }
        }
        return found
    }
}
