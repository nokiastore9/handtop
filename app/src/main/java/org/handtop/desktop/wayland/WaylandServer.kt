package org.handtop.desktop.wayland

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.system.Os
import android.util.DisplayMetrics
import android.util.Log
import android.view.Choreographer
import org.handtop.desktop.ui.UiScale
import java.io.File

/**
 * HandTop's Wayland compositor: a Unix socket inside the Linux root filesystem
 * (/tmp/.handtop-runtime/wayland-0) and the protocol objects behind it. Linux windows
 * are shown on the desktop through [host].
 */
class WaylandServer private constructor(private val app: Context) {

    /** Receives toplevels that become visible / go away. Set by the desktop activity. */
    interface Host {
        fun toplevelMapped(toplevel: Toplevel)
        fun toplevelUnmapped(toplevel: Toplevel)
    }

    var host: Host? = null

    private val main = Handler(Looper.getMainLooper())
    private val clients = ArrayList<Client>()
    private var serverFd = -1
    private var socketPath: String? = null
    private var serial = 0

    /** HandTop as the window manager of Xwayland, while it runs (see X11Display). */
    @Volatile
    var xwm: Xwm? = null

    val seat = Seat(this)
    val clipboard = Clipboard(this, app)
    val textInputs = TextInputs(this)
    /** XKB keymap text sent to clients, NUL-terminated as libxkbcommon expects. */
    val keymap: ByteArray = app.assets.open("keymap-us.xkb").use { it.readBytes() } + byteArrayOf(0)

    /** Integer output scale: client buffers are this many pixels per logical unit. */
    var scale = 1
        private set
    var outputWidth = 0
        private set
    var outputHeight = 0
        private set
    var outputWidthMm = 0
        private set
    var outputHeightMm = 0
        private set

    init {
        applyDisplay(app.resources.displayMetrics)
    }

    /**
     * The desktop's screen: physical size and the density after the user's interface scale
     * (Settings). Bound wl_outputs get the new geometry / scale, so programs re-render.
     */
    fun updateDisplay(metrics: DisplayMetrics) {
        if (!applyDisplay(metrics)) return
        Log.i(TAG, "output ${outputWidth}x$outputHeight, scale $scale")
        for (client in clients.toList()) {
            client.resources().filterIsInstance<XdgOutput>().forEach { it.sendInfo(false) }
            client.resources().filterIsInstance<WlOutput>().forEach { it.sendInfo() }
            client.flush()
        }
    }

    private fun applyDisplay(dm: DisplayMetrics): Boolean {
        val newScale = UiScale.waylandScale(dm.density)
        if (newScale == scale && dm.widthPixels == outputWidth && dm.heightPixels == outputHeight) return false
        scale = newScale
        outputWidth = dm.widthPixels
        outputHeight = dm.heightPixels
        outputWidthMm = (dm.widthPixels / dm.xdpi * 25.4f).toInt()
        outputHeightMm = (dm.heightPixels / dm.ydpi * 25.4f).toInt()
        return true
    }

    val globals = listOf(
        Global(1, "wl_compositor", 4) { c, id, v -> c.register(WlCompositor(c, id, v)) },
        Global(2, "wl_subcompositor", 1) { c, id, _ -> c.register(WlSubcompositor(c, id)) },
        Global(3, "wl_shm", 1) { c, id, v -> c.register(WlShm(c, id, v)) },
        Global(4, "wl_output", 3) { c, id, v -> c.register(WlOutput(c, id, v)) },
        Global(5, "wl_seat", 5) { c, id, v -> c.register(WlSeat(c, id, v)) },
        Global(6, "wl_data_device_manager", 3) { c, id, v -> c.register(WlDataDeviceManager(c, id, v)) },
        Global(7, "xdg_wm_base", 3) { c, id, v -> c.register(XdgWmBase(c, id, v)) },
        Global(8, "zxdg_decoration_manager_v1", 1) { c, id, _ -> c.register(XdgDecorationManager(c, id)) },
        Global(9, "zwp_text_input_manager_v3", 1) { c, id, _ -> c.register(TextInputManager(c, id)) },
        Global(10, "xwayland_shell_v1", 1) { c, id, _ -> c.register(XwaylandShell(c, id)) },
        Global(11, "zxdg_output_manager_v1", 2) { c, id, v -> c.register(XdgOutputManager(c, id, v)) },
        Global(12, "zwp_idle_inhibit_manager_v1", 1) { c, id, _ -> c.register(IdleInhibitManager(c, id)) },
        Global(13, "wp_viewporter", 1) { c, id, _ -> c.register(WpViewporter(c, id)) },
    )

    fun nextSerial() = ++serial

    // ---- socket ----

    /** Listens on the socket inside [rootfs], (re)creating it if it disappeared. */
    private fun ensureListening(rootfs: File) {
        val dir = File(rootfs, RUNTIME_DIR)
        val socket = File(dir, SOCKET_NAME)
        if (serverFd >= 0 && socket.path == socketPath && socket.exists()) return
        if (serverFd >= 0) Native.shutdown(serverFd) // stops the old accept thread
        dir.mkdirs()
        try {
            Os.chmod(dir.path, 0x1C0) // 0700, as XDG_RUNTIME_DIR must be
        } catch (e: Exception) {
            Log.w(TAG, "chmod $dir", e)
        }
        val fd = Native.listenUnix(socket.path)
        if (fd < 0) {
            Log.e(TAG, "cannot listen on $socket: $fd")
            return
        }
        serverFd = fd
        socketPath = socket.path
        Log.i(TAG, "listening on $socket (scale $scale)")
        Thread({ acceptLoop(fd) }, "wayland-accept").start()
    }

    private fun acceptLoop(fd: Int) {
        while (true) {
            val clientFd = Native.accept(fd)
            if (clientFd < 0) {
                Log.i(TAG, "accept loop ends: $clientFd")
                Native.close(fd)
                return
            }
            main.post {
                val client = Client(this, clientFd, main)
                clients += client
                client.start()
                Log.i(TAG, "client connected (${clients.size})")
            }
        }
    }

    fun clientGone(client: Client) {
        clients.remove(client)
        Log.i(TAG, "client disconnected (${clients.size} left)")
    }

    /** All toplevels currently shown, e.g. to re-create their windows after an activity restart. */
    fun mappedToplevels(): List<Toplevel> =
        clients.flatMap { c -> c.resources().filterIsInstance<XdgToplevel>().filter { it.mapped } } +
            (xwm?.toplevels() ?: emptyList())

    /**
     * The wl_surface an older Xwayland means by WL_SURFACE_ID: object [id] without a role,
     * preferably in a client that is not an xdg-shell program (Xwayland in rootless mode).
     */
    fun findXwaylandSurface(id: Int): WlSurface? =
        clients.sortedBy { c -> c.resources().any { it is XdgWmBase } }
            .firstNotNullOfOrNull { c -> (c.get(id) as? WlSurface)?.takeIf { it.role == null } }

    // ---- frame callbacks ----

    private val frameCallbacks = ArrayList<WlCallback>()
    private var frameScheduled = false

    fun scheduleFrameCallbacks(callbacks: List<WlCallback>) {
        frameCallbacks += callbacks
        if (frameScheduled) return
        frameScheduled = true
        Choreographer.getInstance().postFrameCallback { frameTimeNanos ->
            frameScheduled = false
            val due = frameCallbacks.toList()
            frameCallbacks.clear()
            val time = (frameTimeNanos / 1_000_000).toInt()
            due.forEach { if (it.client.connected) it.done(time) }
            due.map { it.client }.distinct().forEach { it.flush() }
        }
    }

    companion object {
        const val TAG = "HandTopWayland"
        const val DEBUG = false

        /** Paths inside the Linux root filesystem (guest view). */
        const val RUNTIME_DIR = "tmp/.handtop-runtime"
        const val SOCKET_NAME = "wayland-0"

        @Volatile
        private var instance: WaylandServer? = null

        fun get(context: Context): WaylandServer =
            instance ?: synchronized(this) {
                instance ?: WaylandServer(context.applicationContext).also { instance = it }
            }

        /** Makes sure Linux programs started now can reach the compositor. Main thread. */
        fun ensureRunning(context: Context, rootfs: File): WaylandServer =
            get(context).also { it.ensureListening(rootfs) }
    }
}

class Global(val name: Int, val iface: String, val version: Int, val bind: (Client, Int, Int) -> Resource)
