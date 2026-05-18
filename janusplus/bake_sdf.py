#!/usr/bin/env python3
"""
Bake SDF (Signed Distance Field) font atlases for Janus+.
Each glyph rendered at high res, SDF computed, packed into grayscale atlas pages.
Single-channel output — 1/4 memory of RGBA. Sharp at any size.

Usage: python3 bake_sdf.py
"""

import struct
import numpy as np
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
from scipy.ndimage import distance_transform_edt

FONTS_DIR = Path("app/src/main/assets/fonts")
OUTPUT_DIR = Path("janusplus/src/main/assets/baked_fonts")
ATLAS_SIZE = 2048

RENDER_SIZE = 96        # render glyph at this size for SDF computation
CELL_SIZE = 32          # output cell size in atlas (includes SDF spread)
SDF_SPREAD = 4          # distance field spread in output pixels
PADDING = 2             # padding between cells in atlas

RANGES = [
    (0x0020, 0x007F),   # Basic Latin
    (0x00A0, 0x00FF),   # Latin-1 Supplement
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


def compute_sdf(glyph_img, spread_pixels):
    """Compute SDF from a binary glyph image. Returns float array in [0, 1]."""
    arr = np.array(glyph_img, dtype=np.float32) / 255.0
    inside = arr > 0.5
    outside = ~inside

    dist_outside = distance_transform_edt(outside) if outside.any() else np.zeros_like(arr)
    dist_inside = distance_transform_edt(inside) if inside.any() else np.zeros_like(arr)

    # Signed distance: positive inside, negative outside
    sdf = dist_inside - dist_outside
    # Normalize to [0, 1] with spread
    sdf = sdf / spread_pixels * 0.5 + 0.5
    sdf = np.clip(sdf, 0.0, 1.0)
    return sdf


def bake_font_sdf(font_path, codepoints):
    font_render = ImageFont.truetype(str(font_path), RENDER_SIZE)
    font_metrics = ImageFont.truetype(str(font_path), CELL_SIZE)

    scale = CELL_SIZE / RENDER_SIZE
    spread_render = SDF_SPREAD / scale  # spread in render-space pixels

    pages = []
    all_metrics = {}
    page_idx = 0

    atlas = Image.new("L", (ATLAS_SIZE, ATLAS_SIZE), 0)
    cursor_x = 0
    cursor_y = 0
    row_height = 0

    # White pixel at (0,0) for solid quads (SDF value = 1.0 = fully inside)
    atlas.putpixel((0, 0), 255)
    atlas.putpixel((1, 0), 255)
    atlas.putpixel((0, 1), 255)
    atlas.putpixel((1, 1), 255)
    cursor_x = 4

    for cp in codepoints:
        ch = chr(cp)
        try:
            bbox_render = font_render.getbbox(ch, anchor='ls')
            bbox_cell = font_metrics.getbbox(ch, anchor='ls')
        except Exception:
            continue
        if bbox_render is None or bbox_cell is None:
            continue

        # Render-space dimensions
        rw = bbox_render[2] - bbox_render[0]
        rh = bbox_render[3] - bbox_render[1]
        if rw <= 0 or rh <= 0:
            continue

        advance_render = font_render.getlength(ch)
        advance_cell = font_metrics.getlength(ch)
        if advance_render == 0 and cp > 0x7F:
            continue

        # Add spread margin to render
        margin = int(spread_render + 2)
        render_w = rw + margin * 2
        render_h = rh + margin * 2

        # Render glyph at high res
        glyph_img = Image.new("L", (render_w, render_h), 0)
        glyph_draw = ImageDraw.Draw(glyph_img)
        glyph_draw.text((margin - bbox_render[0], margin - bbox_render[1]),
                        ch, font=font_render, fill=255, anchor='ls')

        # Compute SDF
        sdf = compute_sdf(glyph_img, spread_render)

        # Downscale SDF to cell size
        cell_w = int(render_w * scale) + 1
        cell_h = int(render_h * scale) + 1
        sdf_img = Image.fromarray((sdf * 255).astype(np.uint8), mode='L')
        sdf_cell = sdf_img.resize((cell_w, cell_h), Image.BILINEAR)

        # Check atlas space
        if cursor_x + cell_w + PADDING > ATLAS_SIZE:
            cursor_x = 0
            cursor_y += row_height + PADDING
            row_height = 0

        if cursor_y + cell_h + PADDING > ATLAS_SIZE:
            pages.append(atlas)
            page_idx += 1
            atlas = Image.new("L", (ATLAS_SIZE, ATLAS_SIZE), 0)
            cursor_x = 0
            cursor_y = 0
            row_height = 0

        # Paste into atlas
        atlas.paste(sdf_cell, (cursor_x, cursor_y))

        # Metrics in cell-space (what the renderer uses)
        ascent_cell = (-bbox_cell[1]) + int(SDF_SPREAD)

        all_metrics[cp] = {
            "page": page_idx,
            "u0": cursor_x / ATLAS_SIZE,
            "v0": cursor_y / ATLAS_SIZE,
            "u1": (cursor_x + cell_w) / ATLAS_SIZE,
            "v1": (cursor_y + cell_h) / ATLAS_SIZE,
            "w": cell_w,
            "h": cell_h,
            "advance": advance_cell,
            "ascent": ascent_cell,
        }

        cursor_x += cell_w + PADDING
        if cell_h > row_height:
            row_height = cell_h

    pages.append(atlas)
    return pages, all_metrics


def main():
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    codepoints = collect_codepoints()
    print(f"Total codepoints: {len(codepoints)}, render: {RENDER_SIZE}px, cell: {CELL_SIZE}px, spread: {SDF_SPREAD}px")

    for font_key, font_file in FONTS.items():
        font_path = FONTS_DIR / font_file
        if not font_path.exists():
            print(f"SKIP {font_file} — not found")
            continue

        name = f"{font_key}_sdf"
        print(f"Baking {name}...", flush=True)

        pages, metrics = bake_font_sdf(font_path, codepoints)

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

    total = sum(f.stat().st_size for f in OUTPUT_DIR.iterdir() if "sdf" in f.name)
    print(f"\nTotal SDF: {total / 1024 / 1024:.1f}MB")


if __name__ == "__main__":
    main()
