# Desktop Widgets (Clock, Weather, Linux Load) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Clock, weather and Linux-load widgets that the user adds to the desktop, moves on the icons' grid and removes, drawn in the current theme.

**Architecture:** A pure placement module (`DesktopGrid`) decides where icons and widgets go; `Widgets` stores the user's widgets next to the shortcuts; `DesktopIcons` hosts widget views as multi-cell children with the same gestures as icons. Each theme draws a widget card (`Theme.widgetBackground`, `Theme.widgetColors`). Widgets refresh only while HandTop is in front.

**Tech Stack:** Kotlin, Android views (minSdk 26, targetSdk 37), `HttpURLConnection` + `org.json`, `LocationManager`/`Geocoder` (no Google services), JUnit 4.

**Spec:** `docs/superpowers/specs/2026-11-28-desktop-widgets-design.md`

## Global Constraints

- Code, comments, commits, CHANGELOG, README in English; ROADMAP and spec in Russian; every new UI string in `values/strings.xml` and `values-ru/strings.xml`.
- No widgets on a fresh or upgraded desktop: widgets appear only when the user adds them; each kind at most once.
- Sizes in cells: Clock 3 × 1, Weather 3 × 2, Linux 3 × 1. Same grid as icons (cell 92 × 90 dp, gap 4 dp, padding 12 dp).
- Layout order: icons with a saved cell → widgets → icons without a cell. A widget never moves an icon; a dropped icon on a widget's cell goes back; a widget dropped where it does not fit goes back; "Arrange icons" moves icons only.
- Updates only while HandTop's desktop activity is resumed: clock per minute (per second with seconds on), Linux every 2 s, weather when older than 30 min (on add, on resume, every 30 min).
- Weather: Open-Meteo only (`api.open-meteo.com/v1/forecast`, `geocoding-api.open-meteo.com/v1/search`), no keys; coordinates rounded to 0.01° before sending; permission `ACCESS_COARSE_LOCATION` only, asked when a weather widget is added; place name from `android.location.Geocoder`; no Google Play services.
- Readability rule (as in themes): widget text ≥ 4.5 : 1 on its card; a translucent card is checked over white and over black.
- Modern, Classic, Soft, Glossy keep every existing look; icons behave as today when there are no widgets.
- One new test-only dependency: `testImplementation("org.json:json:20240303")` (Android's `org.json` is a stub in JVM tests).
- Commit after each task; ask the user before pushing. Device checks on DeX (display 61) with `bench/ui.sh` helpers; check HandTop is in front before every tap; the location permission dialog is the user's decision — ask them to answer it.

## Review Focus

1. A desktop already full of icons when a widget is added — the widget must still appear somewhere reachable (top-left overlap rule) and be movable to a free place. Pinned by `widgetWithNoRoomOverlapsTopLeft` (Task 1).
2. Phone ↔ DeX switch and scale change with widgets placed near the right edge — widget moves to the nearest fit, its saved cell is kept for the larger screen. Pinned by `smallerGridMovesWidgetButKeepsSavedCell` (Task 1).
3. Weather with no network at all on first add (nothing cached) — widget shows a "no data yet" state, not a crash or empty card. Pinned by `weatherStateWithoutDataOrNetwork` (Task 5).
4. Open-Meteo answer missing optional parts (no `hourly`, fewer than 7 days) — parse what is there. Pinned by `parseForecastToleratesMissingParts` (Task 5).
5. A process that ends between listing `/proc` and reading it during a Linux sample — skipped, no crash, no negative CPU. Pinned by `cpuPercentNeverNegative` and `statTicksOfGarbageIsNull` (Task 4).

---

### Task 1: Placement and storage

**Files:**
- Create: `app/src/main/java/org/handtop/desktop/widgets/DesktopGrid.kt`, `app/src/main/java/org/handtop/desktop/widgets/Widgets.kt`
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts` (test dependency)
- Test: `app/src/test/java/org/handtop/desktop/widgets/DesktopGridTest.kt`, `app/src/test/java/org/handtop/desktop/widgets/WidgetsJsonTest.kt`

**Interfaces:**
- Produces:
  - `data class CellSize(val columns: Int, val rows: Int)`
  - `enum class WidgetKind(val key: String, val size: CellSize) { CLOCK("clock", CellSize(3, 1)), WEATHER("weather", CellSize(3, 2)), LINUX("linux", CellSize(3, 1)) }` with `companion fun fromKey(key: String?): WidgetKind?`
  - `data class PlacedWidget(val kind: WidgetKind, val cell: Cell?, val settings: Map<String, String> = emptyMap())`
  - `data class Layout(val icons: List<Cell>, val widgets: List<Cell>)`
  - `object DesktopGrid`:
    - `fun layOut(columns: Int, rows: Int, iconCells: List<Cell?>, widgets: List<PlacedWidget>): Layout` — `iconCells` are the icons' saved cells in icon order (null = none);
    - `fun firstFree(columns: Int, rows: Int, size: CellSize, taken: Set<Cell>): Cell?` — rows top→bottom, in each row columns right→left;
    - `fun fits(columns: Int, rows: Int, size: CellSize, at: Cell, taken: Set<Cell>): Boolean`;
    - `fun cellsOf(at: Cell, size: CellSize): Set<Cell>`.
  - `object Widgets` (prefs `desktop`, key `widgets`): `load(context): List<PlacedWidget>`, `add(context, kind, cell)`, `remove(context, kind)`, `move(context, kind, cell)`, `setSetting(context, kind, key, value: String?)` (null removes), `addListener/removeListener(() -> Unit)`; pure `toJson(list): String` and `fromJson(text: String?): List<PlacedWidget>` (bad or missing → empty list; unknown kinds dropped).

- [ ] **Step 1: Add the test dependency** — `org-json = { group = "org.json", name = "json", version = "20240303" }` in `libs.versions.toml`, `testImplementation(libs.org.json)` in `app/build.gradle.kts`.

- [ ] **Step 2: Write the failing tests** (`DesktopGridTest`):

```kotlin
private val clock = PlacedWidget(WidgetKind.CLOCK, null)
@Test fun noWidgetsKeepsTodaysIconOrder() {
    // 6 icons, none saved, grid 8 x 4: column-first, as DesktopIcons.place() does today.
    val l = DesktopGrid.layOut(8, 4, List(6) { null }, emptyList())
    assertEquals(listOf(Cell(0,0), Cell(0,1), Cell(0,2), Cell(0,3), Cell(1,0), Cell(1,1)), l.icons)
}
@Test fun newWidgetGoesTopRight() = assertEquals(Cell(5, 0), DesktopGrid.layOut(8, 4, List(6) { null }, listOf(clock)).widgets[0])
@Test fun savedIconsComeBeforeWidgetsAndUnsavedIconsFlowAround() {
    val w = PlacedWidget(WidgetKind.WEATHER, Cell(0, 0))           // 3 x 2 at the top left
    val l = DesktopGrid.layOut(8, 4, listOf(Cell(1, 0), null), listOf(w))
    assertEquals(Cell(1, 0), l.icons[0])                           // saved icon keeps its cell…
    assertNotEquals(Cell(0, 0), l.widgets[0])                      // …so the widget moves
    assertTrue(l.icons[1] !in DesktopGrid.cellsOf(l.widgets[0], w.kind.size)) // unsaved icon avoids the widget
}
@Test fun widgetKeepsItsCellWhenAnIconIsAdded() {
    val w = PlacedWidget(WidgetKind.CLOCK, Cell(0, 2))
    val before = DesktopGrid.layOut(8, 4, List(2) { null }, listOf(w)).widgets[0]
    val after = DesktopGrid.layOut(8, 4, List(3) { null }, listOf(w)).widgets[0]
    assertEquals(Cell(0, 2), before); assertEquals(before, after)
}
@Test fun smallerGridMovesWidgetButKeepsSavedCell() {
    val w = PlacedWidget(WidgetKind.CLOCK, Cell(9, 0))
    val l = DesktopGrid.layOut(6, 4, emptyList(), listOf(w))
    assertTrue(DesktopGrid.fits(6, 4, w.kind.size, l.widgets[0], emptySet()))
    assertEquals(Cell(9, 0), w.cell)                               // the input is not changed
}
@Test fun widgetWithNoRoomOverlapsTopLeft() =
    assertEquals(Cell(0, 0), DesktopGrid.layOut(3, 1, List(3) { Cell(it, 0) }, listOf(clock)).widgets[0])
@Test fun fitsRejectsTakenAndOffGrid() {
    assertFalse(DesktopGrid.fits(8, 4, CellSize(3, 1), Cell(6, 0), emptySet()))
    assertFalse(DesktopGrid.fits(8, 4, CellSize(3, 1), Cell(0, 0), setOf(Cell(2, 0))))
    assertTrue(DesktopGrid.fits(8, 4, CellSize(3, 1), Cell(5, 3), emptySet()))
}
```
and `WidgetsJsonTest`: round trip of `[CLOCK at (5,0) {seconds=true}, WEATHER no cell {mode=city, city=Paris}]` equals input; `fromJson(null)` and `fromJson("garbage")` are empty; an entry with kind `"radio"` is dropped.

- [ ] **Step 3: Run to verify failure.** Run: `./gradlew :app:testDebugUnitTest --tests 'org.handtop.desktop.widgets.*'`. Expected: compilation FAILS (`DesktopGrid` unresolved).

- [ ] **Step 4: Implement `DesktopGrid` and `Widgets`.** `layOut`: (1) icons with a saved cell inside the grid and free, as `DesktopIcons.place` today; (2) each widget at its saved cell if it fits, otherwise the free position nearest to it (squared distance of top-left cells), otherwise `firstFree`, otherwise `Cell(0, 0)`; (3) icons without a cell column-first through cells not taken, exactly as today's loop. `Widgets` follows `Shortcuts` (same prefs file, listeners called on every change).

- [ ] **Step 5: Run tests.** Run: `./gradlew :app:testDebugUnitTest`. Expected: PASS.

- [ ] **Step 6: Commit** — `git commit -m "Desktop widgets: placement on the icons' grid and storage"`.

---

### Task 2: Widget cards in each theme

**Files:**
- Modify: `ui/Theme.kt`, `ui/ModernTheme.kt`, `ui/ClassicTheme.kt`, `ui/SoftTheme.kt`, `ui/GlossyTheme.kt`
- Test: `app/src/test/java/org/handtop/desktop/ui/ThemeTest.kt`

**Interfaces:**
- Produces: `data class WidgetColors(val text: Int, val textDim: Int, val track: Int, val cpu: Int, val memory: Int, val under: IntArray)` (`under` = the colours text sits on: the card, or each stop of its gradient); `abstract val widgetColors: WidgetColors` and `abstract fun widgetBackground(context: Context): Drawable` in `Theme`; `abstract fun widgetMeter(context: Context, fill: Int): Drawable` (a progress bar's layer list: track + fill clipped by level).

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun widgetTextReadsInEveryTheme() {
    for (t in listOf(ModernTheme, ClassicTheme, SoftTheme, GlossyTheme)) with(t.widgetColors) {
        for (bg in under) { assertReadable("${t.id} text", text, bg); assertReadable("${t.id} dim", textDim, bg) }
    }
}
@Test fun modernWidgetCardIsDarkGlass() = with(ModernTheme.widgetColors) {
    assertEquals(0xB30F1420.toInt(), under.single()); assertEquals(0xFFFFFFFF.toInt(), text)
}
```

- [ ] **Step 2: Run to verify failure** (`--tests org.handtop.desktop.ui.ThemeTest`). Expected: compilation FAILS.

- [ ] **Step 3: Implement.** Values (spec "Вид по темам"):
  - Modern: card `0xB30F1420`, radius 12 dp, 1 dp border `0x29FFFFFF`; text `#FFFFFF`, dim `#DDE2EA`, track `0x2EFFFFFF`, cpu `#4F8CFF`, memory `#46C46E`.
  - Classic: `context.bevel(Bevel.RAISED)` (face `#C0C0C0`); text and dim `#000000`; meter = sunken white field, fill `#000080` for both.
  - Soft: card `0xF5FAFAFB`, radius 14 dp; text `#1F1F23`, dim `#6A6A6F`, track `0x14000000`, cpu `#2A68C8`, memory `#2F8F4E`.
  - Glossy: vertical gradient `#FFFFFF → #ECE9D8`, 1 dp border `#0A54CC`, radius 6 dp, a 5 dp strip on top in `TITLE_ACTIVE_STOPS`; text `#000000`, dim `#444444`; meter = white field with 1 dp `#ACA899` border, fill gradient `#5A91F0 → #316AC5`; `under = [#FFFFFF, #ECE9D8]`.
  - Soft and Modern cards cast a shadow only through the view's elevation when `shadows` is on (no per-frame drawing).

- [ ] **Step 4: Run tests.** Run: `./gradlew :app:testDebugUnitTest`. Expected: PASS.

- [ ] **Step 5: Commit** — `git commit -m "Desktop widgets: cards in each theme"`.

---

### Task 3: Widgets on the desktop, and the clock

**Files:**
- Create: `widgets/WidgetView.kt` (base), `widgets/ClockWidget.kt`, `shell/CalendarPopup.kt`
- Modify: `shell/DesktopIcons.kt`, `shell/StatusArea.kt` (calendar moved out), `DesktopActivity.kt` (menu, resume/pause), strings en/ru
- Test: `app/src/test/java/org/handtop/desktop/widgets/ClockTextTest.kt`

**Interfaces:**
- Consumes: Task 1 (`DesktopGrid`, `Widgets`, `WidgetKind`), Task 2 (`Theme.widgetBackground`, `widgetColors`, `widgetMeter`).
- Produces:
  - `abstract class WidgetView(context: Context, val kind: WidgetKind) : FrameLayout(context)` with `open fun onActive(active: Boolean)` (start/stop own updates), `open fun settingsDialog(): Boolean` (shows the settings, false = has none), `abstract fun onTap()`;
  - `fun widgetView(context: Context, kind: WidgetKind, host: WidgetHost): WidgetView` factory in `widgets/WidgetView.kt`; `interface WidgetHost { fun openLinuxSystem(); fun openWindow(content: WindowContent, title: String, icon: Int); fun requestLocation(onResult: (Boolean) -> Unit) }` (implemented by `DesktopActivity`);
  - `DesktopIcons.addWidget(kind: WidgetKind)` (computes `DesktopGrid.firstFree` on the current grid, saves it), `DesktopIcons.setActive(active: Boolean)`, `DesktopIcons.addableWidgets(): List<WidgetKind>`;
  - `fun showCalendarPopup(anchor: View, nearBottomRight: Boolean = true): PopupWindow` (the code now in `StatusArea.showCalendar`; the widget passes `false` to open it under itself);
  - pure `object ClockText { fun timePattern(is24: Boolean, seconds: Boolean): String; fun is24(setting: String?, system24: Boolean): Boolean }` (setting `"24"`, `"12"`, or null = system).

- [ ] **Step 1: Write the failing test** (`ClockTextTest`): `timePattern(true, false) == "HH:mm"`, `timePattern(true, true) == "HH:mm:ss"`, `timePattern(false, false) == "h:mm a"`; `is24(null, true)`, `!is24("12", true)`, `is24("24", false)`.

- [ ] **Step 2: Run to verify failure.** Expected: compilation FAILS.

- [ ] **Step 3: Implement.**
  - `DesktopIcons`: children = icons then widgets; `onLayout` uses `DesktopGrid.layOut(columns, rows, savedIconCells, Widgets.load())` instead of `place()`; a widget child is measured `w·cell + (w−1)·gap` by `h·cell + (h−1)·gap`; `blockWidth` counts icons only. Drag: a widget drags like an icon (same slop and right-click/long-press rules); the drop outline is the widget's size at the hovered top-left cell; on drop `fits` (taken = icons + other widgets) → `Widgets.move`, else back. An icon dropped on a widget's cell → back. Widget menu: "Settings…" (only if `settingsDialog` exists), separator, "Remove from desktop" (`Widgets.remove`). Listen to `Widgets` like `Shortcuts`.
  - `DesktopActivity.desktopMenu()`: "Add widget…" (enabled when `addableWidgets()` is not empty) opens a second popup at the same point listing those kinds (Clock, Weather, Linux); picking WEATHER first calls `host.requestLocation` (see Task 5). `onResume`/`onPause` → `icons.setActive(true/false)`.
  - `ClockWidget`: time (large, light), full date below (`DateFormat.getLongDateFormat` style "суббота, 28 ноября" via `SimpleDateFormat("EEEE, d MMMM", locale)`); ticks aligned to the next minute (second); tap → `showCalendarPopup(this, nearBottomRight = false)`; settings dialog (`dialogBuilder()`): "As in Android / 24-hour / 12-hour" and a "Seconds" check box, saved as `format` (`"24"`/`"12"`/absent) and `seconds` (`"true"`/absent).
  - Strings: `widget_add` "Add widget…"/«Добавить виджет…», `widget_clock` "Clock"/«Часы», `widget_weather` "Weather"/«Погода», `widget_linux` "Linux"/«Linux», `widget_settings` "Settings…"/«Настроить…», `widget_remove` "Remove from desktop"/«Убрать с рабочего стола», `widget_clock_system` "As in Android"/«Как в Android», `widget_clock_24` "24-hour"/«24 часа», `widget_clock_12` "12-hour"/«12 часов», `widget_clock_seconds` "Seconds"/«Секунды».

- [ ] **Step 4: Run tests.** Run: `./gradlew :app:testDebugUnitTest`. Expected: PASS.

- [ ] **Step 5: Device check.** Build, install, relaunch on DeX. Modern: desktop has no widgets; add Clock → top right; drag it to the left (icons stay); drop it on icons → back; drop a desktop icon onto it → back; "Arrange icons" → widget stays; settings (24/12, seconds) apply; tap → calendar under the widget; remove. Switch Classic, Soft, Glossy: card looks as in `widget-themes.html`. Expected: all as described; screenshots in `shots/widgets/`.

- [ ] **Step 6: Commit** — `git commit -m "Desktop widgets on the desktop: add, move, remove; the clock"`.

---

### Task 4: The Linux widget

**Files:**
- Create: `widgets/LinuxLoad.kt` (pure), `widgets/LinuxSampler.kt` (reads `/proc`), `widgets/LinuxWidget.kt`
- Modify: `linux/LinuxEnv.kt` (share the proot-executable test), strings en/ru
- Test: `app/src/test/java/org/handtop/desktop/widgets/LinuxLoadTest.kt`

**Interfaces:**
- Consumes: Task 3 (`WidgetView`, `WidgetHost.openLinuxSystem`).
- Produces: `object LinuxLoad { fun statTicks(stat: String): Long?; fun rssBytes(status: String): Long?; fun cpuPercent(prevTicks: Long, ticks: Long, elapsedMs: Long, cores: Int, hz: Int = 100): Int; fun memoryText(used: Long, total: Long, locale: Locale, gb: String): String }`; `LinuxEnv.isProotExe(context: Context, exe: String): Boolean`; `class LinuxSampler(context) { fun sample(): Sample? }` with `data class Sample(val ticks: Long, val rss: Long, val linuxRunning: Boolean, val atMs: Long)`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun statTicksAreUtimePlusStime() =   // comm may hold spaces and parentheses
    assertEquals(150L, LinuxLoad.statTicks("1234 (my (prog) x) S 1 1 1 0 -1 4194560 0 0 0 0 100 50 0 0 20 0 1 0 1 1 1"))
@Test fun statTicksOfGarbageIsNull() = assertNull(LinuxLoad.statTicks("1234 (x"))
@Test fun rssFromStatus() = assertEquals(204800L * 1024, LinuxLoad.rssBytes("Name:\tx\nVmRSS:\t  204800 kB\n"))
@Test fun cpuPercentOfAllCores() = assertEquals(25, LinuxLoad.cpuPercent(0, 400, 2000, 8))   // 400 ticks over 2 s on 8 cores
@Test fun cpuPercentNeverNegative() = assertEquals(0, LinuxLoad.cpuPercent(500, 100, 2000, 8)) // processes ended
@Test fun cpuPercentCapped() = assertEquals(100, LinuxLoad.cpuPercent(0, 99999, 1000, 1))
@Test fun memoryTextRussianAndEnglish() {
    assertEquals("1,8 / 12 ГБ", LinuxLoad.memoryText(1_932_735_283, 12_884_901_888, Locale("ru"), "ГБ"))
    assertEquals("1.8 / 12 GB", LinuxLoad.memoryText(1_932_735_283, 12_884_901_888, Locale.US, "GB"))
}
```
(`memoryText`: used in GiB with one decimal; total in GiB rounded to a whole number.)

- [ ] **Step 2: Run to verify failure.** Expected: compilation FAILS.

- [ ] **Step 3: Implement.** `LinuxSampler.sample()` on a background thread: every `/proc/<pid>` whose `exe` link is readable (this app's processes, HandTop included) → sum `statTicks` and `rssBytes` (unreadable or vanished → skipped); `linuxRunning` = any `exe` passing `LinuxEnv.isProotExe` (the check `killLinuxProcesses` already does, moved into this function). `LinuxWidget`: title "Linux · <Distro.title>" (`LinuxEnv.installedDistro`), two labelled meters (`Theme.widgetMeter`), every 2 s while active; states: not installed → "Linux is not installed" (tap opens setup), not running → "Linux is not running", no meters; tap → `host.openLinuxSystem()`. Total memory: `ActivityManager.MemoryInfo.totalMem`; cores: `Runtime.availableProcessors()`; hz: `Os.sysconf(OsConstants._SC_CLK_TCK)`. Strings: `widget_linux_cpu` "Processor"/«Процессор», `widget_linux_memory` "Memory"/«Память», `widget_linux_not_installed`, `widget_linux_not_running`, `widget_gb` "GB"/«ГБ».

- [ ] **Step 4: Run tests.** Run: `./gradlew :app:testDebugUnitTest`. Expected: PASS.

- [ ] **Step 5: Device check.** Right after a HandTop restart (no Linux started): "Linux is not running". Open a Linux terminal: meters appear; run `yes > /dev/null` for 10 s in it: CPU rises (≈ 1/cores of 100 %), falls after Ctrl+C; memory value plausible (compare with `top -u <uid>` RSS sum). Tap → Linux System window. Four themes. Expected: as described.

- [ ] **Step 6: Commit** — `git commit -m "Desktop widgets: Linux processor and memory"`.

---

### Task 5: The weather widget

**Files:**
- Create: `widgets/weather/OpenMeteo.kt` (URLs, parsing), `widgets/weather/WeatherKind.kt` (WMO codes, icons, words), `widgets/weather/WeatherStore.kt` (cache, refresh rule, fetching), `widgets/weather/WeatherLocation.kt` (permission, location, place name), `widgets/WeatherWidget.kt`, `widgets/weather/WeatherContent.kt` (7-day window), drawables `ic_weather_clear`, `_clear_night`, `_partly`, `_partly_night`, `_cloudy`, `_fog`, `_drizzle`, `_rain`, `_snow`, `_thunder` (own vector art)
- Modify: `AndroidManifest.xml` (`ACCESS_COARSE_LOCATION`), `DesktopActivity.kt` (`requestLocation`, `openWindow`), strings en/ru
- Test: `app/src/test/java/org/handtop/desktop/widgets/weather/OpenMeteoTest.kt`, `WeatherKindTest.kt`, `WeatherStoreTest.kt`

**Interfaces:**
- Consumes: Task 3 (`WidgetView`, `WidgetHost.requestLocation`, `openWindow`).
- Produces:
  - `data class Forecast(val temperature: Double, val code: Int, val isDay: Boolean, val hours: List<Hour>, val days: List<Day>)`, `data class Hour(val time: String /* "15:00" */, val temperature: Double, val code: Int)`, `data class Day(val date: String /* ISO */, val code: Int, val max: Double, val min: Double)`, `data class Place(val name: String, val country: String?, val latitude: Double, val longitude: Double)`;
  - `object OpenMeteo { fun round(c: Double): Double; fun forecastUrl(lat: Double, lon: Double, fahrenheit: Boolean): String; fun parseForecast(json: String, nowIso: String): Forecast; fun searchUrl(name: String, language: String): String; fun parsePlaces(json: String): List<Place> }` — forecast params: `current=temperature_2m,weather_code,is_day`, `hourly=temperature_2m,weather_code`, `daily=weather_code,temperature_2m_max,temperature_2m_min`, `forecast_days=7`, `timezone=auto`, `temperature_unit=fahrenheit` only when asked;
  - `enum class WeatherKind { CLEAR, PARTLY, CLOUDY, FOG, DRIZZLE, RAIN, SNOW, THUNDER }`, `fun kindOf(code: Int): WeatherKind`, `fun iconOf(kind: WeatherKind, day: Boolean): Int`, `fun wordOf(kind): Int` (string res);
  - `object WeatherUnits { fun fahrenheit(preference: String?, country: String): Boolean }` (preference from `LocalePreferences.getTemperatureUnit()` — `"fahrenhe"`/`"celsius"`; none → countries US, BS, BZ, KY, PW, LR);
  - `object WeatherStore { fun needsRefresh(nowMs: Long, fetchedMs: Long?): Boolean /* ≥ 30 min or never */; fun state(cached: Forecast?, fetchedMs: Long?, online: Boolean): WeatherState }` with `sealed class WeatherState { Ready(forecast, fetchedMs, stale: Boolean), NoDataYet, NeedsCity }`.

- [ ] **Step 1: Write the failing tests**
  - `OpenMeteoTest`: `assertEquals(55.76, round(55.75583), 1e-9)`, `assertEquals(0.0, round(-0.004), 1e-9)`; `forecastUrl(55.75583, 37.6173, false)` contains `latitude=55.76&longitude=37.62` and no `temperature_unit`; with `true` contains `temperature_unit=fahrenheit`; `parseForecast(FIXTURE, "2026-11-28T14:00")` gives temperature 3.1, code 3, `isDay`, hours `[15:00, 16:00, 17:00]` (the three after now), 7 days with first max/min from the fixture; **`parseForecastToleratesMissingParts`**: fixture without `hourly` and with 3 days → `hours` empty, 3 days; `parsePlaces` of a two-result fixture → names and coordinates; empty `results` → empty list. (Fixtures: small hand-written JSON strings in the test.)
  - `WeatherKindTest`: 0→CLEAR, 2→PARTLY, 3→CLOUDY, 45→FOG, 53→DRIZZLE, 63→RAIN, 81→RAIN, 73→SNOW, 86→SNOW, 95→THUNDER, 99→THUNDER, unknown 150→CLOUDY; every kind has an icon for day and night.
  - `WeatherStoreTest`: `needsRefresh(now, null)`; `!needsRefresh(now, now - 29 min)`; `needsRefresh(now, now - 30 min)`; **`weatherStateWithoutDataOrNetwork`**: `state(null, null, online = false) == NoDataYet`; cached + offline → `Ready(stale = true)`; `WeatherUnits.fahrenheit(null, "US")`, `!fahrenheit(null, "RU")`, `fahrenheit("fahrenhe", "RU")`, `!fahrenheit("celsius", "US")`.

- [ ] **Step 2: Run to verify failure.** Expected: compilation FAILS.

- [ ] **Step 3: Implement.**
  - Location (`WeatherLocation`): `DesktopActivity.requestLocation` asks `ACCESS_COARSE_LOCATION` with `requestPermissions` (only coarse); granted → `LocationManager.getLastKnownLocation` from NETWORK, then PASSIVE; none → one `getCurrentLocation(NETWORK_PROVIDER)` (API 30+) / `requestSingleUpdate` (below); place name from `Geocoder.getFromLocation` (async variant on API 33+) → `locality ?: subAdminArea ?: adminArea`, none → no name. Denied or no fix → `NeedsCity`.
  - Settings dialog: "By location" / "City:" with a search field; search runs `searchUrl` (language = UI language) after a short pause or the Done key and lists results "Name, Country"; picking one saves `mode=city`, `city`, `lat`, `lon`; "By location" saves `mode=location` and asks the permission if missing.
  - Fetching (`WeatherStore`): background thread, `HttpURLConnection` 10 s timeouts, `User-Agent: HandTop`; the raw JSON and fetch time cached in prefs `weather`; refresh on add, on resume and every 30 min while active, when `needsRefresh`; failure keeps the cache.
  - `WeatherWidget` (3 × 2): icon + temperature (large) + word and place; below three "15 +3°" hours; stale → "Updated at 13:40"; `NoDataYet` → "No weather yet" (+ "No network" when offline); `NeedsCity` → "Choose a city" (tap opens the settings). Tap otherwise → `WeatherContent` window: place, 7 rows (weekday, icon, max/min), updated time.
  - Strings: the eight weather words, `widget_weather_updated` "Updated at %1$s", `widget_weather_no_data`, `widget_weather_offline`, `widget_weather_need_city`, `widget_weather_by_location`, `widget_weather_city`, `widget_weather_search`, `weather_title` "Weather"/«Погода» — en and ru.

- [ ] **Step 4: Run tests.** Run: `./gradlew :app:testDebugUnitTest`. Expected: PASS.

- [ ] **Step 5: Device check** (ask the user to answer the permission dialog on the phone). Add Weather in Modern: permission dialog → allowed: temperature, place, three hours; tap → 7-day window. Deny path: "Choose a city" → search "Paris" → pick → forecast. Airplane mode is the user's setting — instead block nothing and check the stale label by setting the cached fetch time back via `run-as` prefs edit. Four themes. Expected: as described; no request leaves the phone with more than 2 decimals of coordinates (check the URL in a debug log line removed before commit).

- [ ] **Step 6: Commit** — `git commit -m "Desktop widgets: weather from Open-Meteo"`.

---

### Task 6: Sweep and docs

**Files:**
- Modify: whatever the sweep finds; `CHANGELOG.md` (Unreleased), `README.md` (What works now), `docs/ROADMAP.md`, the spec («Как вышло»)

- [ ] **Step 1: Sweep** on DeX and on the phone screen (only if the user is not using it): all three widgets in all four themes; phone ↔ DeX and a scale change with widgets at the right edge (Review Focus 2); a desktop full of icons + add a widget (Review Focus 1); dragging a window over the widgets — `adb shell dumpsys gfxinfo org.handtop.desktop` janky frames not above today's. Expected: nothing unreadable, nothing lost, no jank.
- [ ] **Step 2: Fix findings** with a failing test first where the logic is testable.
- [ ] **Step 3: Run** `./gradlew :app:testDebugUnitTest`. Expected: PASS.
- [ ] **Step 4: Docs** — CHANGELOG: "Desktop widgets: a clock, the weather (Open-Meteo, by location or a chosen city) and Linux's processor and memory. Add them from the desktop's menu (long press or right click on the wallpaper), drag them anywhere on the grid, each in the look of the theme." README feature line; ROADMAP line in Russian; spec «Как вышло».
- [ ] **Step 5: Commit, then ask the user before pushing.**
