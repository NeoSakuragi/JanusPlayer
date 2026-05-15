#!/usr/bin/env python3
"""
Bake CJK font atlases as PNG files for Janus+.
Each atlas is 2048x2048 RGBA, containing glyphs rendered at a specific size.
Outputs a PNG (the atlas) and a JSON (glyph metrics: UV rects, advance, ascent).

Usage: python3 bake_fonts.py
"""

import json
import struct
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

FONTS_DIR = Path("app/src/main/assets/fonts")
OUTPUT_DIR = Path("janusplus/src/main/assets/baked_fonts")
ATLAS_SIZE = 2048

# Character ranges to bake
RANGES = [
    (0x0020, 0x007F),   # Basic Latin
    (0x00A0, 0x00FF),   # Latin-1 Supplement (accented chars for FR)
    (0x2000, 0x206F),   # General Punctuation
    (0x2190, 0x21FF),   # Arrows
    (0x25A0, 0x25FF),   # Geometric Shapes
    (0x2600, 0x26FF),   # Miscellaneous Symbols (gear, arrows)
    (0x3000, 0x303F),   # CJK Symbols and Punctuation
    (0x3040, 0x309F),   # Hiragana
    (0x30A0, 0x30FF),   # Katakana
    (0x31F0, 0x31FF),   # Katakana Phonetic Extensions
    (0x4E00, 0x9FFF),   # CJK Unified Ideographs (20,992 chars)
    (0xFF00, 0xFFEF),   # Halfwidth and Fullwidth Forms
]

FONTS = {
    "noto_sans": "NotoSansJP-Regular.ttf",
    "noto_serif": "NotoSerifJP-Regular.ttf",
    "shippori": "ShipporiMincho-Regular.ttf",
}

SIZES = [20, 28, 36]  # px sizes matching sp values at ~2x density


def collect_codepoints():
    cps = []
    for start, end in RANGES:
        for cp in range(start, end + 1):
            cps.append(cp)
    return cps


def bake_atlas(font_path, size_px, codepoints):
    font = ImageFont.truetype(str(font_path), size_px)
    atlas = Image.new("RGBA", (ATLAS_SIZE, ATLAS_SIZE), (0, 0, 0, 0))
    draw = ImageDraw.Draw(atlas)

    metrics = {}
    cursor_x = 0
    cursor_y = 0
    row_height = 0
    padding = 1

    # White pixel at (0,0) for solid quads
    atlas.putpixel((0, 0), (255, 255, 255, 255))
    atlas.putpixel((1, 0), (255, 255, 255, 255))
    atlas.putpixel((0, 1), (255, 255, 255, 255))
    atlas.putpixel((1, 1), (255, 255, 255, 255))
    cursor_x = 3

    baked_count = 0

    for cp in codepoints:
        ch = chr(cp)
        try:
            bbox = font.getbbox(ch)
        except Exception:
            continue
        if bbox is None:
            continue

        glyph_w = bbox[2] - bbox[0] + 2
        glyph_h = bbox[3] - bbox[1] + 2
        advance = font.getlength(ch)

        if glyph_w <= 0 or glyph_h <= 0:
            continue

        # Check if font has this glyph (skip missing)
        if advance == 0 and cp > 0x7F:
            continue

        if cursor_x + glyph_w > ATLAS_SIZE:
            cursor_x = 0
            cursor_y += row_height + padding
            row_height = 0

        if cursor_y + glyph_h > ATLAS_SIZE:
            break  # Atlas full

        draw.text((cursor_x - bbox[0] + 1, cursor_y - bbox[1] + 1), ch, font=font, fill=(255, 255, 255, 255))

        metrics[cp] = {
            "u0": cursor_x / ATLAS_SIZE,
            "v0": cursor_y / ATLAS_SIZE,
            "u1": (cursor_x + glyph_w) / ATLAS_SIZE,
            "v1": (cursor_y + glyph_h) / ATLAS_SIZE,
            "w": glyph_w,
            "h": glyph_h,
            "advance": advance,
            "ascent": -bbox[1] + 1,
        }

        cursor_x += glyph_w + padding
        if glyph_h > row_height:
            row_height = glyph_h
        baked_count += 1

    return atlas, metrics, baked_count


def main():
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    codepoints = collect_codepoints()
    print(f"Total codepoints to bake: {len(codepoints)}")

    for font_key, font_file in FONTS.items():
        font_path = FONTS_DIR / font_file
        if not font_path.exists():
            print(f"SKIP {font_file} — not found at {font_path}")
            continue

        for size_px in SIZES:
            name = f"{font_key}_{size_px}"
            print(f"Baking {name}...", end=" ", flush=True)

            atlas, metrics, count = bake_atlas(font_path, size_px, codepoints)

            atlas_path = OUTPUT_DIR / f"{name}.png"
            atlas.save(str(atlas_path), optimize=True)
            atlas_size_kb = atlas_path.stat().st_size / 1024

            # Binary metrics file: [4B count][per glyph: 4B codepoint, 8x4B floats]
            bin_path = OUTPUT_DIR / f"{name}.bin"
            with open(bin_path, "wb") as f:
                f.write(struct.pack("<I", len(metrics)))
                for cp, m in metrics.items():
                    f.write(struct.pack("<I", cp))
                    f.write(struct.pack("<8f",
                        m["u0"], m["v0"], m["u1"], m["v1"],
                        m["w"], m["h"], m["advance"], m["ascent"]))

            bin_kb = bin_path.stat().st_size / 1024
            print(f"{count} glyphs, atlas={atlas_size_kb:.0f}KB, metrics={bin_kb:.0f}KB")

    # Summary
    total = sum(f.stat().st_size for f in OUTPUT_DIR.iterdir())
    print(f"\nTotal baked: {total / 1024 / 1024:.1f}MB in {OUTPUT_DIR}")


if __name__ == "__main__":
    main()
