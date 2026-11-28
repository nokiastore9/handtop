package org.handtop.desktop.wayland

import kotlin.math.max
import kotlin.math.min

/**
 * The text around a program's caret, as it said with zwp_text_input_v3.set_surrounding_text.
 * The protocol counts UTF-8 bytes, Android's keyboards count UTF-16 chars: this translates,
 * and never splits a character in two.
 */
class SurroundingText(val text: String, cursorByte: Int, anchorByte: Int) {

    /** The caret and the other end of the selection, as char indices into [text]. */
    val cursor = charIndex(text, cursorByte)
    val anchor = charIndex(text, anchorByte)
    val selectionStart get() = min(cursor, anchor)
    val selectionEnd get() = max(cursor, anchor)

    /** Up to [n] chars before the selection (one more when a pair would be split). */
    fun before(n: Int): String = text.substring(startBack(selectionStart, n), selectionStart)

    /** Up to [n] chars after the selection. */
    fun after(n: Int): String = text.substring(selectionEnd, endForward(selectionEnd, n))

    fun selected(): String = text.substring(selectionStart, selectionEnd)

    /** UTF-8 byte counts for deleting [before] chars before the selection and [after] after it. */
    fun deleteBytes(before: Int, after: Int): Pair<Int, Int> =
        utf8Length(before(before)) to utf8Length(after(after))

    /**
     * The chars [start] to [end] taken back into composition (a keyboard editing a word it
     * already wrote): the bytes to delete around the caret and the text to compose instead.
     * Null when the caret is not inside, text is selected or the region is not in the text.
     */
    fun region(start: Int, end: Int): Region? {
        if (cursor != anchor || start > end || start < 0 || end > text.length) return null
        if (cursor < start || cursor > end) return null
        if (splits(start) || splits(end)) return null
        return Region(
            beforeBytes = utf8Length(text.substring(start, cursor)),
            afterBytes = utf8Length(text.substring(cursor, end)),
            text = text.substring(start, end),
        )
    }

    data class Region(val beforeBytes: Int, val afterBytes: Int, val text: String)

    private fun splits(at: Int) =
        at > 0 && at < text.length && Character.isLowSurrogate(text[at]) && Character.isHighSurrogate(text[at - 1])

    private fun startBack(from: Int, n: Int): Int {
        var start = max(0, from - max(0, n))
        if (splits(start)) start--
        return start
    }

    private fun endForward(from: Int, n: Int): Int {
        var end = min(text.length, from + max(0, n))
        if (splits(end)) end++
        return end
    }

    companion object {
        /** The char index of UTF-8 byte offset [byte] in [text], rounded down to a whole character. */
        fun charIndex(text: String, byte: Int): Int {
            if (byte <= 0) return 0
            var bytes = 0
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                val size = utf8Size(cp)
                if (bytes + size > byte) return i
                bytes += size
                i += Character.charCount(cp)
            }
            return text.length
        }

        fun utf8Length(s: CharSequence): Int {
            var bytes = 0
            var i = 0
            while (i < s.length) {
                val cp = Character.codePointAt(s, i)
                bytes += utf8Size(cp)
                i += Character.charCount(cp)
            }
            return bytes
        }

        private fun utf8Size(cp: Int) = when {
            cp < 0x80 -> 1
            cp < 0x800 -> 2
            cp < 0x10000 -> 3
            else -> 4
        }
    }
}
