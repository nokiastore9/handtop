package org.handtop.desktop.wayland

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.Region
import android.graphics.RegionIterator
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
        /**
         * Damage since this slot was last written; null = everything. A region, so damage that
         * overlaps (a video's whole frame, every commit) is copied once, not once per commit.
         */
        var dirty: Region? = null
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
        for (slot in slots) slot?.dirty?.let { dirty -> for (r in damage) dirty.op(r, Region.Op.UNION) }
        val slot = slots[next] ?: allocate() ?: return false
        slots[next] = slot

        val count = rectsOf(slot.dirty)
        val pool = buffer.pool
        val result = Native.hbBlit(
            slot.handle, pool.addr, pool.size, buffer.offset, buffer.width, buffer.height, buffer.stride,
            buffer.format == WlShm.FORMAT_XRGB8888, rects, count,
        )
        if (result == -1) throw ProtocolError(buffer.id, 0, "buffer outside its pool")
        if (result != 0) return false
        slot.dirty = Region()
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

    /**
     * Puts the disjoint rectangles of [region] (null = the whole buffer) into [rects] as
     * x, y, width, height and returns how many; past [MAX_RECTS], their bounding box.
     */
    private fun rectsOf(region: Region?): Int {
        if (region == null) return put(0, Rect(0, 0, width, height))
        val r = Rect()
        val iterator = RegionIterator(region)
        var count = 0
        while (iterator.next(r)) {
            if (count == MAX_RECTS) return put(0, region.bounds)
            count = put(count, r)
        }
        return count
    }

    private fun put(index: Int, r: Rect): Int {
        rects[index * 4] = r.left
        rects[index * 4 + 1] = r.top
        rects[index * 4 + 2] = r.width()
        rects[index * 4 + 3] = r.height()
        return index + 1
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
