package org.handtop.desktop.wm

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The first size of a window that brings no size of its own (Settings, a terminal…). */
object WindowSizing {

    /** Width and height in pixels, for a desktop of [areaWidth] x [areaHeight] pixels. */
    fun defaultSize(areaWidth: Int, areaHeight: Int, density: Float): IntArray {
        fun dp(v: Int) = (v * density).roundToInt()
        val width = max(min(areaWidth, dp(380)), (areaWidth * 0.6f).toInt())
        val share = max(min(areaHeight, dp(260)), (areaHeight * 0.62f).toInt())
        // A short desktop (a phone on its side, ~500 points) would leave a cramped window over a
        // third of empty screen: there windows take the height but a strip, up to 480 points.
        val tall = min(areaHeight - dp(48), dp(480))
        return intArrayOf(width, max(share, tall))
    }

    /**
     * The frame size a program's window takes for the size it draws ([frameWidth] x
     * [frameHeight] pixels, with HandTop's frame): within the desktop ([areaWidth] x
     * [areaHeight], 0 before its first layout) as if the on-screen keyboard did not hide
     * [keyboardCover] of it. The keyboard only hides the window's bottom for a while (the
     * layout fits it above), so the window gets its size back when the keyboard goes.
     */
    fun fitContent(frameWidth: Int, frameHeight: Int, areaWidth: Int, areaHeight: Int, keyboardCover: Int): IntArray =
        intArrayOf(
            if (areaWidth > 0) min(frameWidth, areaWidth) else frameWidth,
            if (areaHeight > 0) min(frameHeight, areaHeight + keyboardCover) else frameHeight,
        )
}
