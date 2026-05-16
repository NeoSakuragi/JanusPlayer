#!/usr/bin/env python3
"""
Bake CJK font atlas pages. Each page is a 4096x4096 PNG.
Full CJK at all sizes, split across multiple pages.
"""

import struct
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

FONTS_DIR = Path("app/src/main/assets/fonts")
OUTPUT_DIR = Path("janusplus/src/main/assets/baked_fonts")
ATLAS_SIZE = 4096

RANGES = [
    (0x0020, 0x007F), (0x00A0, 0x00FF), (0x2000, 0x206F),
    (0x2190, 0x21FF), (0x25A0, 0x25FF), (0x2600, 0x26FF),
    (0x3000, 0x303F), (0x3040, 0x309F), (0x30A0, 0x30FF),
    (0x31F0, 0x31FF), (0x4E00, 0x9FFF), (0xFF00, 0xFFEF),
]

FONTS = {"noto_sans": "NotoSansJP-Regular.ttf"}
SIZES = [20, 28, 36]


def collect_codepoints():
    cps = []
    for start, end in RANGES:
        cps.extend(range(start, end + 1))
    return cps


def bake_pages(font_path, codepoints):
    pages = []
    all_metrics = {}
    page_idx = 0
    cursor_x = 0
    cursor_y = 0
    row_height = 0
    padding = 1

    atlas = Image.new("RGBA", (ATLAS_SIZE, ATLAS_SIZE), (0, 0, 0, 0))
    draw = ImageDraw.Draw(atlas)

    # White pixel on page 0
    for x in range(4):
        for y in range(4):
            atlas.putpixel((x, y), (255, 255, 255, 255))
    cursor_x = 5

    for size_px in SIZES:
        font = ImageFont.truetype(str(font_path), size_px)
        count = 0

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
            if advance == 0 and cp > 0x7F:
                continue

            if cursor_x + glyph_w > ATLAS_SIZE:
                cursor_x = 0
                cursor_y += row_height + padding
                row_height = 0

            if cursor_y + glyph_h > ATLAS_SIZE:
                # Page full — save and start new page
                pages.append(atlas)
                page_idx += 1
                atlas = Image.new("RGBA", (ATLAS_SIZE, ATLAS_SIZE), (0, 0, 0, 0))
                draw = ImageDraw.Draw(atlas)
                cursor_x = 0
                cursor_y = 0
                row_height = 0

            draw.text(
                (cursor_x - bbox[0] + 1, cursor_y - bbox[1] + 1),
                ch, font=font, fill=(255, 255, 255, 255)
            )

            all_metrics[(cp, size_px)] = {
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
            count += 1

        print(f"  Size {size_px}px: {count} glyphs (page {page_idx})")

    pages.append(atlas)
    return pages, all_metrics


def main():
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    # Clean old files
    for f in OUTPUT_DIR.glob("*"):
        f.unlink()

    codepoints = collect_codepoints()
    print(f"Codepoints: {len(codepoints)}")

    for font_key, font_file in FONTS.items():
        font_path = FONTS_DIR / font_file
        if not font_path.exists():
            print(f"SKIP {font_file}")
            continue

        print(f"Baking {font_key}...")
        pages, metrics = bake_pages(font_path, codepoints)

        # Save pages
        for i, page in enumerate(pages):
            path = OUTPUT_DIR / f"{font_key}_page{i}.png"
            page.save(str(path), optimize=True)
            print(f"  Page {i}: {path.stat().st_size / 1024:.0f}KB")

        # Compute per-size line metrics
        size_metrics = {}
        for sz in SIZES:
            fnt = ImageFont.truetype(str(font_path), sz)
            asc, desc = fnt.getmetrics()
            size_metrics[sz] = (float(asc), float(desc))
            print(f"  Size {sz}px: ascent={asc} descent={desc} lineH={asc+desc}")

        # Save binary: [4B page_count][4B glyph_count][4B size_count]
        #   [per size: 4B sz, 2x4B floats (ascent, descent)]
        #   [per glyph: 4B cp, 4B sz, 4B page, 8x4B floats]
        bin_path = OUTPUT_DIR / f"{font_key}.bin"
        with open(bin_path, "wb") as f:
            f.write(struct.pack("<III", len(pages), len(metrics), len(size_metrics)))
            for sz, (asc, desc) in sorted(size_metrics.items()):
                f.write(struct.pack("<I2f", sz, asc, desc))
            for (cp, sz), m in metrics.items():
                f.write(struct.pack("<III", cp, sz, m["page"]))
                f.write(struct.pack("<8f",
                    m["u0"], m["v0"], m["u1"], m["v1"],
                    m["w"], m["h"], m["advance"], m["ascent"]))

        print(f"  Metrics: {bin_path.stat().st_size / 1024:.0f}KB")
        print(f"  Total: {len(metrics)} glyphs across {len(pages)} pages")


if __name__ == "__main__":
    main()
