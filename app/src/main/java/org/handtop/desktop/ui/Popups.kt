package org.handtop.desktop.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.PopupWindow
import android.widget.TextView
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

/**
 * A HandTop popup in the current theme: its [background], a shadow of [elevationDp] where
 * the theme has shadows, and no open/close animation where it has none.
 */
fun PopupWindow.applyTheme(context: Context, background: Drawable, elevationDp: Float) {
    setBackgroundDrawable(background)
    elevation = if (Themes.current.shadows) context.dp(elevationDp) else 0f
    if (!Themes.current.animations) animationStyle = 0
}

/** The line between groups of menu items, as the theme draws it. */
fun menuDivider(context: Context): View = View(context).apply { background = Themes.current.divider(context) }

/** Text of a menu line: highlighted with its line, engraved when disabled where the theme does that. */
fun TextView.styleMenuText(enabled: Boolean) {
    val theme = Themes.current
    setTextColor(theme.menuTextColors())
    isDuplicateParentStateEnabled = true
    if (!enabled && theme.embossDisabled) setShadowLayer(0.01f, context.dp(1f), context.dp(1f), theme.colors.light)
}
