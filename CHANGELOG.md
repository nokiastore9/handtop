# Version history

What changed in each version of HandTop. 0.3.0 is the first version published as an APK; the
dates of the earlier ones are when they were finished.

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
