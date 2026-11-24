package org.handtop.desktop.wayland

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi

/**
 * A surface's committed pixels in GPU memory (AHardwareBuffer wrapped as a hardware Bitmap):
 * the UI draws them without uploading, and each commit copies only the damaged rectangles.
 *
 * Three buffers take turns, so a commit never writes into the one the GPU may still be
 * drawing from. Every buffer collects the damage of the commits since it was last written
 * ("buffer age"), so it is brought up to date with as few copies as possible.
 */
@RequiresApi(Build.VERSION_CODES.Q)
internal class HardwareFrames private constructor(val width: Int, val height: Int) {

    private class Slot(val handle: Long, val bitmap: Bitmap) {
        /** Damage since this slot was last written; null = everything. */
        var dirty: MutableList<Rect>? = null
    }

    private val slots = arrayOfNulls<Slot>(SLOTS)
    private var next = 0
    private val rects = IntArray(MAX_RECTS * 4)

    /** The most recently completed buffer. */
    var current: Bitmap? = null
        private set

    /**
     * Copies the [damage] (buffer pixels) of [buffer] into the next slot and makes it current.
     * Returns false if no hardware buffer could be used (the caller falls back to software).
     */
    fun update(buffer: WlBuffer, damage: List<Rect>): Boolean {
        for (slot in slots) slot?.dirty?.let { add(it, damage) }
        val slot = slots[next] ?: allocate() ?: return false
        slots[next] = slot

        val region = slot.dirty ?: listOf(Rect(0, 0, width, height))
        val count = region.size.coerceAtMost(MAX_RECTS)
        for (i in 0 until count) {
            val r = region[i]
            rects[i * 4] = r.left
            rects[i * 4 + 1] = r.top
            rects[i * 4 + 2] = r.width()
            rects[i * 4 + 3] = r.height()
        }
        val pool = buffer.pool
        val result = Native.hbBlit(
            slot.handle, pool.addr, pool.size, buffer.offset, buffer.width, buffer.height, buffer.stride,
            buffer.format == WlShm.FORMAT_XRGB8888, rects, count,
        )
        if (result == -1) throw ProtocolError(buffer.id, 0, "buffer outside its pool")
        if (result != 0) return false
        slot.dirty = ArrayList()
        current = slot.bitmap
        next = (next + 1) % SLOTS
        return true
    }

    private fun allocate(): Slot? {
        val handle = Native.hbCreate(width, height, persistentMapping)
        if (handle == 0L) return null
        val hardware = Native.hbToJava(handle)
        val bitmap = hardware?.let { Bitmap.wrapHardwareBuffer(it, null) }
        hardware?.close() // the bitmap keeps its own reference
        if (bitmap == null) {
            Native.hbRelease(handle)
            return null
        }
        return Slot(handle, bitmap)
    }

    /** Adds damage to a slot's list, merging into one bounding box when it gets long. */
    private fun add(list: MutableList<Rect>, damage: List<Rect>) {
        list.addAll(damage)
        if (list.size > MERGE_ABOVE) {
            val box = Rect(list[0])
            for (r in list) box.union(r)
            list.clear()
            list += box
        }
    }

    fun release() {
        val old = slots.filterNotNull()
        slots.fill(null)
        current = null
        // The UI may still draw the last frame: free the memory a moment later.
        main.postDelayed({
            for (slot in old) {
                slot.bitmap.recycle()
                Native.hbRelease(slot.handle)
            }
        }, RELEASE_DELAY_MS)
    }

    companion object {
        private const val SLOTS = 3
        private const val MAX_RECTS = 64
        private const val MERGE_ABOVE = 16
        private const val RELEASE_DELAY_MS = 500L
        private val main = Handler(Looper.getMainLooper())

        val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        /**
         * Qualcomm's gralloc maps the buffer itself, so a lock can stay open (measured: 30 µs
         * per small update instead of 320 µs). Elsewhere a lock may return a shadow copy.
         */
        private val persistentMapping: Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER.equals("QTI", ignoreCase = true)
            else Build.HARDWARE.equals("qcom", ignoreCase = true)

        fun create(width: Int, height: Int): HardwareFrames? =
            if (supported && width > 0 && height > 0) HardwareFrames(width, height) else null
    }
}
