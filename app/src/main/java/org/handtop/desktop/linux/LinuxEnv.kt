package org.handtop.desktop.linux

import android.content.Context
import org.handtop.desktop.R
import org.handtop.desktop.term.ShellSpec
import java.io.File
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/** Where the Linux root filesystem lives and how to enter it with proot. */
object LinuxEnv {

    /** Image the installer fetches. */
    const val IMAGE_REPOSITORY = "library/debian"
    const val IMAGE_TAG = "trixie-slim"

    fun rootfs(context: Context) = File(context.filesDir, "linux/debian")

    fun marker(context: Context) = File(rootfs(context), ".handtop-installed")

    fun isInstalled(context: Context) = marker(context).isFile

    /** A login shell inside Debian, as fake root, through proot. */
    fun shellSpec(context: Context): ShellSpec {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val rootfs = rootfs(context).absolutePath
        val prootTmp = File(context.cacheDir, "proot").apply { mkdirs() }

        val args = listOf(
            "proot",
            "--kill-on-exit",
            "--link2symlink",          // hard links -> symlinks (link(2) is denied to apps)
            "--sysvipc",               // System V IPC emulation
            "-0",                      // fake root
            "-r", rootfs,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/proc/self/fd:/dev/fd",
            "-b", "$rootfs/tmp:/dev/shm",
        ) + ProcFakes.bindings(File(context.filesDir, "linux/proc-fakes")) + listOf(
            "-w", "/root",
            "/usr/bin/env", "-i",
            "HOME=/root",
            "USER=root",
            "LOGNAME=root",
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "LANG=C.UTF-8",
            "TZ=" + timeZone(File(rootfs)),
            "TMPDIR=/tmp",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "/bin/bash", "--login",
        )

        val env = LinkedHashMap(System.getenv())
        env.remove("LD_PRELOAD")
        env["PROOT_LOADER"] = "$nativeDir/libproot-loader.so"
        env["PROOT_TMP_DIR"] = prootTmp.absolutePath

        return ShellSpec(
            executable = "$nativeDir/libproot.so",
            args = args,
            cwd = context.filesDir.absolutePath,
            env = env,
            title = context.getString(R.string.app_terminal_linux),
            icon = R.drawable.ic_linux,
        )
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
