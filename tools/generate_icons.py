#!/usr/bin/env python3
"""Generate 「TSV 교대달력」 launcher icons.

The artwork is drawn from scratch (no source bitmap): a deep blue → indigo
rounded-square background, a white calendar card with a saturated blue top bar
and two binder rings, and a bold TSV wordmark.

Outputs (app/src/main/res):
  mipmap-{mdpi..xxxhdpi}/ic_launcher.png             legacy, squircle mask
  mipmap-{mdpi..xxxhdpi}/ic_launcher_round.png       legacy, circle mask
  mipmap-{mdpi..xxxhdpi}/ic_launcher_foreground.png  adaptive 108dp layer,
        the calendar card at 60% of the canvas so it stays inside the safe zone
  mipmap-{mdpi..xxxhdpi}/ic_launcher_monochrome.png  themed-icon silhouette
        (white card with the wordmark knocked out)

Usage: python3 tools/generate_icons.py [out_res_dir]
"""

import os
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

MASTER = 1024
LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
ADAPTIVE = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

ART_BOX = 0.60   # card width inside the 108dp adaptive canvas
SS = 4           # supersampling for masks

FONT_CANDIDATES = [
    "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
    "/usr/share/fonts/truetype/freefont/FreeSansBold.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
]

BG_TOP = (30, 58, 138)      # #1e3a8a
BG_BOTTOM = (67, 56, 202)   # #4338ca
BAR = (37, 99, 235)         # #2563eb
BAR_DARK = (29, 78, 216)    # #1d4ed8
INK = (30, 41, 59)          # #1e293b
RING = (219, 234, 254)      # #dbeafe


def load_font(size):
    for path in FONT_CANDIDATES:
        if os.path.exists(path):
            return ImageFont.truetype(path, size)
    raise SystemExit("No bold TTF font found")


def gradient(size):
    """Diagonal deep-blue → indigo gradient."""
    base = Image.new("RGB", (size, size), BG_TOP)
    draw = ImageDraw.Draw(base)
    for y in range(size):
        t = y / float(size - 1)
        # ease-in so the indigo settles only near the bottom
        t = t * t * (3 - 2 * t)
        color = tuple(int(BG_TOP[i] + (BG_BOTTOM[i] - BG_TOP[i]) * t) for i in range(3))
        draw.line([(0, y), (size, y)], fill=color)
    glow = Image.new("L", (size, size), 0)
    gd = ImageDraw.Draw(glow)
    gd.ellipse([-size * 0.35, -size * 0.55, size * 0.75, size * 0.45], fill=70)
    glow = glow.filter(ImageFilter.GaussianBlur(size * 0.16))
    light = Image.new("RGB", (size, size), (96, 165, 250))
    base = Image.composite(light, base, glow.point(lambda v: int(v * 0.55)))
    return base.convert("RGBA")


def calendar_card(size, with_rings=True, with_shadow=True):
    """White card + blue bar + TSV, transparent outside the card."""
    s = size
    card = Image.new("RGBA", (s, s), (0, 0, 0, 0))

    left, right = int(s * 0.145), int(s * 0.855)
    top, bottom = int(s * 0.245), int(s * 0.845)
    radius = int(s * 0.105)
    bar_h = int(s * 0.165)

    if with_rings:
        rings = Image.new("RGBA", (s, s), (0, 0, 0, 0))
        rd = ImageDraw.Draw(rings)
        ring_w = int(s * 0.062)
        ring_h = int(s * 0.115)
        ring_y = top - int(ring_h * 0.55)
        for cx in (int(s * 0.335), int(s * 0.665)):
            rd.rounded_rectangle(
                [cx - ring_w // 2, ring_y, cx + ring_w // 2, ring_y + ring_h],
                radius=ring_w // 2, fill=RING)
        card.alpha_composite(rings)

    if with_shadow:
        shadow = Image.new("L", (s, s), 0)
        sd = ImageDraw.Draw(shadow)
        sd.rounded_rectangle(
            [left, top + int(s * 0.012), right, bottom + int(s * 0.022)],
            radius=radius, fill=110)
        shadow = shadow.filter(ImageFilter.GaussianBlur(s * 0.022))
        shade = Image.new("RGBA", (s, s), (15, 23, 42, 255))
        shade.putalpha(shadow)
        card.alpha_composite(shade)

    d = ImageDraw.Draw(card)
    d.rounded_rectangle([left, top, right, bottom], radius=radius, fill=(255, 255, 255, 255))
    # Top bar with rounded top corners and a square bottom edge.
    d.rounded_rectangle([left, top, right, top + bar_h], radius=radius, fill=BAR + (255,))
    d.rectangle([left, top + bar_h - radius, right, top + bar_h], fill=BAR + (255,))
    d.rectangle([left, top + bar_h, right, top + bar_h + int(s * 0.004)],
                fill=BAR_DARK + (255,))

    # Bold TSV wordmark, optically centred in the white area under the bar.
    font = load_font(int(s * 0.245))
    text = "TSV"
    tracking = int(s * 0.012)
    widths = [d.textlength(ch, font=font) for ch in text]
    total = sum(widths) + tracking * (len(text) - 1)
    x = left + ((right - left) - total) / 2.0
    area_top = top + bar_h
    area_bottom = bottom
    bbox = d.textbbox((0, 0), text, font=font)
    text_h = bbox[3] - bbox[1]
    y = area_top + ((area_bottom - area_top) - text_h) / 2.0 - bbox[1] + int(s * 0.012)
    for ch, w in zip(text, widths):
        d.text((x, y), ch, font=font, fill=INK + (255,))
        x += w + tracking
    return card


def full_icon(size):
    bg = gradient(size)
    bg.alpha_composite(calendar_card(size))
    return bg


def squircle_mask(size, radius_ratio=0.215):
    m = Image.new("L", (size * SS, size * SS), 0)
    ImageDraw.Draw(m).rounded_rectangle(
        [0, 0, size * SS - 1, size * SS - 1], radius=int(size * SS * radius_ratio), fill=255)
    return m.resize((size, size), Image.LANCZOS)


def circle_mask(size):
    m = Image.new("L", (size * SS, size * SS), 0)
    ImageDraw.Draw(m).ellipse([0, 0, size * SS - 1, size * SS - 1], fill=255)
    return m.resize((size, size), Image.LANCZOS)


def legacy(size, round_icon):
    art = full_icon(size)
    art.putalpha(circle_mask(size) if round_icon else squircle_mask(size))
    return art


def adaptive_foreground(size):
    box = int(round(size * ART_BOX))
    card = calendar_card(box)
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    off = (size - box) // 2
    layer.alpha_composite(card, (off, off))
    return layer


def monochrome(size):
    box = int(round(size * ART_BOX))
    # Solid silhouette, no shadow: a soft shadow would read as grey haze once
    # the system tints the layer.
    card = calendar_card(box, with_rings=True, with_shadow=False)
    alpha = card.getchannel("A")
    # Paint the whole silhouette white, then cut the wordmark back out so the
    # letters read in the system tint colour.
    d = ImageDraw.Draw(card)
    left, right = int(box * 0.145), int(box * 0.855)
    top, bar_h = int(box * 0.245), int(box * 0.165)
    font = load_font(int(box * 0.245))
    text = "TSV"
    tracking = int(box * 0.012)
    widths = [d.textlength(ch, font=font) for ch in text]
    total = sum(widths) + tracking * (len(text) - 1)
    x = left + ((right - left) - total) / 2.0
    bbox = d.textbbox((0, 0), text, font=font)
    text_h = bbox[3] - bbox[1]
    y = top + bar_h + ((int(box * 0.845) - (top + bar_h)) - text_h) / 2.0 - bbox[1] + int(box * 0.012)
    text_mask = Image.new("L", (box, box), 0)
    td = ImageDraw.Draw(text_mask)
    for ch, w in zip(text, widths):
        td.text((x, y), ch, font=font, fill=255)
        x += w + tracking

    hole = Image.composite(Image.new("L", (box, box), 0), alpha, text_mask)
    white = Image.new("RGBA", (box, box), (255, 255, 255, 255))
    white.putalpha(hole)
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    off = (size - box) // 2
    layer.alpha_composite(white, (off, off))
    return layer


def preview():
    """Preview sheet for the user: squircle, round, adaptive circle, adaptive squircle."""
    p = 320
    bgc = (236, 240, 246)
    sheet = Image.new("RGB", (p * 4 + 50, p + 20), bgc)

    def tile(icon):
        tile = Image.new("RGB", (p, p), bgc)
        tile.paste(icon, (0, 0), icon)
        return tile

    sheet.paste(tile(legacy(p, False)), (10, 10))
    sheet.paste(tile(legacy(p, True)), (p + 23, 10))
    for i, mask in enumerate((circle_mask, squircle_mask)):
        art = gradient(p)
        art.alpha_composite(adaptive_foreground(p))
        art.putalpha(mask(p))
        sheet.paste(tile(art), (p * (2 + i) + 36 + i * 13, 10))
    return sheet


def main():
    base = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/res"
    for dens, size in LEGACY.items():
        d = os.path.join(base, "mipmap-" + dens)
        os.makedirs(d, exist_ok=True)
        legacy(size, False).save(os.path.join(d, "ic_launcher.png"))
        legacy(size, True).save(os.path.join(d, "ic_launcher_round.png"))
        print("legacy", d, size)
    for dens, size in ADAPTIVE.items():
        d = os.path.join(base, "mipmap-" + dens)
        os.makedirs(d, exist_ok=True)
        adaptive_foreground(size).save(os.path.join(d, "ic_launcher_foreground.png"))
        monochrome(size).save(os.path.join(d, "ic_launcher_monochrome.png"))
        print("adaptive", d, size)
    root = os.path.abspath(os.path.join(base, "..", "..", "..", ".."))
    out = os.path.join(root, "icon-preview.png")
    preview().save(out)
    print("preview", out)


if __name__ == "__main__":
    main()
