package org.handtop.desktop.wayland

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * The Wayland clipboard (selection), bridged with Android's.
 *
 * - Linux → Android: when a program sets the selection, its text is read at once through a
 *   pipe and put on Android's clipboard.
 * - Android → Linux: Linux programs are offered Android's clipboard as text; it is read only
 *   when a program actually pastes (Android 12+ shows a notice on every read).
 * - Linux → Linux: the pasting program's pipe is handed straight to the copying program.
 *
 * Main thread only, except the pipe reads and writes, which run on worker threads.
 */
class Clipboard(private val server: WaylandServer, private val context: Context) {

    private val android = context.getSystemService(ClipboardManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    val devices = ArrayList<WlDataDevice>()

    /** What the selection currently is. */
    sealed interface Selection {
        class Linux(val source: WlDataSource) : Selection
        object Android : Selection
    }

    private var selection: Selection? = null
    /** Timestamp of the Android clip we last saw, to notice new ones without reading them. */
    private var seenAndroidClip = 0L

    private val androidListener = ClipboardManager.OnPrimaryClipChangedListener { syncFromAndroid() }

    init {
        android?.addPrimaryClipChangedListener(androidListener)
        syncFromAndroid()
    }

    // ---- Linux → everyone ----

    fun setLinuxSelection(source: WlDataSource?) {
        val old = (selection as? Selection.Linux)?.source
        if (old === source) return
        if (old != null && old.client.connected) {
            old.event(2) // cancelled
            old.client.flush()
        }
        selection = source?.let { Selection.Linux(it) }
        source?.let { copyToAndroid(it) }
        offerToFocusedClient()
    }

    /** A source died: the selection is empty until someone copies again. */
    fun sourceGone(source: WlDataSource) {
        if ((selection as? Selection.Linux)?.source !== source) return
        selection = null
        offerToFocusedClient()
    }

    private fun copyToAndroid(source: WlDataSource) {
        val mime = TEXT_MIMES.firstOrNull { it in source.mimeTypes } ?: return
        val pipe = ParcelFileDescriptor.createPipe()
        source.event(1) { string(mime); fd(pipe[1].detachFd()) } // send(mime, fd): the fd is closed after sending
        source.client.flush()
        Thread({
            val text = try {
                ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).use { input ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    while (out.size() < MAX_BYTES) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                    out.toString(Charsets.UTF_8.name())
                }
            } catch (e: Exception) {
                Log.w(WaylandServer.TAG, "clipboard: reading from Linux failed", e)
                null
            }
            if (text != null) main.post { putOnAndroid(text) }
        }, "clipboard-read").start()
    }

    private fun putOnAndroid(text: String) {
        val manager = android ?: return
        try {
            manager.setPrimaryClip(ClipData.newPlainText(LABEL, text))
            seenAndroidClip = manager.primaryClipDescription?.timestamp ?: seenAndroidClip
        } catch (e: RuntimeException) {
            Log.w(WaylandServer.TAG, "clipboard: Android refused the clip", e)
        }
    }

    // ---- Android → Linux ----

    /**
     * Android's clipboard changed (or HandTop came back to the front): a new text clip from
     * another app becomes the selection. Only its description is looked at here.
     */
    fun syncFromAndroid() {
        val description = try {
            android?.primaryClipDescription
        } catch (_: RuntimeException) {
            null
        } ?: return
        if (description.timestamp == seenAndroidClip) return
        seenAndroidClip = description.timestamp
        if (description.label?.toString() == LABEL) return // our own copy of a Linux selection
        if (!description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) &&
            !description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
        ) return
        (selection as? Selection.Linux)?.source?.let { old ->
            if (old.client.connected) {
                old.event(2) // cancelled
                old.client.flush()
            }
        }
        selection = Selection.Android
        offerToFocusedClient()
    }

    // ---- offers ----

    private fun offerToFocusedClient() {
        server.seat.keyboardFocus?.client?.let { sendSelection(it) }
    }

    /** Tells [client]'s data devices what the selection is (before its keyboard focus). */
    fun sendSelection(client: Client) {
        if (!client.connected) return
        val current = selection
        val mimes = when (current) {
            is Selection.Linux -> current.source.mimeTypes.toList()
            Selection.Android -> TEXT_MIMES
            null -> emptyList()
        }
        for (device in devices.filter { it.client === client }) {
            if (current == null) {
                device.event(5) { obj(0) } // selection: empty
                continue
            }
            val offer = client.register(WlDataOffer(client, client.nextServerId(), device.version, current))
            device.event(0) { uint(offer.id) } // data_offer(new id)
            for (mime in mimes) offer.event(0) { string(mime) }
            device.event(5) { obj(offer.id) }
        }
        client.flush()
    }

    /** A program pastes: deliver [mime] of the offered [selection] into [fd]. */
    fun receive(selection: Selection, mime: String, fd: Int) {
        when (selection) {
            is Selection.Linux -> {
                val source = selection.source
                if (source.client.connected && source.alive) {
                    source.event(1) { string(mime); fd(fd) } // the pipe goes straight to the copier
                    source.client.flush()
                } else {
                    Native.close(fd)
                }
            }
            Selection.Android -> {
                val text = try {
                    android?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                } catch (_: RuntimeException) {
                    null
                }
                val out = ParcelFileDescriptor.adoptFd(fd)
                Thread({
                    try {
                        ParcelFileDescriptor.AutoCloseOutputStream(out).use { it.write((text ?: "").toByteArray()) }
                    } catch (e: Exception) {
                        Log.w(WaylandServer.TAG, "clipboard: writing to Linux failed", e)
                    }
                }, "clipboard-write").start()
            }
        }
    }

    companion object {
        /** Clip label marking HandTop's copies of Linux selections. */
        private const val LABEL = "Linux (HandTop)"
        private const val MAX_BYTES = 8 * 1024 * 1024

        /** Text types, most preferred first. */
        val TEXT_MIMES = listOf("text/plain;charset=utf-8", "UTF8_STRING", "text/plain", "STRING", "TEXT")
    }
}

// ---- protocol objects ----

class WlDataDeviceManager(client: Client, id: Int, version: Int) : Resource(client, id, version) {
    override val iface = "wl_data_device_manager"

    override fun dispatch(opcode: Int, r: MessageReader) {
        when (opcode) {
            0 -> client.register(WlDataSource(client, r.newId(), version)) // create_data_source
            1 -> { // get_data_device(id, seat)
                val newId = r.newId()
                r.obj()
                val device = client.register(WlDataDevice(client, newId, version))
                client.server.clipboard.devices += device
            }
            else -> super.dispatch(opcode, r)
        }
    }
}

class WlDataSource(client: Client, id: Int, version: Int) : Resource(client, id, version) {
    override val iface = "wl_data_source"

    val mimeTypes = LinkedHashSet<String>()
    var alive = true
        private set

    override fun dispatch(opcode: Int, r: MessageReader) {
        when (opcode) {
            0 -> r.string()?.let { mimeTypes += it } // offer
            1 -> destroy()
            2 -> r.uint() // set_actions (drag & drop)
            else -> super.dispatch(opcode, r)
        }
    }

    override fun destroyed() {
        alive = false
        client.server.clipboard.sourceGone(this)
    }
}

class WlDataDevice(client: Client, id: Int, version: Int) : Resource(client, id, version) {
    override val iface = "wl_data_device"

    override fun dispatch(opcode: Int, r: MessageReader) {
        when (opcode) {
            0 -> { // start_drag(source, origin, icon, serial): no drag & drop yet
                val source = client.optional<WlDataSource>(r.obj())
                r.obj(); r.obj(); r.uint()
                source?.event(2) // cancelled
            }
            1 -> { // set_selection(source, serial)
                val source = client.optional<WlDataSource>(r.obj())
                r.uint()
                client.server.clipboard.setLinuxSelection(source)
            }
            2 -> destroy() // release
            else -> super.dispatch(opcode, r)
        }
    }

    override fun destroyed() {
        client.server.clipboard.devices -= this
    }
}

/** What a program sees of the selection; created by the compositor (server-side id). */
class WlDataOffer(
    client: Client, id: Int, version: Int,
    private val selection: Clipboard.Selection,
) : Resource(client, id, version) {
    override val iface = "wl_data_offer"

    override fun dispatch(opcode: Int, r: MessageReader) {
        when (opcode) {
            0 -> { r.uint(); r.string() } // accept (drag & drop)
            1 -> { // receive(mime, fd)
                val mime = r.string() ?: ""
                val fd = r.fd()
                client.server.clipboard.receive(selection, mime, fd)
            }
            2 -> destroy()
            3 -> Unit // finish
            4 -> { r.uint(); r.uint() } // set_actions
            else -> super.dispatch(opcode, r)
        }
    }
}
