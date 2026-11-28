package org.handtop.desktop.shell

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.CalendarView
import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextClock
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import org.handtop.desktop.R
import org.handtop.desktop.notify.NotificationCenter
import org.handtop.desktop.notify.Notifications
import org.handtop.desktop.ui.Palette
import org.handtop.desktop.ui.setBarIcon
import org.handtop.desktop.ui.applyTheme
import org.handtop.desktop.ui.Themes
import org.handtop.desktop.ui.Popups
import org.handtop.desktop.ui.dp
import org.handtop.desktop.ui.dpi
import org.handtop.desktop.ui.hideSystemBarsOfWindow
import org.handtop.desktop.ui.roundedRect
import org.handtop.desktop.ui.selectableBackground
import java.util.Locale

/**
 * Right part of the taskbar: what Android's status bar would show, since HandTop runs in
 * exclusive full screen — VPN, hotspot, Wi-Fi / Ethernet, mobile signal, airplane mode,
 * battery, time and date. Tapping a group opens the matching Android settings, the clock a calendar.
 * Needs no runtime permissions (ACCESS_NETWORK_STATE is a normal one).
 */
@SuppressLint("ViewConstructor")
class StatusArea(context: Context) : LinearLayout(context) {

    private val main = Handler(Looper.getMainLooper())
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val telephony = context.getSystemService(TelephonyManager::class.java)
    private val hasTelephony = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

    private val vpn = label("VPN", 10f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) }
    private val airplane = label("✈︎", 15f)
    private val hotspot = SignalIcon(context, SignalIcon.Kind.HOTSPOT)
    private val ethernet = SignalIcon(context, SignalIcon.Kind.ETHERNET)
    private val wifi = SignalIcon(context, SignalIcon.Kind.WIFI)
    private val cell = SignalIcon(context, SignalIcon.Kind.CELL)
    private val dataType = label("", 10f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) }
    private val batteryIcon = BatteryIcon(context)
    private val batteryText = label("", 13f)
    private val networkGroup: LinearLayout
    private val batteryGroup: LinearLayout

    // Notifications: icons of the latest ones, a bell with the count; opens the center.
    private val notificationCenter = (context as? Activity)?.let { NotificationCenter(it) }
    private val notifyIcons = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val bell = ImageView(context).apply { setBarIcon(R.drawable.ic_bell, Palette.TASKBAR_TEXT) }
    private val badge = TextView(context)
    private val notifyGroup: LinearLayout
    private val notificationsListener: () -> Unit = { refreshNotifications() }

    // Network state, updated from ConnectivityManager callbacks on the main thread.
    private var defaultCaps: NetworkCapabilities? = null
    /** Every Wi-Fi network: the station connection and, on Android 15+, our own hotspot. */
    private val wifiNetworks = HashMap<Network, NetworkCapabilities>()
    /** "5G", "LTE"…: from TelephonyDisplayInfo (Android 12+, no permission needed). */
    private var dataLabel = ""
    private var telephonyCallback: Any? = null
    private var registered = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        notifyGroup = group(R.string.notifications_title) { notificationCenter?.toggle(it) }
        notifyGroup.addView(notifyIcons, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        val bellBox = FrameLayout(context)
        bellBox.addView(bell, FrameLayout.LayoutParams(dpi(20), dpi(20), Gravity.CENTER))
        badge.apply {
            textSize = 9f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            minWidth = dpi(14)
            setPadding(dpi(3), 0, dpi(3), 0)
            includeFontPadding = false
            background = roundedRect(Palette.ACCENT, dp(7f))
        }
        bellBox.addView(badge, FrameLayout.LayoutParams(WRAP_CONTENT, dpi(14), Gravity.TOP or Gravity.END))
        notifyGroup.addView(bellBox, LayoutParams(dpi(30), dpi(30)).apply { marginStart = dpi(2) })
        addView(notifyGroup, LayoutParams(WRAP_CONTENT, LayoutParams.MATCH_PARENT))

        networkGroup = group(R.string.status_network) { openSettings(networkSettings()) }
        networkGroup.addView(vpn, iconParams(WRAP_CONTENT))
        networkGroup.addView(airplane, iconParams(WRAP_CONTENT))
        networkGroup.addView(hotspot, iconParams(dpi(20)))
        networkGroup.addView(ethernet, iconParams(dpi(20)))
        networkGroup.addView(wifi, iconParams(dpi(20)))
        networkGroup.addView(dataType, iconParams(WRAP_CONTENT).apply { marginEnd = 0 })
        networkGroup.addView(cell, iconParams(dpi(18)))
        addView(networkGroup, LayoutParams(WRAP_CONTENT, LayoutParams.MATCH_PARENT))

        batteryGroup = group(R.string.status_battery_settings) { openSettings(Intent(Intent.ACTION_POWER_USAGE_SUMMARY)) }
        batteryGroup.addView(batteryIcon, iconParams(dpi(26)))
        batteryGroup.addView(batteryText, iconParams(WRAP_CONTENT))
        batteryGroup.visibility = GONE
        addView(batteryGroup, LayoutParams(WRAP_CONTENT, LayoutParams.MATCH_PARENT))

        addView(clock(), LayoutParams(WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    // ---- lifecycle: listen only while on screen ----

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        // ACTION_BATTERY_CHANGED is sticky: the current state comes back right away.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            ?.let { onBattery(it) }
        if (connectivity != null && !registered) {
            try {
                connectivity.registerDefaultNetworkCallback(defaultCallback, main)
                connectivity.registerNetworkCallback(
                    NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                    wifiCallback, main,
                )
                registered = true
            } catch (e: RuntimeException) {
                // SecurityException / too many callbacks: show what we can without them.
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hasTelephony) listenDisplayInfo()
        Notifications.addListener(notificationsListener)
        Notifications.ensureConnected(context)
        refreshNotifications()
        main.post(poll)
        refreshNetwork()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun listenDisplayInfo() {
        if (telephonyCallback != null) return
        val callback = object : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
            override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
                dataLabel = dataLabelFor(info)
                refreshNetwork()
            }
        }
        try {
            telephony?.registerTelephonyCallback(context.mainExecutor, callback)
            telephonyCallback = callback
        } catch (_: RuntimeException) {
            // Some builds still want READ_PHONE_STATE: then simply no "5G" label.
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun dataLabelFor(info: TelephonyDisplayInfo): String = when (info.overrideNetworkType) {
        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> "5G+"
        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA -> "5G"
        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA,
        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO -> "4G+"
        else -> when (info.networkType) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
            TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
            TelephonyManager.NETWORK_TYPE_EDGE -> "E"
            TelephonyManager.NETWORK_TYPE_GPRS -> "G"
            else -> ""
        }
    }

    override fun onDetachedFromWindow() {
        main.removeCallbacks(poll)
        Notifications.removeListener(notificationsListener)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (telephonyCallback as? TelephonyCallback)?.let {
                try { telephony?.unregisterTelephonyCallback(it) } catch (_: RuntimeException) {}
            }
            telephonyCallback = null
        }
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
        }
        if (registered && connectivity != null) {
            try {
                connectivity.unregisterNetworkCallback(defaultCallback)
                connectivity.unregisterNetworkCallback(wifiCallback)
            } catch (_: IllegalArgumentException) {
            }
            registered = false
        }
        super.onDetachedFromWindow()
    }

    /** Mobile signal has no cheap permission-free listener on every API level: poll it. */
    private val poll = object : Runnable {
        override fun run() {
            refreshNetwork()
            main.postDelayed(this, POLL_MS)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> onBattery(intent)
                else -> refreshNetwork()
            }
        }
    }

    private val defaultCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            defaultCaps = caps
            refreshNetwork()
        }

        override fun onLost(network: Network) {
            defaultCaps = null
            refreshNetwork()
        }
    }

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            wifiNetworks[network] = caps
            refreshNetwork()
        }

        override fun onLost(network: Network) {
            wifiNetworks.remove(network)
            refreshNetwork()
        }
    }

    /**
     * The phone's own hotspot (softAP, e.g. for wireless DeX) shows up as a Wi-Fi network
     * with LOCAL_NETWORK on Android 15+; it is not a connection to a Wi-Fi access point.
     */
    private fun isHotspot(caps: NetworkCapabilities) =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK)

    // ---- state → views ----

    private fun refreshNotifications() {
        val granted = Notifications.isAccessGranted(context)
        val items = Notifications.items
        notifyIcons.removeAllViews()
        // Like Android's status bar: the newest apps' small icons.
        for (item in items.distinctBy { it.packageName }.take(MAX_NOTIFY_ICONS)) {
            val icon = item.smallIcon ?: continue
            val view = ImageView(context)
            try {
                view.setImageIcon(icon)
            } catch (_: RuntimeException) {
                continue // the posting app's resources are not visible to us
            }
            view.setColorFilter(Palette.TASKBAR_TEXT)
            notifyIcons.addView(view, LayoutParams(dpi(16), dpi(16)).apply { marginEnd = dpi(4) })
        }
        badge.text = if (items.size > 99) "99+" else items.size.toString()
        badge.visibility = if (items.isEmpty()) GONE else VISIBLE
        bell.alpha = if (granted) 1f else 0.45f
        notifyGroup.tooltipText = if (granted) context.getString(R.string.status_notifications, items.size)
        else context.getString(R.string.notif_access_denied)
    }

    private fun onBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val present = intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)
        if (level < 0 || scale <= 0 || !present) {
            batteryGroup.visibility = GONE // e.g. a desktop-like device without a battery
            return
        }
        val percent = level * 100 / scale
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            (plugged && status == BatteryManager.BATTERY_STATUS_FULL)
        batteryIcon.set(percent, charging)
        batteryText.text = context.getString(R.string.status_percent, percent)
        batteryGroup.tooltipText = context.getString(
            if (charging) R.string.status_battery_charging else R.string.status_battery, percent,
        )
        batteryGroup.visibility = VISIBLE
    }

    private fun refreshNetwork() {
        val caps = defaultCaps
        val airplaneOn = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        val simReady = hasTelephony && !airplaneOn && simState() == TelephonyManager.SIM_STATE_READY
        val onEthernet = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        val station = wifiNetworks.values.firstOrNull { !isHotspot(it) }
        val wifiConnected = station != null
        val rssi = if (station != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) station.signalStrength
        else NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED

        vpn.visibility = visibleIf(caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true)
        airplane.visibility = visibleIf(airplaneOn)
        hotspot.visibility = visibleIf(wifiNetworks.values.any { isHotspot(it) })
        ethernet.visibility = visibleIf(onEthernet)
        cell.visibility = visibleIf(simReady)
        cell.level = cellLevel()
        // Like Android: the mobile data type shows only while mobile data carries the traffic.
        dataType.text = dataLabel
        dataType.visibility = visibleIf(simReady && dataLabel.isNotEmpty() && !wifiConnected && !onEthernet)

        val nothing = caps == null && !wifiConnected && !onEthernet
        // No network at all: a dim Wi-Fi fan with a slash, like a desktop tray.
        wifi.visibility = visibleIf(wifiConnected || (nothing && !simReady && !airplaneOn))
        wifi.active = wifiConnected
        wifi.level = if (wifiConnected) wifiLevel(rssi) else 0

        networkGroup.tooltipText = context.getString(
            when {
                nothing -> R.string.status_no_network
                airplaneOn && !wifiConnected -> R.string.status_airplane
                else -> R.string.status_network
            },
        )
    }

    private fun simState(): Int = try {
        telephony?.simState ?: TelephonyManager.SIM_STATE_UNKNOWN
    } catch (_: RuntimeException) {
        TelephonyManager.SIM_STATE_UNKNOWN
    }

    /** 0..4, or -1 when the level can't be read (bars drawn empty). */
    private fun cellLevel(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return -1
        return try {
            telephony?.signalStrength?.level ?: -1
        } catch (_: RuntimeException) {
            -1
        }
    }

    /** Same thresholds as most Android status bars, without needing WifiManager. */
    private fun wifiLevel(rssi: Int): Int = when {
        rssi == NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED -> 4
        rssi >= -55 -> 4
        rssi >= -66 -> 3
        rssi >= -77 -> 2
        rssi >= -88 -> 1
        else -> 0
    }

    // ---- actions ----

    private fun networkSettings(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
        else Intent(Settings.ACTION_WIRELESS_SETTINGS)

    private fun openSettings(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            context.startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun showCalendar(anchor: View) {
        // Android's calendar takes its colours from the context's theme: dark text on the
        // classic gray panel, light text on the modern dark one.
        val style = Themes.current.calendarStyle
        val themed = if (style != 0) ContextThemeWrapper(context, style) else context
        val calendar = CalendarView(themed)
        val popup = PopupWindow(calendar, WRAP_CONTENT, WRAP_CONTENT, true)
        popup.applyTheme(context, Themes.current.panel(context, 14f), 20f)
        popup.isOutsideTouchable = true
        // Just above the taskbar, at the right edge, like a desktop tray calendar.
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val fromBottom = anchor.rootView.height - loc[1] + dpi(6)
        popup.showAtLocation(anchor, Gravity.BOTTOM or Gravity.END, dpi(6), fromBottom)
        Popups.track(popup)
        calendar.hideSystemBarsOfWindow()
    }

    // ---- views ----

    private fun clock(): View {
        val box = group(R.string.status_calendar) { showCalendar(it) }
        box.orientation = VERTICAL
        box.gravity = Gravity.CENTER
        box.setPadding(dpi(10), 0, dpi(14), 0)
        val time = TextClock(context).apply {
            format24Hour = "HH:mm"
            format12Hour = "h:mm a"
            setTextColor(Palette.TASKBAR_TEXT)
            textSize = 13f
            gravity = Gravity.CENTER
        }
        val datePattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "ddMMyyyy")
        val date = TextClock(context).apply {
            format24Hour = datePattern
            format12Hour = datePattern
            setTextColor(Palette.TASKBAR_TEXT_DIM)
            textSize = 11f
            gravity = Gravity.CENTER
        }
        box.addView(time, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        box.addView(date, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        return box
    }

    private fun group(tooltip: Int, onClick: (View) -> Unit) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dpi(8), 0, dpi(8), 0)
        background = context.selectableBackground()
        tooltipText = context.getString(tooltip)
        setOnClickListener(onClick)
    }

    private fun label(text: String, sizeSp: Float) = TextView(context).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(Palette.TASKBAR_TEXT)
        gravity = Gravity.CENTER
        includeFontPadding = false
    }

    private fun iconParams(width: Int) = LayoutParams(width, WRAP_CONTENT).apply {
        marginStart = dpi(3)
        marginEnd = dpi(3)
    }

    private fun visibleIf(condition: Boolean) = if (condition) VISIBLE else GONE

    private companion object {
        const val POLL_MS = 10_000L
        const val MAX_NOTIFY_ICONS = 3
    }
}

/** Wi-Fi fan, mobile signal bars, hotspot waves or the Ethernet "<···>" glyph, in the taskbar colours. */
@SuppressLint("ViewConstructor")
class SignalIcon(context: Context, private val kind: Kind) : View(context) {

    enum class Kind { WIFI, CELL, ETHERNET, HOTSPOT }

    /** 0..4; -1 = unknown. */
    var level = 4
        set(value) {
            if (field != value) { field = value; invalidate() }
        }

    /** False: drawn dim with a slash (not connected). */
    var active = true
        set(value) {
            if (field != value) { field = value; invalidate() }
        }

    private val on = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.TASKBAR_TEXT }
    private val off = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.STATUS_ICON_OFF }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.TASKBAR_TEXT
        style = Paint.Style.STROKE
        strokeWidth = dp(1.6f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val arc = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = dpi(18)
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        when (kind) {
            Kind.WIFI -> {
                // A 90° wedge pointing up; the bright part grows with the signal level.
                val r = minOf(w / 1.42f, h * 0.9f)
                val cx = w / 2
                val apexY = h / 2 + r / 2
                arc.set(cx - r, apexY - r, cx + r, apexY + r)
                canvas.drawArc(arc, 225f, 90f, true, off)
                if (active && level > 0) {
                    val rr = r * level.coerceAtMost(4) / 4f
                    arc.set(cx - rr, apexY - rr, cx + rr, apexY + rr)
                    canvas.drawArc(arc, 225f, 90f, true, on)
                }
            }
            Kind.CELL -> {
                val bars = 4
                val gap = w * 0.08f
                val barW = (w - gap * (bars - 1)) / bars
                val top = h * 0.12f
                val bottom = h * 0.88f
                for (i in 0 until bars) {
                    val left = i * (barW + gap)
                    val barTop = bottom - (bottom - top) * (i + 1) / bars
                    canvas.drawRoundRect(left, barTop, left + barW, bottom, dp(1f), dp(1f), if (i < level) on else off)
                }
            }
            Kind.ETHERNET -> {
                val cy = h / 2
                val s = h * 0.28f
                // < · · · >
                canvas.drawLine(w * 0.22f, cy - s, w * 0.06f, cy, stroke)
                canvas.drawLine(w * 0.06f, cy, w * 0.22f, cy + s, stroke)
                canvas.drawLine(w * 0.78f, cy - s, w * 0.94f, cy, stroke)
                canvas.drawLine(w * 0.94f, cy, w * 0.78f, cy + s, stroke)
                val dot = dp(1.3f)
                for (x in floatArrayOf(0.36f, 0.5f, 0.64f)) canvas.drawCircle(w * x, cy, dot, on)
            }
            Kind.HOTSPOT -> {
                // ((•)) : a dot with two pairs of waves.
                val cx = w / 2
                val cy = h / 2
                canvas.drawCircle(cx, cy, dp(1.8f), on)
                for (r in floatArrayOf(w * 0.24f, w * 0.42f)) {
                    arc.set(cx - r, cy - r, cx + r, cy + r)
                    canvas.drawArc(arc, 135f, 90f, false, stroke)
                    canvas.drawArc(arc, -45f, 90f, false, stroke)
                }
            }
        }
        if (!active) canvas.drawLine(w * 0.15f, h * 0.15f, w * 0.85f, h * 0.85f, stroke)
    }
}

/** Horizontal battery like a desktop tray: fill = charge, green while charging, red when low. */
class BatteryIcon(context: Context) : View(context) {

    private var percent = 0
    private var charging = false

    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.4f)
        color = Palette.TASKBAR_TEXT
    }
    private val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.TASKBAR_TEXT }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val boltFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.TASKBAR_TEXT }
    private val boltEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
        strokeJoin = Paint.Join.ROUND
        color = Palette.TASKBAR or 0xFF000000.toInt()
    }
    private val body = RectF()
    private val bolt = Path()

    fun set(percent: Int, charging: Boolean) {
        if (this.percent == percent && this.charging == charging) return
        this.percent = percent.coerceIn(0, 100)
        this.charging = charging
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(dpi(26), widthMeasureSpec), resolveSize(dpi(14), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val tip = dp(2.5f)
        val half = outline.strokeWidth / 2
        body.set(half, half, w - tip - half, h - half)
        val radius = dp(2.5f)
        canvas.drawRoundRect(body, radius, radius, outline)
        canvas.drawRoundRect(w - tip, h * 0.32f, w, h * 0.68f, dp(1f), dp(1f), solid)

        fill.color = when {
            charging -> Palette.BATTERY_CHARGING
            percent <= 15 -> Palette.BATTERY_LOW
            else -> Palette.TASKBAR_TEXT
        }
        val pad = dp(2.4f)
        val inner = body.width() - 2 * pad + 2 * half
        val right = body.left - half + pad + inner * percent / 100f
        if (percent > 0) canvas.drawRoundRect(body.left - half + pad, body.top - half + pad, right, body.bottom + half - pad, dp(1f), dp(1f), fill)

        if (charging) {
            val bw = body.width()
            val bh = body.height()
            val x = body.left
            val y = body.top
            bolt.reset()
            bolt.moveTo(x + bw * 0.58f, y - bh * 0.05f)
            bolt.lineTo(x + bw * 0.30f, y + bh * 0.58f)
            bolt.lineTo(x + bw * 0.50f, y + bh * 0.58f)
            bolt.lineTo(x + bw * 0.42f, y + bh * 1.05f)
            bolt.lineTo(x + bw * 0.72f, y + bh * 0.40f)
            bolt.lineTo(x + bw * 0.52f, y + bh * 0.40f)
            bolt.close()
            canvas.drawPath(bolt, boltEdge)
            canvas.drawPath(bolt, boltFill)
        }
    }
}
