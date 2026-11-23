package org.handtop.desktop.term

import android.content.Context
import androidx.annotation.DrawableRes
import org.handtop.desktop.R
import java.io.File

/** What a terminal session runs: executable, argv (argv[0] first), working dir, environment. */
data class ShellSpec(
    val executable: String,
    val args: List<String>,
    val cwd: String,
    val env: Map<String, String>,
    val title: String,
    @param:DrawableRes val icon: Int,
) {
    companion object {
        /** The plain Android shell (/system/bin/sh) in the app's home directory. */
        fun android(context: Context): ShellSpec {
            val home = File(context.filesDir, "home").apply { mkdirs() }
            val env = LinkedHashMap(System.getenv())
            env["HOME"] = home.absolutePath
            env["TMPDIR"] = context.cacheDir.absolutePath
            env["TERM"] = "xterm-256color"
            env["COLORTERM"] = "truecolor"
            env["LANG"] = "en_US.UTF-8"
            if (env["PATH"].isNullOrEmpty()) env["PATH"] = "/system/bin:/system/xbin:/vendor/bin"
            return ShellSpec(
                executable = "/system/bin/sh",
                args = listOf("-sh"), // leading dash = login shell
                cwd = home.absolutePath,
                env = env,
                title = context.getString(R.string.app_terminal_android),
                icon = R.drawable.ic_terminal,
            )
        }
    }
}
