package org.handtop.desktop.term

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.HapticFeedbackConstants
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import org.handtop.desktop.service.SessionService

/**
 * Process-wide registry of terminal sessions. Sessions outlive the activity (e.g. when
 * Android recreates it on a DeX / display switch) and get re-attached to new windows.
 */
object TermSessions {
    val hosts = ArrayList<TermSessionHost>()

    fun create(context: Context, spec: ShellSpec): TermSessionHost {
        val host = TermSessionHost(context.applicationContext, spec)
        hosts += host
        SessionService.update(context)
        return host
    }

    fun remove(context: Context, host: TermSessionHost) {
        if (hosts.remove(host)) SessionService.update(context)
    }

    /** Linux terminals still open: the root filesystem must not be replaced under them. */
    fun linuxSessionCount() = hosts.count { it.spec.linux }
}

/**
 * Owns one [TerminalSession] (a process on a pty) and forwards its callbacks to whatever
 * window currently shows it.
 */
class TermSessionHost(private val app: Context, val spec: ShellSpec) : TerminalSessionClient {

    /** The window content currently displaying this session, if any. */
    var ui: TerminalContent? = null

    val session = TerminalSession(
        spec.executable,
        spec.cwd,
        spec.args.toTypedArray(),
        spec.env.map { (k, v) -> "$k=$v" }.toTypedArray(),
        TRANSCRIPT_ROWS,
        this,
    )

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

    private companion object {
        const val TRANSCRIPT_ROWS = 3000
    }
}
