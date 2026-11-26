package org.handtop.desktop.linux

import org.handtop.desktop.wayland.WaylandServer

/** Environment that sends Linux graphical programs to HandTop, the Wayland compositor. */
object GraphicsEnv {
    val variables: List<String> = listOf(
        "XDG_RUNTIME_DIR=/${WaylandServer.RUNTIME_DIR}",
        "WAYLAND_DISPLAY=${WaylandServer.SOCKET_NAME}",
        "XDG_SESSION_TYPE=wayland",
        "GDK_BACKEND=wayland",
        // Wayland, else X11: Qt's Wayland shell plugins are a package of their own (qt6-wayland,
        // only recommended on Debian and Ubuntu, optional on Arch), and a Qt program without
        // them would abort at start.
        "QT_QPA_PLATFORM=wayland;xcb",
        "SDL_VIDEODRIVER=wayland",
        "MOZ_ENABLE_WAYLAND=1",
        // No accessibility bus in the session: GTK would look for it at every start.
        "NO_AT_BRIDGE=1",
        // Firefox's process sandboxes (seccomp, namespaces) cannot work under proot, which
        // runs programs through ptrace: tabs would crash on start. Linux as a whole stays
        // inside HandTop's Android app sandbox.
        "MOZ_DISABLE_CONTENT_SANDBOX=1",
        "MOZ_DISABLE_RDD_SANDBOX=1",
        "MOZ_DISABLE_SOCKET_PROCESS_SANDBOX=1",
        "MOZ_DISABLE_GMP_SANDBOX=1",
        "MOZ_DISABLE_UTILITY_SANDBOX=1",
        // Firefox's main process reopens its shared memory read-only through /proc/self/fd,
        // which Android denies (EACCES); it then shares memory without seals, and tabs that
        // require seals refused it and crashed. Without seals in every process they agree.
        "MOZ_SHM_NO_SEALS=1",
    )
}
