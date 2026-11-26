package org.handtop.desktop.wayland

import org.handtop.desktop.wayland.PopupRules.Companion.BOTTOM_LEFT
import org.handtop.desktop.wayland.PopupRules.Companion.BOTTOM_RIGHT
import org.handtop.desktop.wayland.PopupRules.Companion.FLIP_Y
import org.handtop.desktop.wayland.PopupRules.Companion.SLIDE_X
import org.handtop.desktop.wayland.PopupRules.Companion.SLIDE_Y
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupRulesTest {

    /** A menu like Mousepad's File menu: 270 x 300 under a button at (5, 40)-(45, 60). */
    private val fileMenu = PopupRules(
        width = 270, height = 300,
        anchorRect = Box(5, 40, 45, 60),
        anchor = BOTTOM_LEFT, gravity = BOTTOM_RIGHT,
        offsetX = 0, offsetY = 0,
        adjustment = SLIDE_X or SLIDE_Y or FLIP_Y,
    )

    /** The desktop below the window's top while the on-screen keyboard covers the rest. */
    private val withKeyboard = Box(0, -10, 1200, 250)
    private val withoutKeyboard = Box(0, -100, 1200, 600)

    @Test
    fun aMenuThatFitsOpensUnderItsButton() =
        assertEquals(Box(5, 60, 275, 360), fileMenu.place(withoutKeyboard))

    @Test
    fun aMenuSlidesUpWhenTheKeyboardLeavesNoRoom() =
        // Taller than the room: it slides up to the top edge and runs past the bottom one.
        assertEquals(Box(5, -10, 275, 290), fileMenu.place(withKeyboard))

    @Test
    fun aMenuSlidUpForTheKeyboardGoesBackUnderItsButtonOnceTheKeyboardHides() {
        val placement = Placement(fileMenu, withKeyboard)
        assertTrue(placement.reconstrain(withoutKeyboard))
        assertEquals(Box(5, 60, 275, 360), placement.position)
    }

    @Test
    fun nothingChangesWhenTheRoomStaysTheSame() {
        val placement = Placement(fileMenu, withoutKeyboard)
        assertFalse(placement.reconstrain(withoutKeyboard))
        assertEquals(Box(5, 60, 275, 360), placement.position)
    }
}
