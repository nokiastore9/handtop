#!/usr/bin/env python3
"""HandTop's built-in wallpapers, as vector layers.

Writes app/src/main/assets/wallpapers.json: for each wallpaper, layers of SVG path data in a
1600 x 1000 design space, each filled with a colour or a linear / radial gradient. The app
draws them with Android's Canvas (Wallpapers.kt), scaled to cover the screen and cropped to
its middle, so every screen shape (phone, DeX, tablet, portrait) gets the same picture. The
curves and facets are generated here, from fixed seeds: run it again after a change and
look at preview.html.

    python tools/wallpapers/make_wallpapers.py
"""
import json
import math
import os
import random

W, H = 1600, 1000
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "..", "app", "src", "main", "assets", "wallpapers.json")


# ---- building blocks ----

def n(v):
    return f"{v:.1f}".rstrip("0").rstrip(".")


def rect(x=0, y=0, w=W, h=H):
    return f"M{n(x)} {n(y)} H{n(x + w)} V{n(y + h)} H{n(x)} Z"


def circle(cx, cy, r):
    # Two arcs: SVG and Android's PathParser both understand A.
    return (f"M{n(cx - r)} {n(cy)} A{n(r)} {n(r)} 0 1 0 {n(cx + r)} {n(cy)} "
            f"A{n(r)} {n(r)} 0 1 0 {n(cx - r)} {n(cy)} Z")


def smooth(points):
    """A smooth curve through points (Catmull-Rom as cubic Béziers), without the initial M."""
    out = []
    for i in range(len(points) - 1):
        p0 = points[max(i - 1, 0)]
        p1, p2 = points[i], points[i + 1]
        p3 = points[min(i + 2, len(points) - 1)]
        c1 = (p1[0] + (p2[0] - p0[0]) / 6, p1[1] + (p2[1] - p0[1]) / 6)
        c2 = (p2[0] - (p3[0] - p1[0]) / 6, p2[1] - (p3[1] - p1[1]) / 6)
        out.append(f"C{n(c1[0])} {n(c1[1])} {n(c2[0])} {n(c2[1])} {n(p2[0])} {n(p2[1])}")
    return " ".join(out)


def wave_points(base, amp, length, phase, wobble=0.0, steps=14, x0=-80, x1=W + 80):
    pts = []
    for i in range(steps + 1):
        x = x0 + (x1 - x0) * i / steps
        y = base + amp * math.sin(2 * math.pi * x / length + phase) + wobble * math.sin(2 * math.pi * x / (length * 0.37) + phase * 1.7)
        pts.append((x, y))
    return pts


def area_below(points, bottom=H + 40):
    first = points[0]
    return f"M{n(first[0])} {n(bottom)} L{n(first[0])} {n(first[1])} {smooth(points)} L{n(points[-1][0])} {n(bottom)} Z"


def band(top, bottom):
    """The area between two curves running left to right."""
    back = list(reversed(bottom))
    return (f"M{n(top[0][0])} {n(top[0][1])} {smooth(top)} L{n(back[0][0])} {n(back[0][1])} "
            f"{smooth(back)} Z")


def solid(color, opacity=1.0):
    return {"type": "solid", "color": color, "opacity": opacity}


def linear(x1, y1, x2, y2, *stops):
    return {"type": "linear", "x1": x1, "y1": y1, "x2": x2, "y2": y2, "stops": [list(s) for s in stops]}


def radial(cx, cy, r, *stops):
    return {"type": "radial", "cx": cx, "cy": cy, "r": r, "stops": [list(s) for s in stops]}


def layer(d, fill, opacity=1.0, even_odd=False):
    out = {"d": d, "fill": fill}
    if opacity != 1.0:
        out["opacity"] = round(opacity, 3)
    if even_odd:
        out["evenOdd"] = True
    return out


def glow(cx, cy, r, color, alpha):
    return layer(circle(cx, cy, r), radial(cx, cy, r, (0, color, alpha), (1, color, 0)))


def mix(a, b, t):
    a = [int(a[i:i + 2], 16) for i in (1, 3, 5)]
    b = [int(b[i:i + 2], 16) for i in (1, 3, 5)]
    return "#" + "".join(f"{round(x + (y - x) * t):02X}" for x, y in zip(a, b))


def shade(c, k):
    v = [int(c[i:i + 2], 16) for i in (1, 3, 5)]
    return "#" + "".join(f"{max(0, min(255, round(x * k))):02X}" for x in v)


# ---- the wallpapers ----

def twilight():
    """HandTop's first wallpaper, kept as the default, with two soft lights for depth."""
    return [
        layer(rect(), linear(0, 0, W, H, (0, "#0B1023", 1), (0.5, "#1B2A6B", 1), (1, "#3B1D5E", 1))),
        glow(1260, 230, 720, "#4F8CFF", 0.16),
        glow(260, 920, 620, "#8A4FE0", 0.12),
    ]


def waves():
    layers = [layer(rect(), linear(0, 0, 0, H, (0, "#06101F", 1), (1, "#0B2A57", 1)))]
    layers.append(glow(1150, 300, 650, "#2E7BFF", 0.14))
    specs = [  # base, amplitude, wavelength, phase, colours left -> right, opacity
        (470, 46, 1500, 0.6, "#1D3F8F", "#1C6FA3", 0.45),
        (560, 40, 1250, 2.1, "#1F4FB0", "#1B8CB0", 0.55),
        (650, 52, 1700, 4.0, "#2459C8", "#19A2B8", 0.65),
        (745, 38, 1150, 1.2, "#2A63D6", "#22B8C2", 0.78),
        (845, 44, 1450, 3.3, "#3170E0", "#36CBC6", 0.9),
    ]
    for base, amp, length, phase, c1, c2, op in specs:
        pts = wave_points(base, amp, length, phase, wobble=amp * 0.25)
        layers.append(layer(area_below(pts), linear(0, 0, W, 0, (0, c1, 1), (1, c2, 1)), op))
    return layers


def dunes():
    layers = [
        layer(rect(), linear(0, 0, 0, H, (0, "#160B2B", 1), (0.42, "#4A1F52", 1), (0.7, "#C2585A", 1), (1, "#F2A065", 1))),
        glow(1060, 560, 380, "#FFC98A", 0.55),
        layer(circle(1060, 560, 92), solid("#FFD7A0", 0.95)),
    ]
    specs = [  # base, amplitude, wavelength, phase, top colour, bottom colour
        (600, 60, 1900, 0.4, "#8A3A63", "#5E2550"),
        (680, 70, 1500, 2.2, "#6A2A57", "#431B44"),
        (770, 55, 1300, 4.4, "#4A1D46", "#2E1234"),
        (870, 65, 1700, 1.4, "#2E1233", "#1A0A20"),
    ]
    for base, amp, length, phase, top, bottom in specs:
        pts = wave_points(base, amp, length, phase, wobble=amp * 0.35, steps=10)
        layers.append(layer(area_below(pts), linear(0, base - amp, 0, H, (0, top, 1), (1, bottom, 1))))
    return layers


def aurora():
    rnd = random.Random(7)
    layers = [layer(rect(), linear(0, 0, 0, H, (0, "#030814", 1), (1, "#0A1A33", 1)))]
    stars = " ".join(circle(rnd.uniform(0, W), rnd.uniform(0, H * 0.75), rnd.uniform(0.9, 2.2)) for _ in range(70))
    layers.append(layer(stars, solid("#FFFFFF", 0.7)))
    curtains = [  # top base, top amp, height, phase, colour, opacity
        (200, 90, 300, 0.3, "#2BE3A0", 0.32),
        (280, 70, 240, 2.4, "#29C7D8", 0.24),
        (170, 60, 200, 4.2, "#8B5CF6", 0.2),
    ]
    for base, amp, height, phase, color, op in curtains:
        top = wave_points(base, amp, 1500, phase, wobble=amp * 0.3, steps=12)
        bottom = [(x, y + height + 40 * math.sin(x / 210 + phase)) for x, y in top]
        layers.append(layer(band(top, bottom), linear(0, base - amp, 0, base + height + amp,
                                                       (0, color, 0), (0.6, color, 1), (1, color, 0)), op))

        # The curtain's folds: vertical rays, brightest at their lower edge, as auroras are.
        def edge(x):
            return base + height * 0.85 + amp * math.sin(2 * math.pi * x / 1500 + phase) + 40 * math.sin(x / 210 + phase)
        x = -40.0
        while x < W + 40:
            w = rnd.uniform(14, 40)
            length = rnd.uniform(height * 0.6, height * 1.3)
            y1 = edge(x) + rnd.uniform(-20, 20)
            alpha = rnd.uniform(0.08, 0.26)
            layers.append(layer(rect(x, y1 - length, w, length + 6),
                                linear(0, y1 - length, 0, y1, (0, color, 0), (0.8, color, alpha), (1, color, 0)), 0.8))
            x += rnd.uniform(22, 60)
    layers.append(glow(400, 820, 520, "#1B6F7A", 0.22))
    return layers


def facets():
    rnd = random.Random(11)
    cols, rows = 10, 7
    pts = [[(x * W / cols + (rnd.uniform(-0.32, 0.32) * W / cols if 0 < x < cols else 0),
             y * H / rows + (rnd.uniform(-0.32, 0.32) * H / rows if 0 < y < rows else 0))
            for x in range(cols + 1)] for y in range(rows + 1)]
    layers = [layer(rect(), linear(0, 0, W, H, (0, "#14205A", 1), (1, "#3A1D63", 1)))]

    def colour(cx, cy):
        t = (cx / W) * 0.6 + (cy / H) * 0.4
        base = mix("#1A2C78", "#3A6BE8", t * 1.4) if t < 0.55 else mix("#3A6BE8", "#6B3FC9", (t - 0.55) / 0.45)
        light = 1.0 - 0.35 * (cy / H) + rnd.uniform(-0.09, 0.09)
        return shade(base, light)

    for y in range(rows):
        for x in range(cols):
            a, b = pts[y][x], pts[y][x + 1]
            c, d = pts[y + 1][x], pts[y + 1][x + 1]
            tris = [(a, b, d), (a, d, c)] if (x + y) % 2 == 0 else [(a, b, c), (b, d, c)]
            for t in tris:
                cx = sum(p[0] for p in t) / 3
                cy = sum(p[1] for p in t) / 3
                path = f"M{n(t[0][0])} {n(t[0][1])} L{n(t[1][0])} {n(t[1][1])} L{n(t[2][0])} {n(t[2][1])} Z"
                layers.append(layer(path, solid(colour(cx, cy))))
    return layers


def orbits():
    cx, cy = 1120, 430
    layers = [
        layer(rect(), radial(cx, cy, 1300, (0, "#1E2A66", 1), (0.55, "#0E1433", 1), (1, "#060915", 1))),
        glow(cx, cy, 300, "#6F8BFF", 0.35),
        layer(circle(cx, cy, 70), radial(cx - 20, cy - 20, 90, (0, "#E8EEFF", 1), (1, "#7F9BFF", 1))),
    ]
    rings = [(170, 2.0, 0.55), (260, 1.6, 0.45), (380, 3.0, 0.32), (520, 1.6, 0.3), (690, 2.4, 0.22), (880, 1.6, 0.18), (1100, 2.2, 0.14)]
    for r, width, op in rings:
        layers.append(layer(circle(cx, cy, r + width) + " " + circle(cx, cy, r - width), solid("#A9BCFF", op), even_odd=True))
    planets = [(260, 3.6, 14, "#7FE0FF"), (520, 2.3, 20, "#C49BFF"), (690, 5.3, 11, "#FFB38A"), (880, 0.9, 16, "#8AA8FF")]
    for r, angle, size, color in planets:
        px, py = cx + r * math.cos(angle), cy + r * math.sin(angle)
        layers.append(glow(px, py, size * 4, color, 0.35))
        layers.append(layer(circle(px, py, size), solid(color)))
    return layers


def ribbons():
    layers = [layer(rect(), linear(0, 0, W, H, (0, "#0B1626", 1), (1, "#1A2240", 1)))]
    specs = [  # start y, end y, thickness, phase, colours along, opacity
        (980, 120, 200, 0.4, ("#0FA3B1", "#3A6BE8"), 0.55),
        (1080, 300, 150, 2.1, ("#3A6BE8", "#7A3FD1"), 0.6),
        (860, -60, 120, 4.0, ("#1FC8A8", "#2E7BFF"), 0.45),
    ]
    for y0, y1, thick, phase, (c1, c2), op in specs:
        top = []
        for i in range(13):
            x = -100 + (W + 200) * i / 12
            y = y0 + (y1 - y0) * i / 12 + 70 * math.sin(i / 12 * math.pi * 2 + phase)
            top.append((x, y))
        bottom = [(x, y + thick * (0.6 + 0.4 * math.sin(i / 12 * math.pi + phase))) for i, (x, y) in enumerate(top)]
        layers.append(layer(band(top, bottom), linear(0, y0, W, y1, (0, c1, 1), (1, c2, 1)), op))
        # The lit edge of the fold.
        edge = [(x, y + 6) for x, y in top]
        layers.append(layer(band(top, edge), solid("#FFFFFF", 0.10)))
    layers.append(glow(1300, 200, 600, "#3A6BE8", 0.12))
    return layers


def bokeh():
    rnd = random.Random(23)
    layers = [
        layer(rect(), linear(0, 0, W, H, (0, "#120A26", 1), (0.6, "#2A1245", 1), (1, "#3B1638", 1))),
        glow(1250, 780, 700, "#C2407A", 0.18),
        glow(300, 200, 600, "#4F5BFF", 0.16),
    ]
    palette = ["#FF7AB6", "#FFB86B", "#B48CFF", "#7AA8FF", "#FF9E8A"]
    for _ in range(26):
        r = rnd.choice([rnd.uniform(18, 45), rnd.uniform(45, 110), rnd.uniform(110, 170)])
        x, y = rnd.uniform(-50, W + 50), rnd.uniform(-50, H + 50)
        color = rnd.choice(palette)
        alpha = rnd.uniform(0.10, 0.32) * (1.25 if r < 60 else 1.0)
        layers.append(layer(circle(x, y, r), radial(x, y, r, (0, color, alpha), (0.75, color, alpha * 0.7), (1, color, 0))))
    return layers


def turquoise():
    """A plain colour, the classic desktop's; goes with the classic theme."""
    return [layer(rect(), solid("#008080"))]


WALLPAPERS = [
    ("twilight", twilight),
    ("waves", waves),
    ("dunes", dunes),
    ("aurora", aurora),
    ("facets", facets),
    ("orbits", orbits),
    ("ribbons", ribbons),
    ("bokeh", bokeh),
    ("turquoise", turquoise),
]


def main():
    data = {
        "width": W,
        "height": H,
        "wallpapers": [{"id": wid, "layers": make()} for wid, make in WALLPAPERS],
    }
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, separators=(",", ":"))
        f.write("\n")
    print(f"{OUT}: {os.path.getsize(OUT)} bytes, {sum(len(w['layers']) for w in data['wallpapers'])} layers")


if __name__ == "__main__":
    main()
