package org.handtop.desktop.wm

/** What a window's menu on the taskbar offers. */
enum class WindowAction { RESTORE, MINIMIZE, MAXIMIZE, NORMAL_SIZE, CLOSE }

/**
 * The entries of a window's taskbar menu: show it again or minimize it, maximize it or give
 * back its normal size (only a [resizable] window), close it. Also the way to a window that
 * was dragged off the screen, whose title bar buttons cannot be reached.
 */
fun windowActions(minimized: Boolean, maximized: Boolean, resizable: Boolean): List<WindowAction> = buildList {
    add(if (minimized) WindowAction.RESTORE else WindowAction.MINIMIZE)
    if (resizable) add(if (maximized) WindowAction.NORMAL_SIZE else WindowAction.MAXIMIZE)
    add(WindowAction.CLOSE)
}
