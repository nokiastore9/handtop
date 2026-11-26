package org.handtop.desktop.linux

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Environment
import android.system.Os
import android.system.OsConstants
import android.util.Log
import org.handtop.desktop.R
import org.handtop.desktop.audio.SoundBridge
import org.handtop.desktop.term.ShellSpec
import org.handtop.desktop.wayland.WaylandServer
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/** Where the Linux root filesystem lives and how to enter it with proot. */
object LinuxEnv {

    const val HOSTNAME = "handtop"
    const val HOME = "/root"

    fun rootfs(context: Context) = File(context.filesDir, "linux/rootfs")

    private fun marker(context: Context) = File(rootfs(context), ".handtop-installed")

    fun isInstalled(context: Context) = marker(context).isFile

    /** The installed distribution, or null. */
    fun installedDistro(context: Context): Distro? {
        val lines = marker(context).takeIf { it.isFile }?.readLines() ?: return null
        val props = lines.mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 } }
            .associate { it[0] to it[1] }
        return Distro.byId(props["distro"]) ?: Distro.DEFAULT // first installs only wrote the image name
    }

    fun writeMarker(context: Context, distro: Distro, digests: List<String>) {
        marker(context).writeText("distro=${distro.id}\nimage=${distro.image}\nlayers=${digests.joinToString(",")}\n")
    }

    /** 0.1 kept Debian in linux/debian; move it to the distro-neutral location once. */
    fun migrate(context: Context) {
        val old = File(context.filesDir, "linux/debian")
        val new = rootfs(context)
        if (File(old, ".handtop-installed").isFile && !new.exists()) old.renameTo(new)
    }

    /** Phone storage is shared with Linux once the user grants "All files access". */
    fun hasStorageAccess() = Environment.isExternalStorageManager()

    /** Opens Android's "All files access" screen for HandTop (the user decides). */
    fun requestStorageAccess(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    /** What Linux programs need from HandTop: the display server and, if installed, D-Bus, sound, X11. */
    fun startServices(context: Context) {
        WaylandServer.ensureRunning(context, rootfs(context))
        SessionBus.ensureRunning(context)
        SoundBridge.ensureRunning(context)
        X11Display.ensureRunning(context)
        LinuxBookmarks.update(context)
        LinuxSetup.startIfNeeded(context) // GPU drivers, X11, sound…: without the user asking
    }

    /**
     * Stops everything that runs from the installed distribution (X11, sound, session bus,
     * programs from the menu), before it is replaced or removed: left running, it would keep
     * serving the deleted system, and the new one would get no X11 display or sound until
     * HandTop restarts. Linux terminals are closed by then. Main thread.
     */
    fun stopServices(context: Context) {
        X11Display.stop()
        SoundBridge.stop()
        SessionBus.stop()
        killLinuxProcesses(context)
    }

    /**
     * Kills every proot of this app and every Linux process under one. proot ignores SIGTERM,
     * and a proot killed with SIGKILL leaves its processes running, so each is killed itself.
     * They are recognised by their executable: proot, or its loader, which is what a process
     * running under proot shows in /proc.
     */
    private fun killLinuxProcesses(context: Context) {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val self = android.os.Process.myPid()
        var killed = 0
        for (dir in File("/proc").listFiles() ?: emptyArray()) {
            val pid = dir.name.toIntOrNull() ?: continue
            if (pid == self) continue
            val exe = try {
                Os.readlink("/proc/$pid/exe") // other apps' processes: not readable, skipped
            } catch (_: Exception) {
                continue
            }
            if (exe == "$nativeDir/libproot.so" || exe == "$nativeDir/libproot-loader.so") {
                try {
                    Os.kill(pid, OsConstants.SIGKILL)
                    killed++
                } catch (_: Exception) {
                    // already gone
                }
            }
        }
        if (killed > 0) Log.i("HandTopLinux", "stopped $killed Linux processes")
    }

    /**
     * Kills the Linux processes running the program [name] (their argv[0], or its last part).
     * Under proot a Linux process runs as proot's loader, with the program's own command line.
     */
    fun killLinuxProgram(context: Context, name: String) {
        val loader = context.applicationInfo.nativeLibraryDir + "/libproot-loader.so"
        for (dir in File("/proc").listFiles() ?: emptyArray()) {
            val pid = dir.name.toIntOrNull() ?: continue
            val exe = try {
                Os.readlink("/proc/$pid/exe")
            } catch (_: Exception) {
                continue
            }
            if (exe != loader) continue
            val argv0 = try {
                File(dir, "cmdline").readBytes().takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8)
            } catch (_: Exception) {
                continue
            }
            if (argv0 != name && argv0.substringAfterLast('/') != name) continue
            try {
                Os.kill(pid, OsConstants.SIGKILL)
                Log.i("HandTopLinux", "stopped $name ($pid)")
            } catch (_: Exception) {
                // already gone
            }
        }
    }

    /** A login shell inside the installed distribution, as fake root, through proot, in [cwd]. */
    fun shellSpec(context: Context, cwd: String = HOME): ShellSpec {
        startServices(context)
        return prootSpec(context, LoginShell.command(rootfs(context)), cwd)
    }

    /**
     * [command] run by a login shell inside the distribution (a menu app's Exec line), as
     * fake root, through proot. The shell reads the profile, so PATH etc. are as in a terminal.
     */
    fun commandSpec(context: Context, command: String): ShellSpec =
        prootSpec(context, listOf("/bin/sh", "-lc", command))

    /**
     * HandTop's part of the login profile, /etc/profile.d/handtop.sh: what terminals and menu
     * programs need beyond the distribution's own profile (which resets PATH). Rewritten
     * whenever it differs, so a HandTop update reaches distributions installed before it.
     */
    private fun ensureProfile(rootfsDir: File) {
        val file = File(rootfsDir, "etc/profile.d/handtop.sh")
        try {
            if (file.isFile && file.readText() == PROFILE) return
            file.parentFile?.mkdirs()
            file.writeText(PROFILE)
        } catch (e: IOException) {
            Log.w("HandTopLinux", "cannot write $file", e)
        }
    }

    private val PROFILE = """
        |# Written by HandTop whenever it starts a Linux program: changes here are lost.
        |
        |# Programs run as (fake) root, and Debian leaves games out of root's PATH: games from
        |# the menu (Exec=supertuxkart) or typed in a terminal would not be found.
        |case ":${'$'}PATH:" in
        |    *:/usr/games:*) ;;
        |    *) PATH="${'$'}PATH:/usr/local/games:/usr/games" ;;
        |esac
        |export PATH
        |
        |# HandTop is still setting Linux up (GPU drivers, X11, sound): new terminals say so.
        |case ${'$'}- in
        |    *i*) if [ -r /tmp/handtop-setup-running ]; then cat /tmp/handtop-setup-running; fi ;;
        |esac
        |""".trimMargin()

    /** [program] (argv inside Linux) with the HandTop environment, through proot. */
    private fun prootSpec(context: Context, program: List<String>, cwd: String = HOME): ShellSpec {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val rootfsDir = rootfs(context)
        val rootfs = rootfsDir.absolutePath
        val prootTmp = File(context.cacheDir, "proot").apply { mkdirs() }
        val distro = installedDistro(context) ?: Distro.DEFAULT
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""

        // Linux GUI programs talk to HandTop's compositor through this socket.
        WaylandServer.ensureRunning(context, rootfsDir)
        ensureProfile(rootfsDir)
        LinuxOpen.install(rootfsDir)
        GpuDrivers.register(context)

        val storage = if (hasStorageAccess()) {
            listOf("-b", "/storage", "-b", "${Environment.getExternalStorageDirectory().absolutePath}:/sdcard")
        } else {
            emptyList()
        }

        val args = listOf(
            "proot",
            "--kill-on-exit",
            "--link2symlink",          // hard links -> symlinks (link(2) is denied to apps)
            "--sysvipc",               // System V IPC emulation
            "-0",                      // fake root
            "--kernel-release=" + utsname(),
            "-r", rootfs,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/proc/self/fd:/dev/fd",
            "-b", "$rootfs/tmp:/dev/shm",
        ) + storage + ProcFakes.bindings(File(context.filesDir, "linux/proc-fakes")) + listOf(
            "-w", cwd,
            "/usr/bin/env", "-i",
            "HOME=/root",
            "USER=root",
            "LOGNAME=root",
            // Menu programs start through sh, which does not set SHELL (bash does): terminal
            // programs such as QTerminal would open plain sh instead of the terminal's shell.
            "SHELL=" + LoginShell.path(rootfsDir),
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "TERM_PROGRAM=HandTop",
            "TERM_PROGRAM_VERSION=$version",
            "LANG=C.UTF-8",
            "TZ=" + timeZone(rootfsDir),
            "TMPDIR=/tmp",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/local/games:/usr/games",
        ) + GraphicsEnv.variables + (if (X11Display.isInstalled(context)) listOf("DISPLAY=${X11Display.DISPLAY}") else emptyList()) +
            (if (SessionBus.isAvailable(context)) listOf("DBUS_SESSION_BUS_ADDRESS=${SessionBus.ADDRESS}") else emptyList()) +
            GpuDrivers.environment(context) +
            program

        val env = LinkedHashMap(System.getenv())
        env.remove("LD_PRELOAD")
        env["PROOT_LOADER"] = "$nativeDir/libproot-loader.so"
        env["PROOT_TMP_DIR"] = prootTmp.absolutePath

        return ShellSpec(
            executable = "$nativeDir/libproot.so",
            args = args,
            cwd = context.filesDir.absolutePath,
            env = env,
            title = context.getString(R.string.terminal_linux_title, distro.shortName),
            icon = R.drawable.ic_linux,
            linux = true,
        )
    }

    /**
     * proot's full uname override ('\sysname\nodename\release\version\machine\domain\hwcap\'):
     * the real kernel, but our hostname, so prompts read root@handtop.
     */
    private fun utsname(): String {
        val u = Os.uname()
        val clean = { s: String -> s.replace('\\', '/') }
        return "\\${clean(u.sysname)}\\$HOSTNAME\\${clean(u.release)}\\${clean(u.version)}\\${clean(u.machine)}\\localdomain\\-1\\"
    }

    /**
     * Android's time zone for Linux: the tz database name when the image ships it,
     * otherwise a fixed POSIX offset (slim images may lack tzdata; no DST then).
     */
    private fun timeZone(rootfs: File): String {
        val tz = TimeZone.getDefault()
        if (File(rootfs, "usr/share/zoneinfo/${tz.id}").isFile) return tz.id
        val minutes = tz.getOffset(System.currentTimeMillis()) / 60_000
        val sign = if (minutes >= 0) "+" else "-"
        val abs = abs(minutes)
        val name = String.format(Locale.US, "%s%02d%02d", sign, abs / 60, abs % 60)
        // POSIX offsets are west-positive: UTC+04:00 is "<+0400>-04:00".
        val posixSign = if (minutes >= 0) "-" else ""
        return String.format(Locale.US, "<%s>%s%02d:%02d", name, posixSign, abs / 60, abs % 60)
    }
}
