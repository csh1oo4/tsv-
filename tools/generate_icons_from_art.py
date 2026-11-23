#!/usr/bin/env python3
"""Build 「TSV 교대달력」 launcher icons from the supplied artwork (v2.4.2).

Source (2026-11-23, second revision): the same white rounded-square icon with the
gold PI film roll, the calendar and the two yellow exclamation marks, but the
light-blue disc that used to sit behind them is gone — v2.4.2 replaces it with a
pure white (#FFFFFF) backdrop.

Nothing is redrawn. Two mask passes work on the pixels of the original:

  clear_blue_background()
     * the disc, its gradient and the shadows it carries are "blue over green"
       (b-g >= 8); that whole field becomes white
     * the artwork is protected by four rules — saturated colours, dark ink, the
       calendar's grey date cells (small blobs) and the silver binding rings
     * the film body is dark with thin cyan rim-light lines, so it is closed as a
       solid body before masking (those highlights are detail, not backdrop)
     * the disc's own soft rim and the pale cyan shadow stroke that framed the
       "!" plate are cleared as well
     * any blue cast left on an object edge is despilled (blue channel pulled back
       to max(r,g)+3) so no cyan hairline survives
  clean_artwork()
     * the black backdrop behind the rounded corners is flood-filled with white
     * the frame hairline is cropped away

The result is laid out at five densities for the legacy squircle, the legacy
circle and the adaptive foreground layer (artwork at 80% of the canvas so it
fills a launcher icon without being clipped).

Outputs (app/src/main/res):
  mipmap-{mdpi..xxxhdpi}/ic_launcher.png             legacy squircle (48..192px)
  mipmap-{mdpi..xxxhdpi}/ic_launcher_round.png       legacy circle
  mipmap-{mdpi..xxxhdpi}/ic_launcher_foreground.png  adaptive 108dp layer (108..432px)
  icon-preview.png                                   preview sheet

Usage: python3 tools/generate_icons_from_art.py SOURCE_IMAGE [out_res_dir] [--preview PATH]
"""

import os
import sys
from collections import deque

import numpy as np
from PIL import Image, ImageDraw, ImageFilter
from scipy import ndimage

LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
ADAPTIVE = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

LEGACY_ART = 0.94      # artwork inside the legacy squircle
ROUND_ART = 0.88       # smaller so the circle never bites into the calendar
ADAPTIVE_ART = 0.80    # artwork inside the 108dp adaptive canvas (safe zone)
KOREAN_FONT = "/usr/share/fonts/pickle-hwp/NotoSansCJKkr-Regular.otf"
SQUIRCLE_RADIUS = 0.21  # legacy squircle corner radius, same family as One UI
SS = 4                 # supersampling for masks

WHITE = (255, 255, 255)
CROP = 6               # trims the anti-aliased line along the artwork's own frame edge
DARK = 110             # "black backdrop" threshold
DILATE = 9             # grows the removed backdrop a few px past its anti-aliased edge


# --------------------------------------------------------------------------- #
# 1. blue disc -> white
# --------------------------------------------------------------------------- #
def _channels(a):
    r = a[:, :, 0].astype(np.int16)
    g = a[:, :, 1].astype(np.int16)
    b = a[:, :, 2].astype(np.int16)
    mx = np.maximum(np.maximum(r, g), b)
    mn = np.minimum(np.minimum(r, g), b)
    C = mx - mn                      # chroma
    B = b - np.maximum(r, g)         # blue dominance
    L = 0.299 * r + 0.587 * g + 0.114 * b
    return r, g, b, C, B, L


def _date_cells(r, g, b, B):
    """Bluish-grey calendar cells: small, enclosed blobs inside the page."""
    cand = (B >= 8) & (b >= 185) & (b <= 250) & (np.abs(g - r) <= 6)
    lab, n = ndimage.label(cand, structure=np.ones((3, 3)))
    cells = np.zeros(cand.shape, bool)
    for i in range(1, n + 1):
        ys, xs = np.where(lab == i)
        if 60 <= len(ys) <= 8000 and 640 <= xs.mean() <= 1150 and 350 <= ys.mean() <= 790:
            cells[lab == i] = True
    return ndimage.binary_dilation(cells, np.ones((5, 5)))


def _rings(r, g, b, C, L, shape):
    """Silver binding rings: neutral grey highlights around the calendar's top."""
    zone = np.zeros(shape, bool)
    zone[110:400, 740:1200] = True
    rings = zone & (C <= 26) & (L >= 140) & (b - g <= 25) & (g - r < 30)
    rings = ndimage.binary_opening(rings, np.ones((3, 3)))
    return ndimage.binary_dilation(rings, np.ones((7, 7)))


def clear_blue_background(path):
    a = np.asarray(Image.open(path).convert("RGB"))
    r, g, b, C, B, L = _channels(a)

    colored = (C >= 30) & (b - g < 8) & (g - r < 20)     # gold, red, yellow
    dark = (L < 120) & (B <= 15)                          # black ink / film
    cells = _date_cells(r, g, b, B)
    rings = _rings(r, g, b, C, L, C.shape)
    prot = colored | dark | cells | rings

    # film body: dark, with thin cyan rim-light lines -> protect it as one body
    yy, xx = np.ogrid[-15:16, -15:16]
    disk = (yy * yy + xx * xx) <= 15 * 15
    # (only neutral black — the dark navy shadows on the disc are background)
    prot = prot | ndimage.binary_closing((L < 110) & (B <= 25), structure=disk)

    blue = (b - g >= 8) & ~prot
    halo = (L >= 234) & (b - g >= 3) & ~prot              # disc's soft outer edge
    halo = ndimage.binary_dilation(halo, np.ones((5, 5))) & halo
    blue |= halo
    blue |= ndimage.binary_dilation(blue, np.ones((3, 3)), iterations=3) & (L >= 236) & ~prot

    bright = ndimage.uniform_filter(L, 15) > 195
    cyan = (g - r >= 20) & (b - g >= -15) & (b - r >= 20) & bright & ~prot
    cyan_only = cyan.copy()
    blue |= cyan
    blue = ndimage.binary_closing(blue, np.ones((3, 3)))
    lab, n = ndimage.label(blue, structure=np.ones((3, 3)))
    sizes = ndimage.sum(blue, lab, range(1, n + 1))
    blue = np.isin(lab, [i + 1 for i, s in enumerate(sizes) if s >= 30])
    blue |= cyan_only

    alpha = Image.fromarray((blue * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(0.8))
    al = np.asarray(alpha).astype(np.float32)[:, :, None] / 255.0

    out = a.astype(np.float32).copy()
    near = ndimage.binary_dilation(blue, np.ones((9, 9))) & ~blue
    despill = near & (B > 3) & (B <= 90)
    target = np.minimum(b, np.maximum(r, g) + 3)
    out[:, :, 2] = np.where(despill, target, b)

    res = np.clip(out * (1 - al) + 255.0 * al + 0.5, 0, 255).astype(np.uint8)

    # pale cyan shadow stroke that framed the "!" plate (light, cyan, not on an object)
    r2, g2, b2, C2, B2, L2 = _channels(res)
    solid = ndimage.binary_dilation((C2 >= 30) & (b2 - g2 < 8) & (g2 - r2 < 20) | (L2 < 110),
                                    np.ones((5, 5)))
    stroke = (g2 - r2 >= 40) & (np.abs(b2 - g2) <= 25) & (L2 >= 150) & ~solid
    res[stroke] = WHITE
    print(f"  cleared {int(blue.sum())} disc px, {int(stroke.sum())} stroke px, "
          f"{int(despill.sum())} despilled")
    return Image.fromarray(res)


# --------------------------------------------------------------------------- #
# 2. black mock-up backdrop + crop
# --------------------------------------------------------------------------- #
def clean_artwork(im):
    """Flood-fill the black outside the rounded square with white, then trim the edge line."""
    a = np.asarray(im.convert("RGB")).astype(np.uint8)
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


# --------------------------------------------------------------------------- #
# 3. render the layers
# --------------------------------------------------------------------------- #
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

    white_backed = clear_blue_background(src)
    master = clean_artwork(white_backed)

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
