# Player Widget Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A 3 × 2 player widget that shows and controls what plays through Android's media sessions (cover, title, artist, app, seek bar with time, ⏮ ⏯ ⏭, ⇄ between apps).

**Architecture:** Pure choice and time logic in `widgets/media/MediaPick.kt` (tested); an Android-side `MediaSessions` that lists and watches sessions through HandTop's notification listener component only while the widget is active; `PlayerWidget` draws the theme's widget card and drives the controllers.

**Tech Stack:** Kotlin, `android.media.session` (`MediaSessionManager`, `MediaController`), Android views, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-11-28-player-widget-design.md`

## Global Constraints

- English code/commits/CHANGELOG/README; Russian ROADMAP and spec; every new string in `values/` and `values-ru/`.
- One player widget, 3 × 2 cells, added from "Add widget…" (name en "Player", ru «Плеер»).
- Session access only through `ComponentName(context, NotificationsService::class.java)`; no new permission. Without access: «Нужен доступ к уведомлениям» + a button to `Notifications.accessSettingsIntent(context)`.
- Subscriptions (sessions list, controller callbacks) and the 1-second time tick exist only while the widget is active; all removed on inactive.
- Choice order: ⇄ pick (until it is gone **or another session starts playing**) → a playing/buffering session (Android's order) → the last shown while it exists → the first → none. The pick lives in the view only (reset by restart or theme change).
- Seek only with `PlaybackState.ACTION_SEEK_TO`; no duration → no bar and no time; unavailable ⏮/⏭ dimmed.
- Readability rule: widget text ≥ 4.5 : 1 (existing cards); the ⏯ glyph is white on `colors.accent`, ≥ 3 : 1 (non-text contrast) in every theme.
- "Open player" button: not in this step.
- Commit after each task; ask the user before pushing. Device check: playing media on the user's phone is the user's to start — ask them to start something (or for permission to start it).

## Review Focus

1. A session that disappears between the list callback and a button press (app killed) — the press does nothing, no crash; the widget moves to the next choice. Pinned by `chooseSkipsAPickThatIsGone` (Task 1) and a guarded controller call (Task 2).
2. A session with no title, no art and no duration (some web players) — title falls back to the app name, art to the app icon, bar hidden. Pinned by `titleFallsBackToTheAppName` (Task 1).
3. Position past the duration or negative (stale `lastPositionUpdateTime`, fast speed) — clamped to 0..duration. Pinned by `positionIsClamped` (Task 1).
4. Notification access revoked while the widget is shown — `SecurityException` from `getActiveSessions` is caught and the widget shows the access state. Pinned by `MediaSessions.start` catching it (Task 2, device check).
5. Dragging the seek bar vs dragging the widget — touches on the bar and buttons go to them, the widget still drags from its other parts. Checked on the device (Task 3).

---

### Task 1: Choice and time logic

**Files:**
- Create: `app/src/main/java/org/handtop/desktop/widgets/media/MediaPick.kt`
- Test: `app/src/test/java/org/handtop/desktop/widgets/media/MediaPickTest.kt`

**Interfaces:**
- Produces:
  - `data class SessionInfo(val pkg: String, val playing: Boolean)` (playing = `STATE_PLAYING` or `STATE_BUFFERING`)
  - `object MediaPick { fun choose(sessions: List<SessionInfo>, picked: String?, lastShown: String?): String?; fun next(sessions: List<SessionInfo>, current: String?): String?; fun pickAfterChange(picked: String?, pkg: String, wasPlaying: Boolean, nowPlaying: Boolean): String?; fun title(title: String?, appName: String): String }`
  - `object MediaTime { fun position(positionMs: Long, updatedAtMs: Long, speed: Float, playing: Boolean, nowMs: Long, durationMs: Long): Long; fun format(ms: Long): String; fun canSeek(actions: Long): Boolean }`

- [ ] **Step 1: Write the failing tests**

```kotlin
private val yt = SessionInfo("com.google.android.youtube", playing = true)
private val ym = SessionInfo("ru.yandex.music", playing = false)
@Test fun playingWinsWithoutAPick() = assertEquals(yt.pkg, MediaPick.choose(listOf(ym, yt), null, null))
@Test fun aPickHolds() = assertEquals(ym.pkg, MediaPick.choose(listOf(yt, ym), ym.pkg, yt.pkg))
@Test fun chooseSkipsAPickThatIsGone() = assertEquals(yt.pkg, MediaPick.choose(listOf(yt), ym.pkg, null))
@Test fun pausedLastShownStays() = assertEquals(ym.pkg, MediaPick.choose(listOf(SessionInfo("a", false), ym), null, ym.pkg))
@Test fun firstWhenNothingElse() = assertEquals("a", MediaPick.choose(listOf(SessionInfo("a", false), ym), null, "gone"))
@Test fun noneWhenEmpty() = assertNull(MediaPick.choose(emptyList(), "x", "y"))
@Test fun nextCycles() {
    val list = listOf(yt, ym, SessionInfo("c", false))
    assertEquals(ym.pkg, MediaPick.next(list, yt.pkg)); assertEquals(yt.pkg, MediaPick.next(list, "c")); assertEquals(yt.pkg, MediaPick.next(list, null))
}
@Test fun anotherAppStartingDropsThePick() {
    assertNull(MediaPick.pickAfterChange(ym.pkg, yt.pkg, wasPlaying = false, nowPlaying = true))
    assertEquals(ym.pkg, MediaPick.pickAfterChange(ym.pkg, yt.pkg, wasPlaying = true, nowPlaying = true))  // just a position update
    assertEquals(ym.pkg, MediaPick.pickAfterChange(ym.pkg, ym.pkg, wasPlaying = false, nowPlaying = true)) // the picked one itself
}
@Test fun titleFallsBackToTheAppName() { assertEquals("YouTube", MediaPick.title(null, "YouTube")); assertEquals("YouTube", MediaPick.title(" ", "YouTube")); assertEquals("Song", MediaPick.title("Song", "YouTube")) }
@Test fun positionAdvancesWhilePlaying() = assertEquals(15_000L, MediaTime.position(10_000, 1_000, 1f, true, 6_000, 60_000))
@Test fun positionStaysWhilePaused() = assertEquals(10_000L, MediaTime.position(10_000, 1_000, 1f, false, 6_000, 60_000))
@Test fun positionIsClamped() { assertEquals(60_000L, MediaTime.position(59_000, 0, 2f, true, 10_000, 60_000)); assertEquals(0L, MediaTime.position(-5, 0, 1f, false, 0, 60_000)) }
@Test fun formats() { assertEquals("0:05", MediaTime.format(5_000)); assertEquals("3:07", MediaTime.format(187_000)); assertEquals("1:12:05", MediaTime.format(4_325_000)) }
@Test fun seekOnlyWhenAllowed() { assertTrue(MediaTime.canSeek(PlaybackStateActions.SEEK_TO)); assertFalse(MediaTime.canSeek(0L)) }
```
(`PlaybackStateActions.SEEK_TO` = a test-local `const val SEEK_TO = 1L shl 8`, Android's `ACTION_SEEK_TO` value; `MediaTime.canSeek` checks that bit — Android's constant is a stub in JVM tests.)

- [ ] **Step 2: Run to verify failure.** `./gradlew :app:testDebugUnitTest --tests 'org.handtop.desktop.widgets.media.*'` → compilation FAILS.

- [ ] **Step 3: Implement.** `choose`: picked if present in `sessions`; else first `playing`; else `lastShown` if present; else first; else null. `next`: the one after `current` in list order, wrapping; `current` absent → first. `pickAfterChange`: null when `pkg != picked && !wasPlaying && nowPlaying`, otherwise `picked`. `position`: `positionMs + (playing ? (nowMs − updatedAtMs) × speed : 0)` clamped to `0..durationMs`. `format`: `m:ss` below an hour, `h:mm:ss` from an hour. `canSeek`: `actions and (1L shl 8) != 0L`.

- [ ] **Step 4: Run tests.** `./gradlew :app:testDebugUnitTest` → PASS.

- [ ] **Step 5: Commit** — `git commit -m "Player widget: which session to show, and its time"`.

---

### Task 2: Media sessions, watched while the widget is shown

**Files:**
- Create: `app/src/main/java/org/handtop/desktop/widgets/media/MediaSessions.kt`

**Interfaces:**
- Consumes: Task 1 (`SessionInfo`, `MediaPick.pickAfterChange`).
- Produces: `class MediaSessions(context: Context) { fun start(onChange: (startedPlaying: String?) -> Unit): Boolean /* false: no access */; fun stop(); val controllers: List<MediaController>; fun infos(): List<SessionInfo> }` — `onChange` gets the package that just moved into playing (or null for any other change).

- [ ] **Step 1: Implement.** `start`: `MediaSessionManager.getActiveSessions(ComponentName(context, NotificationsService::class.java))` in a `try` (`SecurityException` → return false); `addOnActiveSessionsChangedListener(listener, component, Handler(main))`; for each controller register a `MediaController.Callback` (`onPlaybackStateChanged`, `onMetadataChanged`, `onSessionDestroyed`) on the main handler, remembering each controller's last "playing" to report a transition; re-register callbacks when the list changes (unregister the old ones first — never two callbacks on one session). `stop`: remove the list listener and every callback. Every controller call elsewhere goes through `runCatching` (a session can die any time).

- [ ] **Step 2: Build.** `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL (Android-only code; behaviour checked with the widget in Task 3).

- [ ] **Step 3: Commit** — `git commit -m "Player widget: watching Android's media sessions"`.

---

### Task 3: The player widget

**Files:**
- Create: `app/src/main/java/org/handtop/desktop/widgets/PlayerWidget.kt`, drawables `player_prev.xml`, `player_play.xml`, `player_pause.xml`, `player_next.xml`, `player_switch.xml`, `player_note.xml` (white vector glyphs, 24 dp)
- Modify: `widgets/Widgets.kt` (`WidgetKind.PLAYER("player", CellSize(3, 2))`), `widgets/WidgetView.kt` (factory), `shell/DesktopIcons.kt` (`widgetName`), strings en/ru
- Test: `app/src/test/java/org/handtop/desktop/widgets/WidgetsJsonTest.kt` (kind), `app/src/test/java/org/handtop/desktop/ui/ThemeTest.kt` (play glyph contrast)

**Interfaces:**
- Consumes: Task 1, Task 2, `WidgetView`, `Theme.widgetColors`/`widgetMeter`, `Notifications.isAccessGranted/accessSettingsIntent`.

- [ ] **Step 1: Write the failing tests**

```kotlin
// WidgetsJsonTest.kindsAndSizes
assertEquals(CellSize(3, 2), WidgetKind.PLAYER.size); assertEquals(WidgetKind.PLAYER, WidgetKind.fromKey("player"))
// ThemeTest
@Test fun playGlyphShowsOnTheAccent() { for (t in listOf(ModernTheme, ClassicTheme, SoftTheme, GlossyTheme)) assertReadable("${t.id} play", 0xFFFFFFFF.toInt(), t.colors.accent, 3.0) }
```

- [ ] **Step 2: Run to verify failure.** → compilation FAILS (`PLAYER`).

- [ ] **Step 3: Implement `PlayerWidget(context, host) : WidgetView(context, WidgetKind.PLAYER, host)`.**
  - Layout (3 × 2): cover 56 dp (rounded 6 dp; Classic square) + column [title (13, bold), artist (11, dim), "⇄ App" row (10, dim; ⇄ shown only with 2+ sessions)]; below: seek bar (`widgetMeter(context, colors.accent)` as a `SeekBar` progress drawable with a small round thumb in `colors.accent`; height 6 dp), times row (10, dim) "1:12:05 … 3:10:00"; below: ⏮ ⏯ ⏭ centred, ⏯ a 32 dp circle of `colors.accent` with the white glyph, ⏮/⏭ glyphs in `colors.text` (alpha 0.35 when the action is missing).
  - States: no access → message `widget_player_no_access` + a small button `widget_player_open_access` → `context.startActivity(Notifications.accessSettingsIntent(context))`; no session → `player_note` glyph + `widget_player_nothing`; a session → the content above.
  - `onActive(true)`: `MediaSessions.start { started -> picked = MediaPick.pickAfterChange(picked, started…); render() }` (false → no-access state); a 1 s tick updates only the bar and times while the shown session plays; `onActive(false)`: stop both.
  - Cover: `METADATA_KEY_ART` → `ALBUM_ART` → `DISPLAY_ICON` → the app's icon (`packageManager.getApplicationIcon`), scaled once per track to 56 dp.
  - Buttons: ⏯ → `transportControls.pause()` when playing else `play()`; ⏮/⏭ → `skipToPrevious/Next`; seek bar → `seekTo` on release (enabled only when `MediaTime.canSeek`); ⇄ → `picked = MediaPick.next(...)`. Cover or title tap → `controller.sessionActivity?.send()` with the `ActivityOptions` used by `Notifications.open` (launch display = this display); none → `packageManager.getLaunchIntentForPackage(pkg)`.
  - Strings: `widget_player` "Player"/«Плеер», `widget_player_nothing` "Nothing is playing"/«Ничего не играет», `widget_player_no_access` "Needs access to notifications"/«Нужен доступ к уведомлениям», `widget_player_open_access` "Allow…"/«Разрешить…», content descriptions `player_previous`, `player_play_pause`, `player_next`, `player_switch`.

- [ ] **Step 4: Run tests.** `./gradlew :app:testDebugUnitTest` → PASS.

- [ ] **Step 5: Device check** (DeX or phone, the user starts the media or allows me to): add Player; YouTube in background → title, cover, time running, pause/play, next, seek by dragging; a second app (Yandex Music or another) → ⇄ switches, starting the other app's playback drops the pick; pause → stays; close all → «Ничего не играет»; cover tap opens the app; widget drags from its free area, the bar and buttons do not move it; four themes. Without notification access (only if it is already off — switching it is the user's) → message and the settings button.

- [ ] **Step 6: Commit** — `git commit -m "Player widget: now playing, seek, controls"`.

---

### Task 4: Docs

- [ ] CHANGELOG (Unreleased): "Player widget: what plays in Android apps (YouTube in the background, music apps) with its cover, a seek bar and ⏮ ⏯ ⏭; ⇄ switches between apps playing at once; a tap on the cover opens the app. Uses the notification access HandTop already asks for." README widgets line; ROADMAP line; spec «Как вышло». Commit; ask the user before pushing.
