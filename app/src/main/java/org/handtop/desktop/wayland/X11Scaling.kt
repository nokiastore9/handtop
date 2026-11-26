package org.handtop.desktop.wayland

import java.io.ByteArrayOutputStream

/** How X11 programs meet the screen's density (Settings → Graphics and sound → X11). */
enum class X11ScaleMode(val key: String) {
    /** X11 programs draw at the screen's full resolution and are told their scale: sharp. */
    SHARP("sharp"),
    /** X11 programs draw at the logical resolution and HandTop stretches them: softer. */
    SCALED("scaled");

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: SHARP
    }
}

/**
 * Xwayland in rootless mode cannot draw a window at more than one pixel per X pixel. So either
 * the X screen is the physical one and programs scale themselves ([X11ScaleMode.SHARP]: they
 * learn the scale from Xft.dpi and XSETTINGS), or it is the logical one and HandTop stretches
 * every X11 window by the output scale ([X11ScaleMode.SCALED]).
 */
object X11Scaling {

    /** Screen pixels per X pixel, for an output of integer scale [outputScale]. */
    fun pixelsPerUnit(mode: X11ScaleMode, outputScale: Int): Int =
        if (mode == X11ScaleMode.SHARP) 1 else outputScale

    /** The X screen (Xwayland's root window) for a [width] x [height] pixel output. */
    fun screenSize(mode: X11ScaleMode, width: Int, height: Int, outputScale: Int): IntArray {
        val s = pixelsPerUnit(mode, outputScale)
        return intArrayOf(width / s, height / s)
    }

    /** The scale X11 programs should draw at themselves: the output's when sharp, else 1. */
    private fun programScale(mode: X11ScaleMode, outputScale: Int) =
        if (mode == X11ScaleMode.SHARP) outputScale else 1

    /** X resources (the root window's RESOURCE_MANAGER, read by Xlib programs at start). */
    fun resources(mode: X11ScaleMode, outputScale: Int): String {
        val s = programScale(mode, outputScale)
        val lines = mutableListOf(
            "Xft.dpi" to "${BASE_DPI * s}",
            "Xft.antialias" to "1",
            "Xft.hinting" to "1",
            "Xft.hintstyle" to "hintslight",
            "Xcursor.size" to "${BASE_CURSOR * s}",
        )
        if (mode == X11ScaleMode.SHARP) {
            // xterm's default bitmap fonts do not scale; a TrueType one follows Xft.dpi.
            for (term in listOf("XTerm", "UXTerm")) {
                lines += "$term*faceName" to "Monospace"
                lines += "$term*faceSize" to "10"
            }
        }
        return lines.joinToString("") { (name, value) -> "$name:$TAB$value$NEWLINE" }
    }

    /**
     * The XSETTINGS (read by GTK and Chromium-based programs, and followed live), with [serial]:
     * the window scale for GTK, and the DPI with and without it, as GNOME's settings daemon
     * gives them on a HiDPI screen.
     */
    fun xsettings(mode: X11ScaleMode, outputScale: Int, serial: Int): ByteArray {
        val s = programScale(mode, outputScale)
        val ints = listOf(
            "Gdk/WindowScalingFactor" to s,
            "Gdk/UnscaledDPI" to BASE_DPI * 1024,
            "Xft/DPI" to BASE_DPI * s * 1024,
            "Xft/Antialias" to 1,
            "Xft/Hinting" to 1,
        )
        val strings = listOf("Xft/HintStyle" to "hintslight", "Xft/RGBA" to "none")
        val out = ByteArrayOutputStream()
        fun u8(v: Int) = out.write(v and 0xff)
        fun u16(v: Int) { u8(v); u8(v shr 8) }
        fun u32(v: Int) { u16(v); u16(v shr 16) }
        fun pad(n: Int) = repeat((4 - n % 4) % 4) { u8(0) }
        fun header(type: Int, name: String) {
            val bytes = name.toByteArray(Charsets.US_ASCII)
            u8(type); u8(0); u16(bytes.size)
            out.write(bytes); pad(bytes.size)
            u32(0) // last-change serial
        }
        u8(0); u8(0); u8(0); u8(0) // LSBFirst
        u32(serial)
        u32(ints.size + strings.size)
        for ((name, value) in ints) { header(0, name); u32(value) }
        for ((name, value) in strings) {
            header(1, name)
            val bytes = value.toByteArray(Charsets.UTF_8)
            u32(bytes.size); out.write(bytes); pad(bytes.size)
        }
        return out.toByteArray()
    }

    private const val BASE_DPI = 96
    private const val BASE_CURSOR = 24
    private const val TAB = '\t'
    private const val NEWLINE = '\n'
}
