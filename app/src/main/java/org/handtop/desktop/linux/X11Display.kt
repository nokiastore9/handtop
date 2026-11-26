package org.handtop.desktop.linux

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import org.handtop.desktop.wayland.WaylandServer
import org.handtop.desktop.wayland.X11Connection
import org.handtop.desktop.wayland.X11ScaleMode
import org.handtop.desktop.wayland.Xwm
import org.handtop.desktop.wayland.mainAsync
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * X11 programs: a rootless Xwayland inside the distribution (display :0), started with the
 * Linux session when it is installed, and HandTop as its window manager ([Xwm]), so X11
 * windows are desktop windows like Wayland ones.
 */
object X11Display {

    private const val TAG = "HandTopX11"
    const val NUMBER = 0
    const val DISPLAY = ":$NUMBER"
    private const val START_TIMEOUT_MS = 15_000L
    private const val RESTART_DELAY_MS = 1_000L
    private const val RESTART_WINDOW_MS = 60_000L
    private const val MAX_RESTARTS = 3

    // Asynchronous like the Wayland server's: X11 replies must not wait for a screen refresh.
    private val main: Handler = mainAsync()
    private var process: Process? = null

    /** Xwayland and the keyboard layouts it cannot start without (Alpine installs them apart). */
    fun isInstalled(context: Context): Boolean {
        val rootfs = LinuxEnv.rootfs(context)
        return File(rootfs, "usr/bin/Xwayland").exists() && File(rootfs, "usr/share/X11/xkb/rules/evdev").exists()
    }

    val isRunning get() = process?.isAlive == true

    private const val PREFS = "x11"
    private const val KEY_SCALE = "scale"

    /** How X11 programs are scaled (Settings → Graphics and sound); sharp unless chosen otherwise. */
    fun mode(context: Context): X11ScaleMode = X11ScaleMode.fromKey(prefs(context).getString(KEY_SCALE, null))

    /** Changes how X11 programs are scaled: Xwayland starts again, its programs close. Main thread. */
    fun setMode(context: Context, mode: X11ScaleMode) {
        if (mode == mode(context)) return
        prefs(context).edit().putString(KEY_SCALE, mode.key).apply()
        restart(context)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Starts Xwayland again (its X11 programs close), e.g. for a new scale mode. Main thread. */
    fun restart(context: Context) {
        val app = context.applicationContext
        val running = process ?: return ensureRunning(app)
        process = null // not an unexpected exit: the exit watcher leaves the restart to us
        Thread({
            // proot ignores destroy(): the X server itself is killed, and its proot exits with it.
            LinuxEnv.killLinuxProgram(app, "Xwayland")
            running.waitFor(5, TimeUnit.SECONDS)
            main.post { ensureRunning(app) }
        }, "xwayland-restart").start()
    }

    /** The window manager is connected: X11 programs can start now. */
    fun isReady(context: Context) = WaylandServer.get(context).xwm?.ready == true

    /** Starts Xwayland and the window manager once (no-op when running or not installed). Main thread. */
    fun ensureRunning(context: Context) {
        if (isRunning || !isInstalled(context)) return
        val app = context.applicationContext
        val rootfs = LinuxEnv.rootfs(app)
        // A lock or socket left by an Xwayland that died with HandTop would block the display
        // (and the window manager must not connect to a stale socket).
        val socket = File(rootfs, "tmp/.X11-unix/X$NUMBER")
        socket.delete()
        File(rootfs, "tmp/.X$NUMBER-lock").delete()
        // How X11 programs are scaled decides the X screen Xwayland is shown (see X11Scaling).
        WaylandServer.get(app).x11Mode = mode(app)
        // On a socket of its own, so the Wayland server knows which client is Xwayland.
        val spec = LinuxEnv.commandSpec(
            app,
            "exec env WAYLAND_DISPLAY=${WaylandServer.X11_SOCKET_NAME} Xwayland $DISPLAY -rootless -shm -noreset -nolisten tcp -nolisten local",
        )
        val log = File(rootfs, "tmp/handtop-xwayland.log")
        // Keep the previous run's output: after a crash it holds the backtrace.
        if (log.exists()) log.renameTo(File(rootfs, "tmp/handtop-xwayland.log.old"))
        val started = try {
            ProcessBuilder(listOf(spec.executable) + spec.args.drop(1))
                .directory(File(spec.cwd))
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(log))
                .apply { environment().apply { clear(); putAll(spec.env) } }
                .start()
        } catch (e: IOException) {
            Log.w(TAG, "cannot start Xwayland", e)
            return
        }
        process = started
        Log.i(TAG, "Xwayland started")
        val server = WaylandServer.get(app)
        Thread({
            val deadline = SystemClock.uptimeMillis() + START_TIMEOUT_MS
            var connection: X11Connection? = null
            while (connection == null && started.isAlive && SystemClock.uptimeMillis() < deadline) {
                if (socket.exists()) {
                    try {
                        connection = X11Connection.connect(socket.path, main)
                    } catch (e: IOException) {
                        Log.d(TAG, "not yet: ${e.message}") // listening, but not accepting yet
                    }
                }
                if (connection == null) Thread.sleep(50)
            }
            if (connection == null) {
                Log.w(TAG, "window manager cannot connect to Xwayland")
                return@Thread
            }
            main.post {
                val xwm = Xwm(server, connection)
                xwm.onClosed = { if (server.xwm === xwm) server.xwm = null }
                server.xwm = xwm
                xwm.start()
            }
        }, "xwayland-connect").start()
        Thread({
            started.waitFor()
            Log.i(TAG, "Xwayland exited (${started.exitValue()})")
            main.post {
                if (process !== started) return@post
                process = null
                restartAfterExit(app)
            }
        }, "xwayland-wait").start()
    }

    /**
     * Stops Xwayland on purpose (no restart): before the distribution it runs from is
     * replaced or removed. The next Linux session starts the new system's own. Main thread.
     */
    fun stop() {
        val running = process ?: return
        process = null // the exit watcher sees it is no longer ours and does not restart it
        running.destroy() // proot ignores it: LinuxEnv.stopServices kills the Linux processes
        Log.i(TAG, "Xwayland stopped")
    }

    /** When Xwayland exited unexpectedly lately, to stop restarting one that cannot run. */
    private val exits = ArrayDeque<Long>()

    /**
     * Xwayland is meant to run as long as HandTop does. If it crashed or was killed, X11
     * programs would not start again until a new terminal session: start it again, unless it
     * keeps exiting. (Seen once: SIGSYS in accept() under proot, not reproduced.)
     */
    private fun restartAfterExit(app: Context) {
        val now = SystemClock.uptimeMillis()
        while (exits.isNotEmpty() && now - exits.first() > RESTART_WINDOW_MS) exits.removeFirst()
        exits.addLast(now)
        if (exits.size > MAX_RESTARTS) {
            Log.w(TAG, "Xwayland keeps exiting, not restarting it (see /tmp/handtop-xwayland.log)")
            return
        }
        Log.i(TAG, "restarting Xwayland")
        main.postDelayed({ ensureRunning(app) }, RESTART_DELAY_MS)
    }

    /** Waits (off the main thread) until X11 programs can start, at most [timeoutMs]. */
    fun awaitReady(context: Context, timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (!isReady(context)) {
            if (SystemClock.uptimeMillis() > deadline) return false
            Thread.sleep(50)
        }
        return true
    }
}
