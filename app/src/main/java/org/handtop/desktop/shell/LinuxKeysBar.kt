package org.handtop.desktop.shell

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.TextView
import org.handtop.desktop.ui.Palette
import org.handtop.desktop.ui.styleKey
import org.handtop.desktop.wayland.KeyLatch
import org.handtop.desktop.wayland.WaylandServer

/**
 * One row of keys phone keyboards lack, above the on-screen keyboard while it types into a
 * Linux program. Ctrl, Alt and Shift hold for the next key (from the row or the keyboard).
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class LinuxKeysBar(context: Context, private val server: WaylandServer) : LinearLayout(context) {

    private sealed interface Key { val label: String }
    private data class Press(override val label: String, val keyCode: Int, val repeat: Boolean = false) : Key
    private data class Latch(override val label: String, val mod: KeyLatch.Mod) : Key

    private val keys = listOf(
        Press("ESC", KeyEvent.KEYCODE_ESCAPE),
        Press("TAB", KeyEvent.KEYCODE_TAB),
        Latch("CTRL", KeyLatch.Mod.CTRL),
        Latch("ALT", KeyLatch.Mod.ALT),
        Latch("SHIFT", KeyLatch.Mod.SHIFT),
        Press("←", KeyEvent.KEYCODE_DPAD_LEFT, repeat = true),
        Press("↑", KeyEvent.KEYCODE_DPAD_UP, repeat = true),
        Press("↓", KeyEvent.KEYCODE_DPAD_DOWN, repeat = true),
        Press("→", KeyEvent.KEYCODE_DPAD_RIGHT, repeat = true),
        Press("HOME", KeyEvent.KEYCODE_MOVE_HOME),
        Press("END", KeyEvent.KEYCODE_MOVE_END),
        Press("PGUP", KeyEvent.KEYCODE_PAGE_UP, repeat = true),
        Press("PGDN", KeyEvent.KEYCODE_PAGE_DOWN, repeat = true),
        Press("DEL", KeyEvent.KEYCODE_FORWARD_DEL, repeat = true),
    )

    private val latch get() = server.keyLatch
    private val latchViews = HashMap<KeyLatch.Mod, TextView>()
    private val handler = Handler(Looper.getMainLooper())
    private val latchListener: () -> Unit = { refresh() }

    init {
        orientation = HORIZONTAL
        setBackgroundColor(Palette.KEY_BAR_BG)
        for (key in keys) addView(makeKey(key), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        latch.addListener(latchListener)
        refresh()
    }

    override fun onDetachedFromWindow() {
        latch.removeListener(latchListener)
        super.onDetachedFromWindow()
    }

    private fun refresh() {
        for ((mod, view) in latchViews) view.setTextColor(if (latch.isOn(mod)) Palette.KEY_ON else Palette.TEXT)
    }

    private fun makeKey(key: Key) = TextView(context).apply {
        text = key.label
        gravity = Gravity.CENTER
        textSize = 13f
        setTextColor(Palette.TEXT)
        styleKey()
        // Never take the focus: the on-screen keyboard stays connected to the program.
        isFocusable = false
        if (key is Latch) latchViews[key.mod] = this

        var repeater: Runnable? = null
        setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    when (key) {
                        is Latch -> latch.toggle(key.mod)
                        is Press -> {
                            press(key.keyCode)
                            if (key.repeat) {
                                val r = object : Runnable {
                                    override fun run() {
                                        press(key.keyCode)
                                        handler.postDelayed(this, REPEAT_INTERVAL_MS)
                                    }
                                }
                                repeater = r
                                handler.postDelayed(r, REPEAT_DELAY_MS)
                            }
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    repeater?.let { handler.removeCallbacks(it) }
                    repeater = null
                    // Latched modifiers held for this key (and its repeats): now they let go.
                    if (key is Press) latch.consume()
                }
            }
            true
        }
    }

    /** A key with the latched modifiers; they are released at the key's up, so the program does not keep them. */
    private fun press(keyCode: Int) {
        val time = SystemClock.uptimeMillis()
        server.seat.key(KeyEvent(time, time, KeyEvent.ACTION_DOWN, keyCode, 0, latch.metaState()))
        server.seat.key(KeyEvent(time, time, KeyEvent.ACTION_UP, keyCode, 0, 0))
    }

    companion object {
        private const val REPEAT_DELAY_MS = 400L
        private const val REPEAT_INTERVAL_MS = 60L
        const val KEY_ENABLED = "linux_keys"

        /** Settings → Display: the row may be turned off. */
        fun enabled(context: Context) =
            context.getSharedPreferences(SettingsContent.PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

        fun setEnabled(context: Context, on: Boolean) =
            context.getSharedPreferences(SettingsContent.PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
    }
}
