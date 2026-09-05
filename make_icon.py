#!/usr/bin/env python3
"""Generate PNG launcher icons for the PDF Page Extractor Android app."""
from PIL import Image, ImageDraw
import os

OUT = os.path.join(os.path.dirname(__file__),
                   "app", "src", "main", "res")

# density -> px
DENSITIES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

BG = (21, 101, 192, 255)      # #1565C0
PAPER = (255, 255, 255, 255)
FOLD = (187, 222, 251, 255)   # #BBDEFB
LINE = (21, 101, 192, 255)
MARK = (229, 57, 53, 255)     # #E53935


def draw_icon(px: int) -> Image.Image:
    img = Image.new("RGBA", (px, px), BG)
    d = ImageDraw.Draw(img)
    u = px / 108.0  # design on 108 grid

    def r(x, y, w, h):
        return [x * u, y * u, (x + w) * u, (y + h) * u]

    # paper body with folded corner (polygon)
    paper = [
        (30 * u, 22 * u), (66 * u, 22 * u), (80 * u, 36 * u),
        (80 * u, 86 * u), (30 * u, 86 * u),
    ]
    d.polygon(paper, fill=PAPER)
    # fold triangle
    d.polygon([(66 * u, 22 * u), (80 * u, 36 * u), (66 * u, 36 * u)], fill=FOLD)
    # red marker bar
    d.rectangle(r(38, 40, 14, 6), fill=MARK)
    # text lines
    d.rectangle(r(38, 52, 32, 4), fill=LINE)
    d.rectangle(r(38, 62, 32, 4), fill=LINE)
    d.rectangle(r(38, 72, 20, 4), fill=LINE)
    return img


def main():
    for folder, px in DENSITIES.items():
        d = os.path.join(OUT, folder)
        os.makedirs(d, exist_ok=True)
        img = draw_icon(px)
        img.save(os.path.join(d, "ic_launcher.png"))
        print(f"wrote {folder}/ic_launcher.png ({px}px)")


if __name__ == "__main__":
    main()
