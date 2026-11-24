package org.handtop.desktop.wayland

import android.util.Log

/**
 * Keeps the screen on while Linux programs ask for it (a video plays, a presentation runs).
 * Holders: Wayland idle inhibitors and D-Bus inhibitions (see InhibitBus). Main thread.
 */
object ScreenKeeper {

    private const val TAG = "HandTopScreen"
    private val holders = HashSet<Any>()

    /** The desktop window applies FLAG_KEEP_SCREEN_ON with this. */
    var onChange: ((Boolean) -> Unit)? = null

    val active get() = holders.isNotEmpty()

    fun hold(holder: Any) {
        if (!holders.add(holder)) return
        Log.i(TAG, "keep the screen on: $holder (${holders.size})")
        if (holders.size == 1) onChange?.invoke(true)
    }

    fun release(holder: Any) {
        if (!holders.remove(holder)) return
        Log.i(TAG, "released: $holder (${holders.size} left)")
        if (holders.isEmpty()) onChange?.invoke(false)
    }
}

/** zwp_idle_inhibit_manager_v1: Wayland programs (video players, browsers) keep the screen on. */
class IdleInhibitManager(client: Client, id: Int) : Resource(client, id, 1) {
    override val iface = "zwp_idle_inhibit_manager_v1"

    override fun dispatch(opcode: Int, r: MessageReader) {
        when (opcode) {
            0 -> destroy()
            1 -> { // create_inhibitor(id, surface)
                val newId = r.newId()
                val surface = client.require<WlSurface>(r.obj())
                client.register(IdleInhibitor(client, newId, surface))
            }
            else -> super.dispatch(opcode, r)
        }
    }
}

/**
 * Active while it exists. (The protocol limits it to a visible surface; a minimized player
 * still keeps the screen on — the simpler, safer choice for a video.)
 */
class IdleInhibitor(client: Client, id: Int, private val surface: WlSurface) : Resource(client, id, 1) {
    override val iface = "zwp_idle_inhibitor_v1"

    init {
        ScreenKeeper.hold(this)
    }

    override fun dispatch(opcode: Int, r: MessageReader) {
        if (opcode == 0) destroy() else super.dispatch(opcode, r)
    }

    override fun destroyed() = ScreenKeeper.release(this)
}
