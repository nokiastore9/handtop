package org.handtop.desktop.wayland

/** A rectangle in logical units (plain Kotlin, so placement can be tested on the JVM). */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    val isEmpty get() = width <= 0 || height <= 0
    fun offset(dx: Int, dy: Int) = Box(left + dx, top + dy, right + dx, bottom + dy)
}

/**
 * How a popup is placed (an xdg_positioner's rules): its size, the anchor rectangle on its
 * parent, anchor edge, gravity, offset and the constraint adjustment allowed when it would not
 * fit. Kept with the popup, so it can be placed again when the room around it changes.
 */
data class PopupRules(
    val width: Int,
    val height: Int,
    val anchorRect: Box,
    val anchor: Int,
    val gravity: Int,
    val offsetX: Int,
    val offsetY: Int,
    val adjustment: Int,
) {
    /**
     * Position relative to the parent's window geometry. With [bounds] (the desktop in the
     * same coordinates) the constraint adjustment is applied: flip, then slide, then resize,
     * per axis, so menus stay on screen.
     */
    fun place(bounds: Box?): Box {
        var r = Box(xFor(anchor, gravity), yFor(anchor, gravity), 0, 0)
        r = Box(r.left, r.top, r.left + width, r.top + height)
        if (bounds == null || bounds.isEmpty) return r

        if (r.left < bounds.left || r.right > bounds.right) {
            if (adjustment and FLIP_X != 0) {
                val x = xFor(flipX(anchor), flipX(gravity))
                if (x >= bounds.left && x + width <= bounds.right) r = Box(x, r.top, x + width, r.bottom)
            }
            if (adjustment and SLIDE_X != 0) {
                if (r.right > bounds.right) r = r.offset(bounds.right - r.right, 0)
                if (r.left < bounds.left) r = r.offset(bounds.left - r.left, 0)
            }
            if (adjustment and RESIZE_X != 0) {
                r = Box(maxOf(r.left, bounds.left), r.top, minOf(r.right, bounds.right), r.bottom)
            }
        }
        if (r.top < bounds.top || r.bottom > bounds.bottom) {
            if (adjustment and FLIP_Y != 0) {
                val y = yFor(flipY(anchor), flipY(gravity))
                if (y >= bounds.top && y + height <= bounds.bottom) r = Box(r.left, y, r.right, y + height)
            }
            if (adjustment and SLIDE_Y != 0) {
                if (r.bottom > bounds.bottom) r = r.offset(0, bounds.bottom - r.bottom)
                if (r.top < bounds.top) r = r.offset(0, bounds.top - r.top)
            }
            if (adjustment and RESIZE_Y != 0) {
                r = Box(r.left, maxOf(r.top, bounds.top), r.right, minOf(r.bottom, bounds.bottom))
            }
        }
        if (r.width <= 0 || r.height <= 0) r = Box(r.left, r.top, r.left + 1, r.top + 1)
        return r
    }

    private fun xFor(anchor: Int, gravity: Int): Int {
        val ax = when (anchor) {
            LEFT, TOP_LEFT, BOTTOM_LEFT -> anchorRect.left
            RIGHT, TOP_RIGHT, BOTTOM_RIGHT -> anchorRect.right
            else -> (anchorRect.left + anchorRect.right) / 2
        }
        return when (gravity) {
            LEFT, TOP_LEFT, BOTTOM_LEFT -> ax - width
            RIGHT, TOP_RIGHT, BOTTOM_RIGHT -> ax
            else -> ax - width / 2
        } + offsetX
    }

    private fun yFor(anchor: Int, gravity: Int): Int {
        val ay = when (anchor) {
            TOP, TOP_LEFT, TOP_RIGHT -> anchorRect.top
            BOTTOM, BOTTOM_LEFT, BOTTOM_RIGHT -> anchorRect.bottom
            else -> (anchorRect.top + anchorRect.bottom) / 2
        }
        return when (gravity) {
            TOP, TOP_LEFT, TOP_RIGHT -> ay - height
            BOTTOM, BOTTOM_LEFT, BOTTOM_RIGHT -> ay
            else -> ay - height / 2
        } + offsetY
    }

    private fun flipX(edge: Int) = when (edge) {
        LEFT -> RIGHT
        RIGHT -> LEFT
        TOP_LEFT -> TOP_RIGHT
        TOP_RIGHT -> TOP_LEFT
        BOTTOM_LEFT -> BOTTOM_RIGHT
        BOTTOM_RIGHT -> BOTTOM_LEFT
        else -> edge
    }

    private fun flipY(edge: Int) = when (edge) {
        TOP -> BOTTOM
        BOTTOM -> TOP
        TOP_LEFT -> BOTTOM_LEFT
        BOTTOM_LEFT -> TOP_LEFT
        TOP_RIGHT -> BOTTOM_RIGHT
        BOTTOM_RIGHT -> TOP_RIGHT
        else -> edge
    }

    companion object {
        // xdg_positioner anchor / gravity values
        const val TOP = 1
        const val BOTTOM = 2
        const val LEFT = 3
        const val RIGHT = 4
        const val TOP_LEFT = 5
        const val BOTTOM_LEFT = 6
        const val TOP_RIGHT = 7
        const val BOTTOM_RIGHT = 8

        // constraint_adjustment bits
        const val SLIDE_X = 1
        const val SLIDE_Y = 2
        const val FLIP_X = 4
        const val FLIP_Y = 8
        const val RESIZE_X = 16
        const val RESIZE_Y = 32
    }
}

/**
 * Where a popup is now, and the rules it was placed by. When the room around it changes (the
 * on-screen keyboard hides, the window moves), [reconstrain] places it again from its rules.
 */
class Placement(rules: PopupRules, bounds: Box?) {
    var rules = rules
        private set
    var position = rules.place(bounds)
        private set

    /** New rules from the program (xdg_popup.reposition). */
    fun reposition(rules: PopupRules, bounds: Box?) {
        this.rules = rules
        position = rules.place(bounds)
    }

    /** Places the popup again for [bounds]; true when its position or size changed. */
    fun reconstrain(bounds: Box?): Boolean {
        val placed = rules.place(bounds)
        if (placed == position) return false
        position = placed
        return true
    }
}
