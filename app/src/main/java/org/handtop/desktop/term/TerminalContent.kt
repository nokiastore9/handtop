package org.handtop.desktop.term

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.util.Log
import android.view.ContextMenu
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import org.handtop.desktop.R
import org.handtop.desktop.ui.dpi
import org.handtop.desktop.wm.WindowContent
import org.handtop.desktop.wm.WindowHost

/** A terminal window's content: the terminal view plus the extra-keys bar. */
@SuppressLint("ViewConstructor")
class TerminalContent(
    context: Context,
    val term: TermSessionHost,
    private val openNewTerminal: () -> Unit,
) : LinearLayout(context), WindowContent {

    override val view: View get() = this

    val terminalView = TerminalView(context, null)
    private val extraKeys = ExtraKeysBar(context) { onExtraKey(it) }
    private var host: WindowHost? = null
    private val defaultTitle = term.spec.title
    private val minFontPx = dpi(6)
    private val maxFontPx = dpi(32)
    private var fontPx = dpi(11)

    // ---- WindowContent ----

    override fun attach(host: WindowHost) {
        this.host = host
        onSessionTitleChanged()
    }

    override fun onActivated() {
        terminalView.requestFocus()
    }

    override fun onClosed() {
        term.session.finishIfRunning()
        if (term.ui === this) term.ui = null
        TermSessions.remove(context, term)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateExtraKeysVisibility(newConfig)
    }

    override fun requestKeyboard() {
        terminalView.requestFocus()
        context.getSystemService(InputMethodManager::class.java)?.showSoftInput(terminalView, 0)
    }

    // ---- session events ----

    fun onSessionTitleChanged() {
        val t = term.session.title
        val title = if (t.isNullOrBlank()) defaultTitle else t
        host?.setTitle(if (term.session.isRunning) title else context.getString(R.string.terminal_finished, title))
    }

    fun onSessionFinished() {
        // A clean `exit` closes the window like on a desktop; otherwise keep the output
        // visible until the user presses Enter ("[Process completed - press Enter]").
        if (term.session.exitStatus == 0) host?.close() else onSessionTitleChanged()
    }

    fun paste() {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)
        if (!text.isNullOrEmpty()) term.session.emulator?.paste(text.toString())
    }

    private fun copyAll() {
        val text = term.session.emulator?.screen?.transcriptTextWithFullLinesJoined ?: return
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("terminal", text.trim()))
    }

    private fun changeFontSize(delta: Int) {
        val newSize = (fontPx + delta).coerceIn(minFontPx, maxFontPx)
        if (newSize == fontPx) return
        fontPx = newSize
        terminalView.setTextSize(fontPx)
    }

    private fun updateExtraKeysVisibility(config: Configuration) {
        val hardwareKeyboard = config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
        extraKeys.visibility = if (hardwareKeyboard) View.GONE else View.VISIBLE
    }

    private fun hasHardwareKeyboard() =
        resources.configuration.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO

    private fun buildContextMenu(menu: ContextMenu) {
        menu.add(R.string.action_paste).setOnMenuItemClickListener { paste(); true }
        menu.add(R.string.action_copy_all).setOnMenuItemClickListener { copyAll(); true }
        menu.add(R.string.action_new_window).setOnMenuItemClickListener { openNewTerminal(); true }
        menu.add(R.string.action_reset).setOnMenuItemClickListener {
            term.session.reset()
            terminalView.onScreenUpdated()
            true
        }
    }

    private fun onExtraKey(key: ExtraKeysBar.Key) {
        val session = term.session
        if (!session.isRunning || session.emulator == null) return
        val text = key.text
        if (text != null) {
            // Goes through readControlKey()/readAltKey(), so latched CTRL/ALT apply.
            terminalView.inputCodePoint(TerminalView.KEY_EVENT_SOURCE_VIRTUAL_KEYBOARD, text.codePointAt(0), false, false)
            return
        }
        var mod = 0
        if (extraKeys.consume(ExtraKeysBar.Modifier.CTRL)) mod = mod or KeyHandler.KEYMOD_CTRL
        if (extraKeys.consume(ExtraKeysBar.Modifier.ALT)) mod = mod or KeyHandler.KEYMOD_ALT
        terminalView.handleKeyCode(key.keyCode, mod)
    }

    /** Keyboard shortcuts handled by the terminal window itself. */
    private fun handleShortcut(keyCode: Int, e: KeyEvent): Boolean {
        if (!e.isCtrlPressed) return false
        if (e.isShiftPressed) {
            when (keyCode) {
                KeyEvent.KEYCODE_V -> { paste(); return true }
                KeyEvent.KEYCODE_C -> { copySelectionOrAll(); return true }
                KeyEvent.KEYCODE_N -> { openNewTerminal(); return true }
            }
        }
        when (keyCode) {
            KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_NUMPAD_ADD -> { changeFontSize(dpi(1)); return true }
            KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> { changeFontSize(-dpi(1)); return true }
        }
        return false
    }

    private fun copySelectionOrAll() {
        if (terminalView.isSelectingText) {
            val text = terminalView.selectedText
            terminalView.stopTextSelectionMode()
            if (!text.isNullOrEmpty()) {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("terminal", text))
            }
        } else {
            copyAll()
        }
    }

    private val viewClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float {
            if (scale < 0.9f || scale > 1.1f) {
                changeFontSize(if (scale > 1f) dpi(1) else -dpi(1))
                return 1f
            }
            return scale
        }

        override fun onSingleTapUp(e: MotionEvent?) {
            if (e?.isFromSource(InputDevice.SOURCE_MOUSE) == true) return
            if (!hasHardwareKeyboard()) requestKeyboard()
        }

        override fun shouldBackButtonBeMappedToEscape() = false
        override fun shouldEnforceCharBasedInput() = false
        override fun shouldUseCtrlSpaceWorkaround() = false
        override fun isTerminalViewSelected() = true
        override fun copyModeChanged(copyMode: Boolean) {}

        override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean {
            if (session != null && !session.isRunning) {
                if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) host?.close()
                return true
            }
            return e != null && handleShortcut(keyCode, e)
        }

        override fun onKeyUp(keyCode: Int, e: KeyEvent?) = false
        override fun onLongPress(event: MotionEvent?) = false
        override fun readControlKey() = extraKeys.consume(ExtraKeysBar.Modifier.CTRL)
        override fun readAltKey() = extraKeys.consume(ExtraKeysBar.Modifier.ALT)
        override fun readShiftKey() = false
        override fun readFnKey() = false

        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
            if (session != null && !session.isRunning) {
                if (codePoint == '\r'.code || codePoint == '\n'.code) host?.close()
                return true
            }
            return false
        }

        override fun onEmulatorSet() {
            terminalView.setTerminalCursorBlinkerState(true, true)
        }

        override fun logError(tag: String?, message: String?) { Log.e(tag, message.orEmpty()) }
        override fun logWarn(tag: String?, message: String?) { Log.w(tag, message.orEmpty()) }
        override fun logInfo(tag: String?, message: String?) { Log.i(tag, message.orEmpty()) }
        override fun logDebug(tag: String?, message: String?) { Log.d(tag, message.orEmpty()) }
        override fun logVerbose(tag: String?, message: String?) { Log.v(tag, message.orEmpty()) }
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(tag, message.orEmpty(), e) }
        override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag, "Error", e) }
    }

    // Declared after viewClient on purpose: Kotlin runs initializers top to bottom.
    init {
        orientation = VERTICAL
        setBackgroundColor(Color.BLACK)

        terminalView.setTerminalViewClient(viewClient)
        terminalView.setTextSize(fontPx)
        terminalView.setTerminalCursorBlinkerRate(CURSOR_BLINK_MS)
        terminalView.isFocusable = true
        terminalView.isFocusableInTouchMode = true
        terminalView.setOnCreateContextMenuListener { menu, _, _ -> buildContextMenu(menu) }
        addView(terminalView, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(extraKeys, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        term.ui = this
        // The shell process starts on the first layout, once the terminal knows its size.
        terminalView.attachSession(term.session)
        updateExtraKeysVisibility(resources.configuration)
    }

    private companion object {
        const val CURSOR_BLINK_MS = 600
    }
}
