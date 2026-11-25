package org.handtop.desktop.ui

import android.widget.PopupWindow
import java.lang.ref.WeakReference

/**
 * The desktop's popup windows that are showing (menus, the calendar, the notification
 * center), newest last. They are windows of their own, outside the desktop's view tree: the
 * touchpad ([org.handtop.desktop.shell.Touchpad]) needs to know them to click into them.
 */
object Popups {

    private val all = ArrayList<WeakReference<PopupWindow>>()

    /** Call right after showing [popup]. */
    fun track(popup: PopupWindow) {
        all.removeAll { it.get()?.isShowing != true }
        all += WeakReference(popup)
    }

    /** The popups on screen, the topmost last. */
    fun showing(): List<PopupWindow> {
        all.removeAll { it.get()?.isShowing != true }
        return all.mapNotNull { it.get() }
    }
}
