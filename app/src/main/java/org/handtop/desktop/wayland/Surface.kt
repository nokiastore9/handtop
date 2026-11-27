package org.handtop.desktop.wayland

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF

/** What a surface is used for; decides what a commit means. */
sealed interface SurfaceRole {
    /** A window or popup: see [XdgSurface]. */
    class Xdg(val xdg: XdgSurface) : SurfaceRole
    object Cursor : SurfaceRole
    /** What a program draws under the finger while it drags (wl_data_device.start_drag). */
    object DndIcon : SurfaceRole
    class Subsurface(val subsurface: WlSubsurface) : SurfaceRole
    /** An X11 window through Xwayland: see [Xwm]. */
    class Xwayland(val surface: XwaylandSurface) : SurfaceRole
}

class WlSurface(client: Client, id: Int, version: Int) : Resource(client, id, version) {
    override val iface = "wl_surface"

    var role: SurfaceRole? = null

    // Double-buffered state.
    private var pendingBuffer: WlBuffer? = null
    private var pendingAttached = false
    private var pendingScale = 1
    /** set_input_region: where the surface takes pointer and touch input; null = everywhere. */
    private var pendingInputRegion: Region? = null
    private var inputRegionSet = false
    private var inputRegion: Region? = null
    private val pendingFrames = ArrayList<WlCallback>()
    /** wl_surface.damage (surface coordinates) and damage_buffer (buffer pixels). */
    private val pendingSurfaceDamage = ArrayList<Rect>()
    private val pendingBufferDamage = ArrayList<Rect>()

    /** Committed content in GPU memory (Android 10+), updated by damage only. */
    private var frames: HardwareFrames? = null
    /** Fallback: committed content copied whole into a software bitmap. */
    private var softwareBitmap: Bitmap? = null
    private var bufferWidth = 0
    private var bufferHeight = 0

    /** Committed content, already copied out of the client's buffer. */
    val bitmap: Bitmap? get() = frames?.current ?: softwareBitmap
    var bufferScale = 1
        private set

    /** Content size in surface-local (logical) coordinates. */
    val width get() = if (bitmap == null) 0 else if (destWidth > 0) destWidth else source?.width()?.toInt() ?: (bufferWidth / bufferScale)
    val height get() = if (bitmap == null) 0 else if (destHeight > 0) destHeight else source?.height()?.toInt() ?: (bufferHeight / bufferScale)

    // ---- wp_viewport (see Viewporter.kt), double-buffered like the rest ----

    var viewport: WpViewport? = null
    private var pendingSource: RectF? = null
    private var sourceSet = false
    private var pendingDestWidth = -1
    private var pendingDestHeight = -1
    private var destSet = false
    /** The part of the buffer shown, in surface coordinates (before the buffer scale); null = all. */
    private var source: RectF? = null
    private var destWidth = -1
    private var destHeight = -1

    fun setPendingSource(rect: RectF?) {
        pendingSource = rect
        sourceSet = true
    }

    fun setPendingDestination(width: Int, height: Int) {
        pendingDestWidth = width
        pendingDestHeight = height
        destSet = true
    }

    private val scaled get() = source != null || destWidth > 0

    /** The buffer pixels to draw (the viewport's source), or null for the whole buffer. */
    fun sourceInBuffer(): Rect? = source?.let {
        Rect(
            (it.left * bufferScale).toInt(), (it.top * bufferScale).toInt(),
            Math.round(it.right * bufferScale), Math.round(it.bottom * bufferScale),
        )
    }

    /** Called after each commit (on the main thread), e.g. to redraw the window. */
    var onCommit: (() -> Unit)? = null

    // ---- sub-surfaces ----

    /** This surface and its sub-surfaces, bottom to top (just this surface if it has none). */
    private val stack = ArrayList<WlSurface>().also { it += this }

    /** Bottom-to-top drawing order of this surface and its direct sub-surfaces. */
    val drawOrder: List<WlSurface> get() = stack

    val subsurface: WlSubsurface? get() = (role as? SurfaceRole.Subsurface)?.subsurface

    fun addChild(child: WlSurface) {
        stack += child // new sub-surfaces start on top
        contentChanged()
    }

    fun removeChild(child: WlSurface) {
        if (stack.remove(child)) contentChanged()
    }

    /** place_above / place_below: [child] goes right above or below [sibling] (or this surface). */
    fun restack(child: WlSurface, sibling: WlSurface, above: Boolean) {
        if (child === sibling || child !in stack || sibling !in stack) return
        stack.remove(child)
        val at = stack.indexOf(sibling)
        stack.add(if (above) at + 1 else at, child)
        contentChanged()
    }

    /** The window or popup this surface belongs to (through any sub-surface parents). */
    fun rootXdg(): XdgSurface? {
        var s: WlSurface? = this
        var guard = 0
        while (s != null && guard++ < 64) {
            (s.role as? SurfaceRole.Xdg)?.let { return it.xdg }
            s = s.subsurface?.parent
        }
        return null
    }

    /**
     * False when the window showing this surface is hidden: minimized, or HandTop is not on
     * screen. Popups, cursors and surfaces not shown yet count as shown.
     */
    fun isShown(): Boolean {
        var s: WlSurface = this
        var guard = 0
        while (guard++ < 64) s = s.subsurface?.parent ?: break
        val view = when (val r = s.role) {
            is SurfaceRole.Xdg -> r.xdg.toplevel?.view
            is SurfaceRole.Xwayland -> r.surface.window?.toplevel?.view
            else -> null
        }
        return view?.isShownOnScreen != false
    }

    /** Asks the window showing this surface to redraw. */
    fun contentChanged() {
        val xdg = rootXdg() ?: return
        if (xdg.surface === this) return // its own onCommit handles that
        (xdg.toplevel ?: xdg.popup?.toplevel)?.view?.contentChanged()
    }

    /**
     * The topmost surface of this tree under the point (logical, relative to this surface),
     * with the point in that surface's coordinates.
     */
    fun surfaceAt(x: Double, y: Double): Pair<WlSurface, DoubleArray>? {
        for (s in stack.asReversed()) {
            if (s === this) {
                // E.g. Firefox draws on a sub-surface with an empty input region: input goes below.
                if (bitmap != null && x >= 0 && y >= 0 && x < width && y < height && inputRegion?.contains(x, y) != false) {
                    return this to doubleArrayOf(x, y)
                }
            } else {
                val sub = s.subsurface ?: continue
                s.surfaceAt(x - sub.x, y - sub.y)?.let { return it }
            }
        }
        return null
    }

    override fun dispatch(opcode: Int, r: MessageReader) {
        when (opcode) {
            0 -> destroy()
            1 -> { // attach(buffer, x, y)
                pendingBuffer = client.optional<WlBuffer>(r.obj())
                r.int(); r.int()
                pendingAttached = true
            }
            2 -> pendingSurfaceDamage += readRect(r) // damage
            9 -> pendingBufferDamage += readRect(r) // damage_buffer (v4)
            3 -> pendingFrames += client.register(WlCallback(client, r.newId()))
            4 -> r.obj() // set_opaque_region
            5 -> { // set_input_region(region or null)
                pendingInputRegion = client.optional<WlRegion>(r.obj())?.snapshot()
                inputRegionSet = true
            }
            6 -> commit()
            7 -> r.int() // set_buffer_transform
            8 -> pendingScale = r.int().coerceIn(1, 8)
            10 -> { r.int(); r.int() } // offset (v5)
            else -> super.dispatch(opcode, r)
        }
    }

    /** x, y, width, height; clients often send INT32_MAX for "everything", so keep it in range. */
    private fun readRect(r: MessageReader): Rect {
        val x = r.int().toLong()
        val y = r.int().toLong()
        val w = r.int().toLong().coerceAtLeast(0)
        val h = r.int().toLong().coerceAtLeast(0)
        fun clamp(v: Long) = v.coerceIn(-LIMIT, LIMIT).toInt()
        return Rect(clamp(x), clamp(y), clamp(x + w), clamp(y + h))
    }

    private fun commit() {
        val scale = pendingScale
        if (pendingAttached) {
            pendingAttached = false
            val buffer = pendingBuffer
            pendingBuffer = null
            if (buffer == null) {
                dropContent()
            } else {
                copyFrom(buffer, damageInBuffer(scale, buffer))
                buffer.release() // the pixels are ours now: the client may reuse its buffer
            }
        }
        pendingSurfaceDamage.clear()
        pendingBufferDamage.clear()
        bufferScale = scale
        if (sourceSet) {
            source = pendingSource
            sourceSet = false
        }
        if (destSet) {
            destWidth = pendingDestWidth
            destHeight = pendingDestHeight
            destSet = false
        }
        if (inputRegionSet) {
            inputRegion = pendingInputRegion
            inputRegionSet = false
        }
        if (pendingFrames.isNotEmpty()) {
            client.server.scheduleFrameCallbacks(this, pendingFrames)
            pendingFrames.clear()
        }
        for (child in stack) if (child !== this) child.subsurface?.parentCommitted()
        (role as? SurfaceRole.Xdg)?.xdg?.surfaceCommitted()
        if (role === SurfaceRole.Cursor) client.server.seat.cursorCommitted(this)
        if (role === SurfaceRole.DndIcon) client.server.dnd.iconCommitted(this)
        if (role is SurfaceRole.Subsurface) contentChanged()
        (role as? SurfaceRole.Xwayland)?.surface?.committed()
        onCommit?.invoke()
    }

    /** This commit's damage in buffer pixels, clipped to the buffer (whole buffer if none). */
    private fun damageInBuffer(scale: Int, buffer: WlBuffer): List<Rect> {
        val bounds = Rect(0, 0, buffer.width, buffer.height)
        val out = ArrayList<Rect>(pendingSurfaceDamage.size + pendingBufferDamage.size)
        // Damage in surface coordinates with a viewport (scaled, cropped): take the whole buffer.
        if (pendingSurfaceDamage.isNotEmpty() && (scaled || pendingSource != null || pendingDestWidth > 0)) out += bounds
        else for (d in pendingSurfaceDamage) {
            val r = Rect(d.left * scale, d.top * scale, d.right * scale, d.bottom * scale)
            if (r.intersect(bounds)) out += r
        }
        for (d in pendingBufferDamage) {
            val r = Rect(d)
            if (r.intersect(bounds)) out += r
        }
        // A new buffer without damage would show nothing new; be lenient and take all of it.
        if (out.isEmpty() && pendingSurfaceDamage.isEmpty() && pendingBufferDamage.isEmpty()) out += bounds
        return out
    }

    private fun copyFrom(buffer: WlBuffer, damage: List<Rect>) {
        if (buffer.width <= 0 || buffer.height <= 0) return
        if (buffer.width != bufferWidth || buffer.height != bufferHeight) {
            // New size: start over (the first frame of every buffer is copied whole).
            dropContent()
            bufferWidth = buffer.width
            bufferHeight = buffer.height
            // Pointer and drag icons need a software bitmap.
            frames = if (role === SurfaceRole.Cursor || role === SurfaceRole.DndIcon) null
            else HardwareFrames.create(bufferWidth, bufferHeight)
        }
        val hardware = frames
        if (hardware != null) {
            if (damage.isEmpty() || hardware.update(buffer, damage)) return
            // No usable hardware buffer here: software from now on.
            hardware.release()
            frames = null
        }
        copyToSoftware(buffer)
    }

    private fun copyToSoftware(buffer: WlBuffer) {
        var target = softwareBitmap
        if (target == null || target.width != buffer.width || target.height != buffer.height) {
            target = Bitmap.createBitmap(buffer.width, buffer.height, Bitmap.Config.ARGB_8888)
        }
        val pool = buffer.pool
        val ok = Native.blit(
            pool.addr, pool.size, buffer.offset, buffer.width, buffer.height, buffer.stride,
            buffer.format == WlShm.FORMAT_XRGB8888, target,
        )
        if (ok != 0) throw ProtocolError(buffer.id, 0, "buffer outside its pool")
        softwareBitmap = target
    }

    private fun dropContent() {
        frames?.release()
        frames = null
        softwareBitmap = null
        bufferWidth = 0
        bufferHeight = 0
    }

    /** wl_surface.enter for every output the client bound. */
    fun enterOutputs() {
        client.resources().filterIsInstance<WlOutput>().forEach { out -> event(0) { obj(out.id) } }
    }

    override fun destroyed() {
        pendingFrames.forEach { client.destroy(it) }
        pendingFrames.clear()
        (role as? SurfaceRole.Xdg)?.xdg?.surfaceGone()
        (role as? SurfaceRole.Xwayland)?.surface?.surfaceGone()
        client.server.seat.surfaceGone(this)
        // Orphan our sub-surfaces; leave our parent's stack.
        for (child in stack) if (child !== this) child.subsurface?.parent = null
        stack.clear()
        stack += this
        subsurface?.parent?.removeChild(this)
        dropContent()
    }

    private companion object {
        const val LIMIT = 1L shl 20
    }
}
