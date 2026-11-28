"""Builds HandTop's classic GTK themes (CSS) from the upstream Adwaita stylesheets.

HandTop's classic look for GTK programs is the upstream stylesheet compiled with HandTop's
classic palette and drawing, plus a layer for what those do not reach:
- GTK 3: Adwaita's theme from GTK 3.24 with classic _colors.scss and _drawing.scss (Adwaita calls
  the drawing mixins for every widget and every state) and _classic.scss.
- GTK 4 and libadwaita: libadwaita's stylesheet (it styles plain GTK 4 widgets too; libadwaita
  programs do not load their own while GTK_THEME is set) with classic _defaults.scss and
  _classic.scss. Two builds: libadwaita 1.9 for GTK 4.20 and later, libadwaita 1.7 for older
  GTK 4 (Debian 13 has GTK 4.18), whose CSS parser does not know the newer stylesheet's syntax.

The upstream sources are downloaded once (pinned versions, SHA-256 checked) into .cache/.
The output goes to the app's assets; HandTop copies it into Linux (LinuxTheme.kt).

    pip install libsass
    python -I tools/linux-theme/build.py

Upstream: GTK 3.24's Adwaita theme and the libadwaita stylesheet, both LGPL-2.1-or-later
(https://gitlab.gnome.org/GNOME/gtk, https://gitlab.gnome.org/GNOME/libadwaita).
"""
import hashlib
import os
import re
import shutil
import sys
import tarfile
import tempfile
import urllib.request

import sass

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
CACHE = os.path.join(HERE, '.cache')
ASSETS_ROOT = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'linux-theme')

# The palettes the classic theme is built in (palettes/<name>.scss), each into its own folder:
# classic for the Classic theme, glossy (beige, blue) for the Glossy one.
VARIANTS = ['classic', 'glossy']

GTK3_VERSION = '3.24.52'
GTK3_URL = 'https://gitlab.gnome.org/GNOME/gtk/-/raw/%s/gtk/theme/Adwaita/' % GTK3_VERSION
GTK3_FILES = {
    '_common.scss': '744ca8eaacc74652087925ab51810a759a17112c12b456e96c70c5da7aee69ae',
    '_colors-public.scss': '484fd475f65782bf3ef32b4bb2d8ba28e59bb5a4efe7b3829ab436b0c3f42e69',
}

# libadwaita release, its tarball's SHA-256, and the stylesheet's file name in gtk-4.0/.
LIBADWAITA = [
    ('1.9.4', 'f5ab3453ee743dc293cdf96d6821789cd20e8d125d9b41637703c12bfa4162ee', 'gtk.css'),
    ('1.7.12', '94c41ab41c2d2188af8290ac0dbb906896cf75e76e69722c2ea2572531de3308', 'gtk-4.18.css'),
]


def download(url, path, digest):
    """[url] saved once at [path], checked against its pinned SHA-256."""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    if not os.path.isfile(path):
        with urllib.request.urlopen(url) as response:
            data = response.read()
        with open(path, 'wb') as out:
            out.write(data)
    with open(path, 'rb') as f:
        actual = hashlib.sha256(f.read()).hexdigest()
    if actual != digest:
        sys.exit('%s: SHA-256 %s, expected %s' % (path, actual, digest))
    return path


def replace_once(text, old, new, what):
    if text.count(old) != 1:
        sys.exit('upstream changed: %s (%d matches)' % (what, text.count(old)))
    return text.replace(old, new)


def no_transitions(text):
    """Every transition property: none, as in the shell (no animations in the classic look)."""
    return re.sub(r'(?m)^([ \t]*)transition:[^;]*;', lambda m: m.group(1) + 'transition: none;', text)


def square_corners(css):
    """Every corner radius computed from the (now 0) radii, e.g. "$button_radius - 2", is 0:
    GTK rejects negative and unitless radii. Percentages stay (round radio buttons)."""
    return re.sub(r'((?:-gtk-outline|border)(?:-[a-z]+)*-radius): ([^;%]+);',
                  lambda m: m.group(1) + ': 0;', css)


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    text = text.replace('\r\n', '\n')  # some upstream sources have Windows line ends
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text)
    print('%s: %d bytes' % (os.path.relpath(path, ROOT), len(text.encode('utf-8'))))


# ---- GTK 3 ----

def classic_gtk3_common(text):
    """Adwaita's _common.scss with square corners and no transitions."""
    for old, new in [
        ('$backdrop_transition: 200ms ease-out;', '$backdrop_transition: none;'),
        ('$button_transition: all 200ms $ease-out-quad;', '$button_transition: none;'),
        ('$button_radius: 5px;', '$button_radius: 0;'),
        ('$menu_radius: 5px;', '$menu_radius: 0;'),
        ('$window_radius: $button_radius + 3;', '$window_radius: 0;'),
        ('$popover_radius: $button_radius + 4;', '$popover_radius: 0;'),
    ]:
        text = replace_once(text, old, new, old)
    return no_transitions(text)


def build_gtk3(variant):
    folder = os.path.join(CACHE, 'gtk-' + GTK3_VERSION)
    for name, digest in GTK3_FILES.items():
        download(GTK3_URL + name, os.path.join(folder, name), digest)
    with tempfile.TemporaryDirectory() as work:
        with open(os.path.join(folder, '_common.scss'), encoding='utf-8') as f:
            common = classic_gtk3_common(f.read())
        with open(os.path.join(work, '_common.scss'), 'w', encoding='utf-8') as f:
            f.write(common)
        shutil.copy(os.path.join(folder, '_colors-public.scss'), work)
        for name in ('_colors.scss', '_drawing.scss', '_classic.scss'):
            shutil.copy(os.path.join(HERE, 'gtk3', name), work)
        copy_palette(variant, work)
        main = os.path.join(work, 'gtk.scss')
        with open(main, 'w', encoding='utf-8') as f:
            f.write('\n'.join(["$variant: 'light';", "@import 'colors';", "@import 'drawing';",
                               "@import 'common';", "@import 'colors-public';", "@import 'classic';", '']))
        css = sass.compile(filename=main, output_style='expanded')
    # Adwaita's images (check marks…) are built into GTK 3: point at them there.
    css = css.replace('url("assets/', 'url("resource:///org/gtk/libgtk/theme/Adwaita/assets/')
    css = square_corners(css)
    header = ('/* HandTop classic theme for GTK 3. Generated by tools/linux-theme/build.py from\n'
              ' * the Adwaita theme of GTK %s (LGPL-2.1-or-later) with HandTop\'s classic palette\n'
              ' * and drawing. Do not edit: change the sources and build again. */\n' % GTK3_VERSION)
    write(os.path.join(ASSETS_ROOT, variant, 'gtk-3.0', 'gtk.css'), named(header, variant) + css)


# ---- GTK 4 and libadwaita ----

def without_media(text, queries=('(prefers-color-scheme: dark)', '(prefers-contrast: more)')):
    """[text] without its @media blocks for [queries]: the classic look is always light, and
    libadwaita 1.8+ carries its dark and high-contrast colours in such blocks."""
    for query in queries:
        while True:
            start = text.find('@media ' + query)
            if start < 0:
                break
            depth = 0
            i = text.index('{', start)
            while True:
                if text[i] == '{':
                    depth += 1
                elif text[i] == '}':
                    depth -= 1
                    if depth == 0:
                        break
                i += 1
            text = text[:start] + text[i + 1:]
    return text


def classic_libadwaita(work):
    """libadwaita's stylesheet with square corners, no transitions, light only."""
    common = os.path.join(work, '_common.scss')
    with open(common, encoding='utf-8') as f:
        text = f.read()
    for old, new in [
        (r'\$button_radius: [0-9]+px;', '$button_radius: 0;'),
        (r'\$card_radius: [0-9]+px;', '$card_radius: 0;'),
        (r'\$menu_radius: [0-9]+px;', '$menu_radius: 0;'),
        (r'\$popover_radius: [^;]+;', '$popover_radius: 0;'),
        (r'\$dialog_radius: [^;]+;', '$dialog_radius: 0;'),
        (r'--window-radius: [^;]+;', '--window-radius: 0;'),
        (r'(?s)\$backdrop_transition:[^;]*;', '$backdrop_transition: none;'),
        (r'(?s)\$focus_transition:[^;]*;', '$focus_transition: none;'),
        (r'(?s)\$button_transition:[^;]*;', '$button_transition: none;'),
    ]:
        text, count = re.subn(old, lambda m, new=new: new, text)
        if count != 1:
            sys.exit('upstream changed: %s (%d matches)' % (old, count))
    with open(common, 'w', encoding='utf-8') as f:
        f.write(text)
    for folder, _, names in os.walk(work):
        for name in names:
            if name.endswith('.scss'):
                path = os.path.join(folder, name)
                with open(path, encoding='utf-8') as f:
                    text = without_media(no_transitions(f.read()))
                with open(path, 'w', encoding='utf-8') as f:
                    f.write(text)


def build_libadwaita(variant, version, digest, output):
    series = '.'.join(version.split('.')[:2])
    tarball = download('https://download.gnome.org/sources/libadwaita/%s/libadwaita-%s.tar.xz' % (series, version),
                       os.path.join(CACHE, 'libadwaita-%s.tar.xz' % version), digest)
    out_dir = os.path.join(ASSETS_ROOT, variant, 'gtk-4.0')
    with tempfile.TemporaryDirectory() as work:
        prefix = 'libadwaita-%s/src/stylesheet/' % version
        with tarfile.open(tarball) as tar:
            for member in tar.getmembers():
                if member.isfile() and member.name.startswith(prefix):
                    target = os.path.join(work, *member.name[len(prefix):].split('/'))
                    os.makedirs(os.path.dirname(target), exist_ok=True)
                    with tar.extractfile(member) as src, open(target, 'wb') as dst:
                        dst.write(src.read())
        classic_libadwaita(work)
        # HandTop's colours come after libadwaita's (1.8+ define their defaults in _colors).
        shutil.copy(os.path.join(HERE, 'gtk4', '_handtop-colors.scss'), work)
        shutil.copy(os.path.join(HERE, 'gtk4', '_classic.scss'), work)
        copy_palette(variant, work)
        main = os.path.join(work, 'handtop.scss')
        with open(main, 'w', encoding='utf-8') as f:
            f.write('\n'.join([
                "$variant: 'light';", "$contrast: 'normal';",
                "@import 'palette';", "@import 'colors';", "@import 'handtop-colors';", "@import 'drawing';",
                "@import 'common';", "@import 'compat-colors';", "@import 'classic';", '']))
        css = sass.compile(filename=main, output_style='expanded')
        # The check marks and bullets, next to the stylesheet: plain GTK 4 programs have no
        # libadwaita resources. Both builds share the folder (1.9 uses SVGs, 1.7 PNGs).
        shutil.copytree(os.path.join(work, 'assets'), os.path.join(out_dir, 'assets'), dirs_exist_ok=True)
    css = square_corners(css)
    header = ('/* HandTop classic theme for GTK 4 and libadwaita. Generated by tools/linux-theme/build.py\n'
              ' * from the stylesheet of libadwaita %s (LGPL-2.1-or-later) with HandTop\'s classic\n'
              ' * palette and bevels. Do not edit: change the sources and build again. */\n' % version)
    write(os.path.join(out_dir, output), named(header, variant) + css)


# ---- palettes ----

def copy_palette(variant, work):
    """The variant's colours, as _handtop-palette.scss: the sources import them by that name."""
    shutil.copy(os.path.join(HERE, 'palettes', variant + '.scss'), os.path.join(work, '_handtop-palette.scss'))


def named(header, variant):
    """The generated file's header, naming the palette unless it is the classic one."""
    if variant == 'classic':
        return header
    return header.replace('HandTop classic theme', 'HandTop classic theme in the %s colours' % variant, 1)


if __name__ == '__main__':
    for variant in VARIANTS:
        build_gtk3(variant)
        shutil.rmtree(os.path.join(ASSETS_ROOT, variant, 'gtk-4.0', 'assets'), ignore_errors=True)
        for version, digest, output in LIBADWAITA:
            build_libadwaita(variant, version, digest, output)
