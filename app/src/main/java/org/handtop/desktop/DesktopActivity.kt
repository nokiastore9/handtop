package org.handtop.desktop

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
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
import org.handtop.desktop.shell.AboutContent
import org.handtop.desktop.shell.DesktopIcons
import org.handtop.desktop.shell.Taskbar
import org.handtop.desktop.term.TermSessionHost
import org.handtop.desktop.term.TermSessions
import org.handtop.desktop.term.TerminalContent
import org.handtop.desktop.ui.Palette
import org.handtop.desktop.ui.dp
import org.handtop.desktop.ui.dpi
import org.handtop.desktop.ui.roundedRect
import org.handtop.desktop.ui.selectableBackground
import org.handtop.desktop.wm.DesktopArea
import org.handtop.desktop.wm.DesktopWindow

/**
 * The whole desktop lives in this single activity: wallpaper with icons, the window
 * area and the taskbar. It handles every config change itself (rotation, DeX switch,
 * keyboard plug) so windows and shell sessions are never torn down.
 */
class DesktopActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var column: LinearLayout
    private lateinit var area: DesktopArea
    private lateinit var taskbar: Taskbar
    private var startMenu: PopupWindow? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        area = DesktopArea(this)
        taskbar = Taskbar(this, area, onStart = { toggleStartMenu() }, onKeyboard = { area.activeWindow?.content?.requestKeyboard() })
        area.listener = taskbar

        val icons = DesktopIcons(this).apply {
            add(R.drawable.ic_terminal, R.string.app_terminal) { openTerminal() }
            add(R.drawable.ic_info, R.string.app_about) { openAbout() }
        }
        val desktop = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, Palette.WALLPAPER)
            addView(icons, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(area, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(desktop, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(taskbar, LinearLayout.LayoutParams(MATCH_PARENT, dpi(48)))
        }
        root = FrameLayout(this).apply { addView(column) }
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
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
        if (TermSessions.hosts.isEmpty()) openTerminal()
        else TermSessions.hosts.toList().forEach { openTerminal(it) }
    }

    override fun onDestroy() {
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
                    openTerminal(); return true
                }
                event.isAltPressed && event.keyCode == KeyEvent.KEYCODE_F4 -> {
                    area.activeWindow?.close(); return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---- apps ----

    fun openTerminal(existing: TermSessionHost? = null) {
        val host = existing ?: TermSessions.create(this)
        val content = TerminalContent(this, host) { openTerminal() }
        area.addWindow(DesktopWindow(this, area, content, getString(R.string.app_terminal), R.drawable.ic_terminal))
    }

    private fun openAbout() {
        area.windows.firstOrNull { it.content is AboutContent }?.let { area.activate(it); return }
        area.addWindow(DesktopWindow(this, area, AboutContent(this), getString(R.string.app_about), R.drawable.ic_info))
    }

    private fun exitAll() {
        area.windows.toList().forEach { area.closeWindow(it) }
        TermSessions.hosts.toList().forEach { it.session.finishIfRunning(); TermSessions.remove(it) }
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
        item(R.drawable.ic_terminal, R.string.app_terminal) { openTerminal() }
        item(R.drawable.ic_apps, R.string.app_linux_soon, enabled = false)
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
}
