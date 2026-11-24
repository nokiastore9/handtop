package org.handtop.desktop.term

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.TextView
import org.handtop.desktop.ui.Palette
import org.handtop.desktop.ui.dpi
import org.handtop.desktop.ui.selectableBackground

/**
 * Two rows of keys missing from phone keyboards (Esc, Tab, Ctrl, Alt, arrows...).
 * Shown with the terminal window's title bar toggle.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class ExtraKeysBar(context: Context, private val listener: Listener) : LinearLayout(context) {

    enum class Modifier { CTRL, ALT }

    /** A key either sends an Android key code, types text, or toggles a modifier. */
    data class Key(
        val label: String,
        val keyCode: Int = 0,
        val text: String? = null,
        val modifier: Modifier? = null,
        val repeat: Boolean = false,
    )

    fun interface Listener {
        fun onExtraKey(key: Key)
    }

    private val rows = listOf(
        listOf(
            Key("ESC", KeyEvent.KEYCODE_ESCAPE),
            Key("/", text = "/"),
            Key("-", text = "-"),
            Key("HOME", KeyEvent.KEYCODE_MOVE_HOME),
            Key("↑", KeyEvent.KEYCODE_DPAD_UP, repeat = true),
            Key("END", KeyEvent.KEYCODE_MOVE_END),
            Key("PGUP", KeyEvent.KEYCODE_PAGE_UP, repeat = true),
        ),
        listOf(
            Key("TAB", KeyEvent.KEYCODE_TAB),
            Key("CTRL", modifier = Modifier.CTRL),
            Key("ALT", modifier = Modifier.ALT),
            Key("←", KeyEvent.KEYCODE_DPAD_LEFT, repeat = true),
            Key("↓", KeyEvent.KEYCODE_DPAD_DOWN, repeat = true),
            Key("→", KeyEvent.KEYCODE_DPAD_RIGHT, repeat = true),
            Key("PGDN", KeyEvent.KEYCODE_PAGE_DOWN, repeat = true),
        ),
    )

    private val active = HashSet<Modifier>()
    private val modifierViews = HashMap<Modifier, TextView>()
    private val handler = Handler(Looper.getMainLooper())

    init {
        orientation = VERTICAL
        setBackgroundColor(Palette.KEY_BAR_BG)
        for (row in rows) {
            val rowView = LinearLayout(context).apply { orientation = HORIZONTAL }
            for (key in row) rowView.addView(makeKey(key), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(rowView, LayoutParams(LayoutParams.MATCH_PARENT, dpi(38)))
        }
    }

    /** Returns whether the modifier was latched, and releases it (one-shot, like Termux). */
    fun consume(modifier: Modifier): Boolean {
        if (!active.remove(modifier)) return false
        refresh()
        return true
    }

    private fun refresh() {
        for ((mod, view) in modifierViews) {
            view.setTextColor(if (mod in active) Palette.KEY_ON else Palette.TEXT)
        }
    }

    private fun makeKey(key: Key) = TextView(context).apply {
        text = key.label
        gravity = Gravity.CENTER
        textSize = 13f
        setTextColor(Palette.TEXT)
        background = context.selectableBackground()
        // Never steal focus from the terminal, otherwise the soft keyboard disconnects.
        isFocusable = false
        if (key.modifier != null) modifierViews[key.modifier] = this

        var repeater: Runnable? = null
        setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    fire(key)
                    if (key.repeat) {
                        val r = object : Runnable {
                            override fun run() {
                                fire(key)
                                handler.postDelayed(this, REPEAT_INTERVAL_MS)
                            }
                        }
                        repeater = r
                        handler.postDelayed(r, REPEAT_DELAY_MS)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    repeater?.let { handler.removeCallbacks(it) }
                    repeater = null
                }
            }
            true
        }
    }

    private fun fire(key: Key) {
        val mod = key.modifier
        if (mod != null) {
            if (!active.remove(mod)) active.add(mod)
            refresh()
        } else {
            listener.onExtraKey(key)
        }
    }

    private companion object {
        const val REPEAT_DELAY_MS = 400L
        const val REPEAT_INTERVAL_MS = 60L
    }
}
