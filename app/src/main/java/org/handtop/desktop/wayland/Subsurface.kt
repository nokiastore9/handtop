package org.handtop.desktop.wayland

/**
 * Sub-surfaces: surfaces drawn as part of another one (video areas, GL widgets, Qt child
 * windows, Firefox layers), positioned relative to their parent and stacked with their
 * siblings.
 *
 * Simplification: a synchronized sub-surface applies its content at its own commit, not at
 * the parent's; only its position waits for the parent's commit, as the protocol asks.
 */
class WlSubcompositor(client: Client, id: Int) : Resource(client, id, 1) {
    override val iface = "wl_subcompositor"

    override fun dispatch(opcode: Int, r: MessageReader) {
        when (opcode) {
            0 -> destroy()
            1 -> { // get_subsurface(id, surface, parent)
                val newId = r.newId()
                val surface = client.require<WlSurface>(r.obj())
                val parent = client.require<WlSurface>(r.obj())
                if (surface === parent || surface.role != null) {
                    throw ProtocolError(id, 0, "bad sub-surface") // wl_subcompositor.error.bad_surface
                }
                val sub = client.register(WlSubsurface(client, newId, surface, parent))
                surface.role = SurfaceRole.Subsurface(sub)
                parent.addChild(surface)
            }
            else -> super.dispatch(opcode, r)
        }
    }
}

class WlSubsurface(
    client: Client, id: Int,
    val surface: WlSurface,
    /** Null once the parent is gone. */
    var parent: WlSurface?,
) : Resource(client, id, 1) {
    override val iface = "wl_subsurface"

    /** Position of the surface origin relative to the parent's, in logical pixels. */
    var x = 0
        private set
    var y = 0
        private set
    private var pendingX = 0
    private var pendingY = 0
    private var positionPending = false

    override fun dispatch(opcode: Int, r: MessageReader) {
        when (opcode) {
            0 -> destroy()
            1 -> { // set_position: applied with the parent's next commit
                pendingX = r.int()
                pendingY = r.int()
                positionPending = true
            }
            2 -> client.optional<WlSurface>(r.obj())?.let { parent?.restack(surface, it, above = true) }
            3 -> client.optional<WlSurface>(r.obj())?.let { parent?.restack(surface, it, above = false) }
            4, 5 -> Unit // set_sync / set_desync (see the class comment)
            else -> super.dispatch(opcode, r)
        }
    }

    /** The parent committed. */
    fun parentCommitted() {
        if (!positionPending) return
        positionPending = false
        x = pendingX
        y = pendingY
    }

    override fun destroyed() {
        val old = parent
        old?.removeChild(surface)
        parent = null
        if (surface.role is SurfaceRole.Subsurface) surface.role = null
        old?.contentChanged()
    }
}
