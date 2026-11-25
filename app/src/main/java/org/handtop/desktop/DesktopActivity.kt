package org.handtop.desktop

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
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
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.handtop.desktop.files.FilesContent
import org.handtop.desktop.linux.LinuxApp
import org.handtop.desktop.linux.LinuxApps
import org.handtop.desktop.linux.LinuxEnv
import org.handtop.desktop.linux.LinuxSetupContent
import org.handtop.desktop.shell.AboutContent
import org.handtop.desktop.shell.SettingsContent
import org.handtop.desktop.shell.DesktopIcons
import org.handtop.desktop.shell.Launchpad
import org.handtop.desktop.shell.Shortcut
import org.handtop.desktop.shell.Shortcuts
import org.handtop.desktop.shell.Taskbar
import org.handtop.desktop.shell.WallpaperContent
import org.handtop.desktop.shell.WallpaperDrawable
import org.handtop.desktop.shell.Wallpapers
import org.handtop.desktop.term.ShellSpec
import org.handtop.desktop.term.TermSessionHost
import org.handtop.desktop.term.TermSessions
import org.handtop.desktop.term.TerminalContent
import org.handtop.desktop.ui.MenuEntry
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
import org.handtop.desktop.wayland.ScreenKeeper
import org.handtop.desktop.wayland.Toplevel
import org.handtop.desktop.wm.DesktopArea
import org.handtop.desktop.wm.DesktopWindow
import java.io.File

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
    private lateinit var wallpaper: WallpaperDrawable
    private lateinit var icons: DesktopIcons
    private lateinit var launchpad: Launchpad
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
        taskbar = Taskbar(
            this, area,
            onStart = { toggleStartMenu() },
            onKeyboard = { area.activeWindow?.content?.requestKeyboard() },
            onSetup = { openLinuxSetup() },
            onApps = { if (launchpad.isOpen) launchpad.close() else openLinuxApps() },
            onWindowButton = { launchpad.close() },
        )
        area.listener = taskbar

        icons = DesktopIcons(this, open = { openShortcut(it) }, desktopMenu = { desktopMenu() })
        launchpad = Launchpad(
            this,
            launch = { launchLinuxApp(it) },
            addToDesktop = { addToDesktop(Shortcut.LinuxProgram(it.id, it.name), it.name) },
            openLinuxSetup = { openLinuxSetup() },
            onShown = { blurDesktop(it) },
        )
        wallpaper = WallpaperDrawable(this, Wallpapers.current(this), follow = true)
        val desktop = FrameLayout(this).apply {
            background = wallpaper
            addView(icons, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(area, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(popupLayer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(launchpad, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
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
        // Linux programs playing a video keep the screen on (Wayland idle inhibit, D-Bus).
        ScreenKeeper.onChange = { keepScreenOn(it) }
        keepScreenOn(ScreenKeeper.active)
        wayland.mappedToplevels().forEach { toplevelMapped(it) }
        // Re-created after changing the scale: keep the Settings window where the user was.
        if (savedInstanceState?.getBoolean(STATE_SETTINGS_OPEN) == true) openSettings(onPage = true)

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
        // Something may have been copied in another app meanwhile: offer it to Linux.
        if (hasFocus) WaylandServer.get(this).clipboard.syncFromAndroid()
    }

    override fun onResume() {
        super.onResume()
        // Back from Android settings (e.g. storage permission): show the new state.
        area.windows.forEach { (it.content as? SettingsContent)?.refresh() }
    }

    private fun keepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onDestroy() {
        wallpaper.release()
        ScreenKeeper.onChange = null
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
        if (launchpad.isOpen) launchpad.close()
        else if (startMenu?.isShowing == true) startMenu?.dismiss()
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

    // ---- desktop: shortcuts, its menu, wallpaper ----

    private fun openShortcut(shortcut: Shortcut) {
        when (shortcut) {
            is Shortcut.BuiltIn -> when (shortcut.id) {
                Shortcuts.LINUX_TERMINAL -> openLinuxTerminal()
                Shortcuts.LINUX_APPS -> openLinuxApps()
                Shortcuts.FILES -> openFiles()
                Shortcuts.ANDROID_TERMINAL -> openAndroidTerminal()
                Shortcuts.SETTINGS -> openSettings()
                else -> openAbout()
            }
            is Shortcut.Folder -> {
                val dir = File(shortcut.path)
                if (dir.isDirectory) openFiles(dir) else toast(getString(R.string.shortcut_missing, shortcut.name))
            }
            is Shortcut.LinuxProgram -> {
                if (!LinuxEnv.isInstalled(this)) return openLinuxSetup()
                // Reading the program's menu entry touches files: not on the main thread.
                Thread({
                    val app = LinuxApps.forAppId(applicationContext, shortcut.appId)
                    runOnUiThread {
                        if (app != null) launchLinuxApp(app)
                        else toast(getString(R.string.shortcut_missing, shortcut.name))
                    }
                }, "shortcut").start()
            }
        }
    }

    /** A long press or a right click on the wallpaper. */
    private fun desktopMenu(): List<MenuEntry?> {
        val items = ArrayList<MenuEntry?>()
        if (LinuxEnv.isInstalled(this)) {
            items += MenuEntry(getString(R.string.desktop_add_program), R.drawable.ic_apps) { openLinuxApps(pick = true) }
        }
        items += MenuEntry(getString(R.string.desktop_restore_icons), R.drawable.ic_home, Shortcuts.missingBuiltIns(this).isNotEmpty()) {
            Shortcuts.restoreBuiltIns(this)
        }
        // Forget where icons were dragged: they line up in columns again.
        items += MenuEntry(getString(R.string.desktop_arrange_icons), R.drawable.ic_apps, Shortcuts.cells(this).isNotEmpty()) {
            Shortcuts.setCells(this, emptyMap())
        }
        items += null
        items += MenuEntry(getString(R.string.desktop_wallpaper), R.drawable.ic_camera) { openWallpapers() }
        items += MenuEntry(getString(R.string.app_settings), R.drawable.ic_settings) { openSettings() }
        return items
    }

    private fun addToDesktop(shortcut: Shortcut, name: String) {
        toast(getString(if (Shortcuts.add(this, shortcut)) R.string.shortcut_added else R.string.shortcut_exists, name))
    }

    private fun openWallpapers() {
        area.windows.firstOrNull { it.content is WallpaperContent }?.let { area.activate(it); return }
        val content = WallpaperContent(this) { pickWallpaperImage() }
        area.addWindow(DesktopWindow(this, area, content, getString(R.string.wallpaper_title), R.drawable.ic_camera))
    }

    /** Android's photo picker (no permission needed), or the document picker before Android 13. */
    private fun pickWallpaperImage() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_WALLPAPER)
        } catch (_: ActivityNotFoundException) {
            toast(getString(R.string.wallpaper_failed))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQUEST_WALLPAPER || resultCode != RESULT_OK || uri == null) return
        Wallpapers.setCustom(this, uri) { ok -> if (!ok) toast(getString(R.string.wallpaper_failed)) }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    // ---- Linux GUI windows (WaylandServer.Host) ----

    override fun toplevelMapped(toplevel: Toplevel) {
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
            override fun appIdChanged() = showLinuxIcon(window, toplevel)
        }
        val content = WaylandWindowContent(this, toplevel, control, popupLayer)
        window = DesktopWindow(this, area, content, toplevel.title.ifEmpty { "Linux" }, R.drawable.ic_apps)
        // Programs without server-side decorations draw their own title bar.
        window.decorated = toplevel.serverSideDecorations
        showLinuxIcon(window, toplevel)
        window.resizable = !toplevel.fixedSize
        // Dialogs open over the window they belong to.
        val parent = toplevel.parent?.let { p ->
            area.windows.firstOrNull { (it.content as? WaylandWindowContent)?.toplevel === p }
        }
        // Size the window around what the program drew (in view pixels).
        val g = toplevel.visibleGeometry()
        val s = toplevel.client.server.scale
        area.addWindow(window, g.width() * s, g.height() * s, parent)
    }

    /** The program's own icon, from its menu entry (app id = desktop file id, usually). */
    private fun showLinuxIcon(window: DesktopWindow, toplevel: Toplevel) {
        val icon = LinuxApps.forAppId(this, toplevel.appId)?.let { LinuxApps.icon(it, dpi(48)) }
        if (icon != null && icon !== window.iconBitmap) window.iconBitmap = icon
        Log.d("HandTop", "linux window \"${toplevel.title}\" app id \"${toplevel.appId}\" icon ${icon != null}")
    }

    override fun toplevelUnmapped(toplevel: Toplevel) {
        area.windows.firstOrNull { (it.content as? WaylandWindowContent)?.toplevel === toplevel }
            ?.let { area.closeWindow(it) }
    }

    /** The Linux apps menu window (one at a time). */
    /** The Linux programs, full screen; with [pick], a tap puts one on the desktop. */
    private fun openLinuxApps(pick: Boolean = false) {
        startMenu?.dismiss()
        launchpad.open(pick)
    }

    /** What is behind the launchpad goes out of focus (Android 12+; before, it is only dimmed). */
    private fun blurDesktop(blur: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val effect = if (blur) RenderEffect.createBlurEffect(dp(18f), dp(18f), Shader.TileMode.CLAMP) else null
        area.setRenderEffect(effect)
        icons.setRenderEffect(effect)
    }

    private fun launchLinuxApp(app: LinuxApp, paths: List<String> = emptyList()) {
        if (app.terminal) {
            // Terminal programs (htop, mc, vim…) get a terminal window of their own.
            val spec = LinuxEnv.commandSpec(this, LinuxApps.commandFor(app, paths)).copy(title = app.name)
            openTerminalWindow(TermSessions.create(this, spec))
        } else {
            LinuxApps.launch(this, app, paths)
        }
    }

    /** A new file manager window (several can be open, e.g. to copy between them). */
    private fun openFiles(start: File? = null) {
        val content = FilesContent(this, object : FilesContent.Actions {
            override fun openWithLinux(app: LinuxApp, linuxPaths: List<String>) = launchLinuxApp(app, linuxPaths)
            override fun openTerminalAt(linuxPath: String) {
                openTerminalWindow(TermSessions.create(this@DesktopActivity, LinuxEnv.shellSpec(this@DesktopActivity, linuxPath)))
            }
            override fun addToDesktop(dir: File) = addToDesktop(Shortcut.Folder(dir.path, dir.name), dir.name)
        }, start)
        area.addWindow(DesktopWindow(this, area, content, getString(R.string.app_files), R.drawable.ic_files))
    }

    private fun openSettings(onPage: Boolean = false) {
        area.windows.firstOrNull { it.content is SettingsContent }?.let { area.activate(it); return }
        area.addWindow(DesktopWindow(this, area, SettingsContent(
            this,
            openLinuxSetup = { openLinuxSetup() },
            setScale = { setInterfaceScale(it) },
            openWallpapers = { openWallpapers() },
            startOnPage = onPage,
        ), getString(R.string.app_settings), R.drawable.ic_settings))
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
        launchpad.close()
        startMenu?.let { if (it.isShowing) { it.dismiss(); return } }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(6), dpi(10), dpi(6), dpi(6))
        }
        panel.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            setTextColor(Palette.TEXT)
            textSize = 18f
            gravity = Gravity.CENTER_VERTICAL
            // HandTop's mark, as on the Start button.
            val mark = getDrawable(R.drawable.ic_handtop)?.mutate()?.apply {
                setTint(Palette.ACCENT)
                setBounds(0, 0, dpi(22), dpi(22))
            }
            setCompoundDrawablesRelative(mark, null, null, null)
            compoundDrawablePadding = dpi(10)
            setPadding(dpi(14), dpi(4), dpi(14), dpi(10))
        })
        val popup = PopupWindow(panel, dpi(280), WRAP_CONTENT, true)
        fun item(@DrawableRes icon: Int, @StringRes label: Int, enabled: Boolean = true, action: () -> Unit = {}) {
            panel.addView(menuItem(icon, label, enabled) { popup.dismiss(); action() })
        }
        item(R.drawable.ic_linux, R.string.app_terminal_linux) { openLinuxTerminal() }
        item(R.drawable.ic_terminal, R.string.app_terminal_android) { openAndroidTerminal() }
        item(R.drawable.ic_apps, R.string.app_linux_apps) { openLinuxApps() }
        item(R.drawable.ic_files, R.string.app_files) { openFiles() }
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
        const val REQUEST_WALLPAPER = 2
    }
}
