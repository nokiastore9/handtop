package org.handtop.desktop.wm

import android.view.View
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

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

    /** The user let go of the window's frame after resizing it. */
    fun onResizeEnded() {}

    // Configuration changes (keyboard plugged, DeX, rotation) reach content views through
    // the regular View.onConfigurationChanged() dispatch.

    /** Show the on-screen keyboard for this content, if it accepts text. */
    fun requestKeyboard() {}

    /** Extra buttons for the window's title bar, left of minimize (e.g. a terminal's key bar). */
    val titleActions: List<TitleAction> get() = emptyList()
}

/** A title bar button; [checked] shows a toggle as on. */
class TitleAction(@param:DrawableRes val icon: Int, @param:StringRes val label: Int, val onClick: () -> Unit) {
    var checked = false
        set(value) {
            field = value
            onChange?.invoke()
        }

    /** Set by the window to refresh the button. */
    var onChange: (() -> Unit)? = null
}

/** What the content may ask from its window. */
interface WindowHost {
    fun setTitle(title: CharSequence)
    fun close()
}
