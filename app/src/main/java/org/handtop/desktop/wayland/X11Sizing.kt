package org.handtop.desktop.wayland

import org.handtop.desktop.wayland.X11Connection.Companion.CONFIG_HEIGHT
import org.handtop.desktop.wayland.X11Connection.Companion.CONFIG_WIDTH

/** Window sizes X11 programs ask for (ConfigureRequest) once their window is on the desktop. */
object X11Sizing {

    /**
     * The size a ConfigureRequest with [mask] asks for ([width] / [height] where the mask says
     * so, the current size otherwise), or null when it asks for no new size.
     */
    fun requestedSize(mask: Int, width: Int, height: Int, currentWidth: Int, currentHeight: Int): IntArray? {
        val w = if (mask and CONFIG_WIDTH != 0) width else currentWidth
        val h = if (mask and CONFIG_HEIGHT != 0) height else currentHeight
        if (w <= 0 || h <= 0 || (w == currentWidth && h == currentHeight)) return null
        return intArrayOf(w, h)
    }

    /**
     * Whether an X11 window at ([x], [y]), [width] x [height], lies wholly outside a
     * [screenWidth] x [screenHeight] screen. GTK keeps such windows mapped (tiny, at -100,-100)
     * for drag and drop; they are no menus: nothing shows them, nothing taps them.
     */
    fun offScreen(x: Int, y: Int, width: Int, height: Int, screenWidth: Int, screenHeight: Int) =
        x + width <= 0 || y + height <= 0 || x >= screenWidth || y >= screenHeight
}
