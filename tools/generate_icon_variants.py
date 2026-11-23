#!/usr/bin/env python3
"""Build the 「TSV 교대달력」 black / sky-blue launcher icons (v2.4.3).

Nothing is redrawn. Each supplied mock-up is a rounded square of artwork on a flat
backdrop (pure black for the dark icon, pure white for the sky-blue one). Two masks
work on its pixels:

  background_field()  a normalised blur of the pixels that are plainly backdrop
                      (cyan / neutral dark). The mock-up's specular bevel and every
                      object are left out of the average, so it is the clean colour
                      the frame should continue into.
  full_bleed()        the mock-up's outside corners and its outer band (bevel, frame
                      line) fade into that field, giving a square of artwork on a
                      seamless full-bleed colour.

Layout (after 승현 님's "롤 이미지는 다 나와야대 짤리면 안되고"): the roll, the
ribbon, the calendar and both exclamation marks stay wholly inside the mask.

  LEGACY_SQ = 0.95   legacy squircle  (content fits: limit 1.014)
  LEGACY_CI = 0.79   legacy circle    (content fits: limit 0.826)
  ADAPTIVE  = 0.545  adaptive 108dp   (content fits the 72dp visible circle: limit 0.550)

Usage: python3 tools/generate_icon_variants.py [out_res_dir] [--masters]
"""
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont
from scipy import ndimage

LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
ADAPTIVE = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

LEGACY_SQ = 0.95
LEGACY_CI = 0.79
ADAPTIVE_ART = 0.545
SQUIRCLE_RADIUS = 0.21
SS = 4
LIP = 0.06               # the artwork's outer 6% fades into its background field
FLAT = 0.55              # of which this outer share is pure field

KOREAN_FONT = "/usr/share/fonts/pickle-hwp/NotoSansCJKkr-Regular.otf"

SOURCES = {
    "black": "attachments/2026-11-23/54549-ae1270c7066f4a63aca84a5595e78a1d.jpg",
    "blue": "attachments/2026-11-23/54547-ccd3059363054e54ada5cfc6e1763926.jpg",
}
SHADE = {"black": "dark", "blue": "light"}


# --------------------------------------------------------------------------- #
# 1. rounded-square mock-up -> full-bleed square
# --------------------------------------------------------------------------- #
def backdrop_mask(a, shade):
    """The mock-up sheet outside the rounded square, connected to the image border."""
    r, g, b = (a[:, :, i].astype(np.int16) for i in range(3))
    if shade == "dark":
        cand = np.maximum(np.maximum(r, g), b) < 14
    else:
        cand = np.minimum(np.minimum(r, g), b) > 240
    lab, _ = ndimage.label(cand, np.ones((3, 3)))
    edge = (set(lab[0].tolist()) | set(lab[-1].tolist())
            | set(lab[:, 0].tolist()) | set(lab[:, -1].tolist()))
    edge.discard(0)
    return np.isin(lab, list(edge))


def face_mask(a, shade):
    face = ~ndimage.binary_dilation(backdrop_mask(a, shade), np.ones((3, 3)))
    face = ndimage.binary_fill_holes(face)
    lab, n = ndimage.label(face)
    sizes = ndimage.sum(face, lab, range(1, n + 1))
    return lab == (int(np.argmax(sizes)) + 1)


def background_field(a, face, shade, sigma=0.05):
    """Smooth colour field of the plain backdrop, objects and bevel excluded."""
    r, g, b = (a[:, :, i].astype(np.int16) for i in range(3))
    mx = np.maximum(np.maximum(r, g), b)
    mn = np.minimum(np.minimum(r, g), b)
    chroma, L = mx - mn, 0.299 * r + 0.587 * g + 0.114 * b
    if shade == "light":
        plain = (b - r > 100) & (g - r > 100) & face
    else:
        plain = (chroma <= 14) & (L <= 120) & face
    plain = ndimage.binary_opening(plain, np.ones((5, 5)))
    k = max(3, int(round(sigma * min(a.shape[:2]))))
    w = ndimage.uniform_filter(plain.astype(np.float32), k, mode="nearest")
    ww = np.maximum(w, 1e-4)[:, :, None]
    field = np.stack([
        ndimage.uniform_filter(np.where(plain, a[:, :, c], 0).astype(np.float32), k,
                               mode="nearest") for c in range(3)], axis=2) / ww
    # windows with almost no backdrop (the mock-up's own corners) -> nearest real value
    valid = w > 0.02
    if not valid.all():
        _, (iy, ix) = ndimage.distance_transform_edt(~valid, return_indices=True)
        field = field[iy, ix]
    return np.clip(field, 0, 255), plain


def fit_field(field, plain, deg=2):
    """Least-squares polynomial of the background field (normalised coords)."""
    h, w = field.shape[:2]
    ys, xs = np.mgrid[0:h, 0:w]
    keep = plain.copy()
    keep[::4, :] = keep[::4, :]   # cheap subsample
    sel = keep & (xs % 3 == 0) & (ys % 3 == 0) if keep.any() else np.ones_like(keep)
    u = (xs[sel] / (w - 1.0)) - 0.5
    v = (ys[sel] / (h - 1.0)) - 0.5
    A = np.stack([np.ones_like(u), u, v, u * u, u * v, v * v], axis=1)
    coeffs = []
    for c in range(3):
        b = field[:, :, c][sel].astype(np.float64)
        coef, *_ = np.linalg.lstsq(A, b, rcond=None)
        coeffs.append(coef)
    return np.array(coeffs)          # (3, 6)


def eval_field(coeffs, u, v):
    """Polynomial field at normalised coordinates centred on 0.5."""
    A = np.stack([np.ones_like(u), u, v, u * u, u * v, v * v], axis=-1)
    return np.stack([A @ coeffs[c] for c in range(3)], axis=-1)


def build_master(path, shade, lip=LIP):
    """Full-bleed square of artwork, plus the polynomial of its background field."""
    a = np.asarray(Image.open(path).convert("RGB"))
    n = min(a.shape[:2])
    face = face_mask(a, shade)
    field, plain = background_field(a, face, shade)
    coeffs = fit_field(field, plain)

    band = max(6, int(round(lip * n)))
    deep = ndimage.binary_erosion(face, np.ones((3, 3)), iterations=band)
    # how far out into the band a pixel sits: 0 deep inside, 1 at the frame edge
    dist = np.clip(ndimage.distance_transform_edt(~deep) / float(band), 0, 1)
    w = np.clip((dist - (1 - FLAT)) / FLAT, 0, 1) ** 1.1
    w[~face] = 1.0
    master = Image.fromarray(np.clip(a * (1 - w[:, :, None]) + field * w[:, :, None], 0, 255)
                             .astype(np.uint8))
    return master, coeffs


# --------------------------------------------------------------------------- #
# 2. masks and layout
# --------------------------------------------------------------------------- #
def squircle_mask(size, radius_ratio=SQUIRCLE_RADIUS):
    m = Image.new("L", (size * SS, size * SS), 0)
    ImageDraw.Draw(m).rounded_rectangle(
        [0, 0, size * SS - 1, size * SS - 1], radius=int(size * SS * radius_ratio), fill=255)
    return m.resize((size, size), Image.LANCZOS)


def circle_mask(size):
    m = Image.new("L", (size * SS, size * SS), 0)
    ImageDraw.Draw(m).ellipse([0, 0, size * SS - 1, size * SS - 1], fill=255)
    return m.resize((size, size), Image.LANCZOS)


def compose(master, coeffs, size, ratio):
    """Artwork at `ratio` of the canvas on its own smooth background field."""
    side = max(1, int(round(size * ratio)))
    art = master.resize((side, side), Image.LANCZOS)
    if side >= size:
        return art.convert("RGB")
    off = (size - side) // 2

    # the ring is the fitted background field, evaluated past the artwork's edge:
    # a smooth continuation instead of streaked border pixels
    ys, xs = np.mgrid[0:size, 0:size]
    u = (xs - off) / float(max(1, side - 1)) - 0.5
    v = (ys - off) / float(max(1, side - 1)) - 0.5
    arr = np.clip(eval_field(coeffs, u, v), 0, 255).astype(np.uint8)

    # feather the artwork's own frame band into that field (kills the seam)
    f = max(1, int(round(side * 0.03)))
    ramp = np.linspace(0.0, 1.0, f, dtype=np.float32) ** 0.8
    alpha = np.ones((side, side), np.float32)
    alpha[:f, :] *= ramp[:, None]
    alpha[-f:, :] *= ramp[::-1, None]
    alpha[:, :f] *= ramp[None, :]
    alpha[:, -f:] *= ramp[::-1][None, :]

    out = arr.astype(np.float32)
    base = out[off:off + side, off:off + side]
    out[off:off + side, off:off + side] = (np.asarray(art).astype(np.float32) * alpha[:, :, None]
                                           + base * (1 - alpha[:, :, None]))
    img = Image.fromarray(np.clip(out, 0, 255).astype(np.uint8))
    if size >= 96:
        blur = np.asarray(img.filter(ImageFilter.GaussianBlur(max(0.6, size * 0.004))))
        seam = np.zeros((size, size), bool)
        seam[max(off - f, 0):off + side + f, max(off - f, 0):off + side + f] = True
        seam[off + 2 * f:off + side - 2 * f, off + 2 * f:off + side - 2 * f] = False
        res = np.asarray(img).copy()
        res[seam] = blur[seam]
        img = Image.fromarray(res)
    return img


def masked(img, size, shape):
    out = img.convert("RGBA")
    out.putalpha(circle_mask(size) if shape == "circle" else squircle_mask(size))
    return out


# --------------------------------------------------------------------------- #
# 3. preview sheet
# --------------------------------------------------------------------------- #
def font(sz):
    try:
        return ImageFont.truetype(KOREAN_FONT, sz)
    except Exception:  # noqa: BLE001
        return ImageFont.load_default()


def sheet(rows, out_path):
    p, pad = 236, 20
    cols = 6
    head, rowh = 118, p + 62
    w = cols * p + (cols + 1) * pad + 40
    h = head + len(rows) * rowh + pad
    img = Image.new("RGB", (w, h), (237, 240, 246))
    d = ImageDraw.Draw(img)
    f_small, f_label, f_title = font(15), font(20), font(26)

    d.text((pad, 18), "TSV 교대달력 v2.4.3 — 아이콘 3종 마스크 시뮬레이션",
           fill=(30, 38, 52), font=f_title)
    heads = ["레거시 스퀘어클", "레거시 원형", "적응형 · 72dp 원", "적응형 · 72dp 스퀘어클",
             "적응형 · 전체 원", "실제 크기 48·72·96"]
    for i, t in enumerate(heads):
        d.text((pad + i * (p + pad), 70), t, fill=(96, 108, 128), font=f_small)

    for ri, (label, master, coeffs, accent) in enumerate(rows):
        y = head + ri * rowh
        d.text((pad, y - 8), label, fill=accent, font=f_label)
        y += 24
        tiles = [masked(compose(master, coeffs, p, LEGACY_SQ), p, "squircle"),
                 masked(compose(master, coeffs, p, LEGACY_CI), p, "circle")]
        ad = compose(master, coeffs, p, ADAPTIVE_ART)
        view = int(round(p * 72 / 108))
        box = ad.crop(((p - view) // 2, (p - view) // 2,
                       (p + view) // 2, (p + view) // 2)).resize((p, p), Image.LANCZOS)
        for shape in ("circle", "squircle"):
            tiles.append(masked(box, p, shape))
        tiles.append(masked(ad, p, "circle"))
        for i, t in enumerate(tiles):
            img.paste(t, (pad + i * (p + pad), y), t)

        xx = pad + 5 * (p + pad)
        for sz, shape, rat in ((48, "squircle", LEGACY_SQ), (72, "circle", LEGACY_CI),
                               (96, "squircle", LEGACY_SQ)):
            icon = masked(compose(master, coeffs, sz, rat), sz, shape)
            img.paste(icon, (xx, y + (96 - sz) // 2), icon)
            xx += sz + 16
    img.save(out_path)
    return out_path


def main():
    base = sys.argv[1] if len(sys.argv) > 1 else "tsv-duty-calendar-android/app/src/main/res"
    built = {k: build_master(p, SHADE[k]) for k, p in SOURCES.items()}
    if os.path.isdir("workspace_tmp"):
        for k, (master, _) in built.items():
            master.save(os.path.join("workspace_tmp", "master_%s.png" % k))
    if "--masters" in sys.argv:
        return

    for key, (master, coeffs) in built.items():
        for dens, size in LEGACY.items():
            d = os.path.join(base, "mipmap-" + dens)
            os.makedirs(d, exist_ok=True)
            masked(compose(master, coeffs, size, LEGACY_SQ), size, "squircle").save(
                os.path.join(d, "ic_launcher_%s.png" % key))
            masked(compose(master, coeffs, size, LEGACY_CI), size, "circle").save(
                os.path.join(d, "ic_launcher_%s_round.png" % key))
            print("legacy", key, dens, size)
        for dens, size in ADAPTIVE.items():
            d = os.path.join(base, "mipmap-" + dens)
            os.makedirs(d, exist_ok=True)
            compose(master, coeffs, size, ADAPTIVE_ART).save(
                os.path.join(d, "ic_launcher_%s_foreground.png" % key))
            print("adaptive", key, dens, size)


if __name__ == "__main__":
    main()
