#!/usr/bin/env python3
"""Generate v1.2 launcher icons for TSV 근무표 from icon-v2-square.png.

Source: /workspace/tsv-secret-schedule/icon-v2-square.png (1024x1024, full-bleed
navy gradient with the TSV / AMMK wordmark).

Outputs (app/src/main/res):
  mipmap-{mdpi..xxxhdpi}/ic_launcher.png         legacy, squircle mask
  mipmap-{mdpi..xxxhdpi}/ic_launcher_round.png   legacy, circle mask
  mipmap-{mdpi..xxxhdpi}/ic_launcher_foreground.png  adaptive 108dp layer,
        the artwork scaled to 340/432 of the layer so the wordmark stays inside
        the 66dp safe zone, with a soft radial fade so no hard square edge shows
        on a #0f172a background
  mipmap-{mdpi..xxxhdpi}/ic_launcher_monochrome.png  themed-icon alpha silhouette
"""

from PIL import Image, ImageDraw, ImageFilter
import numpy as np

SRC = "/workspace/tsv-secret-schedule/icon-v2-square.png"
BASE = "/workspace/tsv-secret-schedule-android/app/src/main/res"

LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
ADAPTIVE = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

ART_BOX = 400 / 432.0       # artwork width relative to the 108dp adaptive canvas
MONO_BOX = 380 / 432.0      # monochrome silhouette a touch larger
SS = 4                      # supersampling for masks


def squircle_mask(size, radius_ratio=0.22):
    m = Image.new("L", (size * SS, size * SS), 0)
    ImageDraw.Draw(m).rounded_rectangle(
        [0, 0, size * SS - 1, size * SS - 1], radius=int(size * SS * radius_ratio), fill=255
    )
    return m.resize((size, size), Image.LANCZOS)


def circle_mask(size):
    m = Image.new("L", (size * SS, size * SS), 0)
    ImageDraw.Draw(m).ellipse([0, 0, size * SS - 1, size * SS - 1], fill=255)
    return m.resize((size, size), Image.LANCZOS)


def legacy(src, size, round_icon):
    art = src.resize((size, size), Image.LANCZOS).convert("RGBA")
    art.putalpha(circle_mask(size) if round_icon else squircle_mask(size))
    return art


def radial_alpha(size, opaque_r, fade_r):
    """1.0 inside opaque_r, smoothstep down to 0.0 at fade_r (separable in radius)."""
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float32)
    c = (size - 1) / 2.0
    r = np.sqrt((xx - c) ** 2 + (yy - c) ** 2)
    t = np.clip((fade_r - r) / max(fade_r - opaque_r, 1e-3), 0.0, 1.0)
    a = t * t * (3 - 2 * t)  # smoothstep
    return Image.fromarray((a * 255 + 0.5).astype(np.uint8), "L")


def adaptive_foreground(src, size):
    box = int(round(size * ART_BOX))
    art = src.resize((box, box), Image.LANCZOS).convert("RGBA")
    # Opaque past the wordmark (radius ~0.364 * box) and fully faded before the
    # artwork's own square edge (0.5 * box), so no hard edge shows on #0f172a.
    art.putalpha(radial_alpha(box, opaque_r=box * 0.40, fade_r=box * 0.49))
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    off = (size - box) // 2
    layer.alpha_composite(art, (off, off))
    return layer


def monochrome(src, size):
    box = int(round(size * MONO_BOX))
    small = src.resize((box, box), Image.LANCZOS).convert("RGB")
    lum = np.asarray(small).astype(np.float32).mean(axis=2)
    # Background luminance is ~30-60, the wordmark is 150-255.
    a = np.clip((lum - 85.0) / 95.0, 0.0, 1.0)
    alpha = Image.fromarray((a * 255 + 0.5).astype(np.uint8), "L")
    white = Image.new("RGBA", (box, box), (255, 255, 255, 255))
    white.putalpha(alpha)
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    off = (size - box) // 2
    layer.alpha_composite(white, (off, off))
    return layer


def main():
    src = Image.open(SRC).convert("RGB")
    if src.size != (1024, 1024):
        src = src.resize((1024, 1024), Image.LANCZOS)

    for dens, size in LEGACY.items():
        d = f"{BASE}/mipmap-{dens}"
        legacy(src, size, False).save(f"{d}/ic_launcher.png")
        legacy(src, size, True).save(f"{d}/ic_launcher_round.png")
        print("legacy", d, size)

    for dens, size in ADAPTIVE.items():
        d = f"{BASE}/mipmap-{dens}"
        adaptive_foreground(src, size).save(f"{d}/ic_launcher_foreground.png")
        monochrome(src, size).save(f"{d}/ic_launcher_monochrome.png")
        print("adaptive", d, size)

    # Preview sheet: legacy squircle / legacy round / adaptive under circle mask /
    # adaptive under squircle mask, on a light and a dark launcher wallpaper grey.
    P = 320
    sheet = Image.new("RGB", (P * 4, P), (236, 240, 246))
    sheet.paste(legacy(src, P, False).convert("RGB"), (0, 0))
    sheet.paste(legacy(src, P, True).convert("RGB"), (P, 0))

    # Adaptive preview at 320px canvas = 108dp; background color #0f172a.
    for i, mask_fn in enumerate((circle_mask, lambda s: squircle_mask(s, 0.5))):
        canvas = Image.new("RGBA", (P, P), (15, 23, 42, 255))
        canvas.alpha_composite(adaptive_foreground(src, P))
        canvas.putalpha(mask_fn(P))
        bg = Image.new("RGB", (P, P), (236, 240, 246))
        bg.paste(canvas, (0, 0), canvas)
        sheet.paste(bg, (P * (2 + i), 0))

    sheet.save("/workspace/tsv-secret-schedule-android/tools/icon-preview-v2.png")
    print("preview -> tools/icon-preview-v2.png")


if __name__ == "__main__":
    main()
