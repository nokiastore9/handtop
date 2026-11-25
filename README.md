<img src="docs/brand/handtop-icon.svg" width="96" alt="HandTop">

# HandTop — Desktop in your hand

**A PC-style desktop for Android: windows, a taskbar, terminals and real Linux with its desktop apps.**
No root required. For phones, tablets and external screens, with or without Samsung DeX.

**Рабочий стол как на ПК для Android: окна, панель задач, терминалы и настоящий Linux с его программами.**
Без root. Для телефонов, планшетов и внешних экранов, с Samsung DeX и без него.

> [!WARNING]
> **Early version — 0.3.0 (preview).** HandTop is young and changes fast, so expect rough edges,
> bugs and things that will be reworked. So far it has been tested on one phone, a Samsung Galaxy
> S24 Ultra (Snapdragon 8 Gen 3). There is no ready-made APK yet: build it yourself (see
> [Building](#building)). Ideas and bug reports are welcome.
>
> **Ранняя версия — 0.3.0 (предварительная).** HandTop молод и быстро меняется, поэтому возможны
> шероховатости, ошибки и переделки. Пока он проверен на одном телефоне: Samsung Galaxy S24 Ultra
> (Snapdragon 8 Gen 3). Готового APK ещё нет, его нужно собрать самому (см. [Building](#building)).
> Идеи и сообщения об ошибках приветствуются.

## Screenshots / Скриншоты

| | |
|---|---|
| ![Linux terminal and XTerm on the desktop](docs/screenshots/01-desktop.jpg) | ![glmark2 on the Adreno GPU](docs/screenshots/02-gpu.jpg) |
| The Linux terminal and an X11 program (htop in XTerm)<br>Терминал Linux и X11-программа (htop в XTerm) | 3D on the Adreno GPU: glmark2 through Mesa Turnip + Zink<br>3D на видеокарте Adreno: glmark2 через Mesa Turnip + Zink |
| ![The Classic theme](docs/screenshots/03-classic.jpg) | ![Choosing a Linux distribution](docs/screenshots/04-linux.jpg) |
| The Classic theme<br>Классическая тема | Choose a distribution: Debian, Ubuntu, Alpine, Fedora<br>Выбор дистрибутива: Debian, Ubuntu, Alpine, Fedora |

## What works now / Что уже работает

- Desktop with wallpapers (9 built-in or your own picture) and shortcuts you can add, remove and drag;
  taskbar with clock, start menu, notifications
- **Themes**: Modern (dark, translucent) or Classic — gray 3D windows, menus and controls, its own
  colour icons, no shadows, blur or animations (Settings → Desktop → Theme)
- **Interface scale** from 50 to 130 %, separately for the phone screen (70 % at first) and for
  DeX or a monitor
- **The screen as a touchpad**, with a pointer: tap to click, hold for a right click or to drag,
  two fingers to scroll (long-press the keyboard button on the taskbar)
- Windows: drag, resize (mouse cursors on the frame), minimize, maximize,
  snap to the left / right half or full screen by dragging to the edge
- **Linux Terminal** through proot as fake root, no root needed. One-tap install of an official
  Docker Hub image (sha256-verified): **Debian 13**, **Ubuntu 26.04 LTS**, **Alpine**, **Fedora** —
  with their package managers (`apt`, `apk`, `dnf`). Switch or remove it in Settings
- **Linux apps in their own windows**: HandTop is the Wayland compositor, X11 apps run through
  Xwayland; the clipboard is shared
- **Full-screen launcher** for Linux programs, with search and the programs' own icons
- **Sound** from Linux apps (PulseAudio inside Linux, played by Android)
- **GPU acceleration on Snapdragon** (Adreno): OpenGL 4.6, OpenGL ES 3.2 and Vulkan 1.4 through
  Mesa Turnip + Zink, shipped in the APK (see [native/mesa](native/mesa/README.md))
- **Linux sets itself up** after installing: GPU drivers, X11, sound and `gpg` on every
  distribution, with the progress on the taskbar
- Files: a file manager for the phone and Linux
- Phone storage in Linux as `/sdcard` (optional "All files access")
- Settings in categories: display, desktop, notifications, Linux, graphics and sound, Android
- Android Terminal (the system shell), several terminals at once
- Terminals keep running in the background (foreground service) and survive DeX switches
- Extra keys row (Esc, Tab, Ctrl, Alt, arrows…) — hidden when a hardware keyboard is attached
- Mouse: right-click menu, wheel scrolling, hover highlights
- Shortcuts: `Ctrl+Alt+T` new terminal, `Alt+F4` close, `Ctrl+Shift+C/V` copy/paste, `Ctrl +/-` font size

## Known limitations / Известные ограничения

- Tested on one phone so far (Galaxy S24 Ultra). Other phones and other Android versions
  from other makers have not been tried yet.
- GPU acceleration needs a Snapdragon (Adreno) GPU. On other phones Linux programs draw on the CPU,
  which is much slower.
- Frames of Linux programs are copied through shared memory (no zero-copy yet), so very light
  scenes stop at a few hundred FPS.
- Android 12 and newer may stop Linux processes in the background (the "phantom process killer").
  Settings → Android shows its status and how to turn it off.
- No ready-made APK yet.
- x86 Linux programs (box64) and Windows programs (Wine) are not supported yet.

## Version history / История версий

See [CHANGELOG.md](CHANGELOG.md).

## Roadmap

See [docs/ROADMAP.md](docs/ROADMAP.md).

1. Desktop shell + terminal ✔
2. Linux userland via proot: Debian / Ubuntu / Alpine / Fedora ✔ (optional chroot with root — to do)
3. Own Wayland compositor: every Linux app is a separate window on the desktop ✔
4. Xwayland ✔, clipboard ✔, sound ✔, file sharing
5. GPU acceleration (Turnip + Zink on Snapdragon) ✔, frames without copying, DeX polish
6. Themes: Modern and Classic ✔; a classic look for Linux programs (GTK / Qt), more themes
7. Gamepads; x86 programs (box64) and Windows programs (Wine)

## Building

Requirements: JDK 17+, Android SDK (platform 37, NDK 30.0.16138531). Works on Windows, Linux and macOS:
proot and its loader are compiled from `native/` by a Gradle task with plain NDK clang.

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## License

[GNU GPL v3](LICENSE). Third-party code keeps its license:

- `terminal-emulator`, `terminal-view` — from [Termux](https://github.com/termux/termux-app), Apache 2.0
- `native/proot` — [termux/proot](https://github.com/termux/proot), GPL v2+
- `native/talloc` — [talloc](https://talloc.samba.org/), LGPL v3+
- [AndroidSVG](https://github.com/BigBadaboom/androidsvg) (a dependency) draws the SVG icons of Linux programs, Apache 2.0
