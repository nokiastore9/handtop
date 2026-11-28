package org.handtop.desktop.widgets.media

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import org.handtop.desktop.service.NotificationsService

/**
 * What plays through Android's media sessions, watched while the player widget is shown. Apps'
 * sessions are visible to HandTop through its notification listener (the access the
 * notification center uses); nothing is watched between [start] and [stop].
 */
class MediaSessions(context: Context) {

    private val app = context.applicationContext
    private val manager = app.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(app, NotificationsService::class.java)
    private val main = Handler(Looper.getMainLooper())

    /** The sessions now, in Android's order (recent first). */
    var controllers: List<MediaController> = emptyList()
        private set

    private var onChange: ((String?) -> Unit)? = null
    private val callbacks = HashMap<MediaController, MediaController.Callback>()
    private val playing = HashMap<MediaController, Boolean>()

    private val listListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> watch(list.orEmpty()) }

    /**
     * Starts watching; [onChange] hears of every change, with the app that just started playing
     * (or null). False without access to the sessions (no notification access).
     */
    fun start(onChange: (startedPlaying: String?) -> Unit): Boolean {
        stop()
        this.onChange = onChange
        val list = try {
            manager?.getActiveSessions(component) ?: return false
        } catch (_: SecurityException) {
            this.onChange = null
            return false
        }
        try {
            manager.addOnActiveSessionsChangedListener(listListener, component, main)
        } catch (_: SecurityException) {
            this.onChange = null
            return false
        }
        watch(list)
        return true
    }

    fun stop() {
        runCatching { manager?.removeOnActiveSessionsChangedListener(listListener) }
        unwatchAll()
        controllers = emptyList()
        onChange = null
    }

    /** The sessions for choosing ([MediaPick.choose]). */
    fun infos(): List<SessionInfo> = controllers.map { SessionInfo(idOf(it), it.packageName, isPlaying(it)) }

    private fun watch(list: List<MediaController>) {
        // One callback per session: the old ones go first.
        unwatchAll()
        controllers = list
        for (c in list) {
            playing[c] = isPlaying(c)
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    val was = playing[c] == true
                    val now = isPlaying(c)
                    playing[c] = now
                    onChange?.invoke(if (!was && now) c.packageName else null)
                }

                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    onChange?.invoke(null)
                }

                override fun onSessionDestroyed() {
                    onChange?.invoke(null)
                }
            }
            runCatching { c.registerCallback(callback, main) }.onSuccess { callbacks[c] = callback }
        }
        onChange?.invoke(null)
    }

    private fun unwatchAll() {
        for ((c, callback) in callbacks) runCatching { c.unregisterCallback(callback) }
        callbacks.clear()
        playing.clear()
    }

    companion object {
        /** A session's identity: its token, the same for every controller of that session. */
        fun idOf(c: MediaController): String = runCatching { c.sessionToken.hashCode().toString() }.getOrDefault(c.packageName)

        fun isPlaying(c: MediaController): Boolean = runCatching {
            val state = c.playbackState?.state
            state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
        }.getOrDefault(false)
    }
}
