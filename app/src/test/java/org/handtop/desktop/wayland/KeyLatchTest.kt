package org.handtop.desktop.wayland

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyLatchTest {

    @Test fun aLatchedModifierIsInTheMetaState() {
        val latch = KeyLatch()
        assertEquals(0, latch.metaState())
        latch.toggle(KeyLatch.Mod.CTRL)
        assertTrue(latch.isOn(KeyLatch.Mod.CTRL))
        assertEquals(KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON, latch.metaState())
        latch.toggle(KeyLatch.Mod.SHIFT)
        assertEquals(
            KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON,
            latch.metaState(),
        )
    }

    @Test fun tappingAgainReleases() {
        val latch = KeyLatch()
        latch.toggle(KeyLatch.Mod.ALT)
        latch.toggle(KeyLatch.Mod.ALT)
        assertFalse(latch.isOn(KeyLatch.Mod.ALT))
        assertEquals(0, latch.metaState())
    }

    @Test fun aKeyUsesTheLatchedModifiersOnce() {
        val latch = KeyLatch()
        var changes = 0
        latch.addListener { changes++ }
        latch.toggle(KeyLatch.Mod.CTRL)
        latch.toggle(KeyLatch.Mod.ALT)
        val meta = latch.consume()
        assertEquals(
            KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON,
            meta,
        )
        assertEquals(0, latch.metaState())
        assertEquals(3, changes)
        assertEquals(0, latch.consume())
        assertEquals(3, changes) // nothing was latched: no change
    }
}
