# Архитектура и план развития

## Принятые решения

| Вопрос | Решение |
|---|---|
| Root | Не нужен. Root — опция, которую можно включить в любой момент (chroot вместо proot). |
| Распространение | GitHub / F-Droid, не Google Play. |
| Лицензия | GPLv3. Модули терминала из Termux — Apache 2.0; proot — GPL v2+; talloc — LGPL v3+. |
| Целевое железо | Snapdragon (Adreno) — ради GPU-ускорения через Turnip. Остальные — программный рендер. |
| Финальная цель | Полная оптимизация под Samsung DeX. |

## Слои

```
┌──────────────── Android-приложение (Kotlin) ─────────────────┐
│  Рабочий стол: обои, значки, панель задач, меню «Пуск»       │
│  Оконный менеджер (DesktopArea / DesktopWindow)              │
│  Нативные окна: терминалы, установщик Linux, «О программе»   │
│  Linux-окна ← Wayland-композитор (C/C++, NDK)        [этап 3]│
└───────────────────────────────┬──────────────────────────────┘
                                │ pty, позже Unix-сокеты
┌──────── Debian 13 arm64 через proot (libproot.so) ───────────┐
│  apt, bash, консольные программы                    [этап 2] │
│  GTK/Qt-приложения, Xwayland                     [этапы 3–4] │
│  PulseAudio → AAudio, Mesa Zink/Turnip → GPU        [этап 5] │
└──────────────────────────────────────────────────────────────┘
```

## Код

- `app/` — оболочка.
  - `wm/` — оконный менеджер. Окна — обычные View, порядок детей = z-order.
    Любое содержимое окна реализует `WindowContent`.
  - `shell/` — панель задач, значки, системные окна.
  - `term/` — терминал: `ShellSpec` (что запускать), `TermSessions` (сессии живут дольше
    Activity), `TerminalContent` (окно), `ExtraKeysBar`.
  - `linux/` — Linux-окружение: `LinuxInstaller` (Docker Hub → проверка sha256 → распаковка),
    `DockerRegistry`, `TarExtractor`, `LinuxEnv` (команда proot), `ProcFakes`, `LinuxSetupContent`.
  - `service/SessionService` — foreground service, держит процессы живыми в фоне.
- `native/proot/` — исходники termux/proot (GPL v2+), `native/talloc/` — talloc (LGPL v3+).
- `buildSrc/BuildProotTask.kt` — сборка proot и загрузчика чистым clang из NDK (без make/waf),
  результат кладётся в APK как `libproot.so` / `libproot-loader.so`.
- `terminal-emulator/`, `terminal-view/` — библиотеки Termux (Apache 2.0), изменения помечены в шапке файла.

## Этапы

### 1. Рабочий стол и терминал — готово
Окна (перемещение, размер, свернуть/развернуть, прилипание к краям), панель задач,
меню «Пуск», терминал Android с доп. клавишами, мышь и горячие клавиши.

Проверено в беспроводном DeX (2026-11-24): окна плавающие, рамки и курсоры мыши, прилипание
к краям, правый клик, колесо, переподключение DeX без перезапуска Activity, фокус клавиатуры
следует за активным окном.

### 2. Linux-окружение — основное готово
Проверено на S24 Ultra, Android 17, targetSdk 37 (2026-11-24):
- установщик: официальный `debian:trixie-slim` (arm64) с Docker Hub, проверка sha256,
  распаковка ≈ 108 МБ за несколько секунд;
- «Терминал Linux»: bash под proot с фейковым root, `apt update` / `apt install` работают
  (fastfetch, htop), часовой пояс берётся из Android;
- недоступные приложению файлы `/proc` (stat, uptime, loadavg, version…) подменяются;
- foreground service: сессия пережила 30 с в фоне.

Осталось:
- **режим root (chroot)** — нужен телефон с root для проверки;
- доступ к памяти телефона (`/sdcard`) — по разрешению пользователя;
- подсказка про phantom process killer (см. «Риски», п. 3), если Android начнёт убивать процессы;
- выбор дистрибутива (Ubuntu и др. — тот же механизм Docker Hub), переустановка/удаление.

### 3. Свой Wayland-композитор
- libwayland-server + EGL/GLES в NDK, сокет в общем каталоге с rootfs.
- Протоколы: wl_compositor, wl_shm, xdg_shell, wl_seat (мышь/клавиатура/тач), wl_output.
- Каждый xdg_toplevel = отдельное окно `DesktopWindow` (контент — TextureView/SurfaceView).
- Первая цель: `weston-terminal`, затем GTK/Qt-приложения.

### 4. Интеграция
Xwayland (X11-приложения), общий буфер обмена, drag & drop файлов,
звук (PulseAudio → AAudio), уведомления.

### 5. Ускорение и DeX
- Mesa Turnip (Vulkan для Adreno) + Zink (OpenGL поверх Vulkan), dmabuf.
- DeX: захват Meta-клавиши, курсоры, несколько мониторов, режим «лаунчер».
- Проверить горячие клавиши (Ctrl+Alt+T, Alt+F4, Ctrl+Shift+C/V, Ctrl ±) с клавиатурой,
  подключённой прямо к телефону: в беспроводном DeX на ПК клавиатуру перехватывает Windows.
- box64 (x86_64 Linux-программы), Wine (Windows-программы).

## Риски и находки

1. **Запуск бинарников (W^X) — решено.** С targetSdk ≥ 29 Android запрещает `execve()` файлов
   из каталога данных приложения (`./uname` из `files/` → `Permission denied`), но разрешает
   `mmap` с PROT_EXEC (`linker64 files/.../uname` работает). Поэтому proot и загрузчик лежат в
   APK как `lib*.so` (nativeLibraryDir исполняем), а Linux-программы грузит загрузчик proot.
   proot собран с `PROOT_UNBUNDLE_LOADER`: загрузчик не распаковывается в app data.
2. **Android закрывает приложениям часть системы.** `/system/bin/sh` можно запустить, но нельзя
   прочитать (поэтому proot не может его загрузить — для Linux не важно); листинг `/system/bin`
   и `/data` запрещён; `/proc/stat`, `/proc/uptime` и др. закрыты — подменяются (`ProcFakes`).
3. **Phantom process killer (Android 12+).** Убивает лишние дочерние процессы (лимит 32 на все
   приложения). Android 14+: «Отключить ограничения дочерних процессов» в настройках разработчика;
   12–13: `adb shell device_config put activity_manager max_phantom_processes 2147483647`.
4. **Отладка proot на устройстве** однажды повесила телефон (вместе с беспроводным DeX и scrcpy):
   эксперименты с нативным кодом — по одному и с `timeout`.
5. **Дисплейный сервер** — самая трудоёмкая часть; делать маленькими шагами.
