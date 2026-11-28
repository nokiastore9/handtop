package org.handtop.desktop.widgets.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPickTest {

    private val yt = SessionInfo("yt", "com.google.android.youtube", playing = true)
    private val ym = SessionInfo("ym", "ru.yandex.music", playing = false)

    /** Android's PlaybackState.ACTION_SEEK_TO (a stub in JVM tests). */
    private val seekTo = 1L shl 8

    @Test
    fun playingWinsWithoutAPick() = assertEquals(yt.id, MediaPick.choose(listOf(ym, yt), null))

    @Test
    fun pausedLastShownStays() = assertEquals(ym.id, MediaPick.choose(listOf(SessionInfo("a", "a", false), ym), ym.id))

    @Test
    fun firstWhenNothingElse() = assertEquals("a", MediaPick.choose(listOf(SessionInfo("a", "a", false), ym), "gone"))

    @Test
    fun aPlayingAppWinsOverThePausedOneShown() = assertEquals(yt.id, MediaPick.choose(listOf(ym, yt), ym.id))

    @Test
    fun twoSessionsOfOneAppTheOnePlayingIsShown() {
        // Two browser tabs: B paused but first in Android's order, A playing.
        val b = SessionInfo("tab-b", "org.mozilla.firefox", playing = false)
        val a = SessionInfo("tab-a", "org.mozilla.firefox", playing = true)
        assertEquals("tab-a", MediaPick.choose(listOf(b, a), "tab-b"))
    }

    @Test
    fun aCoverThatArrivesLateIsANewCover() {
        // Media3 apps send the title first, the artwork a moment later: the cover must be redrawn.
        val before = MediaPick.coverKey("ru.yandex.music", "Song", artWidth = null)
        val after = MediaPick.coverKey("ru.yandex.music", "Song", artWidth = 512)
        assertNotEquals(before, after)
        assertEquals(after, MediaPick.coverKey("ru.yandex.music", "Song", artWidth = 512))
    }

    @Test
    fun noneWhenEmpty() = assertNull(MediaPick.choose(emptyList(), "y"))

    @Test
    fun titleFallsBackToTheAppName() {
        assertEquals("YouTube", MediaPick.title(null, "YouTube"))
        assertEquals("YouTube", MediaPick.title(" ", "YouTube"))
        assertEquals("Song", MediaPick.title("Song", "YouTube"))
    }

    @Test
    fun positionAdvancesWhilePlaying() = assertEquals(15_000L, MediaTime.position(10_000, 1_000, 1f, true, 6_000, 60_000))

    @Test
    fun positionStaysWhilePaused() = assertEquals(10_000L, MediaTime.position(10_000, 1_000, 1f, false, 6_000, 60_000))

    @Test
    fun positionIsClamped() {
        assertEquals(60_000L, MediaTime.position(59_000, 0, 2f, true, 10_000, 60_000))
        assertEquals(0L, MediaTime.position(-5, 0, 1f, false, 0, 60_000))
    }

    @Test
    fun formats() {
        assertEquals("0:05", MediaTime.format(5_000))
        assertEquals("3:07", MediaTime.format(187_000))
        assertEquals("1:12:05", MediaTime.format(4_325_000))
        assertEquals("0:00", MediaTime.format(-1))
    }

    @Test
    fun seekOnlyWhenAllowed() {
        assertTrue(MediaTime.canSeek(seekTo))
        assertTrue(MediaTime.canSeek(seekTo or 4L))
        assertFalse(MediaTime.canSeek(0L))
    }
}
