package org.handtop.desktop.linux

import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Inside Linux: the handtop-open command (files and links to Android apps, see [OpenBus]) and
 * its menu entry "Android app", which Linux file managers list under "Open with another
 * application". The entry names no file types on purpose: then no program picks it as the
 * default for a type by itself; the user can.
 */
object LinuxOpen {

    /** The menu entry's id; HandTop's own Linux apps menu leaves it out. */
    const val DESKTOP_ID = "handtop-android"

    private const val TAG = "HandTopOpen"
    private const val COMMAND = "usr/local/bin/handtop-open"
    private const val ENTRY = "usr/local/share/applications/$DESKTOP_ID.desktop"

    /** Writes the command and the entry when missing or out of date (a HandTop update). */
    fun install(rootfs: File) {
        write(File(rootfs, COMMAND), SCRIPT, executable = true)
        write(File(rootfs, ENTRY), DESKTOP_ENTRY, executable = false)
    }

    private fun write(file: File, text: String, executable: Boolean) {
        try {
            if (!file.isFile || file.readText() != text) {
                file.parentFile?.mkdirs()
                file.writeText(text)
            }
            if (executable && !file.canExecute()) file.setExecutable(true, false)
        } catch (e: IOException) {
            Log.w(TAG, "cannot write $file", e)
        }
    }

    private val SCRIPT = """
        #!/bin/sh
        # handtop-open: opens files and links in an app on the phone, through HandTop.
        # Written by HandTop; changes are overwritten.
        if [ "${'$'}#" -eq 0 ] || [ "${'$'}1" = "--help" ]; then
            echo "usage: handtop-open FILE|LINK..." >&2
            echo "Opens files in an Android app you pick, and links (https:, mailto:…) in their app." >&2
            exit 2
        fi
        status=0
        for arg in "${'$'}@"; do
            case "${'$'}arg" in
                /*) target="${'$'}arg" ;;
                [A-Za-z]*:*) target="${'$'}arg" ;;
                *) target="${'$'}PWD/${'$'}arg" ;;
            esac
            if command -v dbus-send >/dev/null 2>&1; then
                dbus-send --session --print-reply=literal --dest=org.handtop.Desktop /org/handtop/Desktop \
                    org.handtop.Desktop.Open "string:${'$'}target" >/dev/null || status=1
            elif command -v gdbus >/dev/null 2>&1; then
                gdbus call --session --dest org.handtop.Desktop --object-path /org/handtop/Desktop \
                    --method org.handtop.Desktop.Open "${'$'}target" >/dev/null || status=1
            elif command -v busctl >/dev/null 2>&1; then
                busctl --user call org.handtop.Desktop /org/handtop/Desktop org.handtop.Desktop Open s "${'$'}target" || status=1
            else
                echo "handtop-open: needs dbus-send, gdbus or busctl" >&2
                exit 1
            fi
        done
        exit ${'$'}status
    """.trimIndent() + "\n"

    private val DESKTOP_ENTRY = """
        [Desktop Entry]
        Type=Application
        Name=Android app
        Name[ru]=Приложение Android
        GenericName=Open on the phone
        GenericName[ru]=Открыть на телефоне
        Comment=Opens files and links in an app on the phone, through HandTop
        Comment[ru]=Открывает файлы и ссылки в приложении на телефоне через HandTop
        Exec=handtop-open %U
        Icon=phone
        Terminal=false
        StartupNotify=false
        Categories=Utility;
    """.trimIndent() + "\n"
}
