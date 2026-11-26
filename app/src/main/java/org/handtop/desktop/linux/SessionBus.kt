package org.handtop.desktop.linux

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.handtop.desktop.wayland.WaylandServer
import java.io.File
import java.io.IOException

/**
 * The D-Bus session bus of the Linux session: desktop programs keep their settings through it
 * (Xfconf, dconf), show notifications and start helper services on demand. Without it, e.g.
 * Xfce programs fail ("Failed to execute child process dbus-launch").
 *
 * The bus listens on the standard `$XDG_RUNTIME_DIR/bus`. proot fakes uid 0 but the kernel
 * reports the app's real uid on the socket, so the usual EXTERNAL authentication cannot
 * succeed: the bus also accepts ANONYMOUS. The socket is inside the app's private data, in a
 * 0700 directory, so only HandTop's own processes can reach it.
 */
object SessionBus {

    private const val TAG = "HandTopDBus"
    private const val SOCKET = "bus"
    private const val CONFIG = "etc/handtop/dbus-session.conf"

    private var process: Process? = null

    fun isAvailable(context: Context) = File(LinuxEnv.rootfs(context), "usr/bin/dbus-daemon").exists()

    val isRunning get() = process?.isAlive == true

    /** DBUS_SESSION_BUS_ADDRESS inside Linux. */
    const val ADDRESS = "unix:path=/${WaylandServer.RUNTIME_DIR}/$SOCKET"

    private fun socket(context: Context) = File(LinuxEnv.rootfs(context), "${WaylandServer.RUNTIME_DIR}/$SOCKET")

    /** The bus socket on Android's side, for HandTop's own services (see InhibitBus). */
    fun socketPath(context: Context): String = socket(context).path

    /** Stops the bus: before the distribution it runs from is replaced or removed. */
    fun stop() {
        process?.destroy() // proot ignores it: LinuxEnv.stopServices kills the Linux processes
        process = null
    }

    /** Starts the bus once (no-op when running or D-Bus is not installed). Main thread. */
    fun ensureRunning(context: Context) {
        if (isRunning || !isAvailable(context)) return
        val app = context.applicationContext
        val rootfs = LinuxEnv.rootfs(app)
        writeConfig(rootfs)
        socket(app).delete() // left by a bus that died with HandTop
        val spec = LinuxEnv.commandSpec(app, "exec dbus-daemon --nofork --nopidfile --config-file=/$CONFIG")
        process = try {
            ProcessBuilder(listOf(spec.executable) + spec.args.drop(1))
                .directory(File(spec.cwd))
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(File(rootfs, "tmp/handtop-dbus.log")))
                .apply { environment().apply { clear(); putAll(spec.env) } }
                .start()
        } catch (e: IOException) {
            Log.w(TAG, "cannot start dbus-daemon", e)
            null
        }
        if (process != null) Log.i(TAG, "session bus started")
        InhibitBus.ensureRunning(app)
        OpenBus.ensureRunning(app)
    }

    /** Waits (off the main thread) until programs can connect to the bus, at most [timeoutMs]. */
    fun awaitReady(context: Context, timeoutMs: Long): Boolean {
        val socket = socket(context)
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (!socket.exists()) {
            if (SystemClock.uptimeMillis() > deadline) return false
            Thread.sleep(25)
        }
        return true
    }

    private fun writeConfig(rootfs: File) {
        File(rootfs, CONFIG).apply {
            parentFile?.mkdirs()
            writeText(
                """
                <!-- Written by HandTop: the Linux session's D-Bus session bus. -->
                <!DOCTYPE busconfig PUBLIC "-//freedesktop//DTD D-Bus Bus Configuration 1.0//EN"
                 "http://www.freedesktop.org/standards/dbus/1.0/busconfig.dtd">
                <busconfig>
                  <type>session</type>
                  <keep_umask/>
                  <listen>$ADDRESS</listen>
                  <auth>EXTERNAL</auth>
                  <auth>ANONYMOUS</auth>
                  <allow_anonymous/>
                  <standard_session_servicedirs/>
                  <policy context="default">
                    <allow send_destination="*" eavesdrop="true"/>
                    <allow eavesdrop="true"/>
                    <allow own="*"/>
                  </policy>
                  <include ignore_missing="yes">/etc/dbus-1/session-local.conf</include>
                </busconfig>
                """.trimIndent() + "\n",
            )
        }
    }
}
