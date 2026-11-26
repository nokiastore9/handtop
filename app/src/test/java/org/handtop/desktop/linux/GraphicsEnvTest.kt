package org.handtop.desktop.linux

import org.junit.Assert.assertEquals
import org.junit.Test

class GraphicsEnvTest {
    private fun values(name: String) =
        GraphicsEnv.variables.filter { it.startsWith("$name=") }.map { it.substringAfter('=') }

    @Test
    fun qtTriesWaylandFirstAndFallsBackToX11() {
        // Qt's Wayland shell plugins are a separate package (qt6-wayland) that a program does not
        // always pull in; with "wayland" alone such a Qt program aborted at start.
        assertEquals(listOf("wayland;xcb"), values("QT_QPA_PLATFORM"))
    }

    @Test
    fun firefoxDoesNotRequireSealedSharedMemory() {
        // Under proot Firefox's main process cannot reopen a memfd through /proc/self/fd
        // (EACCES) and shares unsealed memory; its tabs rejected that and crashed.
        assertEquals(listOf("1"), values("MOZ_SHM_NO_SEALS"))
    }

    @Test
    fun programsTalkToHandTopsCompositor() {
        assertEquals(listOf("wayland-0"), values("WAYLAND_DISPLAY"))
        assertEquals(listOf("wayland"), values("GDK_BACKEND"))
    }
}
