#!/usr/bin/env python3
"""
Janus Library Management

Usage:
  python3 manage.py build     — Rebuild library from video files
  python3 manage.py covers    — Fetch/update cover art from AniList
  python3 manage.py status    — Show library status
  python3 manage.py serve     — Start the server
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from config import DATA_DIR, LIBRARY_FILE, VIDEOS_DIR, SUBS_DIR, COVERS_DIR


def cmd_build():
    """Rebuild library from video files."""
    from build_library import main as build_main
    build_main()


def cmd_covers():
    """Fetch cover art from AniList."""
    from fetch_covers import fetch_all
    fetch_all()


def cmd_status():
    """Show library status."""
    if not os.path.exists(LIBRARY_FILE):
        print("No library.json found. Run: python3 manage.py build")
        return

    with open(LIBRARY_FILE) as f:
        lib = json.load(f)

    print(f"Janus Library v{lib.get('version', '?')}")
    print(f"Data: {DATA_DIR}\n")

    for item in lib["items"]:
        ja = sum(1 for ep in item["episodes"] if ep.get("has_ja_subs"))
        fr = sum(1 for ep in item["episodes"] if ep.get("has_fr_subs"))
        en = sum(1 for ep in item["episodes"] if ep.get("has_en_subs"))
        cover = "✓" if os.path.exists(os.path.join(COVERS_DIR, f"{item['id']}.jpg")) else "✗"

        print(f"  {item['title_en']} ({item['title_ja']})")
        print(f"    Type: {item['type']}")
        print(f"    Episodes: {item['episode_count']}")
        print(f"    Subs: JA={ja} FR={fr} EN={en}")
        print(f"    Cover: {cover}")
        print()

    total_size = 0
    for root, dirs, files in os.walk(VIDEOS_DIR):
        for f in files:
            total_size += os.path.getsize(os.path.join(root, f))
    print(f"Total video size: {total_size / (1024**3):.1f} GB")


def cmd_serve():
    """Start the server."""
    from server import main as server_main
    server_main()


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(0)

    cmd = sys.argv[1]
    if cmd == "build": cmd_build()
    elif cmd == "covers": cmd_covers()
    elif cmd == "status": cmd_status()
    elif cmd == "serve": cmd_serve()
    else:
        print(f"Unknown command: {cmd}")
        print(__doc__)
