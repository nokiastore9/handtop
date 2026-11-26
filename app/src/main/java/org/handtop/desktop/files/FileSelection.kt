package org.handtop.desktop.files

/**
 * Which files of a folder are selected. With a mouse: a click selects one file, Ctrl adds or
 * removes, Shift selects a range. With a finger: a tap opens a file, until the selection mode
 * ([touchMode]) starts; then taps pick files, and the mode ends with the last one.
 */
class FileSelection<T> {
    private val chosen = LinkedHashSet<T>()
    private var anchor = -1

    /** The selected files, in the order they were picked. */
    val items: Set<T> get() = chosen

    /** Taps pick files instead of opening them. */
    var touchMode = false
        private set

    val size get() = chosen.size

    operator fun contains(item: T) = item in chosen

    /** A mouse click on file [index] of [files]: alone, added / removed ([add]), a range ([range]). */
    fun click(files: List<T>, index: Int, add: Boolean, range: Boolean) {
        val item = files.getOrNull(index) ?: return
        touchMode = false
        when {
            range && anchor >= 0 -> {
                if (!add) chosen.clear()
                for (i in minOf(anchor, index)..maxOf(anchor, index)) files.getOrNull(i)?.let { chosen += it }
            }
            add -> {
                if (!chosen.remove(item)) chosen += item
                anchor = index
            }
            else -> {
                chosen.clear()
                chosen += item
                anchor = index
            }
        }
    }

    /** Starts picking files with a finger, file [index] first (a tap on its icon, or "Select"). */
    fun startTouch(files: List<T>, index: Int) {
        val item = files.getOrNull(index) ?: return
        touchMode = true
        chosen += item
        anchor = index
    }

    /** A finger tap on file [index]: picked or put back in the selection mode (true), else it opens. */
    fun tap(files: List<T>, index: Int): Boolean {
        if (!touchMode) return false
        val item = files.getOrNull(index) ?: return true
        if (!chosen.remove(item)) chosen += item
        anchor = index
        if (chosen.isEmpty()) touchMode = false
        return true
    }

    /** Marks [item] (the folder the user came from) without anchoring a range or the mode. */
    fun highlight(item: T) {
        chosen.clear()
        chosen += item
        anchor = -1
        touchMode = false
    }

    fun selectAll(files: List<T>) {
        chosen.clear()
        chosen += files
    }

    fun clear() {
        chosen.clear()
        anchor = -1
        touchMode = false
    }

    /** Keeps only files still in the folder (after it was read again). */
    fun retain(files: Collection<T>) {
        chosen.retainAll(files.toSet())
        if (chosen.isEmpty()) touchMode = false
    }
}
