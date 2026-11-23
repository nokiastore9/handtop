package org.handtop.desktop.wm

import android.view.View

/**
 * Anything that can live inside a desktop window: the terminal, the about page and,
 * later, Linux application surfaces coming from the display server.
 */
interface WindowContent {
    val view: View

    /** Called once when the content is put into a window. */
    fun attach(host: WindowHost) {}

    /** The window became the active (focused) one. */
    fun onActivated() {}

    /** Another window became active. */
    fun onDeactivated() {}

    /**
     * The user asked to close the window (close button, Alt+F4). Return true to handle it
     * yourself — e.g. ask a Linux program to quit — instead of closing immediately.
     */
    fun onCloseRequest(): Boolean = false

    /** The window was closed and removed from the desktop; release resources here. */
    fun onClosed() {}

    // Configuration changes (keyboard plugged, DeX, rotation) reach content views through
    // the regular View.onConfigurationChanged() dispatch.

    /** Show the on-screen keyboard for this content, if it accepts text. */
    fun requestKeyboard() {}
}

/** What the content may ask from its window. */
interface WindowHost {
    fun setTitle(title: CharSequence)
    fun close()
}
