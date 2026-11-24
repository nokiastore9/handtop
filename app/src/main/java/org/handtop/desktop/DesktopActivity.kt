package org.handtop.desktop

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.handtop.desktop.linux.LinuxEnv
import org.handtop.desktop.linux.LinuxSetupContent
import org.handtop.desktop.shell.AboutContent
import org.handtop.desktop.shell.SettingsContent
import org.handtop.desktop.shell.DesktopIcons
import org.handtop.desktop.shell.Taskbar
import org.handtop.desktop.term.ShellSpec
import org.handtop.desktop.term.TermSessionHost
import org.handtop.desktop.term.TermSessions
import org.handtop.desktop.term.TerminalContent
import org.handtop.desktop.ui.Palette
import org.handtop.desktop.ui.UiScale
import org.handtop.desktop.ui.dp
import org.handtop.desktop.ui.dpi
import org.handtop.desktop.ui.hideSystemBars
import org.handtop.desktop.ui.hideSystemBarsOfWindow
import org.handtop.desktop.ui.roundedRect
import org.handtop.desktop.ui.selectableBackground
import org.handtop.desktop.wayland.PopupLayer
import org.handtop.desktop.wayland.WaylandServer
import org.handtop.desktop.wayland.WaylandWindowContent
import org.handtop.desktop.wayland.XdgToplevel
import org.handtop.desktop.wm.DesktopArea
import org.handtop.desktop.wm.DesktopWindow

/**
 * The whole desktop lives in this single activity: wallpaper with icons, the window
 * area and the taskbar. It handles every config change itself (rotation, DeX switch,
 * keyboard plug) so windows and shell sessions are never torn down.
 */
class DesktopActivity : Activity(), WaylandServer.Host {

    private lateinit var root: FrameLayout
    private lateinit var column: LinearLayout
    private lateinit var area: DesktopArea
    private lateinit var taskbar: Taskbar
    /** Menus and other popups of Linux windows, above every window. */
    private val popupLayer by lazy { PopupLayer(this, WaylandServer.get(this)) }
    private var startMenu: PopupWindow? = null

    /** System density and display kind this activity's interface scale was computed for. */
    private var systemDpi = 0
    private var onDesktopDisplay = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        // The interface scale from Settings: shrink / grow every dp and sp of the shell.
        systemDpi = UiScale.systemDpi(newBase)
        onDesktopDisplay = UiScale.isDesktop(newBase)
        UiScale.override(newBase, onDesktopDisplay)?.let { applyOverrideConfiguration(it) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Moved between the phone and DeX, or the system density changed: the scale override
        // was computed for the old screen, so build the desktop again (sessions survive).
        if (UiScale.systemDpi(this) != systemDpi || UiScale.isDesktop(baseContext) != onDesktopDisplay) {
            recreate()
            return
        }
        WaylandServer.get(this).updateDisplay(resources.displayMetrics)
    }

    /** Settings → interface scale. */
    fun setInterfaceScale(percent: Int) {
        if (percent == UiScale.percent(this, onDesktopDisplay)) return
        UiScale.setPercent(this, onDesktopDisplay, percent)
        recreate()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SETTINGS_OPEN, area.windows.any { it.content is SettingsContent })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Exclusive full screen, drawing under the camera cutout too: the taskbar replaces
        // Android's status bar.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        window.hideSystemBars()
        LinuxEnv.migrate(this)

        area = DesktopArea(this)
        taskbar = Taskbar(this, area, onStart = { toggleStartMenu() }, onKeyboard = { area.activeWindow?.content?.requestKeyboard() })
        area.listener = taskbar

        val icons = DesktopIcons(this).apply {
            add(R.drawable.ic_linux, R.string.app_terminal_linux) { openLinuxTerminal() }
            add(R.drawable.ic_terminal, R.string.app_terminal_android) { openAndroidTerminal() }
            add(R.drawable.ic_settings, R.string.app_settings) { openSettings() }
            add(R.drawable.ic_info, R.string.app_about) { openAbout() }
        }
        val desktop = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, Palette.WALLPAPER)
            addView(icons, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(area, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(popupLayer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(desktop, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(taskbar, LinearLayout.LayoutParams(MATCH_PARENT, dpi(48)))
        }
        root = FrameLayout(this).apply { addView(column) }
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            // Hidden bars report no insets; the cutout is ignored on purpose (it only hides a
            // few pixels of wallpaper or window frame). Bars only count if the system keeps
            // them, e.g. in a freeform window.
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            column.setPadding(bars.left, bars.top, bars.right, if (imeVisible) ime.bottom else bars.bottom)
            // While typing on a phone every pixel counts: hide the taskbar.
            taskbar.visibility = if (imeVisible) View.GONE else View.VISIBLE
            WindowInsetsCompat.CONSUMED
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { onBack() }
        }

        // Re-attach sessions that survived an activity re-creation; otherwise start fresh.
        if (TermSessions.hosts.isEmpty()) openLinuxTerminal()
        else TermSessions.hosts.toList().forEach { openTerminalWindow(it) }

        // Linux GUI windows: same for toplevels that are already on screen.
        val wayland = WaylandServer.get(this)
        wayland.updateDisplay(resources.displayMetrics) // after the interface scale
        wayland.host = this
        wayland.mappedToplevels().forEach { toplevelMapped(it) }
        // Re-created after changing the scale: keep the Settings window where the user was.
        if (savedInstanceState?.getBoolean(STATE_SETTINGS_OPEN) == true) openSettings()

        requestNotificationPermission()
    }

    /** The "terminals are running" notification of the foreground service (Android 13+). */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Back from Android settings, a permission dialog etc.: hide the bars again.
        if (hasFocus) window.hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        // Back from Android settings (e.g. storage permission): show the new state.
        area.windows.forEach { (it.content as? SettingsContent)?.refresh() }
    }

    override fun onDestroy() {
        WaylandServer.get(this).let { if (it.host === this) it.host = null }
        startMenu?.dismiss()
        // Sessions stay alive in TermSessions; just drop references to this activity's views.
        TermSessions.hosts.forEach { it.ui = null }
        super.onDestroy()
    }

    @Deprecated("Used only below Android 13")
    override fun onBackPressed() = onBack()

    /** Back = minimize the active window (never quit the desktop by accident). */
    private fun onBack() {
        if (startMenu?.isShowing == true) startMenu?.dismiss()
        else area.activeWindow?.let { area.minimize(it) }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when {
                event.isCtrlPressed && event.isAltPressed && event.keyCode == KeyEvent.KEYCODE_T -> {
                    if (LinuxEnv.isInstalled(this)) openLinuxTerminal() else openAndroidTerminal()
                    return true
                }
                event.isAltPressed && event.keyCode == KeyEvent.KEYCODE_F4 -> {
                    area.activeWindow?.requestClose(); return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---- apps ----

    /** Debian shell through proot, or the installer window if Linux isn't installed yet. */
    private fun openLinuxTerminal() {
        if (LinuxEnv.isInstalled(this)) openTerminalWindow(TermSessions.create(this, LinuxEnv.shellSpec(this)))
        else openLinuxSetup()
    }

    private fun openAndroidTerminal() = openTerminalWindow(TermSessions.create(this, ShellSpec.android(this)))

    private fun openTerminalWindow(host: TermSessionHost) {
        val content = TerminalContent(this, host) { openTerminalWindow(TermSessions.create(this, host.spec)) }
        area.addWindow(DesktopWindow(this, area, content, host.spec.title, host.spec.icon))
    }

    private fun openLinuxSetup() {
        area.windows.firstOrNull { it.content is LinuxSetupContent }?.let { area.activate(it); return }
        val content = LinuxSetupContent(this) { openLinuxTerminal() }
        area.addWindow(DesktopWindow(this, area, content, getString(R.string.linux_setup_title), R.drawable.ic_linux))
    }

    // ---- Linux GUI windows (WaylandServer.Host) ----

    override fun toplevelMapped(toplevel: XdgToplevel) {
        if (area.windows.any { (it.content as? WaylandWindowContent)?.toplevel === toplevel }) return
        lateinit var window: DesktopWindow
        val control = object : WaylandWindowContent.WindowControl {
            override fun beginMove(rawX: Float, rawY: Float) = area.beginMove(window, rawX, rawY)
            override fun moveTo(rawX: Float, rawY: Float) = area.moveTo(window, rawX, rawY)
            override fun endMove(rawX: Float, rawY: Float, cancelled: Boolean) = area.endMove(window, rawX, rawY, cancelled)
            override fun beginResize(edges: Int, rawX: Float, rawY: Float) = area.beginResize(window, edges, rawX, rawY)
            override fun resizeTo(rawX: Float, rawY: Float) = area.resizeTo(window, rawX, rawY)
            override fun endResize() = area.endResize(window)
            override fun setDecorated(decorated: Boolean) {
                window.decorated = decorated
            }
            override fun setMaximized(maximized: Boolean) {
                if (maximized) area.activate(window)
                if (window.resizable) window.setMode(if (maximized) DesktopWindow.Mode.MAXIMIZED else DesktopWindow.Mode.NORMAL)
            }
            override fun minimize() = area.minimize(window)
            override fun isMaximized() = window.mode == DesktopWindow.Mode.MAXIMIZED
            override fun isActive() = window.isActiveWindow
        }
        val content = WaylandWindowContent(this, toplevel, control, popupLayer)
        window = DesktopWindow(this, area, content, toplevel.title.ifEmpty { "Linux" }, R.drawable.ic_apps)
        // Programs without server-side decorations draw their own title bar.
        window.decorated = toplevel.serverSideDecorations
        window.resizable = !toplevel.fixedSize
        // Dialogs open over the window they belong to.
        val parent = toplevel.parent?.let { p ->
            area.windows.firstOrNull { (it.content as? WaylandWindowContent)?.toplevel === p }
        }
        // Size the window around what the program drew (in view pixels).
        val g = toplevel.xdg.visibleGeometry()
        val s = toplevel.client.server.scale
        area.addWindow(window, g.width() * s, g.height() * s, parent)
    }

    override fun toplevelUnmapped(toplevel: XdgToplevel) {
        area.windows.firstOrNull { (it.content as? WaylandWindowContent)?.toplevel === toplevel }
            ?.let { area.closeWindow(it) }
    }

    private fun openSettings() {
        area.windows.firstOrNull { it.content is SettingsContent }?.let { area.activate(it); return }
        area.addWindow(DesktopWindow(this, area, SettingsContent(this, openLinuxSetup = { openLinuxSetup() }, setScale = { setInterfaceScale(it) }), getString(R.string.app_settings), R.drawable.ic_settings))
    }

    private fun openAbout() {
        area.windows.firstOrNull { it.content is AboutContent }?.let { area.activate(it); return }
        area.addWindow(DesktopWindow(this, area, AboutContent(this), getString(R.string.app_about), R.drawable.ic_info))
    }

    private fun exitAll() {
        area.windows.toList().forEach { area.closeWindow(it) }
        TermSessions.hosts.toList().forEach { it.session.finishIfRunning(); TermSessions.remove(this, it) }
        finishAndRemoveTask()
    }

    // ---- start menu ----

    private fun toggleStartMenu() {
        startMenu?.let { if (it.isShowing) { it.dismiss(); return } }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(6), dpi(10), dpi(6), dpi(6))
        }
        panel.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            setTextColor(Palette.TEXT)
            textSize = 18f
            setPadding(dpi(14), dpi(4), dpi(14), dpi(10))
        })
        val popup = PopupWindow(panel, dpi(280), WRAP_CONTENT, true)
        fun item(@DrawableRes icon: Int, @StringRes label: Int, enabled: Boolean = true, action: () -> Unit = {}) {
            panel.addView(menuItem(icon, label, enabled) { popup.dismiss(); action() })
        }
        item(R.drawable.ic_linux, R.string.app_terminal_linux) { openLinuxTerminal() }
        item(R.drawable.ic_terminal, R.string.app_terminal_android) { openAndroidTerminal() }
        item(R.drawable.ic_apps, R.string.app_linux_soon, enabled = false)
        item(R.drawable.ic_settings, R.string.app_settings) { openSettings() }
        item(R.drawable.ic_info, R.string.app_about) { openAbout() }
        panel.addView(View(this).apply { setBackgroundColor(Palette.MENU_DIVIDER) },
            LinearLayout.LayoutParams(MATCH_PARENT, dpi(1)).apply { setMargins(dpi(8), dpi(6), dpi(8), dpi(6)) })
        item(R.drawable.ic_power, R.string.action_exit) { exitAll() }

        popup.setBackgroundDrawable(roundedRect(Palette.MENU_BG, dp(14f)))
        popup.elevation = dp(20f)
        popup.isOutsideTouchable = true
        popup.setOnDismissListener { if (startMenu === popup) startMenu = null }

        // Open just above the taskbar, aligned with the start button.
        val loc = IntArray(2)
        taskbar.getLocationInWindow(loc)
        val fromBottom = root.height - loc[1] + dpi(6)
        popup.showAtLocation(root, Gravity.BOTTOM or Gravity.START, loc[0] + dpi(6), fromBottom)
        panel.hideSystemBarsOfWindow()
        startMenu = popup
    }

    private fun menuItem(@DrawableRes icon: Int, @StringRes label: Int, enabled: Boolean, onClick: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(14), 0, dpi(14), 0)
            background = selectableBackground()
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.45f
            if (enabled) setOnClickListener { onClick() }
            addView(ImageView(context).apply { setImageResource(icon) }, LinearLayout.LayoutParams(dpi(22), dpi(22)))
            addView(TextView(context).apply {
                setText(label)
                setTextColor(Palette.TEXT)
                textSize = 15f
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dpi(14), 0, 0, 0)
            })
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dpi(46))
        }

    private companion object {
        const val STATE_SETTINGS_OPEN = "settings_open"
    }
}
