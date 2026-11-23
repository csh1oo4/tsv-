#!/usr/bin/env python3
"""Build 「TSV 교대달력」 launcher icons from the supplied artwork (v2.4.1).

The source is the icon artwork the user sent (2026-11-23): a white rounded-square
backdrop with a light-blue disc, a gold PI film roll, a calendar and two yellow
exclamation marks. The black behind the rounded corners is the backdrop the image
was exported on, not part of the design, so it is flood-filled with white
(#FFFFFF) — the same white the artwork's own backdrop uses — before anything is
scaled. The artwork itself is never redrawn or retouched: only the mask removal,
LANCZOS resizing, centring and squircle/circle masking happen here.

Outputs (app/src/main/res):
  mipmap-{mdpi..xxxhdpi}/ic_launcher.png             legacy squircle (48..192px)
  mipmap-{mdpi..xxxhdpi}/ic_launcher_round.png       legacy circle
  mipmap-{mdpi..xxxhdpi}/ic_launcher_foreground.png  adaptive 108dp layer (108..432px),
        artwork at 75% of the canvas so the film roll, the calendar and the
        exclamation marks all stay inside the 72dp safe zone; the white backdrop
        blends into the #FFFFFF adaptive background
  icon-preview.png                                   preview sheet

Usage: python3 tools/generate_icons_from_art.py SOURCE_IMAGE [out_res_dir] [--preview PATH]
"""

import os
import sys
from collections import deque

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
ADAPTIVE = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

LEGACY_ART = 0.94      # artwork inside the legacy squircle
ROUND_ART = 0.88       # smaller so the circle never bites into the calendar
ADAPTIVE_ART = 0.75    # artwork inside the 108dp adaptive canvas (safe zone)
KOREAN_FONT = "/usr/share/fonts/pickle-hwp/NotoSansCJKkr-Regular.otf"
SQUIRCLE_RADIUS = 0.21  # legacy squircle corner radius, same family as One UI
SS = 4                 # supersampling for masks

WHITE = (255, 255, 255)
CROP = 6               # trims the anti-aliased line along the artwork's own frame edge
DARK = 110             # "black backdrop" threshold
DILATE = 9             # grows the removed backdrop a few px past its anti-aliased edge


def clean_artwork(path):
    """Flood-fill the black outside the rounded square with white, then trim the edge line."""
    a = np.asarray(Image.open(path).convert("RGB")).astype(np.uint8)
    r, g, b = a[:, :, 0].astype(int), a[:, :, 1].astype(int), a[:, :, 2].astype(int)
    near_black = (r < DARK) & (g < DARK) & (b < DARK)
    h, w = near_black.shape

    seen = np.zeros((h, w), bool)
    q = deque()
    starts = [(x, 0) for x in range(w)] + [(x, h - 1) for x in range(w)]
    starts += [(0, y) for y in range(h)] + [(w - 1, y) for y in range(h)]
    for x, y in starts:
        if near_black[y, x] and not seen[y, x]:
            seen[y, x] = True
            q.append((y, x))
    while q:
        y, x = q.popleft()
        for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            ny, nx = y + dy, x + dx
            if 0 <= ny < h and 0 <= nx < w and near_black[ny, nx] and not seen[ny, nx]:
                seen[ny, nx] = True
                q.append((ny, nx))

    mask = Image.fromarray((seen * 255).astype(np.uint8)).filter(ImageFilter.MaxFilter(DILATE))
    a[np.asarray(mask) > 127] = WHITE
    if CROP:
        a = a[CROP:-CROP, CROP:-CROP]
    return Image.fromarray(a)


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


def on_white(master, size, ratio):
    """Artwork scaled to `ratio` of the canvas and centred on a white square."""
    side = max(1, int(round(size * ratio)))
    art = master.resize((side, side), Image.LANCZOS)
    out = Image.new("RGB", (size, size), WHITE)
    out.paste(art, ((size - side) // 2, (size - side) // 2))
    return out


def legacy(master, size, round_icon):
    art = on_white(master, size, ROUND_ART if round_icon else LEGACY_ART).convert("RGBA")
    art.putalpha(circle_mask(size) if round_icon else squircle_mask(size))
    return art


def adaptive_foreground(master, size):
    return on_white(master, size, ADAPTIVE_ART).convert("RGBA")


def preview(master, out_path, source=None):
    p = 320
    bg = (236, 240, 246)
    pad = 12
    cols = 5
    sheet = Image.new("RGB", (cols * p + (cols + 1) * pad, p + 170), bg)
    d = ImageDraw.Draw(sheet)
    try:
        from PIL import ImageFont
        label_font = ImageFont.truetype(KOREAN_FONT, 20)
    except Exception:  # noqa: BLE001
        label_font = None

    def tile(icon, x, y, size):
        sheet.paste(Image.new("RGB", (size, size), bg), (x, y))
        sheet.paste(icon, (x, y), icon)

    labels = ["스퀘어클", "원형", "적응형 · 스퀘어클", "적응형 · 원형", "원본 아트"]
    x = pad
    tile(legacy(master, p, False), x, 30, p)
    x += p + pad
    tile(legacy(master, p, True), x, 30, p)
    x += p + pad
    for _ in range(2):
        art = on_white(master, p, ADAPTIVE_ART).convert("RGBA")
        art.putalpha(squircle_mask(p) if _ == 0 else circle_mask(p))
        tile(art, x, 30, p)
        x += p + pad
    if source is not None:
        src = source.resize((p, p), Image.LANCZOS)
        tile(src.convert("RGBA"), x, 30, p)
    else:
        tile(legacy(master, p, False), x, 30, p)

    for i, label in enumerate(labels):
        d.text((pad + i * (p + pad) + 4, 8), label, fill=(40, 48, 60), font=label_font)

    # actual launcher sizes, for legibility
    x = pad
    for size in (48, 72, 96):
        tile(legacy(master, size, False), x, p + 60, size)
        x += size + 14
    x = pad + 220
    for size in (48, 72, 96):
        tile(legacy(master, size, True), x, p + 60, size)
        x += size + 14
    x = pad + 440
    for size in (48, 72, 96):
        art = on_white(master, size, ADAPTIVE_ART).convert("RGBA")
        art.putalpha(squircle_mask(size))
        tile(art, x, p + 60, size)
        x += size + 14
    d.text((pad, p + 46), "실제 크기", fill=(40, 48, 60), font=label_font)
    sheet.save(out_path)
    return out_path


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if not args:
        raise SystemExit(__doc__)
    src = args[0]
    base = args[1] if len(args) > 1 else "app/src/main/res"
    preview_path = "icon-preview.png"
    if "--preview" in sys.argv:
        preview_path = sys.argv[sys.argv.index("--preview") + 1]

    master = clean_artwork(src)

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

    print("preview", preview(master, preview_path, Image.open(src).convert("RGB")))


if __name__ == "__main__":
    main()
