package org.handtop.desktop.linux

import android.content.Context
import android.os.Environment
import android.system.Os
import org.handtop.desktop.R
import org.handtop.desktop.term.ShellSpec
import java.io.File
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/** Where the Linux root filesystem lives and how to enter it with proot. */
object LinuxEnv {

    const val HOSTNAME = "handtop"

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

    /** A login shell inside the installed distribution, as fake root, through proot. */
    fun shellSpec(context: Context): ShellSpec {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val rootfsDir = rootfs(context)
        val rootfs = rootfsDir.absolutePath
        val prootTmp = File(context.cacheDir, "proot").apply { mkdirs() }
        val distro = installedDistro(context) ?: Distro.DEFAULT
        val shell = if (File(rootfsDir, "bin/bash").exists()) listOf("/bin/bash", "--login") else listOf("/bin/sh", "-l")
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""

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
            "-w", "/root",
            "/usr/bin/env", "-i",
            "HOME=/root",
            "USER=root",
            "LOGNAME=root",
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "TERM_PROGRAM=HandTop",
            "TERM_PROGRAM_VERSION=$version",
            "LANG=C.UTF-8",
            "TZ=" + timeZone(rootfsDir),
            "TMPDIR=/tmp",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        ) + shell

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
