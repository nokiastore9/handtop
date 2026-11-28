package org.handtop.desktop.wayland

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SurroundingTextTest {

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8).size

    @Test fun asciiTextAroundTheCaret() {
        val t = SurroundingText("hello world", 5, 5)
        assertEquals(5, t.cursor)
        assertEquals("llo", t.before(3))
        assertEquals(" wo", t.after(3))
        assertEquals("", t.selected())
        assertEquals("hello", t.before(100))
        assertEquals(" world", t.after(100))
    }

    @Test fun byteOffsetsBecomeCharIndicesForCyrillic() {
        val text = "привет мир"
        val t = SurroundingText(text, bytes("привет"), bytes("привет"))
        assertEquals(6, t.cursor)
        assertEquals("ет", t.before(2))
        assertEquals(" м", t.after(2))
    }

    @Test fun aSurrogatePairIsNeverSplit() {
        val text = "a😀b"
        val t = SurroundingText(text, bytes("a😀"), bytes("a😀"))
        assertEquals(3, t.cursor)
        assertEquals("😀", t.before(1))
        assertEquals(4 to 0, t.deleteBytes(1, 0))
        val before = SurroundingText(text, 1, 1)
        assertEquals("😀", before.after(1))
        assertEquals(0 to 4, before.deleteBytes(0, 1))
    }

    @Test fun aSelectionIsLeftOutOfBeforeAndAfter() {
        val text = "one two three"
        val t = SurroundingText(text, bytes("one two"), bytes("one "))
        assertEquals("two", t.selected())
        assertEquals("one ", t.before(4))
        assertEquals(" thr", t.after(4))
        assertEquals(4 to 4, t.deleteBytes(4, 4))
    }

    @Test fun deletionIsClippedToTheKnownText() {
        val t = SurroundingText("абcd", bytes("аб"), bytes("аб"))
        assertEquals(4 to 2, t.deleteBytes(100, 100))
        assertEquals(0 to 0, t.deleteBytes(0, 0))
    }

    @Test fun offsetsOutsideOrInsideACharacterAreRounded() {
        val text = "жук"
        assertEquals(0, SurroundingText.charIndex(text, -3))
        assertEquals(3, SurroundingText.charIndex(text, 100))
        assertEquals(0, SurroundingText.charIndex(text, 1)) // the middle of «ж»
        assertEquals(1, SurroundingText.charIndex(text, 2))
    }

    @Test fun aWordAroundTheCaretCanBeComposedAgain() {
        val text = "hello world"
        val t = SurroundingText(text, bytes("hello wo"), bytes("hello wo"))
        val region = t.region(6, 11)!!
        assertEquals(2, region.beforeBytes)
        assertEquals(3, region.afterBytes)
        assertEquals("world", region.text)
    }

    @Test fun aCyrillicWordIsCountedInBytes() {
        val text = "мир дом"
        val t = SurroundingText(text, bytes("мир д"), bytes("мир д"))
        val region = t.region(4, 7)!!
        assertEquals(2, region.beforeBytes)
        assertEquals(4, region.afterBytes)
        assertEquals("дом", region.text)
    }

    @Test fun noRegionWhenTheCaretIsOutsideOrTextIsSelected() {
        val text = "hello world"
        assertNull(SurroundingText(text, 2, 2).region(6, 11))
        assertNull(SurroundingText(text, 7, 9).region(6, 11))
        assertNull(SurroundingText(text, 8, 8).region(9, 6))
        assertNull(SurroundingText(text, 8, 8).region(6, 40))
    }
}
