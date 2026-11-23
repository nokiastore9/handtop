# HandTop — Desktop in your hand

**A PC-style desktop for Android: windows, a taskbar, a terminal — and (soon) real Linux apps.**
No root required. Built for phones, tablets and Samsung DeX.

**Рабочий стол как на ПК для Android: окна, панель задач, терминал — и (скоро) настоящие Linux-приложения.**
Без root. Для телефонов, планшетов и Samsung DeX.

> Status: early development (stage 1 of 5).

## What works now / Что уже работает

- Desktop with wallpaper and shortcuts, taskbar with clock, start menu
- Windows: drag, resize (mouse cursors on the frame), minimize, maximize,
  snap to the left / right half or full screen by dragging to the edge
- Terminal windows (Android shell for now), several at once, survive DeX switches
- Extra keys row (Esc, Tab, Ctrl, Alt, arrows…) — hidden when a hardware keyboard is attached
- Mouse: right-click menu, wheel scrolling, hover highlights
- Shortcuts: `Ctrl+Alt+T` new terminal, `Alt+F4` close, `Ctrl+Shift+C/V` copy/paste, `Ctrl +/-` font size

## Roadmap

See [docs/ROADMAP.md](docs/ROADMAP.md).

1. Desktop shell + terminal ← **we are here**
2. Linux userland (Debian arm64 via proot, optional chroot with root)
3. Own Wayland compositor: every Linux app is a separate window on the desktop
4. Xwayland, clipboard, file sharing, sound
5. GPU acceleration (Turnip + Zink on Snapdragon), DeX polish, box64 / Wine

## Building

Requirements: JDK 17+, Android SDK (platform 37, NDK 30.0.16138531).

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## License

[GNU GPL v3](LICENSE).
The `terminal-emulator` and `terminal-view` modules are vendored from
[Termux](https://github.com/termux/termux-app) and stay under the Apache License 2.0.
