# Classic theme for Linux programs (GTK)

`build.py` builds HandTop's classic theme for GTK 3, GTK 4 and libadwaita in two palettes:
`palettes/classic.scss` into `app/src/main/assets/linux-theme/classic/` (the Classic theme) and
`palettes/glossy.scss` (beige and blue) into `app/src/main/assets/linux-theme/glossy/` (the Glossy
theme). HandTop copies both into Linux and points programs at the one of the theme in use
(`LinuxTheme.kt`, which also writes the Qt/KDE and GTK 2 files in the same colours).

```sh
pip install libsass
python -I tools/linux-theme/build.py
```

The upstream stylesheets are downloaded once into `.cache/` (pinned versions, SHA-256 checked) and
compiled with HandTop's own parts:

| Toolkit | Upstream | HandTop's parts |
|---|---|---|
| GTK 3 | GTK 3.24.52's Adwaita | `gtk3/_colors.scss` (Adwaita's colour names from the palette), `gtk3/_drawing.scss` (bevels, called by Adwaita for every widget and state), `gtk3/_classic.scss` (title bars, scroll bars, tabs…) |
| GTK 4, libadwaita | libadwaita 1.9.4 → `gtk.css`, 1.7.12 → `gtk-4.18.css` | `gtk4/_handtop-colors.scss` (libadwaita's colour names from the palette, after libadwaita's), `gtk4/_classic.scss` (bevels) |

The build also squares every corner, removes every transition and drops libadwaita's dark and
high-contrast blocks. Each palette matches the shell's theme (`ui/ClassicTheme.kt`, `ui/GlossyTheme.kt`); change both together
(`LinuxThemeTest` checks the built Glossy files carry its colours).

Licenses: the generated CSS derives from GTK's Adwaita theme and from libadwaita, both
LGPL-2.1-or-later; the check-mark images in `gtk-4.0/assets` are libadwaita's.
