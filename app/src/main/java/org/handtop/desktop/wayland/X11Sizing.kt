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
}
