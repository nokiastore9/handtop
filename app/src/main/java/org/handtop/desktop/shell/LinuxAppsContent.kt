package org.handtop.desktop.shell

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
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
import org.handtop.desktop.ui.dp
import org.handtop.desktop.ui.dpi
import org.handtop.desktop.ui.roundedRect
import org.handtop.desktop.ui.selectableBackground
import org.handtop.desktop.ui.showPopupMenu
import org.handtop.desktop.wm.WindowContent
import org.handtop.desktop.wm.WindowHost
import java.util.Locale

/**
 * The Linux apps menu: installed graphical programs with icons and a search box. A long press
 * or a right click on a program offers to put it on the desktop; while [picking], a tap does
 * that instead of starting it (the desktop's "Add a Linux program").
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class LinuxAppsContent(
    context: Context,
    private val launch: (LinuxApp) -> Unit,
    private val openLinuxSetup: () -> Unit,
    private val addToDesktop: (LinuxApp) -> Unit,
) : LinearLayout(context), WindowContent {

    /** A tap puts the program on the desktop (and closes the menu) instead of starting it. */
    var picking = false
        set(value) {
            field = value
            pickHint.visibility = if (value) VISIBLE else GONE
        }

    override val view: View get() = this

    private var host: WindowHost? = null
    private val main = Handler(Looper.getMainLooper())
    private val search = EditText(context)
    private val list = LinearLayout(context).apply { orientation = VERTICAL }
    private var apps: List<LinuxApp> = emptyList()
    private val iconPx = dpi(32)
    private val pickHint = TextView(context)
    private var rawX = 0f
    private var rawY = 0f

    init {
        orientation = VERTICAL
        setBackgroundColor(0xFF0F1420.toInt())
        setPadding(dpi(14), dpi(10), dpi(14), dpi(6))

        search.apply {
            setHint(R.string.apps_search)
            setSingleLine()
            textSize = 15f
            setTextColor(Palette.TEXT)
            setHintTextColor(Palette.TEXT_DIM)
            background = roundedRect(Palette.CARD, dp(10f))
            setPadding(dpi(12), dpi(8), dpi(12), dpi(8))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) = render()
            })
        }
        addView(search, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dpi(8) })
        pickHint.apply {
            setText(R.string.apps_pick_hint)
            textSize = 14f
            setTextColor(Palette.ACCENT)
            setPadding(dpi(4), 0, dpi(4), dpi(8))
            visibility = GONE
        }
        addView(pickHint, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(ScrollView(context).apply { addView(list) }, LayoutParams(MATCH_PARENT, 0, 1f))
        // Filled in onActivated(), which also runs when the window opens.
    }

    override fun attach(host: WindowHost) {
        this.host = host
    }

    override fun onActivated() = load() // apps may have been installed meanwhile

    private fun load() {
        if (!LinuxEnv.isInstalled(context)) {
            search.visibility = GONE
            list.removeAllViews()
            list.addView(message(context.getString(R.string.apps_not_installed)))
            list.addView(Button(context).apply {
                isAllCaps = false
                setText(R.string.settings_linux_manage)
                setOnClickListener { openLinuxSetup() }
            }, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            return
        }
        search.visibility = VISIBLE
        if (apps.isEmpty()) {
            list.removeAllViews()
            list.addView(message(context.getString(R.string.apps_loading)))
        }
        val appContext = context.applicationContext
        Thread({
            val found = LinuxApps.scan(appContext)
            found.forEach { LinuxApps.icon(it, iconPx) } // decode icons off the main thread
            main.post {
                apps = found
                render()
            }
        }, "linux-apps-scan").start()
    }

    private fun render() {
        if (!LinuxEnv.isInstalled(context)) return
        list.removeAllViews()
        if (apps.isEmpty()) {
            list.addView(message(context.getString(R.string.apps_empty)))
            list.addView(TextView(context).apply {
                text = context.getString(R.string.apps_example)
                typeface = Typeface.MONOSPACE
                textSize = 14f
                setTextColor(Palette.TEXT)
                setTextIsSelectable(true)
                setPadding(0, dpi(6), 0, 0)
            })
            return
        }
        val query = search.text.toString().trim().lowercase(Locale.getDefault())
        val shown = if (query.isEmpty()) apps else apps.filter { app -> query.split(' ').all { it in app.searchText } }
        for (app in shown) list.addView(row(app))
    }

    private fun row(app: LinuxApp): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dpi(8), dpi(6), dpi(8), dpi(6))
        background = context.selectableBackground()
        setOnClickListener {
            if (picking) addToDesktop(app) else launch(app)
            host?.close()
        }
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
        addView(ImageView(context).apply {
            val icon = LinuxApps.icon(app, iconPx)
            if (icon != null) setImageBitmap(icon) else {
                setImageResource(if (app.terminal) R.drawable.ic_terminal else R.drawable.ic_apps)
                setColorFilter(Palette.TEXT_DIM)
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LayoutParams(iconPx, iconPx).apply { marginEnd = dpi(12) })
        val texts = LinearLayout(context).apply { orientation = VERTICAL }
        texts.addView(TextView(context).apply {
            text = app.name
            textSize = 15f
            setTextColor(Palette.TEXT)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        })
        app.comment?.let { comment ->
            texts.addView(TextView(context).apply {
                text = comment
                textSize = 12f
                setTextColor(Palette.TEXT_DIM)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            })
        }
        addView(texts, LayoutParams(0, WRAP_CONTENT, 1f))
        layoutParams = LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    private fun menu(app: LinuxApp, x: Float, y: Float) {
        showPopupMenu(this, x, y, listOf(
            MenuEntry(context.getString(R.string.shortcut_open), R.drawable.ic_linux) {
                launch(app)
                host?.close()
            },
            MenuEntry(context.getString(R.string.shortcut_add), R.drawable.ic_home) { addToDesktop(app) },
        ))
    }

    private fun message(text: String) = TextView(context).apply {
        this.text = text
        textSize = 14f
        setTextColor(Palette.TEXT_DIM)
        setLineSpacing(0f, 1.15f)
        setPadding(dpi(4), dpi(12), dpi(4), dpi(4))
    }
}
