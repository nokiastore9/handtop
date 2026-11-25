package org.handtop.desktop.shell

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.handtop.desktop.R
import org.handtop.desktop.linux.LinuxApp
import org.handtop.desktop.linux.LinuxApps
import org.handtop.desktop.linux.LinuxEnv
import org.handtop.desktop.ui.MenuEntry
import org.handtop.desktop.ui.Palette
import org.handtop.desktop.ui.styleMenuText
import org.handtop.desktop.ui.styleField
import org.handtop.desktop.ui.pushButton
import org.handtop.desktop.ui.bevel
import org.handtop.desktop.ui.Themes
import org.handtop.desktop.ui.Bevel
import org.handtop.desktop.ui.dp
import org.handtop.desktop.ui.dpi
import org.handtop.desktop.ui.roundedRect
import org.handtop.desktop.ui.selectableBackground
import org.handtop.desktop.ui.showPopupMenu
import java.util.Locale

/**
 * The Linux programs, full screen over the desktop (as macOS Launchpad or GNOME's app grid):
 * a search box and a grid of big icons, in alphabetical order. A tap starts a program and
 * closes the grid; a long press or a right click offers to put it on the desktop. While
 * [picking] (the desktop's "Add a Linux program"), a tap does that instead. A tap on empty
 * space, Back or Escape closes it; Enter in the search box starts the first program found.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class Launchpad(
    context: Context,
    private val launch: (LinuxApp) -> Unit,
    private val addToDesktop: (LinuxApp) -> Unit,
    private val openLinuxSetup: () -> Unit,
    /** Opened or closed (the desktop blurs what is behind it). */
    private val onShown: (Boolean) -> Unit,
) : FrameLayout(context) {

    val isOpen get() = visibility == VISIBLE && !closing

    private val main = Handler(Looper.getMainLooper())
    private val search = EditText(context)
    private val hint = TextView(context)
    private val grid = AppGrid()
    private val scroll = ScrollView(context)
    private val message = LinearLayout(context)
    private val iconPx = dpi(56)
    private var apps: List<LinuxApp> = emptyList()
    private var shown: List<LinuxApp> = emptyList()
    private var picking = false
    private var closing = false
    private var rawX = 0f
    private var rawY = 0f

    init {
        visibility = GONE
        isClickable = true // touches do not fall through to the desktop
        isFocusable = true
        isFocusableInTouchMode = true
        // Over a blurred desktop (Android 12+) a light veil is enough and lets its colours
        // through; over a sharp one, the icons need a darker one to stand out.
        setBackgroundColor(
            when {
                // Classic: an opaque gray surface, nothing shows through.
                Themes.current.bevelled -> Palette.WINDOW_BG
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> 0x990B1023.toInt()
                else -> 0xD90B1023.toInt()
            },
        )

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpi(16), dpi(22), dpi(16), 0)
        }
        search.apply {
            setHint(R.string.launchpad_search)
            setSingleLine()
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Palette.TEXT)
            setHintTextColor(Palette.TEXT_DIM)
            background = roundedRect(0x26FFFFFF, dp(20f))
            setPadding(dpi(16), dpi(9), dpi(16), dpi(9))
            // The keyboard stays below the grid: no full-screen text box in landscape.
            styleField()
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            setOnEditorActionListener { _, _, event ->
                // A hardware Enter comes here twice, pressed and released: start one program.
                if (event == null || event.action == KeyEvent.ACTION_DOWN) shown.firstOrNull()?.let { tapped(it) }
                true
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) = render()
            })
        }
        column.addView(search, LinearLayout.LayoutParams(dpi(360), LinearLayout.LayoutParams.WRAP_CONTENT))
        hint.apply {
            setText(R.string.apps_pick_hint)
            textSize = 14f
            setTextColor(Palette.ACCENT)
            gravity = Gravity.CENTER
            setPadding(0, dpi(10), 0, 0)
        }
        column.addView(hint)
        message.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpi(16), dpi(40), dpi(16), 0)
        }
        column.addView(message, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        scroll.apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(0, dpi(18), 0, dpi(24))
            addView(grid)
        }
        column.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** Shows the grid; with [pick], a tap puts a program on the desktop instead of starting it. */
    fun open(pick: Boolean) {
        picking = pick
        hint.visibility = if (pick) VISIBLE else GONE
        closing = false
        animate().cancel()
        search.setText("")
        visibility = VISIBLE
        if (Themes.current.animations) {
            alpha = 0f
            scaleX = 1.04f
            scaleY = 1.04f
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start()
        } else {
            alpha = 1f
            scaleX = 1f
            scaleY = 1f
        }
        scroll.scrollTo(0, 0)
        // A hardware keyboard types into the search box at once; on a touch screen the
        // keyboard stays down until the box is tapped.
        if (context.resources.configuration.keyboard == android.content.res.Configuration.KEYBOARD_QWERTY) search.requestFocus()
        else requestFocus()
        onShown(true)
        load()
    }

    fun close() {
        if (visibility != VISIBLE || closing) return
        closing = true
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
        if (Themes.current.animations) {
            animate().alpha(0f).scaleX(1.04f).scaleY(1.04f).setDuration(120).withEndAction {
                visibility = GONE
                closing = false
            }.start()
        } else {
            visibility = GONE
            closing = false
        }
        onShown(false)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_UP) close()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ---- the programs ----

    private fun load() {
        if (!LinuxEnv.isInstalled(context)) {
            showMessage(context.getString(R.string.apps_not_installed), withSetup = true)
            return
        }
        if (apps.isEmpty()) showMessage(context.getString(R.string.apps_loading)) else render()
        val app = context.applicationContext
        Thread({
            val found = LinuxApps.scan(app)
            found.forEach { LinuxApps.icon(it, iconPx) } // decoded here, not on the main thread
            main.post {
                apps = found
                render()
            }
        }, "launchpad-scan").start()
    }

    private fun render() {
        if (!LinuxEnv.isInstalled(context)) return
        if (apps.isEmpty()) {
            showMessage(context.getString(R.string.apps_empty), example = true)
            return
        }
        val query = search.text.toString().trim().lowercase(Locale.getDefault())
        shown = if (query.isEmpty()) apps else apps.filter { app -> query.split(' ').all { it in app.searchText } }
        message.visibility = GONE
        scroll.visibility = VISIBLE
        grid.removeAllViews()
        shown.forEach { grid.addView(tile(it)) }
        if (shown.isEmpty()) showMessage(context.getString(R.string.launchpad_nothing, search.text.toString().trim()))
    }

    private fun showMessage(text: String, withSetup: Boolean = false, example: Boolean = false) {
        scroll.visibility = GONE
        message.visibility = VISIBLE
        message.removeAllViews()
        message.addView(TextView(context).apply {
            this.text = text
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Palette.TEXT_SOFT)
            setLineSpacing(0f, 1.15f)
        })
        if (example) message.addView(TextView(context).apply {
            setText(R.string.apps_example)
            typeface = Typeface.MONOSPACE
            textSize = 14f
            setTextColor(Palette.TEXT)
            setTextIsSelectable(true)
            setPadding(0, dpi(10), 0, 0)
        })
        if (withSetup) message.addView(context.pushButton(context.getString(R.string.settings_linux_manage)) {
            close()
            openLinuxSetup()
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dpi(12)
        })
    }

    private fun tapped(app: LinuxApp) {
        if (!isOpen) return // already started, the grid is going away
        if (picking) addToDesktop(app) else launch(app)
        close()
    }

    private fun tile(app: LinuxApp): View {
        val tile = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpi(6), dpi(10), dpi(6), dpi(6))
            // Classic: a pressed tile turns navy with white text, as a chosen list row does.
            background = if (Themes.current.bevelled) Themes.current.menuItemBackground(context) else context.selectableBackground()
            contentDescription = app.name
            setOnClickListener { tapped(app) }
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    rawX = e.rawX
                    rawY = e.rawY
                    if (e.buttonState and MotionEvent.BUTTON_SECONDARY != 0) {
                        menu(app, e.rawX, e.rawY)
                        return@setOnTouchListener true
                    }
                }
                false
            }
            setOnLongClickListener {
                menu(app, rawX, rawY)
                true
            }
        }
        val icon = LinuxApps.icon(app, iconPx)
        tile.addView(if (icon != null) ImageView(context).apply {
            setImageBitmap(icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
        } else noIcon(app), LinearLayout.LayoutParams(iconPx, iconPx))
        tile.addView(TextView(context).apply {
            text = app.name
            textSize = 13f
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            if (Themes.current.bevelled) styleMenuText(enabled = true)
            else {
                setTextColor(Palette.TEXT)
                setShadowLayer(dp(3f), 0f, dp(1f), Color.BLACK)
            }
            setPadding(0, dpi(8), 0, 0)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return tile
    }

    /** For a program that ships no icon: a plain program window (a terminal for terminal programs). */
    private fun noIcon(app: LinuxApp) = FrameLayout(context).apply {
        background = if (Themes.current.bevelled) context.bevel(Bevel.RAISED) else roundedRect(0x26FFFFFF, dp(14f))
        addView(ImageView(context).apply {
            setImageResource(if (app.terminal) R.drawable.ic_terminal else R.drawable.ic_app_generic)
            setColorFilter(Palette.TEXT_SOFT)
        }, LayoutParams(iconPx * 3 / 5, iconPx * 3 / 5, Gravity.CENTER))
    }

    private fun menu(app: LinuxApp, x: Float, y: Float) {
        showPopupMenu(this, x, y, listOf(
            MenuEntry(context.getString(R.string.shortcut_open), R.drawable.ic_linux) {
                launch(app)
                close()
            },
            MenuEntry(context.getString(R.string.shortcut_add), R.drawable.ic_home) { addToDesktop(app) },
        ))
    }

    /**
     * The tiles, as many columns as fit, centred. A tap on the empty part of the grid closes
     * the launchpad, as a tap beside the icons does.
     */
    private inner class AppGrid : ViewGroup(context) {
        private val cellWidth = dpi(116)
        private val cellHeight = dpi(120)
        private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                close()
                return true
            }
        })

        override fun onTouchEvent(event: MotionEvent) = taps.onTouchEvent(event)

        private fun columns(width: Int) = (width / cellWidth).coerceAtLeast(1)

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val cell = { size: Int -> MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY) }
            for (i in 0 until childCount) getChildAt(i).measure(cell(cellWidth), cell(cellHeight))
            val rows = (childCount + columns(width) - 1) / columns(width)
            // At least the visible height, so a tap below the last row still closes.
            val minHeight = (parent as? View)?.height ?: 0
            setMeasuredDimension(width, maxOf(rows * cellHeight, minHeight - scroll.paddingTop - scroll.paddingBottom))
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val width = r - l
            val columns = columns(width)
            val used = minOf(columns, childCount) * cellWidth
            val left = (width - used) / 2
            for (i in 0 until childCount) {
                val x = left + (i % columns) * cellWidth
                val y = (i / columns) * cellHeight
                getChildAt(i).layout(x, y, x + cellWidth, y + cellHeight)
            }
        }
    }

    // A tap on the backdrop (around the search box) closes too.
    private val backdropTaps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            close()
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        backdropTaps.onTouchEvent(event)
        return true
    }
}
