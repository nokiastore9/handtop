# HandTop — Desktop in your hand

**A PC-style desktop for Android: windows, a taskbar, terminals and a real Debian Linux — Linux GUI apps are next.**
No root required. Built for phones, tablets and Samsung DeX.

**Рабочий стол как на ПК для Android: окна, панель задач, терминалы и настоящий Debian Linux — следующий шаг: Linux-приложения с окнами.**
Без root. Для телефонов, планшетов и Samsung DeX.

> Status: early development (stage 2 of 5).

## What works now / Что уже работает

- Desktop with wallpaper and shortcuts, taskbar with clock, start menu
- Windows: drag, resize (mouse cursors on the frame), minimize, maximize,
  snap to the left / right half or full screen by dragging to the edge
- **Linux Terminal**: Debian 13 (trixie, arm64) through proot as fake root — `apt install` works.
  Installed in one tap from the official Docker Hub image (≈30 MB download, sha256-verified)
- Android Terminal (the system shell), several terminals at once
- Terminals keep running in the background (foreground service) and survive DeX switches
- Extra keys row (Esc, Tab, Ctrl, Alt, arrows…) — hidden when a hardware keyboard is attached
- Mouse: right-click menu, wheel scrolling, hover highlights
- Shortcuts: `Ctrl+Alt+T` new terminal, `Alt+F4` close, `Ctrl+Shift+C/V` copy/paste, `Ctrl +/-` font size

## Roadmap

See [docs/ROADMAP.md](docs/ROADMAP.md).

1. Desktop shell + terminal ✔
2. Linux userland: Debian arm64 via proot ✔ (optional chroot with root — to do)
3. Own Wayland compositor: every Linux app is a separate window on the desktop ← **next**
4. Xwayland, clipboard, file sharing, sound
5. GPU acceleration (Turnip + Zink on Snapdragon), DeX polish, box64 / Wine

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
