package org.handtop.desktop.linux

import android.os.SystemClock
import java.io.File
import java.io.FileInputStream
import java.util.Locale

/**
 * Android hides a few /proc files from apps (SELinux), which breaks `htop`, `top`, `uptime`
 * and friends inside Linux. For every such file that can't be opened on this device we
 * generate a plausible stand-in and bind it over the original with proot.
 */
object ProcFakes {

    /** Returns proot `-b host:guest` arguments for the fakes this device needs. */
    fun bindings(dir: File): List<String> {
        dir.mkdirs()
        val args = ArrayList<String>()
        for ((guestPath, content) in entries()) {
            if (readable(guestPath)) continue
            val file = File(dir, guestPath.removePrefix("/").replace('/', '_'))
            file.writeText(content())
            args += listOf("-b", "${file.absolutePath}:$guestPath")
        }
        return args
    }

    private fun entries(): List<Pair<String, () -> String>> = listOf(
        "/proc/stat" to ::stat,
        "/proc/uptime" to ::uptime,
        "/proc/loadavg" to { "0.12 0.07 0.02 2/165 765\n" },
        "/proc/version" to {
            "Linux version ${System.getProperty("os.version")} (handtop@android) #1 SMP PREEMPT\n"
        },
        "/proc/vmstat" to {
            listOf("nr_free_pages", "pgpgin", "pgpgout", "pswpin", "pswpout", "pgfault", "pgmajfault")
                .joinToString("") { "$it 0\n" }
        },
        "/proc/sys/kernel/cap_last_cap" to { "40\n" },
        "/proc/sys/fs/inotify/max_user_watches" to { "4096\n" },
        "/proc/sys/kernel/overflowuid" to { "65534\n" },
        "/proc/sys/kernel/overflowgid" to { "65534\n" },
    )

    /** Static per-CPU counters; the aggregate line is their sum, as in the kernel. */
    private fun stat(): String {
        val cpus = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val row = longArrayOf(1200, 30, 900, IDLE_TICKS, 120, 0, 40, 0, 0, 0)
        val sb = StringBuilder("cpu  ").append(row.joinToString(" ") { (it * cpus).toString() }).append('\n')
        for (i in 0 until cpus) sb.append("cpu$i ").append(row.joinToString(" ")).append('\n')
        val bootTime = System.currentTimeMillis() / 1000 - SystemClock.elapsedRealtime() / 1000
        sb.append("intr 0\nctxt 0\nbtime $bootTime\nprocesses 1\nprocs_running 1\nprocs_blocked 0\n")
        sb.append("softirq 0 0 0 0 0 0 0 0 0 0 0\n")
        return sb.toString()
    }

    private fun uptime(): String {
        val cpus = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val seconds = SystemClock.elapsedRealtime() / 1000.0
        return String.format(Locale.US, "%.2f %.2f\n", seconds, IDLE_TICKS * cpus / 100.0)
    }

    private fun readable(path: String): Boolean = try {
        FileInputStream(path).use { it.read() }
        true
    } catch (_: Exception) {
        false
    }

    private const val IDLE_TICKS = 98_000L
}
