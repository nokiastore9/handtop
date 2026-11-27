package org.handtop.desktop.wayland

import android.text.InputType
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo

/**
 * zwp_text_input_v3: how a Linux program takes text from an input method — here Android's
 * keyboard. The keyboard's text reaches the program as committed / preedit strings, so any
 * language works (wl_keyboard alone only carries keys of the US layout).
 */
class TextInputs(private val server: WaylandServer) {

    private val inputs = ArrayList<TextInput>()
    private val listeners = ArrayList<() -> Unit>()
    private val commitListeners = ArrayList<() -> Unit>()

    fun add(input: TextInput) {
        inputs += input
        val focus = server.seat.keyboardFocus
        if (focus != null && focus.client === input.client) input.enter(focus)
    }

    fun remove(input: TextInput) {
        inputs -= input
        changed()
    }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    /** Called on every commit of any text input, also when nothing the keyboard needs changed. */
    fun addCommitListener(listener: () -> Unit) {
        commitListeners += listener
    }

    fun removeCommitListener(listener: () -> Unit) {
        commitListeners -= listener
    }

    /** The text input of the focused surface, if its program enabled it (a text field has focus). */
    fun active(): TextInput? {
        val focus = server.seat.keyboardFocus ?: return null
        return inputs.lastOrNull { it.client === focus.client && it.focused === focus && it.enabled }
    }

    /** Keyboard focus moved: text inputs follow it (leave the old surface, enter the new one). */
    fun focusChanged(old: WlSurface?, new: WlSurface?) {
        if (old != null) inputs.filter { it.focused === old }.forEach { it.leave(old) }
        if (new != null) inputs.filter { it.client === new.client }.forEach { it.enter(new) }
        changed()
    }

    /** Enabled state or content type changed: the keyboard may have to appear or change. */
    fun changed() = listeners.toList().forEach { it() }

    /** A program committed its text input state (programs do on every caret move). */
    fun committed() = commitListeners.toList().forEach { it() }
}

class TextInputManager(client: Client, id: Int) : Resource(client, id, 1) {
    override val iface = "zwp_text_input_manager_v3"

    override fun dispatch(opcode: Int, r: MessageReader) {
        when (opcode) {
            0 -> destroy()
            1 -> { // get_text_input(id, seat)
                val newId = r.newId()
                r.obj()
                client.server.textInputs.add(client.register(TextInput(client, newId)))
            }
            else -> super.dispatch(opcode, r)
        }
    }
}

class TextInput(client: Client, id: Int) : Resource(client, id, 1) {
    override val iface = "zwp_text_input_v3"

    /** The surface this text input has entered (has keyboard focus). */
    var focused: WlSurface? = null
        private set
    var enabled = false
        private set
    var contentHint = 0
        private set
    var contentPurpose = 0
        private set

    /** The caret the program last committed, in its focused surface's coordinates (height 0: none). */
    var cursorY = 0
        private set
    var cursorHeight = 0
        private set

    private var pendingEnabled = false
    private var pendingHint = 0
    private var pendingPurpose = 0
    private var pendingCursorY = 0
    private var pendingCursorHeight = 0
    /** Number of commit requests received; echoed in done so the client can match state. */
    var commits = 0
        private set

    override fun dispatch(opcode: Int, r: MessageReader) {
        when (opcode) {
            0 -> destroy()
            1 -> { // enable: also resets the pending state
                pendingEnabled = true
                pendingHint = 0
                pendingPurpose = 0
            }
            2 -> pendingEnabled = false // disable
            3 -> { r.string(); r.int(); r.int() } // set_surrounding_text (keyboards get no context yet)
            4 -> r.uint() // set_text_change_cause
            5 -> { pendingHint = r.uint(); pendingPurpose = r.uint() } // set_content_type
            6 -> { // set_cursor_rectangle(x, y, width, height)
                r.int()
                pendingCursorY = r.int()
                r.int()
                pendingCursorHeight = r.int()
            }
            7 -> commit()
            else -> super.dispatch(opcode, r)
        }
    }

    private fun commit() {
        commits++
        val changed = enabled != pendingEnabled || contentHint != pendingHint || contentPurpose != pendingPurpose
        enabled = pendingEnabled
        contentHint = pendingHint
        contentPurpose = pendingPurpose
        cursorY = pendingCursorY
        cursorHeight = pendingCursorHeight
        // Only real changes reach the keyboard (programs commit on every cursor move).
        if (changed) client.server.textInputs.changed()
        client.server.textInputs.committed()
    }

    fun enter(surface: WlSurface) {
        if (focused === surface) return
        focused = surface
        event(0) { obj(surface.id) }
        client.flush()
    }

    fun leave(surface: WlSurface) {
        if (focused !== surface) return
        event(1) { obj(surface.id) }
        client.flush()
        focused = null
        enabled = false // disabled with the focus; the program enables again on enter
    }

    /** Final text from the keyboard (replaces any preedit). */
    fun commitString(text: String) {
        event(3) { string(text) }
        done()
    }

    /** Text being composed (shown underlined by the program), cursor at its end. */
    fun preedit(text: String) {
        val end = text.toByteArray().size // positions are UTF-8 byte offsets
        event(2) { string(text); int(end); int(end) }
        done()
    }

    private fun done() {
        event(5) { uint(commits) }
        client.flush()
    }

    override fun destroyed() = client.server.textInputs.remove(this)

    companion object {
        // content_purpose
        private const val PURPOSE_DIGITS = 2
        private const val PURPOSE_NUMBER = 3
        private const val PURPOSE_PHONE = 4
        private const val PURPOSE_URL = 5
        private const val PURPOSE_EMAIL = 6
        private const val PURPOSE_NAME = 7
        private const val PURPOSE_PASSWORD = 8
        private const val PURPOSE_PIN = 9
        private const val PURPOSE_TERMINAL = 13
        // content_hint
        private const val HINT_AUTO_CAPITALIZATION = 0x4
        private const val HINT_HIDDEN_TEXT = 0x40
        private const val HINT_MULTILINE = 0x200

        /** Android input type for what the program said its text field holds. */
        fun inputTypeFor(input: TextInput?): Int {
            if (input == null) {
                // No text input: keys only. Ask the keyboard for plain keys, no composing.
                return InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            val hint = input.contentHint
            return when (input.contentPurpose) {
                PURPOSE_DIGITS, PURPOSE_NUMBER -> InputType.TYPE_CLASS_NUMBER
                PURPOSE_PIN -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                PURPOSE_PHONE -> InputType.TYPE_CLASS_PHONE
                PURPOSE_URL -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                PURPOSE_EMAIL -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                PURPOSE_NAME -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME or
                    InputType.TYPE_TEXT_FLAG_CAP_WORDS
                PURPOSE_PASSWORD -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                PURPOSE_TERMINAL -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                else -> {
                    var type = InputType.TYPE_CLASS_TEXT
                    if (hint and HINT_HIDDEN_TEXT != 0) type = type or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    if (hint and HINT_MULTILINE != 0) type = type or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    if (hint and HINT_AUTO_CAPITALIZATION != 0) type = type or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    type
                }
            }
        }
    }
}

/**
 * Android keyboard → Linux program. With an enabled text input, text and compositions go
 * through it (any language, word predictions); without one, text is typed as US-layout keys.
 */
class WaylandInputConnection(view: View, private val server: WaylandServer) : BaseInputConnection(view, false) {

    private var composing: String? = null
    private val active get() = server.textInputs.active()

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        composing = null
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

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        repeat(beforeLength) { tap(KeyEvent.KEYCODE_DEL) }
        repeat(afterLength) { tap(KeyEvent.KEYCODE_FORWARD_DEL) }
        return true
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        server.seat.key(event)
        return true
    }

    override fun performEditorAction(actionCode: Int): Boolean {
        tap(KeyEvent.KEYCODE_ENTER)
        return true
    }

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = ""
    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""

    private fun tap(keyCode: Int) {
        server.seat.key(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        server.seat.key(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    /** Text as key presses of the virtual keyboard map (letters, digits, punctuation, Enter). */
    private fun typeAsKeys(text: CharSequence) {
        val events = keyMap.getEvents(text.toString().toCharArray()) ?: return
        for (e in events) server.seat.key(e)
    }

    companion object {
        private val keyMap: KeyCharacterMap by lazy { KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD) }

        fun editorInfo(out: EditorInfo, input: TextInput?) {
            out.inputType = TextInput.inputTypeFor(input)
            out.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_ACTION_NONE
        }
    }
}
