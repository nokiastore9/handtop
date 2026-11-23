# Архитектура и план развития

## Принятые решения

| Вопрос | Решение |
|---|---|
| Root | Не нужен. Root — опция, которую можно включить в любой момент (chroot вместо proot). |
| Распространение | GitHub / F-Droid, не Google Play. |
| Лицензия | GPLv3. Модули терминала из Termux — Apache 2.0. |
| Целевое железо | Snapdragon (Adreno) — ради GPU-ускорения через Turnip. Остальные — программный рендер. |
| Финальная цель | Полная оптимизация под Samsung DeX. |

## Слои

```
┌──────────────── Android-приложение (Kotlin) ─────────────────┐
│  Рабочий стол: обои, значки, панель задач, меню «Пуск»       │
│  Оконный менеджер (DesktopArea / DesktopWindow)              │
│  Нативные окна: терминал, «О программе», позже файлы и т.д.  │
│  Linux-окна ← Wayland-композитор (C/C++, NDK)        [этап 3]│
└───────────────────────────────┬──────────────────────────────┘
                                │ Unix-сокеты
┌───────────── Debian arm64 через proot / chroot ──────────────┐
│  apt, bash, GTK/Qt-приложения, Xwayland            [этапы 2–4]│
│  PulseAudio → AAudio, Mesa Zink/Turnip → GPU          [этап 5]│
└──────────────────────────────────────────────────────────────┘
```

## Код

- `app/` — оболочка.
  - `wm/` — оконный менеджер. Окна — обычные View, порядок детей = z-order.
    Любое содержимое окна реализует `WindowContent`.
  - `shell/` — панель задач, значки, системные окна.
  - `term/` — терминал: `TermSessions` (сессии живут дольше Activity),
    `TerminalContent` (окно), `ExtraKeysBar`.
- `terminal-emulator/`, `terminal-view/` — библиотеки Termux (Apache 2.0), изменения помечены в шапке файла.

## Этапы

### 1. Рабочий стол и терминал — готово
Окна (перемещение, размер, свернуть/развернуть, прилипание к краям), панель задач,
меню «Пуск», терминал Android с доп. клавишами, мышь и горячие клавиши.

### 2. Linux-окружение
- Скачивание и распаковка Debian arm64 rootfs при первом запуске (с прогрессом).
- proot (собирается нами, кладётся в APK как `libproot.so`, чтобы запускаться из nativeLibraryDir).
- Терминал по умолчанию открывается внутри Debian; `apt install` работает.
- Foreground service, чтобы процессы не убивались в фоне.
- Режим root: chroot вместо proot (быстрее), переключатель в настройках.
- proot и его загрузчик кладём в APK как `libproot.so` / `libproot-loader.so`
  (`packaging.jniLibs.useLegacyPackaging = true`), остаёмся на актуальном targetSdk (см. «Риски», п. 1).

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
- box64 (x86_64 Linux-программы), Wine (Windows-программы).

## Риски

1. **Запуск бинарников (W^X) — проверено, решаемо.** С targetSdk ≥ 29 Android запрещает
   `execve()` файлов из каталога данных приложения. Проверка на Galaxy S24 Ultra, Android 17,
   targetSdk 37, домен `untrusted_app` (2026-11-24):
   - `./uname` из `files/` → `Permission denied` (execve запрещён);
   - `/system/bin/linker64 files/.../uname -a` → работает (mmap с PROT_EXEC разрешён).

   Вывод: исполняемые файлы (proot, загрузчик) — в APK как `lib*.so` (nativeLibraryDir
   исполняем), Linux-программы из rootfs грузит загрузчик proot через `mmap`.
   targetSdk 28 не нужен.
2. **Phantom process killer (Android 12+).** Убивает лишние дочерние процессы.
   Android 14+: «Отключить ограничения дочерних процессов» в настройках разработчика;
   12–13: `adb shell device_config put activity_manager max_phantom_processes 2147483647`.
3. **Дисплейный сервер** — самая трудоёмкая часть; делать маленькими шагами.
