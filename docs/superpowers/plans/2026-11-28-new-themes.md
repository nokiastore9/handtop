# Soft and Glossy Themes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Two new HandTop themes, Soft (GNOME-like, light) and Glossy (XP-like, bevelled, beige and blue), with Glossy's colours also reaching Linux programs through the classic Linux theme.

**Architecture:** First the theme API grows the few hooks the new looks need (taskbar text, default icon tint, light Android base, centered title, dialog/calendar styles, bevel colours from the current theme), wired so Modern and Classic stay pixel-identical. Then `SoftTheme` and `GlossyTheme` implement `Theme`. Last, `tools/linux-theme/build.py` builds the classic GTK theme in two palettes and `LinuxTheme` installs and selects a per-theme variant.

**Tech Stack:** Kotlin (Android views, no Compose), JUnit 4 unit tests, Python 3 + libsass 0.23 for the GTK theme build.

**Spec:** `docs/superpowers/specs/2026-11-28-new-themes-design.md`

## Global Constraints

- Code, comments, commit messages, README, CHANGELOG in English; ROADMAP and spec in Russian. User-facing replies in Russian.
- Theme names: en "Soft" / "Glossy", ru «Мягкая» / «Глянцевая». No "GNOME", "XP", "Windows" anywhere in the UI. No third-party logos, pictures or fonts.
- Layout unchanged: bottom taskbar, Start at left, tray and clock at right.
- Modern and Classic must look exactly as today (anti-goal). Their colours are not retuned even where they would fail the contrast rule (Classic's inactive title is 2.96 : 1): the contrast tests cover Soft and Glossy only.
- Readability rule (Soft, Glossy): text ≥ 4.5 : 1 against its background at every gradient stop; inactive window titles and minimized task buttons ≥ 3 : 1; disabled text not checked. Translucent backgrounds are composited over both white and black and must pass on both.
- Soft: Linux programs keep their own look (no environment variables). Glossy: the classic Linux theme in Glossy colours.
- The classic GTK build must come out byte-identical to today's `app/src/main/assets/linux-theme/classic`.
- 60 fps: gradients and frames are Drawables built once per state, never per frame.
- Commit after each task; ask the user before every push.
- Device: adb `C:\Users\samso\AppData\Local\Android\Sdk\platform-tools\adb.exe`; helpers in the scratchpad `bench/ui.sh` (`ADB`, `ht_front`, `shot`, `dshot`). Test on the DeX display (61, `input -d 61`) and leave display 0 alone unless needed; check HandTop is in front (`ht_front`) before every tap; open Files only via one tap on its desktop icon, then "Linux home", before any screenshot; never touch the lock screen.

## Review Focus

1. Untinted white icons on light Soft surfaces (title buttons, window icons, toolbars, menu entries without a tint) — every `setThemedIcon(res)` call with no tint must come out dark in Soft. Pinned by `iconTintAppliesToUntintedIcons` (Task 1) and the Task 5 device sweep.
2. Things on the taskbar coloured with `Palette.TEXT*` (clock, date, signal and battery icons, setup indicator, apps button tint `TEXT_SOFT`) — on Soft's dark and Glossy's blue bar they must use the taskbar colours. Pinned by `taskbarTextIsTodaysTextInModernAndClassic` (Task 1) plus the contrast tests (Tasks 2, 3) and the device sweep.
3. A chosen chip in Settings (accent fill) with dark text in Soft — must use `onAccent`. Pinned by `onAccentReadsOnAccent` contrast checks (Tasks 2, 3).
4. Running Linux programs when the theme changes, and a rootfs installed before this version: both variants are installed after the update (stamp per variant), a program started after switching gets the new variant. Pinned by `installWritesEachVariantIntoItsOwnFolder` (Task 4).
5. Launchpad in Soft: the dark veil (`0x990B1023`) with Soft's dark text would be unreadable — Soft uses a light veil. Pinned by the Task 5 device sweep (Launchpad screenshot) and `softLaunchpadVeilIsLight` (Task 2).

---

### Task 1: Theme hooks, with Modern and Classic unchanged

**Files:**
- Modify: `app/src/main/java/org/handtop/desktop/ui/Theme.kt`, `ModernTheme.kt`, `ClassicTheme.kt`, `Ui.kt` (Palette), `Icons.kt`, `Kit.kt`, `Bevel.kt`, `Dialogs.kt`
- Modify: `app/src/main/java/org/handtop/desktop/shell/Taskbar.kt`, `StatusArea.kt`, `SetupIndicator.kt`
- Modify: `app/src/main/java/org/handtop/desktop/wm/DesktopWindow.kt`, `app/src/main/java/org/handtop/desktop/DesktopActivity.kt`
- Modify: `app/src/main/res/values/themes.xml`
- Test: `app/src/test/java/org/handtop/desktop/ui/ThemeTest.kt`

**Interfaces:**
- Produces (used by Tasks 2–4):
  - `ThemeColors` new fields: `taskbarText: Int`, `taskbarTextDim: Int` (everything on the taskbar itself), `iconTint: Int` (tint of an icon given none; `0` leaves the white icon as it is), `titleGlyph: Int` (glyph colour on title buttons; `0` = as `setThemedIcon` gives), `onAccent: Int` (text on an accent fill).
  - `Theme` new members: `abstract val light: Boolean` (light content on Android's light base, not bevelled), `abstract val titleCentered: Boolean`, `open val dialogStyle: Int = 0` (style for `AlertDialog.Builder`, 0 = the activity's), `open val calendarStyle: Int = 0` (style wrapped around the calendar's context, 0 = none).
  - `Palette.TASKBAR_TEXT`, `Palette.TASKBAR_TEXT_DIM`, `Palette.ON_ACCENT`.
  - Style `Theme.HandTop.Light` in `themes.xml` (parent `@android:style/Theme.Material.Light.NoActionBar`, same window/bar items as `Theme.HandTop`, `colorAccent` `#FF2A68C8`).

- [ ] **Step 1: Take "before" screenshots** of Modern and Classic on DeX: desktop with Files ("Linux home") and Settings → Desktop, start menu open, calendar open, a rename dialog in Files, Launchpad. Save under the scratchpad `shots/themes-before/`. Expected: 12 PNGs (6 per theme).

- [ ] **Step 2: Write the failing tests** in `ThemeTest.kt`:

```kotlin
@Test
fun taskbarTextIsTodaysTextInModernAndClassic() {
    for (t in listOf(ModernTheme, ClassicTheme)) with(t.colors) {
        assertEquals(text, taskbarText)
        assertEquals(textDim, taskbarTextDim)
        assertEquals(0, iconTint)
        assertEquals(0, titleGlyph)
    }
    assertEquals(ModernTheme.colors.text, ModernTheme.colors.onAccent)
}

@Test
fun modernAndClassicKeepTheirBaseAndTitles() {
    for (t in listOf(ModernTheme, ClassicTheme)) {
        assertFalse(t.light)
        assertFalse(t.titleCentered)
    }
    assertEquals(0, ModernTheme.dialogStyle)
    assertEquals(0, ModernTheme.calendarStyle)
    assertEquals(R.style.HandTop_ClassicDialog, ClassicTheme.dialogStyle)
    assertEquals(R.style.HandTop_ClassicCalendar, ClassicTheme.calendarStyle)
}
```

Also add the contrast helpers used by Tasks 2 and 3, in the same file (private): `luminance(argb: Int): Double` (WCAG relative luminance), `over(top: Int, bottom: Int): Int` (alpha compositing to an opaque colour), `contrast(fg: Int, bg: Int): Double`, and `assertReadable(what: String, fg: Int, bg: Int, min: Double = 4.5)` which composites a translucent `bg` over both `0xFFFFFFFF` and `0xFF000000` and asserts each ratio ≥ `min` with `what` in the message. Sanity test: `assertEquals(21.0, contrast(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)`.

- [ ] **Step 3: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests org.handtop.desktop.ui.ThemeTest`
Expected: compilation FAILS (unresolved `taskbarText`, `light`, …).

- [ ] **Step 4: Implement the API and give Modern/Classic today's values.** Modern: `taskbarText = text`, `taskbarTextDim = textDim`, `iconTint = 0`, `titleGlyph = 0`, `onAccent = text`, `light = false`, `titleCentered = false`. Classic: the same pattern (`onAccent = 0xFFFFFFFF`), `dialogStyle = R.style.HandTop_ClassicDialog`, `calendarStyle = R.style.HandTop_ClassicCalendar`. Add `Theme.HandTop.Light` to `themes.xml`.

- [ ] **Step 5: Wire the shell to the hooks** (each is a no-op for Modern and Classic):
  - `Bevel.kt`: `BevelDrawable` and `Context.bevel(kind, fill = …)` take `Themes.current.colors` instead of `ClassicTheme.colors`.
  - `Icons.kt` `setThemedIcon`: when `tintIcons` and `tint == null` and `colors.iconTint != 0`, apply `iconTint`.
  - `DesktopWindow.makeButton`: when `colors.titleGlyph != 0`, show the base (white) icon `res` with a `titleGlyph` colour filter instead of `setThemedIcon`. When `titleCentered`, `titleView.gravity = Gravity.CENTER` (the icon stays at the left).
  - `Taskbar.kt`, `StatusArea.kt` (bell, notification icons, clock and date, labels, `SignalIcon`, `BatteryIcon`), `SetupIndicator.kt` label: `Palette.TEXT` → `TASKBAR_TEXT`, `TEXT_DIM`/`TEXT_SOFT`/`TEXT_DISABLED` → `TASKBAR_TEXT_DIM`. The calendar popup's own content stays on `TEXT`.
  - `StatusArea.showCalendar`: `calendarStyle != 0` → `ContextThemeWrapper(context, calendarStyle)`; drop the `id == ThemeId.CLASSIC` check.
  - `Dialogs.kt` `dialogBuilder`: `dialogStyle == 0` → `AlertDialog.Builder(this)`, else `AlertDialog.Builder(this, dialogStyle)`.
  - `Kit.kt` `chipItem`: a chosen chip's label uses `Palette.ON_ACCENT`.
  - `DesktopActivity.onCreate`: call `Themes.load(this)` and then `setTheme(if (Themes.current.light) R.style.Theme_HandTop_Light else R.style.Theme_HandTop)` **before** `super.onCreate`.

- [ ] **Step 6: Run tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS, all tests (148 before this plan + the new ones).

- [ ] **Step 7: Build, install, compare.** `./gradlew :app:assembleDebug`, `adb install -r`, relaunch on DeX, take the same 12 screenshots into `shots/themes-after1/`. Expected: Modern and Classic look the same as in Step 1 (compare side by side; the clock may differ).

- [ ] **Step 8: Commit**

```bash
git add app/src docs
git commit -m "Theme hooks for more themes: taskbar text, icon tint, light base, dialog and calendar styles"
```

---

### Task 2: The Soft theme

**Files:**
- Create: `app/src/main/java/org/handtop/desktop/ui/SoftTheme.kt`
- Modify: `ui/Theme.kt` (`ThemeId.SOFT("soft")`, `Themes.of`), `shell/SettingsContent.kt` (`themeChoices`), `shell/Launchpad.kt`, `res/values/strings.xml`, `res/values-ru/strings.xml`
- Test: `app/src/test/java/org/handtop/desktop/ui/ThemeTest.kt`

**Interfaces:**
- Consumes: Task 1's fields and members.
- Produces: `object SoftTheme : Theme`; `ThemeId.SOFT`; string `theme_soft`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun softKey() {
    assertEquals(ThemeId.SOFT, ThemeId.fromKey("soft"))
    assertEquals("soft", ThemeId.SOFT.key)
}

@Test
fun softIsLightRoundedAndCentered() = with(SoftTheme) {
    assertTrue(light); assertFalse(bevelled); assertTrue(titleCentered); assertTrue(titleBold)
    assertTrue(tintIcons); assertTrue(roundedCorners); assertTrue(shadows); assertTrue(blur); assertTrue(animations)
    assertFalse(startLabel); assertFalse(startMenuBand); assertFalse(embossDisabled)
    assertEquals(0, dialogStyle); assertEquals(0, calendarStyle)
}

@Test
fun softColoursAreTheSpecs() = with(SoftTheme.colors) {
    assertEquals(0xFFEBEBED.toInt(), titleActive)
    assertEquals(0xFFF4F4F5.toInt(), titleInactive)
    assertEquals(0xFF1F1F23.toInt(), titleTextActive)
    assertEquals(0xFF6E6E73.toInt(), titleTextInactive)
    assertEquals(0xFFFAFAFB.toInt(), windowBg)
    assertEquals(0xFFF0F0F2.toInt(), sidebarBg)
    assertEquals(0xFFEBEBED.toInt(), barBg)
    assertEquals(0xFFFFFFFF.toInt(), menuBg)
    assertEquals(0xFF1F1F23.toInt(), text)
    assertEquals(0xFF6A6A6F.toInt(), textDim)
    assertEquals(0xFF2A68C8.toInt(), accent)
    assertEquals(0xFFF2F2F7.toInt(), taskbarText)
    assertEquals(0xFFE4E4E7.toInt(), keyBarBg)
}

@Test
fun softIsReadable() = with(SoftTheme.colors) {
    for (bg in listOf(windowBg, sidebarBg, barBg, menuBg, card)) {
        assertReadable("text", text, bg); assertReadable("textDim", textDim, bg)
        assertReadable("accent", accent, bg)
    }
    assertReadable("taskbarText", taskbarText, taskbar); assertReadable("taskbarTextDim", taskbarTextDim, taskbar)
    assertReadable("selection", selectionText, over(selection, windowBg))
    assertReadable("title", titleTextActive, titleActive)
    assertReadable("inactive title", titleTextInactive, titleInactive, 3.0)
    assertReadable("onAccent", onAccent, accent)
    assertReadable("warning", warning, windowBg)
}
```

- [ ] **Step 2: Run to verify failure.** Run: `./gradlew :app:testDebugUnitTest --tests org.handtop.desktop.ui.ThemeTest`. Expected: compilation FAILS (`SOFT`, `SoftTheme` unresolved).

- [ ] **Step 3: Implement `SoftTheme`** with these colours (spec values plus my calls for the fields the spec does not list):

```kotlin
ThemeColors(
    titleActive = 0xFFEBEBED, titleActiveEnd = 0xFFEBEBED, titleInactive = 0xFFF4F4F5, titleInactiveEnd = 0xFFF4F4F5,
    titleTextActive = 0xFF1F1F23, titleTextInactive = 0xFF6E6E73,
    taskbar = 0xF21C1C1F, taskbarItemActive = 0x26FFFFFF, taskbarIndicator = 0xFF5A9BF0,
    accent = 0xFF2A68C8, snapPreviewFill = 0x402A68C8, snapPreviewStroke = 0xB02A68C8, snapPreviewHalo = 0,
    closeHover = 0xFFD0D0D4, closePressed = 0xFFC2C2C7,
    menuBg = 0xFFFFFFFF, menuDivider = 0x1A000000,
    text = 0xFF1F1F23, textDim = 0xFF6A6A6F, textSoft = 0xFF3A3A40, textDisabled = 0xFFA1A1A6,
    statusIconOff = 0x55F2F2F7, chip = 0x14000000, card = 0xFFF0F0F2,
    batteryLow = 0xFFE5484D, batteryCharging = 0xFF46C46E, keyBarBg = 0xFFE4E4E7, keyOn = 0xFF2A68C8,
    windowBg = 0xFFFAFAFB, sidebarBg = 0xFFF0F0F2, barBg = 0xFFEBEBED, warning = 0xFFA35200,
    selection = 0x332A68C8, selectionText = 0xFF1F1F23, fieldBg = 0x14000000, field = 0xFFFFFFFF,
    desktopLabel = 0xFFFFFFFF, face = 0xFFEBEBED, light = 0xFFFFFFFF, innerLight = 0xFFF4F4F5,
    shadow = 0x33000000, darkShadow = 0x55000000,
    taskbarText = 0xFFF2F2F7, taskbarTextDim = 0xFFA1A1A6, iconTint = 0xFF3A3A40, titleGlyph = 0, onAccent = 0xFFFFFFFF,
)  // each literal .toInt() as in ModernTheme
```

Drawing: `windowFrame` like Modern's (colour by `active`, 12 dp corners when `floating`, else 0); `titleBar` null; `titleButton` a 24 dp circle centered in the button (`LayerDrawable` with `setLayerSize` + `setLayerGravity(CENTER)`), `#FFDEDEE1` normal, `closeHover` on hover, `closePressed` on press, the same for close (not red), `checked` → `chip` circle; `panel` white rounded `cornerDp`; `menuItemBackground`/`toolButton` = `selectableBackground()` (the light base gives a dark ripple); `menuTextColors` = `text`; `divider` 1 dp `menuDivider`; `taskbarBackground` `taskbar`; `taskButton` a rounded (8 dp) `taskbarItemActive` fill for the front window plus Modern's indicator bar; `startButton`/`trayBackground` null.

- [ ] **Step 4: Wire it.** `Themes.of`: `SOFT -> SoftTheme`. Settings `themeChoices`: four entries in this order — Modern, Classic, Soft, Glossy (Glossy is added in Task 3; for now three). Strings: `theme_soft` "Soft" / «Мягкая». Launchpad: when `Themes.current.light`, the veil is `windowBg` at alpha 0xB3 (blur) / 0xE6 (no blur), and the search field and tile fills are `Palette.CHIP` instead of `0x26FFFFFF`. Add test:

```kotlin
@Test
fun softLaunchpadVeilIsLight() = assertTrue(luminance(Launchpad.veil(SoftTheme, blurred = true) or 0xFF000000.toInt()) > 0.8)
```
with `Launchpad.veil(theme: Theme, blurred: Boolean): Int` a companion function holding today's choice for the other themes (`bevelled` → `windowBg`; blurred → `0x990B1023`; else `0xD90B1023`).

- [ ] **Step 5: Run tests.** Run: `./gradlew :app:testDebugUnitTest`. Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src
git commit -m "Soft theme: light windows, centered titles, round title buttons, dark taskbar"
```

---

### Task 3: The Glossy theme

**Files:**
- Create: `app/src/main/java/org/handtop/desktop/ui/GlossyTheme.kt`
- Modify: `ui/Theme.kt` (`ThemeId.GLOSSY("glossy")`, `Themes.of`), `shell/SettingsContent.kt`, `res/values/themes.xml` (`HandTop.GlossyDialog`, `HandTop.GlossyCalendar`: copies of the classic ones with `colorAccent` `#FF316AC5`), strings en/ru
- Test: `app/src/test/java/org/handtop/desktop/ui/ThemeTest.kt`

**Interfaces:**
- Consumes: Task 1's hooks.
- Produces: `object GlossyTheme : Theme` with public gradient/surface constants used by the test: `TITLE_ACTIVE_STOPS: IntArray`, `TITLE_INACTIVE_STOPS`, `TASKBAR_STOPS`, `START_STOPS`, `TRAY_STOPS`, `TASK_BUTTON: Int`, `TASK_BUTTON_FRONT`, `TASK_BUTTON_MINIMIZED`; `ThemeId.GLOSSY`; `GlossyTheme.colors` (Task 4 builds Linux files from it).

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun glossyKey() = assertEquals(ThemeId.GLOSSY, ThemeId.fromKey("glossy"))

@Test
fun glossyIsBevelledWithColourIcons() = with(GlossyTheme) {
    assertTrue(bevelled); assertFalse(light); assertFalse(tintIcons); assertFalse(roundedCorners)
    assertTrue(animations); assertFalse(blur); assertFalse(shadows)
    assertTrue(titleBold); assertFalse(titleCentered); assertFalse(embossDisabled)
    assertTrue(startMenuBand); assertTrue(startLabel)
    assertEquals(R.style.HandTop_GlossyDialog, dialogStyle); assertEquals(R.style.HandTop_GlossyCalendar, calendarStyle)
}

@Test
fun glossyColoursAreTheSpecs() = with(GlossyTheme.colors) {
    assertEquals(0xFFECE9D8.toInt(), face); assertEquals(0xFFFFFFFF.toInt(), light)
    assertEquals(0xFFF5F4EA.toInt(), innerLight); assertEquals(0xFFACA899.toInt(), shadow)
    assertEquals(0xFF716F64.toInt(), darkShadow); assertEquals(0xFF316AC5.toInt(), selection)
    assertEquals(0xFFFFFFFF.toInt(), selectionText); assertEquals(0xFF000000.toInt(), text)
    assertEquals(0xFFFFFFFF.toInt(), titleGlyph); assertEquals(0xFFFFFFFF.toInt(), taskbarText)
    assertArrayEquals(intArrayOf(0xFF2A72E0.toInt(), 0xFF0A58CE.toInt(), 0xFF0846B0.toInt()), GlossyTheme.TITLE_ACTIVE_STOPS)
}

@Test
fun glossyIsReadableAtEveryStop() = with(GlossyTheme) {
    val white = 0xFFFFFFFF.toInt()
    for (s in TITLE_ACTIVE_STOPS + TASKBAR_STOPS + START_STOPS + TRAY_STOPS) assertReadable("stop", white, s)
    for (s in TITLE_INACTIVE_STOPS) assertReadable("inactive title", colors.titleTextInactive, s, 3.0)
    assertReadable("task button", white, TASK_BUTTON); assertReadable("front", white, TASK_BUTTON_FRONT)
    assertReadable("minimized", white, TASK_BUTTON_MINIMIZED, 3.0)
    with(colors) {
        for (bg in listOf(windowBg, sidebarBg, barBg, menuBg, card, face, field)) {
            assertReadable("text", text, bg); assertReadable("textDim", textDim, bg)
        }
        assertReadable("selection", selectionText, selection); assertReadable("onAccent", onAccent, accent)
        assertReadable("taskbarTextDim", taskbarTextDim, TASKBAR_STOPS[0])
    }
}
```

- [ ] **Step 2: Run to verify failure.** Run: `./gradlew :app:testDebugUnitTest --tests org.handtop.desktop.ui.ThemeTest`. Expected: compilation FAILS.

- [ ] **Step 3: Implement `GlossyTheme`.** Constants: `TITLE_ACTIVE_STOPS` `#2A72E0, #0A58CE, #0846B0`; `TITLE_INACTIVE_STOPS` `#6F8FCC, #5B7EC2`; frame active `#0A54CC`, inactive `#6F8FCC`; `TASKBAR_STOPS` `#2563D6, #1A49B8` with a top highlight line `#5A91F0`; `TASK_BUTTON` `#2F6FE0`, `TASK_BUTTON_FRONT` `#1A47A8`, `TASK_BUTTON_MINIMIZED` `#4A7FDC`; `START_STOPS` `#2A822A, #226B22`; `TRAY_STOPS` `#0B72C8, #0A63B4` with a left edge `#08519A`; title buttons `#2A6FE8` with a 1 dp white outline, close `#C8441E`; menu border `#0A54CC`. Colours:

```kotlin
ThemeColors(
    titleActive = 0xFF0A58CE, titleActiveEnd = 0xFF0846B0, titleInactive = 0xFF6F8FCC, titleInactiveEnd = 0xFF5B7EC2,
    titleTextActive = 0xFFFFFFFF, titleTextInactive = 0xFFFFFFFF,
    taskbar = 0xFF1F56C8, taskbarItemActive = 0xFF1A47A8, taskbarIndicator = 0xFFFFFFFF,
    accent = 0xFF316AC5, snapPreviewFill = 0x40316AC5, snapPreviewStroke = 0xFF0846B0, snapPreviewHalo = 0xFFFFFFFF,
    closeHover = 0xFFE0683F, closePressed = 0xFFA8381A,
    menuBg = 0xFFFFFFFF, menuDivider = 0xFFACA899,
    text = 0xFF000000, textDim = 0xFF444444, textSoft = 0xFF222222, textDisabled = 0xFFACA899,
    statusIconOff = 0x80FFFFFF, chip = 0x1A316AC5, card = 0xFFFFFFFF,
    batteryLow = 0xFFE5484D, batteryCharging = 0xFF46C46E, keyBarBg = 0xFFECE9D8, keyOn = 0xFF316AC5,
    windowBg = 0xFFECE9D8, sidebarBg = 0xFFFFFFFF, barBg = 0xFFECE9D8, warning = 0xFFA00000,
    selection = 0xFF316AC5, selectionText = 0xFFFFFFFF, fieldBg = 0xFFFFFFFF, field = 0xFFFFFFFF,
    desktopLabel = 0xFFFFFFFF, face = 0xFFECE9D8, light = 0xFFFFFFFF, innerLight = 0xFFF5F4EA,
    shadow = 0xFFACA899, darkShadow = 0xFF716F64,
    taskbarText = 0xFFFFFFFF, taskbarTextDim = 0xFFFFFFFF, iconTint = 0, titleGlyph = 0xFFFFFFFF, onAccent = 0xFFFFFFFF,
)
```

Drawing: `windowFrame` a frame of the active/inactive colour, top corners ~6 dp when `floating` (square when not), and `titleBar` a vertical gradient of the stops with the **same top corners** (it covers the frame's top); `titleButton` rounded (~3 dp) squares, lighter on hover, darker on press; `panel` white with a 1 dp `#0A54CC` border, square; `menuItemBackground` `selection` on press/hover; `menuTextColors` text / `selectionText` highlighted / `textDisabled`; `divider` 1 dp `menuDivider`; `taskbarBackground` the gradient with its highlight line; `taskButton` rounded (~3 dp) in the three button colours; `startButton` the green pill, right side rounded (~10 dp); `trayBackground` the tray gradient with its left edge; `toolButton` as Classic's (bevel on press — the bevel now takes Glossy's colours). Square everything else (`roundedCorners = false`).

- [ ] **Step 4: Wire it.** `Themes.of`: `GLOSSY -> GlossyTheme`; Settings gets the fourth choice; strings `theme_glossy` "Glossy" / «Глянцевая»; the two styles in `themes.xml`.

- [ ] **Step 5: Run tests.** Run: `./gradlew :app:testDebugUnitTest`. Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src
git commit -m "Glossy theme: blue gradient title bars and taskbar, green Start, beige controls"
```

---

### Task 4: The classic Linux theme in two palettes

**Files:**
- Create: `tools/linux-theme/palettes/classic.scss`, `tools/linux-theme/palettes/glossy.scss`
- Modify: `tools/linux-theme/build.py`, `tools/linux-theme/gtk3/_colors.scss`, `tools/linux-theme/gtk4/_handtop-colors.scss`, `tools/linux-theme/gtk4/_classic.scss`, `tools/linux-theme/README.md`
- Create (generated): `app/src/main/assets/linux-theme/glossy/**`
- Modify: `app/src/main/java/org/handtop/desktop/linux/LinuxTheme.kt`, `README.md` (License list: the generated themes are now Classic and Glossy)
- Test: `app/src/test/java/org/handtop/desktop/linux/LinuxThemeTest.kt`

**Interfaces:**
- Consumes: `GlossyTheme.colors`, `ClassicTheme.colors`, `ThemeId.GLOSSY`.
- Produces:
  - `class LinuxTheme.Variant(val name: String, val title: String, val colors: ThemeColors)` with `dir = "/usr/local/share/handtop/$name"`, `kdeScheme = "HandTop$title"`, `gtkName = "HandTop-$title"`, `gtk2Rc = "share/themes/$gtkName/gtk-2.0/gtkrc"`, `assets = "linux-theme/$name"`.
  - `LinuxTheme.CLASSIC = Variant("classic", "Classic", ClassicTheme.colors)`, `LinuxTheme.GLOSSY = Variant("glossy", "Glossy", GlossyTheme.colors)`, `fun variant(theme: ThemeId): Variant?` (null for MODERN and SOFT), `fun files(v: Variant): Map<String, String>`, `fun environment(theme: ThemeId): List<String>`. The constants `DIR`, `KDE_SCHEME`, `GTK_NAME`, `GTK2_RC` go away (CLASSIC's values are the same strings as today).

- [ ] **Step 1: Palettes in the build.** Each palette file defines the same SCSS variables, named `$hp_*` (not `_palette.scss`: libadwaita has its own): `light inner_light shadow dark_shadow title_active title_active_end title_inactive title_inactive_end title_text_active title_text_inactive tooltip trough face field text disabled selection selection_text dark_fill`. Classic: today's literals (`#ffffff #dfdfdf #808080 #0a0a0a #000080 #1084d0 #808080 #b4b4b4 #ffffff #dfdfdf #ffffe1 #dfdfdf #c0c0c0 #ffffff #000000 #808080 #000080 #ffffff #a0a0a0`). Glossy: `#ffffff #f5f4ea #aca899 #716f64 #0a58ce #2a72e0 #5b7ec2 #6f8fcc #ffffff #ffffff #ffffe1 #f5f4ea #ece9d8 #ffffff #000000 #aca899 #316ac5 #ffffff #cfcbbb`. Replace every palette literal in `gtk3/_colors.scss`, `gtk4/_handtop-colors.scss` (`@define-color x #{$hp_…}`) and `gtk4/_classic.scss` (lines 11–22 and the title gradients at 145–152) with these variables; each file starts with `@import 'handtop-palette';`. `build.py` takes `VARIANTS = ['classic', 'glossy']`, copies `palettes/<name>.scss` as `_handtop-palette.scss` into each work dir, writes to `assets/linux-theme/<name>/`, and names the variant in the CSS header ("HandTop classic theme" / "HandTop classic theme, Glossy colours").

- [ ] **Step 2: Build and prove classic is unchanged**

Run: `python -I tools/linux-theme/build.py && git status --short app/src/main/assets/linux-theme`
Expected: only `?? app/src/main/assets/linux-theme/glossy/` — nothing under `classic/` modified. Then `grep -c ece9d8 app/src/main/assets/linux-theme/glossy/gtk-3.0/gtk.css` → ≥ 1 and the same for `gtk-4.0/gtk.css` and `#316ac5`.

- [ ] **Step 3: Write the failing tests** in `LinuxThemeTest.kt` (update the existing ones from `DIR`/`KDE_SCHEME`/`GTK_NAME`/`GTK2_RC`/`files(ClassicTheme.colors)` to `CLASSIC.dir`/`.kdeScheme`/`.gtkName`/`.gtk2Rc`/`files(CLASSIC)`, same expected strings), and add:

```kotlin
@Test
fun softLeavesProgramsAlone() = assertEquals(emptyList<String>(), LinuxTheme.environment(ThemeId.SOFT))

@Test
fun glossyIsTheClassicSetInItsOwnFolder() {
    val classic = LinuxTheme.environment(ThemeId.CLASSIC)
    val glossy = LinuxTheme.environment(ThemeId.GLOSSY)
    val expected = classic.map {
        it.replace(LinuxTheme.CLASSIC.dir, LinuxTheme.GLOSSY.dir).replace(LinuxTheme.CLASSIC.gtkName, LinuxTheme.GLOSSY.gtkName)
    }
    assertEquals(expected, glossy)
    assertEquals("/usr/local/share/handtop/glossy", LinuxTheme.GLOSSY.dir)
    assertEquals("HandTop-Glossy", LinuxTheme.GLOSSY.gtkName)
}

@Test
fun glossyFilesCarryGlossyColours() {
    val files = LinuxTheme.files(LinuxTheme.GLOSSY)
    val scheme = files.getValue("share/color-schemes/HandTopGlossy.colors")
    assertTrue(scheme.contains("236,233,216"))   // face #ECE9D8
    assertTrue(scheme.contains("49,106,197"))    // selection #316AC5
    assertTrue(files.getValue(LinuxTheme.GLOSSY.gtk2Rc).contains("#ece9d8"))
    assertEquals("HandTopGlossy", ini(files.getValue("xdg/kdeglobals"), "General", "ColorScheme"))
}

@Test
fun theBuiltGlossyGtkThemesAreBeige() {
    for (path in listOf("gtk-3.0/gtk.css", "gtk-4.0/gtk.css", "gtk-4.0/gtk-4.18.css")) {
        val css = File("src/main/assets/linux-theme/glossy/$path").readText()
        assertTrue(path, css.contains("#ece9d8")); assertTrue(path, css.contains("#316ac5"))
    }
}

@Test
fun installWritesEachVariantIntoItsOwnFolder() =
    assertEquals(listOf("classic", "glossy"), LinuxTheme.installed.map { it.name })
```
(`LinuxTheme.installed: List<Variant>` = the variants `install` writes, in that order.)

- [ ] **Step 4: Run to verify failure.** Run: `./gradlew :app:testDebugUnitTest --tests org.handtop.desktop.linux.LinuxThemeTest`. Expected: compilation FAILS (`Variant`, `GLOSSY` unresolved).

- [ ] **Step 5: Implement** `Variant`, `variant()`, `files(v)` (KDE scheme `Name=HandTop ${v.title}`, all paths and ids from `v`), `environment()` built from `variant()`, and `install()` looping over `installed` with a stamp file in each variant's own folder.

- [ ] **Step 6: Run tests.** Run: `./gradlew :app:testDebugUnitTest`. Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add tools/linux-theme app/src README.md
git commit -m "Classic Linux theme in Glossy colours for the Glossy theme"
```

---

### Task 5: Device check, fixes, docs

**Files:**
- Modify: whatever the sweep finds (each fix with its own failing test where it is testable on the JVM)
- Modify: `CHANGELOG.md` (Unreleased → Changes), `README.md` ("Themes" line in What works now; Roadmap item 6), `docs/ROADMAP.md`, the spec (a «Как вышло» section)

- [ ] **Step 1: Install and sweep.** Build, `adb install -r`, relaunch on DeX. For **each of the four themes** (switch in Settings → Desktop → Theme), take on DeX: desktop with Files ("Linux home") and Settings; start menu; calendar; notification center; a rename dialog in Files; Launchpad; then on the phone screen only if the user is not using it: the key row above the keyboard in a Linux window. Linux programs (start them after the switch): GNOME Text Editor (GTK 4 / libadwaita), a GTK 3 program (e.g. Mousepad or gedit if installed), KCalc (Qt/KDE). Save under `shots/themes-<theme>/`.
Expected: Modern and Classic match `shots/themes-before/`; Soft has no white-on-light or dark-on-dark text or icons anywhere; Glossy's Linux programs are beige with blue selection and blue header bars; Soft's Linux programs look as in Modern.

- [ ] **Step 2: Fix what the sweep finds**, TDD where possible, re-shoot the affected screens. Expected: every screen of Step 1 readable.

- [ ] **Step 3: Run the whole suite.** Run: `./gradlew :app:testDebugUnitTest`. Expected: PASS.

- [ ] **Step 4: Docs.** CHANGELOG: "Two more themes: Soft (light windows, centered titles, round title buttons, a dark taskbar; Linux programs keep their own look) and Glossy (blue gradient title bars and taskbar, a green Start button, beige bevelled controls; Linux programs get the classic theme in the same colours). Settings → Desktop → Theme." README: themes line lists four; Roadmap 6 "Themes: Modern, Classic, Soft and Glossy ✔". ROADMAP line in Russian. Spec «Как вышло»: what was verified and anything left.

- [ ] **Step 5: Commit, then ask the user before pushing**

```bash
git add -A
git commit -m "Soft and Glossy themes: device fixes and docs"
```
