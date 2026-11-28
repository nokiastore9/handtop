package org.handtop.desktop.widgets.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPickTest {

    private val yt = SessionInfo("com.google.android.youtube", playing = true)
    private val ym = SessionInfo("ru.yandex.music", playing = false)

    /** Android's PlaybackState.ACTION_SEEK_TO (a stub in JVM tests). */
    private val seekTo = 1L shl 8

    @Test
    fun playingWinsWithoutAPick() = assertEquals(yt.pkg, MediaPick.choose(listOf(ym, yt), null, null))

    @Test
    fun aPickHolds() = assertEquals(ym.pkg, MediaPick.choose(listOf(yt, ym), ym.pkg, yt.pkg))

    @Test
    fun chooseSkipsAPickThatIsGone() = assertEquals(yt.pkg, MediaPick.choose(listOf(yt), ym.pkg, null))

    @Test
    fun pausedLastShownStays() = assertEquals(ym.pkg, MediaPick.choose(listOf(SessionInfo("a", false), ym), null, ym.pkg))

    @Test
    fun firstWhenNothingElse() = assertEquals("a", MediaPick.choose(listOf(SessionInfo("a", false), ym), null, "gone"))

    @Test
    fun noneWhenEmpty() = assertNull(MediaPick.choose(emptyList(), "x", "y"))

    @Test
    fun nextCycles() {
        val list = listOf(yt, ym, SessionInfo("c", false))
        assertEquals(ym.pkg, MediaPick.next(list, yt.pkg))
        assertEquals(yt.pkg, MediaPick.next(list, "c"))
        assertEquals(yt.pkg, MediaPick.next(list, null))
        assertNull(MediaPick.next(emptyList(), null))
    }

    @Test
    fun anotherAppStartingDropsThePick() {
        assertNull(MediaPick.pickAfterChange(ym.pkg, yt.pkg, wasPlaying = false, nowPlaying = true))
        assertEquals(ym.pkg, MediaPick.pickAfterChange(ym.pkg, yt.pkg, wasPlaying = true, nowPlaying = true)) // a position update
        assertEquals(ym.pkg, MediaPick.pickAfterChange(ym.pkg, ym.pkg, wasPlaying = false, nowPlaying = true)) // the picked one itself
        assertEquals(ym.pkg, MediaPick.pickAfterChange(ym.pkg, yt.pkg, wasPlaying = true, nowPlaying = false)) // another one paused
        assertNull(MediaPick.pickAfterChange(null, yt.pkg, wasPlaying = false, nowPlaying = true))
    }

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
