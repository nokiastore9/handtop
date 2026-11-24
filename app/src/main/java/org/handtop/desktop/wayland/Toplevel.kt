package org.handtop.desktop.wayland

import android.graphics.Rect

/**
 * A top-level Linux window shown on the desktop: an xdg_toplevel ([XdgToplevel]) or an X11
 * window through Xwayland ([XwaylandToplevel]). Sizes and positions are logical pixels.
 */
interface Toplevel {
    val client: Client
    val surface: WlSurface
    val title: String
    /** xdg app_id, or the X11 WM_CLASS class; matched against menu entries for the icon. */
    val appId: String
    /** Owner window for dialogs. */
    val parent: Toplevel?
    /** Dialogs and the like: the program allows exactly one size. */
    val fixedSize: Boolean
    /** HandTop draws the title bar; otherwise the program draws its own. */
    val serverSideDecorations: Boolean
    /** Open menus, tooltips and the like, drawn by the [PopupLayer]. */
    val popups: List<Popup>
    val mapped: Boolean

    /** Window shown on the desktop; set by the host when it maps the toplevel. */
    var view: ToplevelView?

    /** The part of [surface] that is the window (excludes client-side shadows). */
    fun visibleGeometry(): Rect

    /** New size and state requested by the window manager. */
    fun requestState(width: Int, height: Int, activated: Boolean, maximized: Boolean)

    /** The user pressed the window's close button. */
    fun requestClose()

    /** Where the window's content now is on the desktop. X11 needs it for menu positions. */
    fun moved(x: Int, y: Int) = Unit
}

/** A menu, tooltip or combo list of a [Toplevel]. */
interface Popup {
    val surface: WlSurface

    /** Where the popup's geometry is, relative to the toplevel's geometry origin. */
    val originInToplevel: Rect

    /** False for tooltips: they take no clicks and do not close on a click elsewhere. */
    val takesInput: Boolean get() = true

    /**
     * A menu that holds the input: a click elsewhere closes it (and is not passed on).
     * Tooltips and other popups without a grab let the click through.
     */
    val closesOnOutsideClick: Boolean get() = takesInput

    fun visibleGeometry(): Rect

    /** Closes the popup (a click outside it). */
    fun dismiss()
}
