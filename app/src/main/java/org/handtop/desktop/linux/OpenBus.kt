package org.handtop.desktop.linux

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.handtop.desktop.files.AndroidOpen
import org.handtop.desktop.files.FileKinds
import org.handtop.desktop.files.FilePlaces
import org.handtop.desktop.wayland.WaylandServer
import java.io.IOException

/**
 * Linux programs open files and links in Android apps through HandTop: org.handtop.Desktop on
 * the session bus, method Open(s), called by handtop-open ([LinuxOpen]). A file goes to the
 * Android app the user picks, a link to the app Android has for it (browser, mail, phone…).
 */
object OpenBus {

    const val NAME = "org.handtop.Desktop"
    const val PATH = "/org/handtop/Desktop"
    private const val TAG = "HandTopOpen"
    private const val RETRY_MS = 2_000L

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var thread: Thread? = null

    fun ensureRunning(context: Context) {
        if (thread?.isAlive == true) return
        val app = context.applicationContext
        thread = Thread({ loop(app) }, "handtop-open").apply {
            isDaemon = true
            start()
        }
    }

    private fun loop(context: Context) {
        while (true) {
            if (!SessionBus.awaitReady(context, RETRY_MS)) continue
            val bus = try {
                DBusConnection.connect(SessionBus.socketPath(context))
            } catch (e: IOException) {
                Thread.sleep(RETRY_MS)
                continue
            }
            try {
                serve(context, bus)
            } catch (e: IOException) {
                Log.i(TAG, "session bus gone: ${e.message}")
            } finally {
                bus.close()
            }
            Thread.sleep(RETRY_MS)
        }
    }

    private fun serve(context: Context, bus: DBusConnection) {
        bus.call("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "Hello")
        bus.call("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "RequestName", "su", NAME, 4)
        Log.i(TAG, "serving $NAME on the session bus")
        while (true) {
            val m = bus.read()
            if (m.type != DBusMessage.METHOD_CALL) continue
            when (m.member) {
                "Open" -> {
                    val problem = open(context, m.body.getOrNull(0) as? String ?: "")
                    if (problem == null) bus.reply(m) else bus.error(m, "org.handtop.Error.CannotOpen", problem)
                }
                "Ping" -> bus.reply(m)
                "Introspect" -> bus.reply(m, "s", INTROSPECTION)
                else -> bus.error(m, "org.freedesktop.DBus.Error.UnknownMethod", "HandTop: no method ${m.member}")
            }
        }
    }

    /** Opens [text] (a Linux path or a link) on the phone; what went wrong, or null. */
    private fun open(context: Context, text: String): String? {
        val target = LinuxPaths.target(text) ?: return "not an absolute path or a link: $text"
        // Started from the desktop when it is there: on its display (DeX or the phone).
        val starter: Context = WaylandServer.get(context).host as? Activity ?: context
        when (target) {
            is LinuxPaths.Target.Link -> main.post {
                if (!AndroidOpen.link(starter, target.uri)) Log.w(TAG, "no Android app for ${target.uri}")
            }
            is LinuxPaths.Target.Path -> {
                val file = LinuxPaths.toAndroid(target.path, LinuxEnv.rootfs(context), FilePlaces.phoneRoot(), LinuxEnv.hasStorageAccess())
                    ?: return "HandTop cannot see ${target.path} (the phone's storage needs \"All files access\")"
                val real = FilePlaces.resolve(context, file)
                if (!real.isFile) return "no such file: ${target.path}"
                val mime = FileKinds.mimeOf(real, isDir = false)
                main.post {
                    if (!AndroidOpen.file(starter, real, mime, real.name)) Log.w(TAG, "no Android app for ${target.path}")
                }
            }
        }
        return null
    }

    private val INTROSPECTION = """
        <!DOCTYPE node PUBLIC "-//freedesktop//DTD D-BUS Object Introspection 1.0//EN"
         "http://www.freedesktop.org/standards/dbus/1.0/introspect.dtd">
        <node>
          <interface name="$NAME">
            <method name="Open"><arg name="uri" type="s" direction="in"/></method>
          </interface>
          <interface name="org.freedesktop.DBus.Introspectable">
            <method name="Introspect"><arg name="xml" type="s" direction="out"/></method>
          </interface>
        </node>
    """.trimIndent()
}
