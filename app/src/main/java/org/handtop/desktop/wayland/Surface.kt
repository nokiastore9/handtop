package org.handtop.desktop.wayland

import android.graphics.Bitmap

/** What a surface is used for; decides what a commit means. */
sealed interface SurfaceRole {
    /** A window or popup: see [XdgSurface]. */
    class Xdg(val xdg: XdgSurface) : SurfaceRole
    object Cursor : SurfaceRole
    object Subsurface : SurfaceRole
}

class WlSurface(client: Client, id: Int, version: Int) : Resource(client, id, version) {
    override val iface = "wl_surface"

    var role: SurfaceRole? = null

    // Double-buffered state.
    private var pendingBuffer: WlBuffer? = null
    private var pendingAttached = false
    private var pendingScale = 1
    private val pendingFrames = ArrayList<WlCallback>()

    /** Committed content, already copied out of the client's buffer. */
    var bitmap: Bitmap? = null
        private set
    var bufferScale = 1
        private set

    /** Content size in surface-local (logical) coordinates. */
    val width get() = (bitmap?.width ?: 0) / bufferScale
    val height get() = (bitmap?.height ?: 0) / bufferScale

    /** Called after each commit (on the main thread), e.g. to redraw the window. */
    var onCommit: (() -> Unit)? = null

    override fun dispatch(opcode: Int, r: MessageReader) {
        when (opcode) {
            0 -> destroy()
            1 -> { // attach(buffer, x, y)
                pendingBuffer = client.optional<WlBuffer>(r.obj())
                r.int(); r.int()
                pendingAttached = true
            }
            2, 9 -> repeat(4) { r.int() } // damage / damage_buffer: we copy whole buffers for now
            3 -> pendingFrames += client.register(WlCallback(client, r.newId()))
            4, 5 -> r.obj() // set_opaque_region / set_input_region
            6 -> commit()
            7 -> r.int() // set_buffer_transform
            8 -> pendingScale = r.int().coerceIn(1, 8)
            10 -> { r.int(); r.int() } // offset (v5)
            else -> super.dispatch(opcode, r)
        }
    }

    private fun commit() {
        if (pendingAttached) {
            pendingAttached = false
            val buffer = pendingBuffer
            pendingBuffer = null
            if (buffer == null) {
                bitmap = null
            } else {
                copyFrom(buffer)
                buffer.release() // the pixels are ours now: the client may reuse its buffer
            }
        }
        bufferScale = pendingScale
        if (pendingFrames.isNotEmpty()) {
            client.server.scheduleFrameCallbacks(pendingFrames)
            pendingFrames.clear()
        }
        (role as? SurfaceRole.Xdg)?.xdg?.surfaceCommitted()
        onCommit?.invoke()
    }

    private fun copyFrom(buffer: WlBuffer) {
        if (buffer.width <= 0 || buffer.height <= 0) return
        var target = bitmap
        if (target == null || target.width != buffer.width || target.height != buffer.height) {
            target = Bitmap.createBitmap(buffer.width, buffer.height, Bitmap.Config.ARGB_8888)
        }
        val pool = buffer.pool
        val ok = Native.blit(
            pool.addr, pool.size, buffer.offset, buffer.width, buffer.height, buffer.stride,
            buffer.format == WlShm.FORMAT_XRGB8888, target,
        )
        if (ok != 0) throw ProtocolError(buffer.id, 0, "buffer outside its pool")
        bitmap = target
    }

    /** wl_surface.enter for every output the client bound. */
    fun enterOutputs() {
        client.resources().filterIsInstance<WlOutput>().forEach { out -> event(0) { obj(out.id) } }
    }

    override fun destroyed() {
        pendingFrames.forEach { client.destroy(it) }
        pendingFrames.clear()
        (role as? SurfaceRole.Xdg)?.xdg?.surfaceGone()
        client.server.seat.surfaceGone(this)
        bitmap = null
    }
}
