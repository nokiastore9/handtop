package org.handtop.desktop.widgets

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import org.handtop.desktop.R
import org.handtop.desktop.notify.Notifications
import org.handtop.desktop.ui.Themes
import org.handtop.desktop.ui.dp
import org.handtop.desktop.ui.dpi
import org.handtop.desktop.ui.roundedRect
import org.handtop.desktop.widgets.media.MediaPick
import org.handtop.desktop.widgets.media.MediaSessions
import org.handtop.desktop.widgets.media.MediaTime

/**
 * What plays through Android's media sessions (YouTube in the background, music apps, later
 * HandTop's own player): cover, title, artist, the app, a seek bar with the time, ⏮ ⏯ ⏭. With
 * several apps, the one playing now. A tap opens the app. Watches sessions only while shown.
 */
@SuppressLint("ViewConstructor")
class PlayerWidget(context: Context, host: WidgetHost) : WidgetView(context, WidgetKind.PLAYER, host) {

    private val accent = Themes.current.colors.accent
    private val sessions = MediaSessions(context)

    private val cover = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        clipToOutline = true
        background = roundedRect(colors.track, if (Themes.current.roundedCorners) dp(6f) else 0f)
    }
    private val title = label(13f).apply { setTypeface(typeface, Typeface.BOLD) }
    private val artist = label(11f, dim = true)
    private val app = label(10f, dim = true)
    private val seek = SeekBar(context).apply {
        max = SEEK_MAX
        progressDrawable = Themes.current.widgetMeter(context, accent)
        thumb = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(accent)
            setSize(dpi(12), dpi(12))
        }
        splitTrack = false
        setPadding(dpi(6), 0, dpi(6), 0)
        // A thin track in a taller touch area.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            minHeight = dpi(5)
            maxHeight = dpi(5)
        }
    }
    private val elapsed = label(10f, dim = true)
    private val length = label(10f, dim = true)
    private val prev = glyph(R.drawable.player_prev, R.string.player_previous, colors.text, 30)
    private val next = glyph(R.drawable.player_next, R.string.player_next, colors.text, 30)
    private val playIcon = ImageView(context).apply { setColorFilter(0xFFFFFFFF.toInt()) }
    private val play = FrameLayout(context).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent) }
        contentDescription = context.getString(R.string.player_play_pause)
        addView(playIcon, LayoutParams(dpi(20), dpi(20), Gravity.CENTER))
    }

    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val messageIcon = ImageView(context).apply { setImageResource(R.drawable.player_note); setColorFilter(colors.textDim) }
    private val message = label(13f)
    private val allow = TextView(context).apply {
        setText(R.string.widget_player_open_access)
        textSize = 12f
        setTextColor(colors.text)
        background = roundedRect(colors.track, dp(8f))
        setPadding(dpi(10), dpi(4), dpi(10), dpi(4))
        setOnClickListener { openAccessSettings() }
    }
    private val messageBox = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
    }

    private var active = false
    private var lastShown: String? = null
    private var shown: MediaController? = null
    private var coverKey: String? = null
    private var seeking = false
    private val tick = object : Runnable {
        override fun run() {
            updateTime()
            val c = shown
            if (active && c != null && MediaSessions.isPlaying(c)) postDelayed(this, 1_000)
        }
    }

    init {
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(cover, LinearLayout.LayoutParams(dpi(56), dpi(56)).apply { marginEnd = dpi(10) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(artist)
                addView(app, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(2) })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        content.addView(seek, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpi(20)).apply { topMargin = dpi(4) })
        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(elapsed, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(length)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(prev, LinearLayout.LayoutParams(dpi(36), dpi(36)))
            addView(play, LinearLayout.LayoutParams(dpi(38), dpi(38)).apply { marginStart = dpi(14); marginEnd = dpi(14) })
            addView(next, LinearLayout.LayoutParams(dpi(36), dpi(36)))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(2) })

        messageBox.addView(messageIcon, LinearLayout.LayoutParams(dpi(36), dpi(36)))
        messageBox.addView(message.apply { gravity = Gravity.CENTER; setPadding(0, dpi(6), 0, dpi(6)) })
        messageBox.addView(allow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(messageBox, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        play.setOnClickListener { control { c -> if (MediaSessions.isPlaying(c)) c.transportControls.pause() else c.transportControls.play() } }
        prev.setOnClickListener { control { it.transportControls.skipToPrevious() } }
        next.setOnClickListener { control { it.transportControls.skipToNext() } }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) elapsed.text = MediaTime.format(progress.toLong() * duration() / SEEK_MAX)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                seeking = true
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                seeking = false
                val position = bar.progress.toLong() * duration() / SEEK_MAX
                control { it.transportControls.seekTo(position) }
            }
        })
        showMessage(R.string.widget_player_nothing, access = false)
    }

    private fun glyph(icon: Int, description: Int, tint: Int, sizeDp: Int) = ImageView(context).apply {
        setImageResource(icon)
        setColorFilter(tint)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val pad = dpi((36 - sizeDp).coerceAtLeast(0) / 2)
        setPadding(pad, pad, pad, pad)
        contentDescription = context.getString(description)
        isClickable = true
    }

    /** Runs [action] on the shown session; a session that ended meanwhile is let go. */
    private fun control(action: (MediaController) -> Unit) {
        val c = shown ?: return
        runCatching { action(c) }
    }

    override fun onActive(active: Boolean) {
        this.active = active
        removeCallbacks(tick)
        if (!active) {
            sessions.stop()
            return
        }
        if (!Notifications.isAccessGranted(context)) return showMessage(R.string.widget_player_no_access, access = true)
        val watching = sessions.start { render() }
        if (!watching) showMessage(R.string.widget_player_no_access, access = true)
    }

    private fun render() {
        val pkg = MediaPick.choose(sessions.infos(), lastShown)
        val c = sessions.controllers.firstOrNull { it.packageName == pkg }
        shown = c
        removeCallbacks(tick)
        if (c == null) return showMessage(R.string.widget_player_nothing, access = false)
        lastShown = pkg
        content.visibility = VISIBLE
        messageBox.visibility = GONE

        val metadata = runCatching { c.metadata }.getOrNull()
        val state = runCatching { c.playbackState }.getOrNull()
        val appName = appName(c.packageName)
        val name = MediaPick.title(metadata?.getString(MediaMetadata.METADATA_KEY_TITLE), appName)
        title.text = name
        val by = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        artist.text = by.orEmpty()
        artist.visibility = if (by.isNullOrBlank()) GONE else VISIBLE
        app.text = appName

        val key = c.packageName + "\u0000" + name
        if (key != coverKey) {
            coverKey = key
            val art = metadata?.let {
                it.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: it.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            }
            if (art != null) cover.setImageBitmap(scaled(art))
            else cover.setImageDrawable(runCatching { context.packageManager.getApplicationIcon(c.packageName) }.getOrNull())
        }

        val actions = state?.actions ?: 0L
        val playing = MediaSessions.isPlaying(c)
        playIcon.setImageResource(if (playing) R.drawable.player_pause else R.drawable.player_play)
        prev.alpha = if (actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L) 1f else 0.35f
        next.alpha = if (actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L) 1f else 0.35f
        val hasLength = duration() > 0
        seek.visibility = if (hasLength) VISIBLE else INVISIBLE
        elapsed.visibility = seek.visibility
        length.visibility = seek.visibility
        seek.isEnabled = MediaTime.canSeek(actions)
        updateTime()
        if (playing && active) postDelayed(tick, 1_000)
    }

    private fun duration(): Long =
        runCatching { shown?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) }.getOrNull()?.coerceAtLeast(0) ?: 0L

    /** The bar and the times, from the session's last reported position. */
    private fun updateTime() {
        val c = shown ?: return
        val total = duration()
        if (total <= 0 || seeking) return
        val state = runCatching { c.playbackState }.getOrNull() ?: return
        val now = MediaTime.position(state.position, state.lastPositionUpdateTime, state.playbackSpeed, MediaSessions.isPlaying(c), SystemClock.elapsedRealtime(), total)
        seek.progress = (now * SEEK_MAX / total).toInt()
        elapsed.text = MediaTime.format(now)
        length.text = MediaTime.format(total)
    }

    private fun showMessage(text: Int, access: Boolean) {
        shown = null
        content.visibility = GONE
        messageBox.visibility = VISIBLE
        message.setText(text)
        allow.visibility = if (access) VISIBLE else GONE
    }

    private fun appName(pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun scaled(art: Bitmap): Bitmap {
        val size = dpi(56)
        if (art.width <= size * 2 && art.height <= size * 2) return art
        val ratio = art.width.toFloat() / art.height
        val w = if (ratio >= 1) size * 2 else (size * 2 * ratio).toInt()
        val h = if (ratio >= 1) (size * 2 / ratio).toInt() else size * 2
        return Bitmap.createScaledBitmap(art, w.coerceAtLeast(1), h.coerceAtLeast(1), true)
    }

    /** A tap on the widget: the app that plays, on this display. */
    override fun onTap() {
        val c = shown ?: return
        val options = ActivityOptions.makeBasic()
        display?.let { options.launchDisplayId = it.displayId }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("DEPRECATION")
            options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        }
        val session: PendingIntent? = runCatching { c.sessionActivity }.getOrNull()
        val opened = session != null && runCatching { session.send(context, 0, null, null, null, null, options.toBundle()) }.isSuccess
        if (opened) return
        val launch: Intent = context.packageManager.getLaunchIntentForPackage(c.packageName) ?: return
        runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options.toBundle()) }
    }

    private fun openAccessSettings() {
        runCatching { context.startActivity(Notifications.accessSettingsIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private companion object {
        const val SEEK_MAX = 1000
    }
}
