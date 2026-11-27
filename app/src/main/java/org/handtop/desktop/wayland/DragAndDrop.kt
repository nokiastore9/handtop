package org.handtop.desktop.wayland

import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.View
import java.io.ByteArrayOutputStream

/** Files a HandTop window drags (Files), as their Linux paths: what a Linux window takes. */
interface DraggedFiles {
    val linuxPaths: List<String>
}

/**
 * A drag a Linux program started (wl_data_device.start_drag): the localState of the Android
 * drag that carries it across windows. [view] is the window it started in, [icon] the surface
 * the program draws under the finger.
 */
class LinuxDrag(
    private val server: WaylandServer,
    val source: WlDataSource,
    val icon: WlSurface?,
    val view: View,
) {
    /** The program offers files (a file manager), not only text. */
    val offersFiles get() = UriList.MIME in source.mimeTypes

    /**
     * Dropped on a HandTop window that takes files: reads them from the program and gives
     * their Linux paths to [done] on the main thread (empty if the program sends none). The
     * program is told it was a copy: HandTop does the moving, the program deletes nothing.
     */
    fun dropFiles(done: (List<String>) -> Unit) {
        if (!source.client.connected || !source.alive || !offersFiles) return done(emptyList())
        if (source.version >= 3) {
            source.event(5) { uint(DndActions.COPY) } // action
            source.event(3) // dnd_drop_performed
        }
        server.dnd.droppedOutside(this)
        val pipe = ParcelFileDescriptor.createPipe()
        source.event(1) { string(UriList.MIME); fd(pipe[1].detachFd()) } // send
        source.client.flush()
        val main = Handler(Looper.getMainLooper())
        Thread({
            val text = try {
                ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).use { input ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    while (out.size() < MAX_URI_BYTES) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                    out.toString(Charsets.UTF_8.name())
                }
            } catch (e: Exception) {
                Log.w(WaylandServer.TAG, "drag: reading the files failed", e)
                ""
            }
            main.post {
                if (source.client.connected && source.alive && source.version >= 3) {
                    source.event(4) // dnd_finished
                    source.client.flush()
                }
                done(UriList.parse(text))
            }
        }, "drag-read").start()
    }

    private companion object {
        const val MAX_URI_BYTES = 4 * 1024 * 1024
    }
}

/**
 * Drag and drop with Linux programs. Every drag between windows is an Android drag (Files'
 * own, or one a Linux program started); a Linux window under the finger gets it as
 * wl_data_device enter / motion / leave / drop, with offers HandTop makes. One drag at a time;
 * main thread only.
 */
class DragAndDrop(private val server: WaylandServer) {

    /** The drag going on, as far as Linux windows are concerned. */
    class Session(val payload: Any) {
        /** The Linux surface the finger is over and the offers its program got for it. */
        var surface: WlSurface? = null
        val offers = ArrayList<WlDndOffer>()
        /** The offer the drop went to. */
        var dropped: WlDndOffer? = null
        var ended = false
        val linux get() = payload as? LinuxDrag

        /** What the source allows: a Linux program's actions, Files' copy or move. */
        val sourceActions: Int
            get() = when (val p = payload) {
                is LinuxDrag -> p.source.dndActions ?: DndActions.COPY
                else -> DndActions.COPY or DndActions.MOVE
            }

        val mimeTypes: List<String>
            get() = when (val p = payload) {
                is LinuxDrag -> p.source.mimeTypes.toList()
                else -> listOf(UriList.MIME)
            }
    }

    private var session: Session? = null

    // ---- a Linux program starts a drag ----

    /** wl_data_device.start_drag: carried by an Android drag from the window it started in. */
    fun start(source: WlDataSource?, origin: WlSurface?, icon: WlSurface?) {
        if (source == null) return // a drag inside the program: it handles it itself
        if (icon != null && icon.role == null) icon.role = SurfaceRole.DndIcon
        val view = origin?.rootXdg()?.toplevel?.view as? WaylandWindowContent
        if (view == null || origin.client.isXwayland || !view.startLinuxDrag(LinuxDrag(server, source, icon, view))) {
            source.event(2) // cancelled
            source.client.flush()
        }
    }

    /** The program drew its drag icon: the drag shadow follows. */
    fun iconCommitted(surface: WlSurface) {
        val drag = session?.linux ?: return
        if (drag.icon === surface) (drag.view as? WaylandWindowContent)?.updateLinuxDragShadow(drag)
    }

    fun sourceGone(source: WlDataSource) {
        val drag = session?.linux ?: return
        if (drag.source === source) drag.view.cancelDragAndDrop()
    }

    /** [drag] was dropped on a HandTop window (Files): no Linux window has it any more. */
    fun droppedOutside(drag: LinuxDrag) {
        val s = session?.takeIf { it.payload === drag } ?: return
        leave(s)
    }

    // ---- a Linux window under the drag ----

    /** Android's drag started: whether a Linux window of [client] takes what it carries. */
    fun accepts(payload: Any?, client: Client): Boolean {
        if (client.isXwayland || server.clipboard.devices.none { it.client === client }) return false
        val takes = when (payload) {
            is LinuxDrag -> payload.source.alive && payload.source.mimeTypes.isNotEmpty()
            is DraggedFiles -> payload.linuxPaths.isNotEmpty()
            else -> false
        }
        if (takes) sessionFor(payload!!)
        return takes
    }

    private fun sessionFor(payload: Any): Session {
        session?.let { if (it.payload === payload) return it else end(it, dropped = false) }
        return Session(payload).also { session = it }
    }

    /** The drag is over [surface] at ([x], [y]) in its coordinates. */
    fun over(payload: Any?, surface: WlSurface, x: Double, y: Double) {
        val s = session?.takeIf { it.payload === payload && !it.ended } ?: return
        if (s.surface !== surface) {
            leave(s)
            enter(s, surface, x, y)
        } else {
            val time = SystemClock.uptimeMillis().toInt()
            devicesOf(surface.client).forEach { it.event(3) { uint(time); fixed(x); fixed(y) } } // motion
            surface.client.flush()
        }
    }

    /** The drag left the Linux window. */
    fun left(payload: Any?) {
        val s = session?.takeIf { it.payload === payload } ?: return
        leave(s)
    }

    /**
     * Dropped on [surface] at ([x], [y]): true when its program takes it (it accepted a type,
     * and an action both sides allow). It then reads the data and says when it is done.
     */
    fun drop(payload: Any?, surface: WlSurface, x: Double, y: Double): Boolean {
        val s = session?.takeIf { it.payload === payload && !it.ended } ?: return false
        over(payload, surface, x, y)
        val offer = s.offers.firstOrNull { it.takesDrop() }
        if (offer == null) {
            leave(s)
            return false
        }
        s.dropped = offer
        // The pointer is back over the window, as on a desktop when the button goes up. Before
        // the drop: a program that asks what to do (PCManFM-Qt: copy, move or link?) opens a
        // menu, and Qt opens menus only for a window that has had input.
        server.seat.pointerMotion(surface, x, y)
        devicesOf(surface.client).forEach { it.event(4) } // drop
        surface.client.flush()
        s.linux?.source?.let { source ->
            if (source.version >= 3 && source.client.connected) {
                source.event(3) // dnd_drop_performed
                source.client.flush()
            }
        }
        return true
    }

    /** Android's drag ended; [dropped]: someone took it. */
    fun ended(payload: Any?, dropped: Boolean) {
        val s = session?.takeIf { it.payload === payload } ?: return
        end(s, dropped)
    }

    private fun end(s: Session, dropped: Boolean) {
        if (s.ended) return
        s.ended = true
        if (s.dropped == null) leave(s)
        if (!dropped) s.linux?.source?.let { source ->
            if (source.client.connected && source.alive) {
                source.event(2) // cancelled
                source.client.flush()
            }
        }
        if (session === s) session = null
    }

    private fun enter(s: Session, surface: WlSurface, x: Double, y: Double) {
        val client = surface.client
        val serial = server.nextSerial()
        s.surface = surface
        for (device in devicesOf(client)) {
            val offer = client.register(WlDndOffer(client, client.nextServerId(), device.version, this, s))
            s.offers += offer
            device.event(0) { uint(offer.id) } // data_offer
            for (mime in s.mimeTypes) offer.event(0) { string(mime) }
            if (offer.version >= 3) offer.event(1) { uint(s.sourceActions) } // source_actions
            device.event(1) { uint(serial); obj(surface.id); fixed(x); fixed(y); obj(offer.id) } // enter
        }
        client.flush()
    }

    private fun leave(s: Session) {
        val surface = s.surface ?: return
        s.surface = null
        s.offers.clear()
        if (surface.client.connected) {
            devicesOf(surface.client).forEach { it.event(2) } // leave
            surface.client.flush()
        }
        s.linux?.source?.let { source -> // no target any more
            if (source.client.connected && source.alive) {
                source.event(0) { string(null) }
                source.client.flush()
            }
        }
    }

    private fun devicesOf(client: Client) = server.clipboard.devices.filter { it.client === client }

    // ---- what the target program says ----

    fun accepted(offer: WlDndOffer, mime: String?) {
        val s = offer.session
        if (offer !in s.offers) return
        s.linux?.source?.let { source ->
            if (source.client.connected && source.alive) {
                source.event(0) { string(mime) } // target
                source.client.flush()
            }
        }
    }

    /** The target's actions changed: tell both sides the action a drop would do now. */
    fun actionsChanged(offer: WlDndOffer) {
        val s = offer.session
        val action = DndActions.choose(s.sourceActions, offer.actions, offer.preferredAction)
        if (action == offer.action) return
        offer.action = action
        if (offer.version >= 3) {
            offer.event(2) { uint(action) }
            offer.client.flush()
        }
        s.linux?.source?.let { source ->
            if (source.version >= 3 && source.client.connected && source.alive) {
                source.event(5) { uint(action) }
                source.client.flush()
            }
        }
    }

    /** The target reads the dragged data as [mime] into [fd]. */
    fun receive(offer: WlDndOffer, mime: String, fd: Int) {
        when (val p = offer.session.payload) {
            is LinuxDrag -> {
                val source = p.source
                if (source.client.connected && source.alive) {
                    source.event(1) { string(mime); fd(fd) } // the pipe goes straight to the program
                    source.client.flush()
                } else {
                    Native.close(fd)
                }
            }
            is DraggedFiles -> {
                if (mime != UriList.MIME) return Native.close(fd)
                val bytes = UriList.encode(p.linuxPaths).toByteArray(Charsets.UTF_8)
                val out = ParcelFileDescriptor.adoptFd(fd)
                Thread({
                    try {
                        ParcelFileDescriptor.AutoCloseOutputStream(out).use { it.write(bytes) }
                    } catch (e: Exception) {
                        Log.w(WaylandServer.TAG, "drag: writing the files failed", e)
                    }
                }, "drag-write").start()
            }
            else -> Native.close(fd)
        }
    }

    /** The target finished with the drop (finish, or its offer went away). */
    fun finished(offer: WlDndOffer) {
        val s = offer.session
        if (s.dropped !== offer) return
        s.dropped = null
        s.linux?.source?.let { source ->
            if (source.version >= 3 && source.client.connected && source.alive) {
                source.event(4) // dnd_finished
                source.client.flush()
            }
        }
    }
}

/** What a program sees of a drag over it; created by the compositor (server-side id). */
class WlDndOffer(
    client: Client, id: Int, version: Int,
    private val dnd: DragAndDrop,
    val session: DragAndDrop.Session,
) : Resource(client, id, version) {
    override val iface = "wl_data_offer"

    var acceptedMime: String? = null
        private set
    /** set_actions (v3); older programs only copy. */
    var actions = if (version >= 3) DndActions.NONE else DndActions.COPY
        private set
    var preferredAction = if (version >= 3) DndActions.NONE else DndActions.COPY
        private set
    /** The action a drop would do, as last told. */
    var action = DndActions.NONE
    private var finished = false

    /** A drop here counts: a type was accepted, and (v3) an action both sides allow. */
    fun takesDrop() = acceptedMime != null &&
        (version < 3 || DndActions.choose(session.sourceActions, actions, preferredAction) != DndActions.NONE)

    override fun dispatch(opcode: Int, r: MessageReader) {
        when (opcode) {
            0 -> { // accept(serial, mime)
                r.uint()
                acceptedMime = r.string()
                dnd.accepted(this, acceptedMime)
            }
            1 -> { // receive(mime, fd)
                val mime = r.string() ?: ""
                dnd.receive(this, mime, r.fd())
            }
            2 -> destroy()
            3 -> { // finish
                finished = true
                dnd.finished(this)
            }
            4 -> { // set_actions(dnd_actions, preferred_action)
                actions = r.uint()
                preferredAction = r.uint()
                dnd.actionsChanged(this)
            }
            else -> super.dispatch(opcode, r)
        }
    }

    override fun destroyed() {
        if (!finished) dnd.finished(this) // older programs just let the offer go
    }
}
