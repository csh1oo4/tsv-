#!/usr/bin/env python3
"""Generate legacy launcher PNGs for TSV secret 근무표.

Design: blue gradient squircle with a white calendar card, navy header band and
shift cells (blue = 업무, green = 휴식). Rendered at 4x and downsampled for smooth
edges. Writes ic_launcher.png / ic_launcher_round.png for every mipmap density.
"""

from PIL import Image, ImageDraw

SIZES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}
BASE = "/workspace/tsv-secret-schedule-android/app/src/main/res"
SS = 8  # supersampling factor


def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def gradient(size, c0=(29, 78, 216), c1=(59, 130, 246)):
    img = Image.new("RGB", (size, size))
    px = img.load()
    for y in range(size):
        for x in range(size):
            t = (x + y) / (2.0 * (size - 1))
            px[x, y] = lerp(c0, c1, t)
    return img


def squircle_mask(size, radius_ratio=0.22):
    m = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(m)
    r = int(size * radius_ratio)
    d.rounded_rectangle([0, 0, size - 1, size - 1], radius=r, fill=255)
    return m


def circle_mask(size):
    m = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(m)
    d.ellipse([0, 0, size - 1, size - 1], fill=255)
    return m


def render(size, round_icon):
    S = size * SS
    unit = S / 108.0

    bg = gradient(S)
    img = bg.convert("RGBA")

    mask = circle_mask(S) if round_icon else squircle_mask(S)
    layer = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    layer.paste(img, (0, 0), mask)
    img = layer
    d = ImageDraw.Draw(img)

    def box(x0, y0, x1, y1):
        return [x0 * unit, y0 * unit, x1 * unit, y1 * unit]

    # Card
    d.rounded_rectangle(box(28, 30, 80, 80), radius=8 * unit, fill=(255, 255, 255, 255))
    # Header band
    d.rounded_rectangle(box(28, 30, 80, 46), radius=8 * unit, fill=(30, 58, 138, 255))
    d.rectangle(box(28, 40, 80, 46), fill=(30, 58, 138, 255))

    cells = [
        (34.0, 52.0, (191, 219, 254, 255)),
        (48.5, 52.0, (191, 219, 254, 255)),
        (63.0, 52.0, (52, 211, 153, 255)),
        (34.0, 65.0, (191, 219, 254, 255)),
        (48.5, 65.0, (52, 211, 153, 255)),
        (63.0, 65.0, (191, 219, 254, 255)),
    ]
    for x, y, color in cells:
        d.rounded_rectangle(box(x, y, x + 11, y + 8), radius=1.5 * unit, fill=color)

    return img.resize((size, size), Image.LANCZOS)


def main():
    for density, size in SIZES.items():
        folder = f"{BASE}/mipmap-{density}"
        import os

        os.makedirs(folder, exist_ok=True)
        render(size, False).save(f"{folder}/ic_launcher.png", "PNG")
        render(size, True).save(f"{folder}/ic_launcher_round.png", "PNG")
        print(f"wrote {folder}/ic_launcher.png ({size}x{size})")

    # Play-store style preview for humans
    render(512, False).save("/workspace/tsv-secret-schedule-android/icon-preview.png", "PNG")


if __name__ == "__main__":
    main()
