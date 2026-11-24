package org.handtop.desktop.linux

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.handtop.desktop.wayland.ScreenKeeper
import java.io.IOException

/**
 * "Don't turn the screen off" requests from Linux programs over D-Bus, served by HandTop on
 * the session bus: org.freedesktop.ScreenSaver (VLC, Chromium, Firefox…),
 * org.freedesktop.PowerManagement.Inhibit (Parole…) and org.gnome.SessionManager (GTK's
 * gtk_application_inhibit). While one is held, HandTop keeps the screen on ([ScreenKeeper]).
 * A program that quits or crashes without releasing loses its requests.
 */
object InhibitBus {

    private const val TAG = "HandTopInhibit"
    private const val SCREENSAVER = "org.freedesktop.ScreenSaver"
    private const val POWER = "org.freedesktop.PowerManagement"
    private const val POWER_INHIBIT = "org.freedesktop.PowerManagement.Inhibit"
    private const val GNOME_SESSION = "org.gnome.SessionManager"
    private const val GNOME_INHIBIT_IDLE = 8
    private const val RETRY_MS = 2_000L

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var thread: Thread? = null

    /** cookie → unique bus name of the program that holds it (reader thread only). */
    private val cookies = HashMap<Int, String>()
    private var nextCookie = 1

    /** Serves the bus while it runs; reconnects when the bus is restarted. */
    fun ensureRunning(context: Context) {
        if (thread?.isAlive == true) return
        val app = context.applicationContext
        thread = Thread({ loop(app) }, "handtop-inhibit").apply {
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
                serve(bus)
            } catch (e: IOException) {
                Log.i(TAG, "session bus gone: ${e.message}")
            } finally {
                bus.close()
                releaseAll()
            }
            Thread.sleep(RETRY_MS)
        }
    }

    private fun serve(bus: DBusConnection) {
        bus.call("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "Hello")
        for (name in listOf(SCREENSAVER, POWER, GNOME_SESSION)) {
            // Flags 4: do not queue — another owner (a real desktop session) keeps its name.
            bus.call("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "RequestName", "su", name, 4)
        }
        bus.call(
            "org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "AddMatch", "s",
            "type='signal',sender='org.freedesktop.DBus',member='NameOwnerChanged'",
        )
        Log.i(TAG, "serving screen saver inhibition on the session bus")
        while (true) {
            val m = bus.read()
            when (m.type) {
                DBusMessage.METHOD_CALL -> handle(bus, m)
                DBusMessage.SIGNAL -> if (m.member == "NameOwnerChanged" && m.body.size >= 3 && m.body[2] == "") {
                    programGone(m.body[0] as String)
                }
            }
        }
    }

    private fun handle(bus: DBusConnection, m: DBusMessage) {
        val sender = m.sender ?: return
        when (m.member) {
            "Inhibit" -> {
                // ScreenSaver / PowerManagement: (app, reason). GNOME: (app, xid, reason, flags).
                val gnome = m.iface == GNOME_SESSION || m.signature == "susu"
                if (gnome && ((m.body.getOrNull(3) as? Int ?: 0) and GNOME_INHIBIT_IDLE) == 0) {
                    bus.reply(m, "u", nextCookie++) // logout / suspend only: nothing to keep on
                    return
                }
                val cookie = nextCookie++
                cookies[cookie] = sender
                val app = m.body.getOrNull(0) as? String ?: "?"
                main.post { ScreenKeeper.hold(holder(cookie, app)) }
                bus.reply(m, "u", cookie)
            }
            "UnInhibit", "Uninhibit" -> {
                val cookie = m.body.getOrNull(0) as? Int
                if (cookie != null && cookies.remove(cookie) != null) release(cookie)
                bus.reply(m)
            }
            "HasInhibit" -> bus.reply(m, "b", cookies.isNotEmpty())
            "IsInhibited" -> bus.reply(m, "b", cookies.isNotEmpty())
            "GetActive" -> bus.reply(m, "b", false)
            "GetActiveTime", "GetSessionIdleTime" -> bus.reply(m, "u", 0)
            "SimulateUserActivity" -> bus.reply(m)
            "Ping" -> bus.reply(m)
            "Introspect" -> bus.reply(m, "s", introspection(m.path))
            else -> bus.error(m, "org.freedesktop.DBus.Error.UnknownMethod", "HandTop: no method ${m.member}")
        }
    }

    private fun holder(cookie: Int, app: String) = Holder(cookie, app)

    /** A D-Bus inhibition as a [ScreenKeeper] holder (equal by cookie). */
    private data class Holder(val cookie: Int, val app: String) {
        override fun equals(other: Any?) = other is Holder && other.cookie == cookie
        override fun hashCode() = cookie
        override fun toString() = "D-Bus inhibit #$cookie ($app)"
    }

    private fun release(cookie: Int) = main.post { ScreenKeeper.release(Holder(cookie, "")) }

    private fun programGone(name: String) {
        val gone = cookies.filterValues { it == name }.keys
        for (cookie in gone) {
            cookies.remove(cookie)
            release(cookie)
        }
    }

    private fun releaseAll() {
        for (cookie in cookies.keys.toList()) release(cookie)
        cookies.clear()
    }

    private fun introspection(path: String?): String {
        val iface = when (path) {
            "/org/freedesktop/PowerManagement/Inhibit" -> """
                <interface name="$POWER_INHIBIT">
                  <method name="Inhibit"><arg type="s" direction="in"/><arg type="s" direction="in"/><arg type="u" direction="out"/></method>
                  <method name="UnInhibit"><arg type="u" direction="in"/></method>
                  <method name="HasInhibit"><arg type="b" direction="out"/></method>
                </interface>"""
            "/org/gnome/SessionManager" -> """
                <interface name="$GNOME_SESSION">
                  <method name="Inhibit"><arg type="s" direction="in"/><arg type="u" direction="in"/><arg type="s" direction="in"/><arg type="u" direction="in"/><arg type="u" direction="out"/></method>
                  <method name="Uninhibit"><arg type="u" direction="in"/></method>
                  <method name="IsInhibited"><arg type="u" direction="in"/><arg type="b" direction="out"/></method>
                </interface>"""
            else -> """
                <interface name="$SCREENSAVER">
                  <method name="Inhibit"><arg type="s" direction="in"/><arg type="s" direction="in"/><arg type="u" direction="out"/></method>
                  <method name="UnInhibit"><arg type="u" direction="in"/></method>
                  <method name="SimulateUserActivity"/>
                  <method name="GetActive"><arg type="b" direction="out"/></method>
                </interface>"""
        }
        return "<node>$iface\n</node>"
    }
}
