package org.handtop.desktop.wayland

import android.graphics.RectF

/**
 * wp_viewporter: a surface shows a part of its buffer (source) at a size of its choice
 * (destination). Video players hand over full-size frames and HandTop scales them while
 * drawing (mpv's Wayland outputs need it; GTK4 and Chromium use it too).
 */
class WpViewporter(client: Client, id: Int) : Resource(client, id, 1) {
    override val iface = "wp_viewporter"

    override fun dispatch(opcode: Int, r: MessageReader) {
        when (opcode) {
            0 -> destroy()
            1 -> { // get_viewport(id, surface)
                val newId = r.newId()
                val surface = client.require<WlSurface>(r.obj())
                if (surface.viewport != null) throw ProtocolError(id, 0, "surface already has a viewport")
                surface.viewport = client.register(WpViewport(client, newId, surface))
            }
            else -> super.dispatch(opcode, r)
        }
    }
}

class WpViewport(client: Client, id: Int, private val surface: WlSurface) : Resource(client, id, 1) {
    override val iface = "wp_viewport"

    override fun dispatch(opcode: Int, r: MessageReader) {
        when (opcode) {
            0 -> destroy()
            1 -> { // set_source(x, y, width, height) in surface coordinates; all -1 unsets
                val x = r.fixed(); val y = r.fixed(); val w = r.fixed(); val h = r.fixed()
                surface.setPendingSource(if (x == -1.0 && y == -1.0 && w == -1.0 && h == -1.0) null else RectF(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat()))
            }
            2 -> { // set_destination(width, height); -1, -1 unsets
                surface.setPendingDestination(r.int(), r.int())
            }
            else -> super.dispatch(opcode, r)
        }
    }

    override fun destroyed() {
        // The surface goes back to its buffer's size on its next commit.
        surface.setPendingSource(null)
        surface.setPendingDestination(-1, -1)
        if (surface.viewport === this) surface.viewport = null
    }
}
