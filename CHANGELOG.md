# Version history

What changed in each version of HandTop. 0.3.0 is the first version published as an APK; the
dates of the earlier ones are when they were finished.

## Unreleased

### Changes

- Drag and drop works with X11 programs too: drop files from Files or a Wayland program into an
  X11 window (Thunar and other programs running under X11), and drag files out of an X11
  program into Files, a Wayland program or another X11 window.
- Keys the phone keyboard lacks, for Linux programs: while the on-screen keyboard types into a
  Linux window, a row above it has Esc, Tab, Ctrl, Alt, Shift, the arrows, Home, End, Page Up,
  Page Down and Delete. Ctrl, Alt and Shift hold for the next key, from the row or a letter on
  the keyboard (Ctrl, then S: save). Settings → Display turns the row off.
- The on-screen keyboard sees the text around the caret in GTK and Qt programs (Wayland): it can
  autocorrect, suggest and capitalize from the context, and a word it corrects is replaced at
  once instead of being erased key by key.
- Desktop widgets: a clock, the weather and Linux's processor and memory. Add them from the
  desktop's menu (long press or right click on the wallpaper → Add widget…), drag them anywhere on
  the icons' grid, each in the look of the theme; none is there until you add it.
  - Clock: time and date; 24- or 12-hour and seconds in its settings; a tap shows the calendar.
  - Weather (Open-Meteo): now, the next hours and the place, by the phone's approximate location
    or a city you pick; a tap opens the 7-day forecast. Only coordinates rounded to about a
    kilometre leave the phone; no account or key.
  - Linux: the processor and memory Linux uses (with HandTop), every 2 seconds; a tap opens
    Linux System.
- Two more themes, in Settings → Desktop → Theme:
  - **Soft**: light windows whose title bar is the window's own colour, the title centered,
    round gray title buttons, rounded corners and shadows, a dark taskbar. Linux programs keep
    their own look.
  - **Glossy**: blue gradient title bars with rounded tops, a blue taskbar with a green Start
    button, beige bevelled controls with blue selection, colour icons. Linux programs (GTK 2,
    GTK 3, GTK 4 and libadwaita, Qt and KDE) get the classic theme in the same beige and blue.
  Their colours are tested for readable text: 4.5 : 1 contrast or better on the windows, menus
  and the taskbar.

### Fixes

- Taps no longer stop reaching windows when an X11 program keeps a tiny window off the screen
  (GTK does around drag and drop): such windows are not taken for menus.

## 0.3.2 — 2026-11-28 · alpha

### Changes

- Drag and drop between Files and Linux programs: drop files from Files into a GTK or Qt window
  (an editor opens them, a file manager copies them), and drag files out of a Linux file manager
  into Files, which asks Move or Copy. Linux programs also drag to each other through HandTop.
  X11 programs do not take part yet.
- A window's button on the taskbar has a menu (long press or right click): minimize or restore,
  maximize or normal size, close. Also the way to a window dragged off the screen.
- On a phone on its side, windows that bring no size of their own (Settings, terminals) open
  nearly as tall as the desktop instead of 62% of it. DeX and monitors keep the old size.
- Files: pick several files with a finger. A tap on a file's icon (or Select in its menu)
  starts it; taps then pick files, and a bar in place of the toolbar shows how many, Select
  all and their actions. Back, the ✕ or opening another folder ends it.
- Files: search. The magnifier (or Ctrl+F) turns the path into a search field; files and
  folders whose names have every word typed show up as you type, from the folder and the
  folders inside it, each with where it is.
- Dolphin and the file dialogs of KDE programs get the phone's folders (Phone, Downloads,
  Camera…) as places, like GTK programs already do.
- Linux programs can open files and links in Android apps: `handtop-open FILE|LINK` in a
  terminal, and "Android app" under "Open with another application" in Linux file managers.
  A file goes to the Android app you pick, a link to its app (browser, mail…). Linux
  defaults are not changed.
- Files: drag and drop. Hold a file with a finger and move it (with a mouse, just drag it),
  together with the other picked files: onto a folder, into another Files window, or a folder
  onto the desktop for a shortcut. On release a menu asks Move here or Copy here. A long press
  without a move still opens the file's menu.
- The classic theme reaches Linux programs: GTK 2, GTK 3, GTK 4 and libadwaita, Qt 5/6 and
  KDE programs get gray bevelled buttons and fields, blue selection, the navy title gradient on
  their own title bars, square corners and no animations, on every distribution. Nothing is
  installed in Linux and no user setting is changed: HandTop writes its own files and points the
  programs to them while the classic theme is on. Programs take the theme when they start; with
  the modern theme they keep their own look.

### Fixes

- The on-screen keyboard comes up only when you touch a text field: not when a window opens
  with its field already active, not when a program moves the focus to a field, and not for a
  tap on a button while a field has the focus, also when that tap brings the window to the
  front. Linux programs, Files' search and HandTop's dialogs alike; the taskbar's keyboard
  button still brings it up anywhere.
- Rename and New folder in Files: in landscape some keyboards (Samsung's) filled the screen with
  their own edit box and hid the dialog; now the keyboard stays a keyboard, the dialog moves above
  it, and its Done key confirms.
- A Linux window that could not shrink to fit above the on-screen keyboard (GTK programs with a
  large minimum size) stayed short, its bottom cut off, after the keyboard went away; it now gets
  its height back.
- Linux programs no longer look washed out after a key press: a window that took focus from
  the keyboard (DeX, a keyboard, scrcpy) had Android's focus highlight over it, a 16% white
  veil (black showed as dark gray). The same veil could cover Files, terminals and the app
  list.
- Back closes an open menu of a Linux program instead of minimizing its window.
- After a menu closes, the on-screen keyboard comes back only if it was up before; tapping a
  program's menu bar no longer flashes the keyboard.
- A Linux window follows the size its program draws: no black margins around a fixed-size
  program (a game, glmark2), and a program that cannot get smaller than its minimum is no
  longer cut off.

## 0.3.1 — 2026-11-26 · alpha

### Changes

- **Sharp X11 programs.** X11 programs now draw at the screen's full resolution and learn
  their scale (Xft.dpi, XSETTINGS), so VS Code, Firefox, GTK and Qt programs on X11 are sharp
  instead of stretched and blurry. Very old programs that cannot scale look small; for them,
  Settings → Graphics and sound → X11 programs → Scaled brings back the stretched look.
- X11 programs can resize their own windows (a game changing resolution, a growing dialog).

### Fixes

- A Linux menu opened while the on-screen keyboard was up stays under its button when the
  keyboard hides.
- The on-screen keyboard no longer pops up when a submenu opens.
- Typing works again after closing a menu that had a submenu open.
- Firefox tabs no longer crash on start ("Gah. Your tab just crashed").
- VS Code and other Electron programs start from the Linux apps menu (HandTop adds
  `--no-sandbox`, which they need as root).
- Qt programs start even when the distribution lacks Qt's Wayland plugins (`qt6-wayland`): they
  fall back to X11.
- Terminal programs such as QTerminal open the same shell as HandTop's terminal (bash), not
  plain sh.

## 0.3.0 — 2026-11-25 · alpha

The first version meant to be shown publicly, published as a signed APK. Everything since 0.2.0:

### Linux programs in windows

- Own **Wayland compositor**: every Linux program is a separate window on the desktop.
  It supports the program's own title bars, moving and resizing, dialogs, popups,
  sub-surfaces and scaled surfaces (mpv).
- **X11 programs** through rootless Xwayland, with HandTop as the X11 window manager.
  Xwayland restarts if it exits unexpectedly.
- **Shared clipboard** with Android, for Wayland and X11 programs.
- The on-screen keyboard and any-language input in Linux programs (text-input-v3).
- Programs' own mouse cursors.
- **Sound**: Linux programs play through Android (PulseAudio).
- A D-Bus session bus. The screen stays on while a Linux program plays video.
- Faster windows:
  - each changed area is copied once;
  - hidden windows are slowed down;
  - programs get answers without waiting for the next screen refresh.

### GPU acceleration

- **OpenGL 4.6, OpenGL ES 3.2 and Vulkan 1.4 on Adreno** (Snapdragon), without root.
  It works through Mesa 26.3.1, with Turnip and Zink over KGSL.
- Shipped in the APK for glibc (Debian, Ubuntu, Fedora) and musl (Alpine), and set up
  automatically.
- Measured on a Galaxy S24 Ultra:
  - glmark2 terrain: 7 → 117 FPS;
  - SuperTuxKart: ~0.5 → 48 FPS.

### Linux setup

- **Linux sets itself up** after it is installed: GPU drivers, X11, sound and `gpg`.
  - Progress is shown on the taskbar, in a notification and in the Linux System window.
  - A failed step can be retried.
- Linux games start from the menu and from the terminal (`/usr/games` is in `PATH`).

### Desktop

- HandTop's own icon: an H cut out of a window.
- A **full-screen launcher** for Linux programs:
  - search;
  - a blurred wallpaper behind it;
  - the programs' own icons (PNG, SVG, XPM).
- **Wallpapers**: 9 built-in designs, or your own picture.
- **Desktop shortcuts** to Linux programs and folders: add them, remove them, or drag them
  anywhere on the grid.
- **Files**: a file manager for the phone and for Linux. Phone folders also appear in Linux
  file managers.
- Notifications on the taskbar, and a notification center.
- **The screen as a touchpad**, with a pointer. Long-press the keyboard button on the taskbar
  to turn it on.
  - Tap to click.
  - Hold, then let go for a right click, or move to drag.
  - Two fingers scroll.
- **Interface scale**, set separately for the phone screen and for DeX or a monitor.
  The phone screen starts at 70 %.
- Settings sorted into categories.
- Full screen in landscape, with the status icons on the taskbar.
- **Themes**: Modern, or Classic (gray 3D windows, colour icons, no animations).
  Choose one in Settings → Desktop → Theme.

### Fixes

- Windows keep their size after the on-screen keyboard closes, and open beside the desktop
  icons instead of over them.
- No Linux terminal opens by itself when HandTop starts.
- The Linux setup window keeps its Install button in view at any window size, and asks before
  reinstalling.

## 0.2.0 — 2026-11-24

- **Four distributions** to choose from: Debian 13, Ubuntu 26.04 LTS, Alpine and Fedora.
  Each is an official Docker Hub image, verified by its sha256 checksum.
- Installs are atomic: a failed install never leaves half a system behind. Linux can be removed.
- A Settings window:
  - phone storage in Linux as `/sdcard` (with "All files access");
  - the status of Android's background process limit;
  - Linux management.
- The hostname is `handtop`.

## 0.1.0 — 2026-11-24

- **Desktop shell**:
  - windows that move, resize, minimize, maximize and snap to the screen edges;
  - a taskbar with a clock;
  - a start menu.
- **Terminals**:
  - the Android shell, several at once;
  - an extra keys row;
  - they keep running in the background.
- Mouse: a right-click menu. Keyboard shortcuts: `Ctrl+Alt+T`, `Alt+F4`, `Ctrl+Shift+C/V`,
  `Ctrl +/-`.
- **Debian Linux through proot**, without root, from the official Docker Hub image.
