package org.handtop.desktop.wayland

import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputMethodManager
import kotlin.math.abs

/**
 * Android keyboard → Linux program. With an enabled text input, text and compositions go
 * through it (any language, word predictions), and the keyboard sees the text around the caret
 * that the program reports (autocorrection, capitals, suggestions). Without one, text is typed
 * as US-layout keys.
 *
 * Positions given to the keyboard are char indices into the program's surrounding text, with
 * the composition (which the program keeps out of that text) at the caret.
 */
class WaylandInputConnection(private val target: View, private val server: WaylandServer) :
    BaseInputConnection(target, false) {

    private var composing: String? = null
    private val active get() = server.textInputs.active()
    private val surrounding get() = active?.surrounding
    private val latch get() = server.keyLatch

    /** The selection and composition last told to the keyboard. */
    private var reported: IntArray? = null

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        composing = null
        if (latch.holdsShortcut()) {
            typeAsKeys(text)
            return true
        }
        val input = active
        if (input != null) input.commitString(text.toString()) else typeAsKeys(text)
        return true
    }

    override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
        composing = text.toString()
        active?.preedit(text.toString()) // without a text input it is typed when finished
        return true
    }

    override fun finishComposingText(): Boolean {
        val text = composing ?: return true
        composing = null
        val input = active
        if (input != null) input.commitString(text) else typeAsKeys(text)
        return true
    }

    /** The keyboard takes a word the program already has back into composition (to correct it). */
    override fun setComposingRegion(start: Int, end: Int): Boolean {
        val input = active ?: return false
        val known = input.surrounding ?: return false
        if (composing != null) return false
        if (start == end) return true
        val region = known.region(minOf(start, end), maxOf(start, end)) ?: return false
        composing = region.text
        input.recompose(region)
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        val input = active
        val known = input?.surrounding
        if (input == null || known == null) {
            repeat(beforeLength) { tap(KeyEvent.KEYCODE_DEL) }
            repeat(afterLength) { tap(KeyEvent.KEYCODE_FORWARD_DEL) }
            return true
        }
        val (before, after) = known.deleteBytes(beforeLength, afterLength)
        if (before > 0 || after > 0) input.deleteSurrounding(before, after, composing)
        return true
    }

    /** The keyboard moved the caret (e.g. sliding on the space bar): arrow keys, the protocol can't place it. */
    override fun setSelection(start: Int, end: Int): Boolean {
        val known = surrounding ?: return false
        if (start != end) return false
        finishComposingText()
        val to = start.coerceIn(0, known.text.length)
        val from = known.cursor
        val steps = known.text.codePointCount(minOf(from, to), maxOf(from, to))
        repeat(abs(steps)) { tap(if (to < from) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT) }
        return true
    }

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence {
        val known = surrounding ?: return ""
        val c = composing ?: return known.before(n)
        return (known.before(n) + c).takeLast(n)
    }

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = surrounding?.after(n) ?: ""

    override fun getSelectedText(flags: Int): CharSequence? = surrounding?.selected()?.takeIf { it.isNotEmpty() }

    override fun getCursorCapsMode(reqModes: Int): Int {
        if (surrounding == null) return super.getCursorCapsMode(reqModes)
        val before = getTextBeforeCursor(CAPS_CONTEXT, 0)
        return TextUtils.getCapsMode(before, before.length, reqModes)
    }

    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? {
        val known = surrounding ?: return null
        val c = composing.orEmpty()
        return ExtractedText().apply {
            text = known.text.substring(0, known.selectionStart) + c + known.text.substring(known.selectionStart)
            startOffset = 0
            partialStartOffset = -1
            partialEndOffset = -1
            selectionStart = known.selectionStart + c.length
            selectionEnd = known.selectionEnd + c.length
        }
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        val meta = latch.metaState()
        if (meta == 0 || KeyEvent.isModifierKey(event.keyCode)) {
            server.seat.key(event)
            return true
        }
        // A key from the keyboard while Ctrl or Alt is latched on HandTop's key row.
        if (event.action == KeyEvent.ACTION_DOWN) {
            server.seat.key(withMeta(event, event.metaState or meta))
        } else {
            server.seat.key(withMeta(event, event.metaState and meta.inv()))
            latch.consume()
        }
        return true
    }

    override fun performEditorAction(actionCode: Int): Boolean {
        tap(KeyEvent.KEYCODE_ENTER)
        return true
    }

    /**
     * The program reported its text (it does after each change and caret move): tell the
     * keyboard where the caret and the composition are, so it can follow.
     */
    fun reportSelection(imm: InputMethodManager) {
        val known = surrounding ?: return
        val c = composing
        val now = if (c == null) {
            intArrayOf(known.selectionStart, known.selectionEnd, -1, -1)
        } else {
            val start = known.selectionStart
            intArrayOf(start + c.length, start + c.length, start, start + c.length)
        }
        if (reported?.contentEquals(now) == true) return
        reported = now
        imm.updateSelection(target, now[0], now[1], now[2], now[3])
    }

    private fun withMeta(e: KeyEvent, meta: Int) =
        KeyEvent(e.downTime, e.eventTime, e.action, e.keyCode, e.repeatCount, meta, e.deviceId, e.scanCode, e.flags, e.source)

    private fun tap(keyCode: Int) {
        val meta = latch.consume()
        val time = SystemClock.uptimeMillis()
        server.seat.key(KeyEvent(time, time, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        server.seat.key(KeyEvent(time, time, KeyEvent.ACTION_UP, keyCode, 0, 0))
    }

    /**
     * Text as key presses of the virtual keyboard map (letters, digits, punctuation, Enter),
     * with Ctrl or Alt from the key row on the first key.
     */
    private fun typeAsKeys(text: CharSequence) {
        val events = keyMap.getEvents(text.toString().toCharArray())
        val meta = latch.consume()
        if (events == null) {
            // Not on the US layout (e.g. Cyrillic): as text, if the program takes it.
            active?.commitString(text.toString())
            return
        }
        var first = meta != 0
        for (e in events) {
            if (KeyEvent.isModifierKey(e.keyCode)) {
                server.seat.key(e)
                continue
            }
            if (first && e.action == KeyEvent.ACTION_DOWN) {
                server.seat.key(withMeta(e, e.metaState or meta))
            } else {
                if (first && e.action == KeyEvent.ACTION_UP) first = false
                server.seat.key(e)
            }
        }
    }

    companion object {
        /** Text before the caret looked at for capitals (a sentence's end is near). */
        private const val CAPS_CONTEXT = 200
        private val keyMap: KeyCharacterMap by lazy { KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD) }

        /**
         * What the keyboard should be: [keysOnly] while Ctrl or Alt is latched, so that a letter
         * comes at once (not as a word being composed) and makes a shortcut.
         */
        fun editorInfo(out: EditorInfo, input: TextInput?, keysOnly: Boolean = false) {
            out.inputType = if (keysOnly) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            } else {
                TextInput.inputTypeFor(input)
            }
            out.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_ACTION_NONE
            val known = input?.surrounding
            if (known != null && !keysOnly) {
                out.initialSelStart = known.selectionStart
                out.initialSelEnd = known.selectionEnd
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    out.setInitialSurroundingText(known.text)
                }
            }
        }
    }
}
