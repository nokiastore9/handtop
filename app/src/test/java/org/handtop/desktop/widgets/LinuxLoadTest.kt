package org.handtop.desktop.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class LinuxLoadTest {

    @Test
    fun statTicksAreUtimePlusStime() = // the name may hold spaces and parentheses
        assertEquals(150L, LinuxLoad.statTicks("1234 (my (prog) x) S 1 1 1 0 -1 4194560 0 0 0 0 100 50 0 0 20 0 1 0 1 1 1"))

    @Test
    fun statTicksOfGarbageIsNull() {
        assertNull(LinuxLoad.statTicks("1234 (x"))
        assertNull(LinuxLoad.statTicks(""))
        assertNull(LinuxLoad.statTicks("1234 (x) S 1 2"))
    }

    @Test
    fun rssFromStatus() {
        assertEquals(204800L * 1024, LinuxLoad.rssBytes("Name:\tx\nVmRSS:\t  204800 kB\nThreads:\t3\n"))
        assertNull(LinuxLoad.rssBytes("Name:\tkernel thread\n"))
    }

    @Test
    fun cpuPercentOfAllCores() = assertEquals(25, LinuxLoad.cpuPercent(0, 400, 2000, 8)) // 400 ticks in 2 s on 8 cores

    @Test
    fun cpuPercentNeverNegative() = assertEquals(0, LinuxLoad.cpuPercent(500, 100, 2000, 8)) // processes ended

    @Test
    fun cpuPercentCapped() = assertEquals(100, LinuxLoad.cpuPercent(0, 99999, 1000, 1))

    @Test
    fun cpuPercentOfNoTimeIsZero() = assertEquals(0, LinuxLoad.cpuPercent(0, 100, 0, 8))

    @Test
    fun memoryTextRussianAndEnglish() {
        assertEquals("1,8 / 12 ГБ", LinuxLoad.memoryText(1_932_735_283, 12_884_901_888, Locale("ru"), "ГБ"))
        assertEquals("1.8 / 12 GB", LinuxLoad.memoryText(1_932_735_283, 12_884_901_888, Locale.US, "GB"))
    }
}
