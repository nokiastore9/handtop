package org.handtop.desktop.shell

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.handtop.desktop.R
import org.handtop.desktop.linux.LinuxApps
import org.handtop.desktop.ui.MenuEntry
import org.handtop.desktop.ui.Palette
import org.handtop.desktop.ui.dp
import org.handtop.desktop.ui.dpi
import org.handtop.desktop.ui.selectableBackground
import org.handtop.desktop.ui.showPopupMenu

/**
 * The shortcuts on the wallpaper, below all windows: in columns, top to bottom and then the
 * next column, so a short phone screen still shows them all. A tap opens a shortcut; a long
 * press or a right click opens its menu, and on the empty wallpaper, the desktop's menu.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class DesktopIcons(
    context: Context,
    private val open: (Shortcut) -> Unit,
    /** The desktop's menu (a long press or a right click on the wallpaper). */
    private val desktopMenu: () -> List<MenuEntry?>,
) : ViewGroup(context) {

    private val cellWidth = dpi(92)
    private val cellHeight = dpi(90)
    private val gap = dpi(4)
    private val iconPx = dpi(40)
    private val listener: () -> Unit = { reload() }

    // Where the last touch went down, for menus opened by a long press.
    private var rawX = 0f
    private var rawY = 0f

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onLongPress(e: MotionEvent) {
            showPopupMenu(this@DesktopIcons, e.rawX, e.rawY, desktopMenu())
        }
    })

    init {
        setPadding(dpi(12), dpi(12), dpi(12), dpi(12))
        reload()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Shortcuts.addListener(listener)
        reload()
    }

    override fun onDetachedFromWindow() {
        Shortcuts.removeListener(listener)
        super.onDetachedFromWindow()
    }

    private fun reload() {
        removeAllViews()
        for (shortcut in Shortcuts.load(context)) addView(item(shortcut))
    }

    // ---- the wallpaper itself ----

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && event.buttonState and MotionEvent.BUTTON_SECONDARY != 0) {
            showPopupMenu(this, event.rawX, event.rawY, desktopMenu())
            return true
        }
        return gestures.onTouchEvent(event)
    }

    // ---- one shortcut ----

    private fun item(shortcut: Shortcut): View {
        val item = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpi(6), dpi(8), dpi(6), dpi(4))
            background = context.selectableBackground()
            setOnClickListener { open(shortcut) }
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    rawX = e.rawX
                    rawY = e.rawY
                    if (e.buttonState and MotionEvent.BUTTON_SECONDARY != 0) {
                        menu(shortcut, e.rawX, e.rawY)
                        return@setOnTouchListener true
                    }
                }
                false
            }
            setOnLongClickListener {
                menu(shortcut, rawX, rawY)
                true
            }
        }
        val icon = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val label = TextView(context).apply {
            setTextColor(Palette.TEXT)
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setShadowLayer(dp(3f), 0f, dp(1f), Color.BLACK)
            setPadding(0, dpi(4), 0, 0)
        }
        when (shortcut) {
            is Shortcut.BuiltIn -> {
                val (drawable, name) = builtIn(shortcut.id)
                icon.setImageResource(drawable)
                label.setText(name)
            }
            is Shortcut.LinuxProgram -> {
                icon.setImageResource(R.drawable.ic_apps)
                icon.setColorFilter(Palette.TEXT_SOFT)
                label.text = shortcut.name
                loadLinuxIcon(shortcut, icon)
            }
            is Shortcut.Folder -> {
                icon.setImageResource(R.drawable.ic_folder)
                icon.setColorFilter(FOLDER_COLOR) // as folders look in Files
                label.text = shortcut.name
            }
        }
        item.contentDescription = label.text
        item.addView(icon, LinearLayout.LayoutParams(iconPx, iconPx))
        item.addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return item
    }

    /** The program's own icon, read from Linux off the main thread. */
    private fun loadLinuxIcon(shortcut: Shortcut.LinuxProgram, view: ImageView) {
        val app = context.applicationContext
        Thread({
            val bitmap = LinuxApps.forAppId(app, shortcut.appId)?.let { LinuxApps.icon(it, iconPx) }
            if (bitmap != null) post {
                view.clearColorFilter()
                view.setImageBitmap(bitmap)
            }
        }, "desktop-icon").start()
    }

    private fun menu(shortcut: Shortcut, x: Float, y: Float) {
        showPopupMenu(this, x, y, listOf(
            MenuEntry(context.getString(R.string.shortcut_open)) { open(shortcut) },
            null,
            MenuEntry(context.getString(R.string.shortcut_remove), tint = Palette.CLOSE_HOVER) { Shortcuts.remove(context, shortcut) },
        ))
    }

    // ---- layout: columns of cells ----

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cell = { size: Int -> MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY) }
        for (i in 0 until childCount) getChildAt(i).measure(cell(cellWidth), cell(cellHeight))
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val rows = ((b - t - paddingTop - paddingBottom + gap) / (cellHeight + gap)).coerceAtLeast(1)
        for (i in 0 until childCount) {
            val x = paddingLeft + (i / rows) * (cellWidth + gap)
            val y = paddingTop + (i % rows) * (cellHeight + gap)
            getChildAt(i).layout(x, y, x + cellWidth, y + cellHeight)
        }
    }

    companion object {
        private const val FOLDER_COLOR = 0xFFFFC857.toInt()

        /** Icon and name of one of HandTop's own shortcuts. */
        fun builtIn(id: String): Pair<Int, Int> = when (id) {
            Shortcuts.LINUX_TERMINAL -> R.drawable.ic_linux to R.string.app_terminal_linux
            Shortcuts.LINUX_APPS -> R.drawable.ic_apps to R.string.app_linux_apps
            Shortcuts.FILES -> R.drawable.ic_files to R.string.app_files
            Shortcuts.ANDROID_TERMINAL -> R.drawable.ic_terminal to R.string.app_terminal_android
            Shortcuts.SETTINGS -> R.drawable.ic_settings to R.string.app_settings
            else -> R.drawable.ic_info to R.string.app_about
        }
    }
}
