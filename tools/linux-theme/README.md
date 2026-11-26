# Classic theme for Linux programs (GTK)

`build.py` builds HandTop's Classic theme for GTK 3, GTK 4 and libadwaita into
`app/src/main/assets/linux-theme/classic/`. HandTop copies it into Linux and points programs at it
while the Classic theme is on (`LinuxTheme.kt`, which also writes the Qt/KDE and GTK 2 files).

```sh
pip install libsass
python -I tools/linux-theme/build.py
```

The upstream stylesheets are downloaded once into `.cache/` (pinned versions, SHA-256 checked) and
compiled with HandTop's own parts:

| Toolkit | Upstream | HandTop's parts |
|---|---|---|
| GTK 3 | GTK 3.24.52's Adwaita | `gtk3/_colors.scss` (palette), `gtk3/_drawing.scss` (bevels, called by Adwaita for every widget and state), `gtk3/_classic.scss` (title bars, scroll bars, tabs…) |
| GTK 4, libadwaita | libadwaita 1.9.4 → `gtk.css`, 1.7.12 → `gtk-4.18.css` | `gtk4/_handtop-colors.scss` (palette, after libadwaita's), `gtk4/_classic.scss` (bevels) |

The build also squares every corner, removes every transition and drops libadwaita's dark and
high-contrast blocks. The palette matches the shell's (`ui/ClassicTheme.kt`); change both together.

Licenses: the generated CSS derives from GTK's Adwaita theme and from libadwaita, both
LGPL-2.1-or-later; the check-mark images in `gtk-4.0/assets` are libadwaita's.
