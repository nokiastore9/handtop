package org.handtop.desktop.term

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.HapticFeedbackConstants
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File

/**
 * Process-wide registry of terminal sessions. Sessions outlive the activity (e.g. when
 * Android recreates it on a DeX / display switch) and get re-attached to new windows.
 */
object TermSessions {
    val hosts = ArrayList<TermSessionHost>()

    fun create(context: Context): TermSessionHost =
        TermSessionHost(context.applicationContext).also { hosts += it }

    fun remove(host: TermSessionHost) {
        hosts -= host
    }
}

/**
 * Owns one [TerminalSession] (a shell process on a pty) and forwards its callbacks to
 * whatever window currently shows it.
 */
class TermSessionHost(private val app: Context) : TerminalSessionClient {

    /** The window content currently displaying this session, if any. */
    var ui: TerminalContent? = null

    val session: TerminalSession

    init {
        val home = File(app.filesDir, "home").apply { mkdirs() }
        session = TerminalSession(
            SHELL,
            home.absolutePath,
            arrayOf("-sh"), // argv[0] with a leading dash = login shell
            buildEnvironment(home),
            TRANSCRIPT_ROWS,
            this,
        )
    }

    private fun buildEnvironment(home: File): Array<String> {
        val env = LinkedHashMap(System.getenv())
        env["HOME"] = home.absolutePath
        env["TMPDIR"] = app.cacheDir.absolutePath
        env["TERM"] = "xterm-256color"
        env["COLORTERM"] = "truecolor"
        env["LANG"] = "en_US.UTF-8"
        if (env["PATH"].isNullOrEmpty()) env["PATH"] = "/system/bin:/system/xbin:/vendor/bin"
        return env.map { (k, v) -> "$k=$v" }.toTypedArray()
    }

    // ---- TerminalSessionClient ----

    override fun onTextChanged(changedSession: TerminalSession) {
        ui?.terminalView?.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        ui?.onSessionTitleChanged()
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        ui?.onSessionFinished()
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        if (text.isNullOrEmpty()) return
        val cm = app.getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        ui?.paste()
    }

    override fun onBell(session: TerminalSession) {
        ui?.terminalView?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    override fun onColorsChanged(session: TerminalSession) {
        ui?.terminalView?.invalidate()
    }

    override fun onTerminalCursorStateChange(state: Boolean) {
        ui?.terminalView?.setTerminalCursorBlinkerState(state, false)
    }

    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}

    override fun getTerminalCursorStyle(): Int? = null

    override fun logError(tag: String?, message: String?) { Log.e(tag, message.orEmpty()) }
    override fun logWarn(tag: String?, message: String?) { Log.w(tag, message.orEmpty()) }
    override fun logInfo(tag: String?, message: String?) { Log.i(tag, message.orEmpty()) }
    override fun logDebug(tag: String?, message: String?) { Log.d(tag, message.orEmpty()) }
    override fun logVerbose(tag: String?, message: String?) { Log.v(tag, message.orEmpty()) }
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(tag, message.orEmpty(), e) }
    override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag, "Error", e) }

    companion object {
        const val SHELL = "/system/bin/sh"
        const val TRANSCRIPT_ROWS = 3000
    }
}
