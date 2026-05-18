#!/usr/bin/env python3
"""
Bake CJK font atlases as PNG files for Janus+.
Multiple atlas pages per font at 48px for sharp rendering.
Outputs PNGs (atlas pages) and a BIN (glyph metrics with page index).

Usage: python3 bake_fonts.py
"""

import struct
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

FONTS_DIR = Path("app/src/main/assets/fonts")
OUTPUT_DIR = Path("janusplus/src/main/assets/baked_fonts")
ATLAS_SIZE = 4096
BAKE_SIZE = 48

RANGES = [
    (0x0020, 0x007F),   # Basic Latin
    (0x00A0, 0x00FF),   # Latin-1 Supplement (accented chars for FR)
    (0x2000, 0x206F),   # General Punctuation
    (0x2190, 0x21FF),   # Arrows
    (0x25A0, 0x25FF),   # Geometric Shapes
    (0x2600, 0x26FF),   # Miscellaneous Symbols
    (0x3000, 0x303F),   # CJK Symbols and Punctuation
    (0x3040, 0x309F),   # Hiragana
    (0x30A0, 0x30FF),   # Katakana
    (0x31F0, 0x31FF),   # Katakana Phonetic Extensions
    (0x4E00, 0x9FFF),   # CJK Unified Ideographs
    (0xFF00, 0xFFEF),   # Halfwidth and Fullwidth Forms
]

FONTS = {
    "noto_sans": "NotoSansJP-Regular.ttf",
    "noto_serif": "NotoSerifJP-Regular.ttf",
    "shippori": "ShipporiMincho-Regular.ttf",
}


def collect_codepoints():
    cps = []
    for start, end in RANGES:
        for cp in range(start, end + 1):
            cps.append(cp)
    return cps


def bake_font(font_path, size_px, codepoints):
    font = ImageFont.truetype(str(font_path), size_px)
    pages = []
    all_metrics = {}

    page_idx = 0
    atlas = Image.new("RGBA", (ATLAS_SIZE, ATLAS_SIZE), (0, 0, 0, 0))
    draw = ImageDraw.Draw(atlas)
    cursor_x = 0
    cursor_y = 0
    row_height = 0
    padding = 1

    # White pixel at (0,0) on first page for solid quads
    atlas.putpixel((0, 0), (255, 255, 255, 255))
    atlas.putpixel((1, 0), (255, 255, 255, 255))
    atlas.putpixel((0, 1), (255, 255, 255, 255))
    atlas.putpixel((1, 1), (255, 255, 255, 255))
    cursor_x = 3

    for cp in codepoints:
        ch = chr(cp)
        try:
            bbox = font.getbbox(ch, anchor='ls')
        except Exception:
            continue
        if bbox is None:
            continue

        glyph_w = bbox[2] - bbox[0] + 2
        glyph_h = bbox[3] - bbox[1] + 2
        advance = font.getlength(ch)

        if glyph_w <= 0 or glyph_h <= 0:
            continue
        if advance == 0 and cp > 0x7F:
            continue

        if cursor_x + glyph_w > ATLAS_SIZE:
            cursor_x = 0
            cursor_y += row_height + padding
            row_height = 0

        # Need new page?
        if cursor_y + glyph_h > ATLAS_SIZE:
            pages.append(atlas)
            page_idx += 1
            atlas = Image.new("RGBA", (ATLAS_SIZE, ATLAS_SIZE), (0, 0, 0, 0))
            draw = ImageDraw.Draw(atlas)
            cursor_x = 0
            cursor_y = 0
            row_height = 0

        draw.text((cursor_x - bbox[0] + 1, cursor_y - bbox[1] + 1), ch,
                  font=font, fill=(255, 255, 255, 255), anchor='ls')

        all_metrics[cp] = {
            "page": page_idx,
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

    pages.append(atlas)
    return pages, all_metrics


def main():
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    codepoints = collect_codepoints()
    print(f"Total codepoints: {len(codepoints)}, bake size: {BAKE_SIZE}px")

    for font_key, font_file in FONTS.items():
        font_path = FONTS_DIR / font_file
        if not font_path.exists():
            print(f"SKIP {font_file} — not found")
            continue

        name = f"{font_key}_{BAKE_SIZE}"
        print(f"Baking {name}...", flush=True)

        pages, metrics = bake_font(font_path, BAKE_SIZE, codepoints)

        # Save atlas pages
        total_png_kb = 0
        for i, page in enumerate(pages):
            path = OUTPUT_DIR / f"{name}_p{i}.png"
            page.save(str(path), optimize=True)
            kb = path.stat().st_size / 1024
            total_png_kb += kb
            print(f"  Page {i}: {kb:.0f}KB")

        # Binary metrics: [4B pageCount][4B glyphCount][per glyph: 4B cp, 4B page, 8x4B floats]
        bin_path = OUTPUT_DIR / f"{name}.bin"
        with open(bin_path, "wb") as f:
            f.write(struct.pack("<I", len(pages)))
            f.write(struct.pack("<I", len(metrics)))
            for cp, m in metrics.items():
                f.write(struct.pack("<I", cp))
                f.write(struct.pack("<I", m["page"]))
                f.write(struct.pack("<8f",
                    m["u0"], m["v0"], m["u1"], m["v1"],
                    m["w"], m["h"], m["advance"], m["ascent"]))

        bin_kb = bin_path.stat().st_size / 1024
        print(f"  {len(metrics)} glyphs across {len(pages)} pages, metrics={bin_kb:.0f}KB, atlas={total_png_kb:.0f}KB")

    # Clean up old single-page files
    for old in OUTPUT_DIR.glob("*_24*"):
        print(f"  Removing old: {old.name}")
        old.unlink()

    total = sum(f.stat().st_size for f in OUTPUT_DIR.iterdir())
    print(f"\nTotal baked: {total / 1024 / 1024:.1f}MB in {OUTPUT_DIR}")


if __name__ == "__main__":
    main()
