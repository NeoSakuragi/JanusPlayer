#!/usr/bin/env python3
"""
Build ETC2-compressed page blobs for Janus+.

For each series+season:
  1. Pack episode thumbnails into a single atlas PNG
  2. Compress atlas to ETC2 RGBA via etcpak
  3. Build a binary page blob: metadata JSON + banner ETC2 + atlas ETC2

For banners:
  1. Resize to multiples of 4
  2. Compress to ETC2 RGBA

Output: /data/janus/pages/{item_id}_s{season}.bin
"""

import json
import math
import os
import sqlite3
import struct
import subprocess
import tempfile
from pathlib import Path

from PIL import Image

DATA_DIR = Path("/data/janus")
DB_PATH = DATA_DIR / "janus.db"
PAGES_DIR = DATA_DIR / "pages"
THUMBS_DIR = DATA_DIR / "thumbs"
COVERS_DIR = DATA_DIR / "covers"

THUMB_W = 400
THUMB_H = 224
PVR_HEADER_SIZE = 52


def round_up_4(n):
    return (n + 3) & ~3


def compress_etc2(png_path):
    """Compress PNG to ETC2 RGBA, return raw ETC2 bytes."""
    with tempfile.NamedTemporaryFile(suffix=".pvr", delete=False) as tmp:
        pvr_path = tmp.name
    try:
        subprocess.run(
            ["etcpak", str(png_path), pvr_path, "-c", "etc2_rgba"],
            check=True, capture_output=True,
        )
        with open(pvr_path, "rb") as f:
            data = f.read()
        return data[PVR_HEADER_SIZE:]
    finally:
        os.unlink(pvr_path)


def build_atlas_png(thumb_paths, output_path):
    """Pack thumbnails into a single atlas PNG. Returns (width, height, cols)."""
    count = len(thumb_paths)
    cols = math.ceil(math.sqrt(count))
    rows = math.ceil(count / cols)
    # Ensure atlas fits within 4096x4096 texture
    max_cols = 4096 // THUMB_W
    if cols > max_cols:
        cols = max_cols
        rows = math.ceil(count / cols)
    atlas_w = round_up_4(cols * THUMB_W)
    atlas_h = round_up_4(rows * THUMB_H)
    if atlas_h > 4096:
        print(f"  WARN: atlas {atlas_w}x{atlas_h} exceeds 4096 height!")

    atlas = Image.new("RGBA", (atlas_w, atlas_h), (0, 0, 0, 255))

    for i, path in enumerate(thumb_paths):
        col = i % cols
        row = i // cols
        x = col * THUMB_W
        y = row * THUMB_H
        try:
            img = Image.open(path).convert("RGBA")
            img = img.resize((THUMB_W, THUMB_H), Image.LANCZOS)
            atlas.paste(img, (x, y))
        except Exception as e:
            print(f"  WARN: {path}: {e}")

    atlas.save(str(output_path), "PNG")
    return atlas_w, atlas_h, cols


def compress_banner(item_id):
    """Find and compress the banner image. Returns (w, h, etc2_bytes) or None."""
    banner_path = COVERS_DIR / f"{item_id}-banner.jpg"
    if not banner_path.exists():
        banner_path = COVERS_DIR / f"{item_id}-banner.png"
    if not banner_path.exists():
        return None

    img = Image.open(banner_path).convert("RGBA")
    w = round_up_4(img.width)
    h = round_up_4(img.height)
    if w != img.width or h != img.height:
        resized = Image.new("RGBA", (w, h), (0, 0, 0, 255))
        resized.paste(img, (0, 0))
        img = resized

    with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as tmp:
        png_path = tmp.name
    try:
        img.save(png_path, "PNG")
        etc2_data = compress_etc2(png_path)
        return w, h, etc2_data
    finally:
        os.unlink(png_path)


def build_page_blob(db, item_id, season):
    """Build a single page blob for item+season."""
    # Get item metadata
    item = db.execute(
        "SELECT title_en, title_ja, synopsis_en, synopsis_ja, synopsis_fr, episode_count, type FROM items WHERE id=?",
        (item_id,),
    ).fetchone()
    if not item:
        return None

    # Get episodes for this season
    episodes = db.execute(
        "SELECT episode, title_en, duration_sec FROM episodes WHERE item_id=? AND season=? ORDER BY episode",
        (item_id, season),
    ).fetchall()
    if not episodes:
        return None

    meta = {
        "id": item_id,
        "titleEn": item[0],
        "titleJa": item[1],
        "synopsisEn": item[2],
        "synopsisJa": item[3],
        "synopsisFr": item[4],
        "episodeCount": item[5],
        "type": item[6],
        "season": season,
        "episodes": [
            {
                "episode": ep[0],
                "titleEn": ep[1] or "",
                "durationSec": ep[2] or 0,
            }
            for ep in episodes
        ],
    }
    meta_json = json.dumps(meta, ensure_ascii=False).encode("utf-8")

    # Build thumbnail atlas
    thumb_dir = THUMBS_DIR / item_id
    thumb_paths = []
    for ep in episodes:
        ep_num = ep[0]
        found = False
        for fmt in (f"ep{ep_num}", f"ep{ep_num:02d}", f"ep{ep_num:03d}"):
            for ext in (".jpg", ".png", ".webp"):
                p = thumb_dir / f"{fmt}{ext}"
                if p.exists():
                    thumb_paths.append(p)
                    found = True
                    break
            if found:
                break

    atlas_etc2 = b""
    atlas_w = atlas_h = atlas_cols = 0
    if thumb_paths:
        with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as tmp:
            atlas_png = tmp.name
        try:
            atlas_w, atlas_h, atlas_cols = build_atlas_png(thumb_paths, atlas_png)
            atlas_etc2 = compress_etc2(atlas_png)
            print(f"  Atlas: {atlas_w}x{atlas_h}, {atlas_cols} cols, {len(atlas_etc2)} bytes ETC2")
        finally:
            os.unlink(atlas_png)

    # Compress banner
    banner_w = banner_h = 0
    banner_etc2 = b""
    banner = compress_banner(item_id)
    if banner:
        banner_w, banner_h, banner_etc2 = banner
        print(f"  Banner: {banner_w}x{banner_h}, {len(banner_etc2)} bytes ETC2")

    # Pack binary blob
    # Format: [4B meta_len][meta JSON]
    #         [4B banner_w][4B banner_h][4B banner_etc2_len][banner ETC2 data]
    #         [4B atlas_w][4B atlas_h][4B atlas_cols][4B thumb_count][4B atlas_etc2_len][atlas ETC2 data]
    blob = bytearray()
    blob += struct.pack("<I", len(meta_json))
    blob += meta_json
    blob += struct.pack("<III", banner_w, banner_h, len(banner_etc2))
    blob += banner_etc2
    blob += struct.pack("<IIIII", atlas_w, atlas_h, atlas_cols, len(thumb_paths), len(atlas_etc2))
    blob += atlas_etc2

    return bytes(blob)


def main():
    PAGES_DIR.mkdir(parents=True, exist_ok=True)

    db = sqlite3.connect(str(DB_PATH))

    # Get all items and their seasons
    seasons = db.execute(
        "SELECT DISTINCT item_id, season FROM episodes ORDER BY item_id, season"
    ).fetchall()

    total_size = 0
    for item_id, season in seasons:
        print(f"\n{item_id} season {season}:")
        blob = build_page_blob(db, item_id, season)
        if blob is None:
            print("  SKIP — no data")
            continue

        out_path = PAGES_DIR / f"{item_id}_s{season}.bin"
        with open(out_path, "wb") as f:
            f.write(blob)
        size_kb = len(blob) / 1024
        total_size += len(blob)
        print(f"  → {out_path.name} ({size_kb:.0f} KB)")

    db.close()
    print(f"\nTotal: {total_size / 1024 / 1024:.1f} MB in {PAGES_DIR}")


if __name__ == "__main__":
    main()
