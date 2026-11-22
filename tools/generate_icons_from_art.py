#!/usr/bin/env python3
"""Build 「TSV 교대달력」 launcher icons from the supplied artwork (v1.3).

The source is a square image (white rounded-square backdrop, gold PI film roll
and a calendar). It is only resized / centred / masked here — the artwork itself
is never redrawn or retouched.

Outputs (app/src/main/res):
  mipmap-{mdpi..xxxhdpi}/ic_launcher.png             legacy squircle (48..192px)
  mipmap-{mdpi..xxxhdpi}/ic_launcher_round.png       legacy circle
  mipmap-{mdpi..xxxhdpi}/ic_launcher_foreground.png  adaptive 108dp layer (108..432px),
        artwork at 76% so nothing is clipped by any launcher mask; the white
        canvas blends into the #FFFFFF adaptive background
  icon-preview.png                                   preview sheet for the user

Usage: python3 tools/generate_icons_from_art.py SOURCE_IMAGE [out_res_dir]
"""

import os
import sys

from PIL import Image, ImageDraw

LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
ADAPTIVE = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

LEGACY_ART = 0.94      # artwork inside the legacy squircle/circle
ROUND_ART = 0.80       # smaller so the circle never bites into the calendar
ADAPTIVE_ART = 0.76    # artwork inside the 108dp adaptive canvas (safe zone)
SQUIRCLE_RADIUS = 0.21  # legacy squircle corner radius, same family as One UI
SS = 4                 # supersampling for masks

WHITE = (255, 255, 255)


def load_master(path):
    im = Image.open(path).convert("RGB")
    w, h = im.size
    side = min(w, h)
    left = (w - side) // 2
    top = (h - side) // 2
    return im.crop((left, top, left + side, top + side))


def resized(master, box_px):
    return master.resize((box_px, box_px), Image.LANCZOS)


def squircle_mask(size, radius_ratio=SQUIRCLE_RADIUS):
    m = Image.new("L", (size * SS, size * SS), 0)
    ImageDraw.Draw(m).rounded_rectangle(
        [0, 0, size * SS - 1, size * SS - 1],
        radius=int(size * SS * radius_ratio), fill=255)
    return m.resize((size, size), Image.LANCZOS)


def circle_mask(size):
    m = Image.new("L", (size * SS, size * SS), 0)
    ImageDraw.Draw(m).ellipse([0, 0, size * SS - 1, size * SS - 1], fill=255)
    return m.resize((size, size), Image.LANCZOS)


def canvas(size):
    return Image.new("RGB", (size, size), WHITE)


def centred(master, canvas_size, ratio):
    """Artwork scaled to `ratio` of the canvas and centred on a white square."""
    art = resized(master, max(1, int(round(canvas_size * ratio))))
    out = canvas(canvas_size)
    out.paste(art, ((canvas_size - art.width) // 2, (canvas_size - art.height) // 2))
    return out


def legacy(master, size, round_icon):
    art = centred(master, size, ROUND_ART if round_icon else LEGACY_ART)
    out = art.convert("RGBA")
    out.putalpha(circle_mask(size) if round_icon else squircle_mask(size))
    return out


def adaptive_foreground(master, size):
    return centred(master, size, ADAPTIVE_ART).convert("RGBA")


def preview(master, out_path):
    p = 320
    bg = (236, 240, 246)
    sheet = Image.new("RGB", (p * 4 + 50, p + 130), bg)

    def tile(icon, x, y, size):
        t = Image.new("RGB", (size, size), bg)
        t.paste(icon, (0, 0), icon)
        sheet.paste(t, (x, y))

    tile(legacy(master, p, False), 10, 10, p)
    tile(legacy(master, p, True), p + 23, 10, p)
    for i, mask in enumerate((circle_mask, squircle_mask)):
        art = canvas(p)
        art.paste(adaptive_foreground(master, p), (0, 0), adaptive_foreground(master, p))
        art = art.convert("RGBA")
        art.putalpha(mask(p))
        tile(art, p * (2 + i) + 36 + i * 13, 10, p)
    # actual launcher sizes, for legibility
    for i, size in enumerate((48, 72, 96)):
        tile(legacy(master, size, False), 16 + i * (size + 18), p + 35, size)
        tile(legacy(master, size, True), 200 + i * (size + 18), p + 35, size)
    sheet.save(out_path)
    return out_path


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    src = sys.argv[1]
    base = sys.argv[2] if len(sys.argv) > 2 else "app/src/main/res"
    master = load_master(src)

    for dens, size in LEGACY.items():
        d = os.path.join(base, "mipmap-" + dens)
        os.makedirs(d, exist_ok=True)
        legacy(master, size, False).save(os.path.join(d, "ic_launcher.png"))
        legacy(master, size, True).save(os.path.join(d, "ic_launcher_round.png"))
        print("legacy", d, size)

    for dens, size in ADAPTIVE.items():
        d = os.path.join(base, "mipmap-" + dens)
        os.makedirs(d, exist_ok=True)
        adaptive_foreground(master, size).save(os.path.join(d, "ic_launcher_foreground.png"))
        print("adaptive", d, size)

    root = os.path.abspath(os.path.join(base, "..", "..", "..", ".."))
    print("preview", preview(master, os.path.join(root, "icon-preview.png")))


if __name__ == "__main__":
    main()
