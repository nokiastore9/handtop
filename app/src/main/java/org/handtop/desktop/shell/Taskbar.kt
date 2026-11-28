package org.handtop.desktop.shell

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.InsetDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.handtop.desktop.R
import org.handtop.desktop.ui.Bevel
import org.handtop.desktop.ui.MenuEntry
import org.handtop.desktop.ui.Palette
import org.handtop.desktop.ui.Themes
import org.handtop.desktop.ui.bevel
import org.handtop.desktop.ui.dp
import org.handtop.desktop.ui.dpi
import org.handtop.desktop.ui.selectableBackground
import org.handtop.desktop.ui.barIconTint
import org.handtop.desktop.ui.setBarIcon
import org.handtop.desktop.ui.setThemedIcon
import org.handtop.desktop.ui.showPopupMenu
import org.handtop.desktop.wm.DesktopArea
import org.handtop.desktop.wm.DesktopWindow
import org.handtop.desktop.wm.WindowAction
import org.handtop.desktop.wm.windowActions

/**
 * Bottom panel: start button, the Linux programs, one button per open window, the Linux
 * setup while it runs, keyboard button and status / clock.
 */
@SuppressLint("ViewConstructor")
class Taskbar(
    context: Context,
    private val area: DesktopArea,
    private val onStart: (View) -> Unit,
    private val onKeyboard: () -> Unit,
    /** A long press on the keyboard button: the touchpad on or off. */
    private val onKeyboardLong: () -> Unit,
    /** Opens the Linux System window (from the Linux setup's item). */
    private val onSetup: () -> Unit,
    /** Opens or closes the Linux programs (the launchpad). */
    private val onApps: () -> Unit,
    /** A window's button was used (the launchpad gets out of the way). */
    private val onWindowButton: () -> Unit,
) : LinearLayout(context), DesktopArea.Listener {

    /** The start button; the start menu opens from it. */
    val startButton: View
    private val keyboardButton: ImageButton
    private val strip = LinearLayout(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val theme = Themes.current
        background = theme.taskbarBackground(context)
        elevation = if (theme.shadows) dp(12f) else 0f

        startButton = if (theme.startLabel) labelledStartButton() else iconButton(R.drawable.ic_handtop, R.string.start_menu, Palette.ACCENT) { onStart(it) }
        addView(startButton, LayoutParams(if (theme.startLabel) LayoutParams.WRAP_CONTENT else dpi(52), LayoutParams.MATCH_PARENT))
        // The Linux programs, as GNOME's "Show Applications".
        addView(iconButton(R.drawable.ic_apps, R.string.app_linux_apps) { onApps() }.apply { setBarIcon(R.drawable.ic_apps, Palette.TEXT_SOFT) },
            LayoutParams(dpi(44), LayoutParams.MATCH_PARENT))

        strip.orientation = HORIZONTAL
        strip.gravity = Gravity.CENTER_VERTICAL
        val scroller = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        }
        addView(scroller, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

        addView(SetupIndicator(context) { onSetup() }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))

        keyboardButton = iconButton(R.drawable.ic_keyboard, R.string.action_keyboard) { onKeyboard() }.apply {
            setBarIcon(R.drawable.ic_keyboard)
            setOnLongClickListener {
                onKeyboardLong()
                true
            }
        }
        addView(keyboardButton, LayoutParams(dpi(48), LayoutParams.MATCH_PARENT))
        showTouchpad(false)

        // HandTop runs full screen: network, battery and clock live here instead of Android's status bar.
        addView(StatusArea(context).apply { background = theme.trayBackground(context) },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    /** The classic start button: HandTop's mark and the word "Start", raised, pressed in while the menu is open. */
    private fun labelledStartButton() = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Themes.current.startButton(context, open = false)
        setPadding(dpi(12), 0, dpi(10), 0)
        contentDescription = context.getString(R.string.start_menu)
        isFocusable = false
        setOnClickListener { onStart(it) }
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_handtop)
            setColorFilter(barIconTint(Themes.current.colors, R.drawable.ic_handtop, Palette.ACCENT)!!)
        }, LayoutParams(dpi(20), dpi(20)))
        addView(TextView(context).apply {
            setText(R.string.start_menu)
            setTextColor(Palette.TASKBAR_TEXT)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dpi(6), 0, 0, 0)
        })
    }

    /** The start menu opened or closed: the classic start button stays pressed in meanwhile. */
    fun showStartMenu(open: Boolean) {
        Themes.current.startButton(context, open)?.let {
            // Keep the padding: a bevel background brings its own.
            val l = startButton.paddingLeft; val r = startButton.paddingRight
            startButton.background = it
            startButton.setPadding(l, 0, r, 0)
        }
    }

    /** The keyboard button is at this screen point (it stays a touch button in touchpad mode). */
    fun isKeyboardButtonAt(rawX: Float, rawY: Float): Boolean {
        if (!keyboardButton.isShown) return false
        val at = IntArray(2).also { keyboardButton.getLocationOnScreen(it) }
        return rawX >= at[0] && rawX < at[0] + keyboardButton.width && rawY >= at[1] && rawY < at[1] + keyboardButton.height
    }

    /** The keyboard button shows whether the touchpad is on. */
    fun showTouchpad(on: Boolean) {
        if (Themes.current.tintIcons) {
            if (on) keyboardButton.setColorFilter(Palette.ACCENT) else keyboardButton.setBarIcon(R.drawable.ic_keyboard)
        } else {
            // A colour icon can't change colour: the classic button stays pressed in instead.
            keyboardButton.background = if (on) Themes.current.barButtonOn(context) else Themes.current.barButton(context)
        }
    }

    override fun onWindowsChanged() {
        strip.removeAllViews()
        for (w in area.windows) strip.addView(windowButton(w))
    }

    private fun windowButton(w: DesktopWindow): View {
        val isFront = w === area.activeWindow && !w.minimized
        val item = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // The background first: a bevel brings its own padding, which would replace ours.
            background = Themes.current.taskButton(context, isFront, w.minimized)
            setPadding(dpi(10), 0, dpi(12), 0)
            if (Themes.current.shadows) foreground = Themes.current.darkFeedback(context)
            tooltipText = w.title
            setOnClickListener {
                onWindowButton()
                if (w === area.activeWindow && !w.minimized) area.minimize(w) else area.activate(w)
            }
            // A long press or a right click: the window's menu.
            setOnLongClickListener { showWindowMenu(this, w); true }
            setOnContextClickListener { showWindowMenu(this, w); true }
        }
        val icon = ImageView(context).apply {
            val bitmap = w.iconBitmap
            if (bitmap != null) setImageBitmap(bitmap) else setThemedIcon(w.iconRes, barIconTint(Themes.current.colors, w.iconRes, null))
            alpha = if (w.minimized) 0.6f else 1f
        }
        item.addView(icon, LayoutParams(dpi(20), dpi(20)))
        val label = TextView(context).apply {
            text = w.title
            // Modern dims a minimized window's name; Classic keeps it black, gray would read as disabled.
            setTextColor(if (w.minimized && !Themes.current.bevelled) Palette.TASKBAR_TEXT_DIM else Palette.TASKBAR_TEXT)
            if (isFront && Themes.current.titleBold) setTypeface(typeface, Typeface.BOLD)
            textSize = 13f
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dpi(160)
            setPadding(dpi(8), 0, 0, 0)
        }
        item.addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        item.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT).apply {
            marginStart = if (Themes.current.startLabel) dpi(4) else dpi(2)
        }
        return item
    }

    /** Minimize or restore, maximize or normal size, close: above the window's button. */
    private fun showWindowMenu(button: View, w: DesktopWindow) {
        onWindowButton()
        val at = IntArray(2).also { button.getLocationOnScreen(it) }
        val entries = windowActions(w.minimized, w.mode == DesktopWindow.Mode.MAXIMIZED, w.resizable).map { action ->
            when (action) {
                WindowAction.RESTORE -> MenuEntry(context.getString(R.string.action_restore), R.drawable.ic_win_restore) { area.activate(w) }
                WindowAction.MINIMIZE -> MenuEntry(context.getString(R.string.action_minimize), R.drawable.ic_win_minimize) { area.minimize(w) }
                WindowAction.MAXIMIZE -> MenuEntry(context.getString(R.string.action_maximize), R.drawable.ic_win_maximize) {
                    area.activate(w)
                    area.toggleMaximize(w)
                }
                WindowAction.NORMAL_SIZE -> MenuEntry(context.getString(R.string.action_normal_size), R.drawable.ic_win_restore) {
                    area.activate(w)
                    area.toggleMaximize(w)
                }
                WindowAction.CLOSE -> MenuEntry(context.getString(R.string.action_close), R.drawable.ic_win_close) { w.requestClose() }
            }
        }
        // The close entry apart from the others, as in a window's own menu.
        showPopupMenu(button, at[0].toFloat(), at[1].toFloat(), entries.dropLast(1) + null + entries.last())
    }

    private fun iconButton(icon: Int, desc: Int, tint: Int? = null, onClick: (View) -> Unit) = ImageButton(context).apply {
        setThemedIcon(icon, tint)
        contentDescription = context.getString(desc)
        tooltipText = contentDescription
        scaleType = ImageView.ScaleType.CENTER
        background = Themes.current.barButton(context)
        isFocusable = false
        setOnClickListener(onClick)
    }
}
