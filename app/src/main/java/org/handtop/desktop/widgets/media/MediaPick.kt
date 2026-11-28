package org.handtop.desktop.widgets.media

import java.util.Locale

/** A media session as the choice needs it: its app, and whether it plays (or buffers) now. */
data class SessionInfo(val pkg: String, val playing: Boolean)

/** Which session the player widget shows, apart from Android. */
object MediaPick {

    /**
     * The one to show: one that plays (in Android's order, recent first); otherwise the one
     * shown last while it is there (paused, it stays); otherwise the first; none for no sessions.
     */
    fun choose(sessions: List<SessionInfo>, lastShown: String?): String? {
        sessions.firstOrNull { it.playing }?.let { return it.pkg }
        if (lastShown != null && sessions.any { it.pkg == lastShown }) return lastShown
        return sessions.firstOrNull()?.pkg
    }

    /** The title, or the app's name for a session without one. */
    fun title(title: String?, appName: String): String = title?.takeIf { it.isNotBlank() } ?: appName
}

/** A session's position and how it is written. */
object MediaTime {

    /** Android's PlaybackState.ACTION_SEEK_TO. */
    private const val ACTION_SEEK_TO = 1L shl 8

    /**
     * Where playback is now: the reported [positionMs] at [updatedAtMs], moved on by the time
     * since then at [speed] while [playing]; never before the start or past [durationMs].
     */
    fun position(positionMs: Long, updatedAtMs: Long, speed: Float, playing: Boolean, nowMs: Long, durationMs: Long): Long {
        val moved = if (playing) ((nowMs - updatedAtMs) * speed).toLong() else 0L
        return (positionMs + moved).coerceIn(0L, durationMs.coerceAtLeast(0L))
    }

    /** "3:07" below an hour, "1:12:05" from an hour. */
    fun format(ms: Long): String {
        val total = (ms.coerceAtLeast(0) / 1000)
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    /** Whether the app lets its session be moved to a position. */
    fun canSeek(actions: Long): Boolean = actions and ACTION_SEEK_TO != 0L
}
