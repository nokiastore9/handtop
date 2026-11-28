package org.handtop.desktop.widgets

import java.util.Locale
import kotlin.math.roundToInt

/** Reading Linux's load from /proc text, apart from Android. */
object LinuxLoad {

    /**
     * utime + stime from a /proc/<pid>/stat line (fields 14 and 15), or null when it is not one.
     * The program's name (field 2) may hold spaces and parentheses: fields are counted after its
     * last ')'.
     */
    fun statTicks(stat: String): Long? {
        val end = stat.lastIndexOf(')')
        if (end < 0) return null
        val fields = stat.substring(end + 1).trim().split(' ').filter { it.isNotEmpty() }
        // After the name: state is field 3, so utime (14) and stime (15) are at 11 and 12.
        val utime = fields.getOrNull(11)?.toLongOrNull() ?: return null
        val stime = fields.getOrNull(12)?.toLongOrNull() ?: return null
        return utime + stime
    }

    /** The resident memory (VmRSS) from a /proc/<pid>/status text, in bytes; null when there is none. */
    fun rssBytes(status: String): Long? {
        val line = status.lineSequence().firstOrNull { it.startsWith("VmRSS:") } ?: return null
        val kb = line.removePrefix("VmRSS:").trim().substringBefore(' ').toLongOrNull() ?: return null
        return kb * 1024
    }

    /**
     * The share of all [cores] used between two samples, 0..100: [ticks] − [prevTicks] of CPU
     * time ([hz] per second) in [elapsedMs]. Processes that ended meanwhile can make the sum
     * smaller: that is 0, never negative.
     */
    fun cpuPercent(prevTicks: Long, ticks: Long, elapsedMs: Long, cores: Int, hz: Int = 100): Int {
        if (elapsedMs <= 0 || cores <= 0 || hz <= 0) return 0
        val used = (ticks - prevTicks).coerceAtLeast(0).toDouble() / hz
        val available = elapsedMs / 1000.0 * cores
        return (used / available * 100).roundToInt().coerceIn(0, 100)
    }

    /** "1,8 / 12 ГБ": [used] with one decimal, [total] whole, in GiB, in [locale]'s digits. */
    fun memoryText(used: Long, total: Long, locale: Locale, gb: String): String {
        val gib = 1024.0 * 1024 * 1024
        return String.format(locale, "%.1f / %d %s", used / gib, (total / gib).roundToInt(), gb)
    }
}
