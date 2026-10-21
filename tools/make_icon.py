#!/usr/bin/env python3
"""Generate SaysChatV4 launcher icons (no external assets)."""
from PIL import Image, ImageDraw, ImageFont
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}
FONT_CANDIDATES = [
    r"C:\Windows\Fonts\arialbd.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
]


def font(size):
    for path in FONT_CANDIDATES:
        if os.path.exists(path):
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def make(size):
    s = size * 4
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # vertical indigo gradient inside a rounded square
    top, bottom = (48, 63, 159), (26, 35, 126)
    radius = int(s * 0.22)
    for y in range(s):
        t = y / max(1, s - 1)
        color = tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)) + (255,)
        d.line([(0, y), (s, y)], fill=color)
    mask = Image.new("L", (s, s), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, s - 1, s - 1], radius=radius, fill=255)
    img.putalpha(mask)
    # soft highlight
    hi = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    ImageDraw.Draw(hi).ellipse(
        [int(-s * 0.25), int(-s * 0.42), int(s * 0.85), int(s * 0.45)],
        fill=(255, 255, 255, 26),
    )
    img = Image.alpha_composite(img, hi)
    img.putalpha(mask)
    d = ImageDraw.Draw(img)
    # white chat bubble with tail
    left, right = int(s * 0.17), int(s * 0.83)
    top_y, bottom_y = int(s * 0.22), int(s * 0.70)
    d.rounded_rectangle([left, top_y, right, bottom_y],
                        radius=int(s * 0.10), fill=(255, 255, 255, 255))
    d.polygon([(int(s * 0.34), bottom_y - 2),
               (int(s * 0.29), int(s * 0.82)),
               (int(s * 0.49), bottom_y - 2)], fill=(255, 255, 255, 255))
    # V4 in accent indigo
    f = font(int(s * 0.28))
    text = "V4"
    box = d.textbbox((0, 0), text, font=f)
    tw, th = box[2] - box[0], box[3] - box[1]
    d.text(((s - tw) / 2 - box[0], (top_y + bottom_y - th) / 2 - box[1]),
           text, font=f, fill=(48, 63, 159, 255))
    return img.resize((size, size), Image.LANCZOS)


for folder, size in SIZES.items():
    out_dir = os.path.join(ROOT, "android", "app", "src", "main", "res", folder)
    os.makedirs(out_dir, exist_ok=True)
    make(size).save(os.path.join(out_dir, "ic_launcher.png"))
print("icons generated")
