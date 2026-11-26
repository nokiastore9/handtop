#!/usr/bin/env python3
"""
HandTop's classic icon set: one vector drawable per HandTop icon (res/drawable/ic_*.xml),
written as res/drawable/classic_ic_*.xml. The classic theme shows these instead of the
white monochrome icons of the modern theme.

The style: flat colour areas, a dark outline, a light highlight on the top-left where it
helps, in a 24-unit square; the title-bar glyphs are black on transparent in 16 units.
All drawings are HandTop's own.

Run from the repository root:  python tools/icons/make_classic_icons.py
"""
import os

OUT = os.path.join('app', 'src', 'main', 'res', 'drawable')

DARK = '#FF202020'
GRAY = '#FFA0A0A0'
FACE = '#FFC0C0C0'
WHITE = '#FFFFFFFF'
NAVY = '#FF000080'
BLUE = '#FF3A6BE8'
BLUE_DARK = '#FF10307A'
SKY = '#FF1084D0'
YELLOW = '#FFE8C33A'
YELLOW_LIGHT = '#FFF7DC6F'
YELLOW_LINE = '#FF6B5410'
GREEN = '#FF2E9D3E'
GREEN_DARK = '#FF145A1C'
RED = '#FFC0392B'
RED_DARK = '#FF6E1A12'


def p(d, fill=None, stroke=None, width=0.8, cap=None):
    return {'d': d, 'fill': fill, 'stroke': stroke, 'width': width, 'cap': cap}


def rect(x, y, w, h):
    return f'M{x},{y}h{w}v{h}h{-w}z'


def circle(cx, cy, r):
    return f'M{cx - r},{cy}a{r},{r} 0 1,0 {2 * r},0a{r},{r} 0 1,0 {-2 * r},0z'


def group(rotation, *paths):
    return {'group': rotation, 'paths': list(paths)}


# ---- pieces shared by several icons ----

def page():
    return [p('M5,2h10l4,4v16H5z', WHITE, DARK), p('M15,2v4h4', '#FFD8D8D8', DARK)]


def folder():
    return [
        p('M2,6h7l2,2h11v12H2z', YELLOW, YELLOW_LINE),
        p('M2,10h20v10H2z', YELLOW_LIGHT, YELLOW_LINE),
        p('M3,11h18', None, '#FFFFF3B0', 0.8),
    ]


def terminal(title_colour, prompt_colour):
    return [
        p(rect(1, 3, 22, 18), GRAY, DARK),
        p(rect(2, 4, 20, 3), title_colour),
        p(rect(2.5, 8, 19, 12), '#FF000000'),
        p('M5,11l3,2l-3,2', None, prompt_colour, 1.4),
        p(rect(10, 16, 5, 1.4), prompt_colour),
    ]


def keyboard():
    keys = []
    for row, y in enumerate((8, 11)):
        for i in range(6):
            keys.append(p(rect(3.2 + i * 3, y, 2, 2), '#FF505050'))
    keys.append(p(rect(6.2, 14, 11.6, 2), '#FF505050'))
    return [p(rect(1, 6, 22, 12), '#FFE0E0E0', DARK)] + keys


ICONS = {
    'app_generic': [
        p(rect(2, 4, 20, 16), FACE, DARK),
        p(rect(3, 5, 18, 3), NAVY),
        p(rect(4, 10, 16, 8), WHITE, '#FF808080', 0.6),
    ],
    'apps': [p(rect(3 + c * 6.5, 3 + r * 6.5, 5, 5), BLUE, BLUE_DARK, 0.7) for r in range(3) for c in range(3)],
    'arrow_back': [p('M11,5L4,12l7,7v-4.5h9v-5h-9z', DARK)],
    'arrow_forward': [p('M13,5l7,7l-7,7v-4.5H4v-5h9z', DARK)],
    'arrow_up': [p('M5,11l7,-7l7,7h-4.5v9h-5v-9z', DARK)],
    'bell': [
        p('M6,17V11a6,6 0 0,1 12,0v6l2,2H4z', YELLOW, YELLOW_LINE),
        p(circle(12, 20.6, 1.6), YELLOW_LINE),
        p('M8.6,11a3.4,3.4 0 0,1 2.4,-3.2', None, '#FFFFF3B0', 0.9),
    ],
    'camera': [
        p('M3,7h4l1.5,-2h7l1.5,2h4v12H3z', '#FF505050', DARK),
        p(circle(12, 13, 4), BLUE, FACE, 1.0),
        p(rect(17, 8.5, 2.5, 1.5), '#FFFFE680'),
    ],
    'chip': [
        p(rect(5, 5, 14, 14), '#FF2E7D32', '#FF103E12'),
        p(rect(8.5, 8.5, 7, 7), '#FF111111'),
    ] + [p(rect(7 + i * 4.2, 2, 1.6, 3), '#FFC9A227') for i in range(3)]
      + [p(rect(7 + i * 4.2, 19, 1.6, 3), '#FFC9A227') for i in range(3)]
      + [p(rect(2, 7 + i * 4.2, 3, 1.6), '#FFC9A227') for i in range(3)]
      + [p(rect(19, 7 + i * 4.2, 3, 1.6), '#FFC9A227') for i in range(3)],
    'display': [
        p(rect(2, 3, 20, 14), GRAY, DARK),
        p(rect(3.5, 4.5, 17, 11), SKY),
        p(rect(9, 17, 6, 2), '#FF808080'),
        p(rect(6, 19, 12, 2), '#FF808080', DARK),
    ],
    'download': [
        p(rect(3, 17, 18, 4), GRAY, DARK),
        p('M9,3h6v7h4l-7,7l-7,-7h4z', GREEN, GREEN_DARK),
    ],
    'extra_keys': keyboard(),
    'keyboard': keyboard(),
    'file': page(),
    'file_text': page() + [p('M8,10h8M8,13h8M8,16h6', None, BLUE, 1.0)],
    'file_image': page() + [
        p(rect(7, 9, 10, 8), '#FF9AD0FF', DARK, 0.5),
        p('M7,17l3,-4l2,2l2,-3l3,5z', GREEN),
        p(circle(10, 11, 1.3), '#FFFFD700'),
    ],
    'file_video': page() + [
        p(rect(7, 9, 10, 8), '#FF303030'),
        p('M10.5,11v4l3.5,-2z', WHITE),
    ] + [p(rect(7.6 + i * 2.4, 9.4, 1, 0.9), WHITE) for i in range(4)]
      + [p(rect(7.6 + i * 2.4, 15.7, 1, 0.9), WHITE) for i in range(4)],
    'file_audio': page() + [
        p(circle(10, 16, 1.6), '#FF3A3AB0'),
        p(circle(14.6, 14.6, 1.4), '#FF3A3AB0'),
        p('M11.5,16V9.5l4.4,-1.2v6.3', None, '#FF3A3AB0', 1.2),
    ],
    'file_archive': page() + [
        p(rect(9, 12, 6, 5), '#FF8B5A2B', '#FF402810', 0.6),
    ] + [p(rect(11.4, 3 + i * 2, 1.2, 1), '#FF606060') for i in range(4)],
    'file_apk': page() + [
        p('M8,11l4,-2l4,2v5l-4,2l-4,-2z', '#FF7FBF3F', '#FF2E5A10', 0.7),
        p('M8,11l4,2l4,-2M12,13v5', None, '#FF2E5A10', 0.7),
    ],
    'files': [
        p('M2,6h7l2,2h11v12H2z', YELLOW, YELLOW_LINE),
        p('M7,4h9l3,3v9H7z', WHITE, DARK, 0.7),
        p('M9,8h7M9,10.5h7', None, BLUE, 0.8),
        p('M2,12h20v8H2z', YELLOW_LIGHT, YELLOW_LINE),
    ],
    'folder': folder(),
    'home': [
        p(rect(5, 11, 14, 10), '#FFF2E3B3', '#FF5A4010'),
        p('M2,12L12,3l10,9z', RED, RED_DARK),
        p(rect(10, 15, 4, 6), '#FF8B5A2B'),
    ],
    'info': [
        p(circle(12, 12, 10), BLUE, BLUE_DARK),
        p(rect(10.8, 10, 2.4, 8), WHITE),
        p(circle(12, 7, 1.5), WHITE),
    ],
    'linux': terminal('#FFC8335A', '#FF7CFC7C'),
    'terminal': terminal('#FF3C8C3C', WHITE),
    'more': [p(circle(12, y, 2), DARK) for y in (5, 12, 19)],
    'search': [
        p('M14.2,14.2L20.5,20.5', None, DARK, 3.2, 'round'),
        p(circle(10, 10, 6.2), '#FFCFE8FF', DARK, 1.4),
        p('M6.8,9.2a3.4,3.4 0 0,1 2.4,-2.4', None, WHITE, 1.0, 'round'),
    ],
    'phone': [
        p('M7.5,2h9a1.5,1.5 0 0,1 1.5,1.5v17a1.5,1.5 0 0,1 -1.5,1.5h-9a1.5,1.5 0 0,1 -1.5,-1.5v-17A1.5,1.5 0 0,1 7.5,2z', '#FF303030'),
        p(rect(7.5, 4.5, 9, 14), SKY),
        p(circle(12, 20.3, 0.8), '#FF808080'),
    ],
    'power': [
        p(rect(2, 2, 20, 20), RED, RED_DARK),
        p('M8,8a6,6 0 1,0 8,0', None, WHITE, 2.0),
        p(rect(11, 5, 2, 7), WHITE),
    ],
    'sd_card': [
        p('M8,2h11v20H5V5z', BLUE, BLUE_DARK),
        p(rect(7, 12, 10, 7), WHITE),
    ] + [p(rect(9 + i * 2.5, 3, 1.2, 3), YELLOW) for i in range(4)],
    'settings': [
        group(0, p(rect(10.5, 1.5, 3, 21), '#FF7A7A7A')),
        group(45, p(rect(10.5, 1.5, 3, 21), '#FF7A7A7A')),
        group(90, p(rect(10.5, 1.5, 3, 21), '#FF7A7A7A')),
        group(135, p(rect(10.5, 1.5, 3, 21), '#FF7A7A7A')),
        p(circle(12, 12, 7.5), '#FFA8A8A8', '#FF555555'),
        p(circle(12, 12, 3), '#FFE0E0E0', '#FF555555'),
    ],
}

# Title-bar glyphs: black, on the raised classic buttons, in a 16-unit square.
GLYPHS = {
    'win_minimize': [p(rect(4, 10, 6, 2), '#FF000000')],
    'win_maximize': [p('M3,3h10v9H3z M4,5v6h8V5z', '#FF000000')],
    'win_restore': [
        p('M5,2h8v7h-2V4H5z', '#FF000000'),
        p('M3,6h8v7H3z M4,8v4h6V8z', '#FF000000'),
    ],
    'win_close': [p('M4,4L11,11M11,4L4,11', None, '#FF000000', 2.0, 'square')],
}


def path_xml(path, indent='    '):
    attrs = [f'android:pathData="{path["d"]}"']
    if path['fill']:
        attrs.append(f'android:fillColor="{path["fill"]}"')
    if path['stroke']:
        attrs.append(f'android:strokeColor="{path["stroke"]}"')
        attrs.append(f'android:strokeWidth="{path["width"]}"')
        attrs.append('android:strokeLineJoin="round"')
        if path['cap']:
            attrs.append(f'android:strokeLineCap="{path["cap"]}"')
    if 'evenOdd' in path or ' M' in path['d']:
        attrs.append('android:fillType="evenOdd"')
    return f'{indent}<path\n{indent}    ' + f'\n{indent}    '.join(attrs) + ' />'


def write(name, items, size, viewport):
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        '<!-- HandTop classic theme icon, generated by tools/icons/make_classic_icons.py. Do not edit. -->',
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        f'    android:width="{size}dp" android:height="{size}dp"',
        f'    android:viewportWidth="{viewport}" android:viewportHeight="{viewport}">',
    ]
    for item in items:
        if 'group' in item:
            c = viewport / 2
            lines.append(f'    <group android:rotation="{item["group"]}" android:pivotX="{c}" android:pivotY="{c}">')
            for path in item['paths']:
                lines.append(path_xml(path, '        '))
            lines.append('    </group>')
        else:
            lines.append(path_xml(item))
    lines.append('</vector>')
    with open(os.path.join(OUT, f'classic_ic_{name}.xml'), 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(lines) + '\n')


def base_size(name):
    """The modern icon's size in dp, so a classic one takes the same room."""
    with open(os.path.join(OUT, f'ic_{name}.xml'), encoding='utf-8') as f:
        text = f.read()
    start = text.index('android:width="') + len('android:width="')
    return text[start:text.index('dp"', start)]


if __name__ == '__main__':
    for name, items in ICONS.items():
        write(name, items, base_size(name), 24)
    for name, items in GLYPHS.items():
        write(name, items, base_size(name), 16)
    print(f'{len(ICONS) + len(GLYPHS)} classic icons written to {OUT}')
