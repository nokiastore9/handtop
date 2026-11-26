package org.handtop.desktop.wayland

/**
 * HandTop suggests a size for a floating Linux window (xdg_toplevel.configure); the program
 * may draw another one: a game or benchmark with a fixed size, or a GTK window that will not
 * shrink below its minimum. Then the window follows the program, as on a Linux desktop:
 * otherwise a black margin shows, or the content is cut off.
 */
object ClientSize {

    /**
     * Whether the window should take the program's size ([committedWidth] x [committedHeight],
     * surface units) instead of the one asked for ([configuredWidth] x [configuredHeight]).
     * Only an answer to the latest request counts ([ackedLatest]): while the user drags the
     * frame, the program is still answering earlier sizes. Each request is followed once
     * ([handled]), so a window that cannot fit is not resized at every frame.
     */
    fun adopt(
        ackedLatest: Boolean,
        handled: Boolean,
        configuredWidth: Int,
        configuredHeight: Int,
        committedWidth: Int,
        committedHeight: Int,
    ): Boolean =
        ackedLatest && !handled &&
            configuredWidth > 0 && configuredHeight > 0 && committedWidth > 0 && committedHeight > 0 &&
            (committedWidth != configuredWidth || committedHeight != configuredHeight)
}
