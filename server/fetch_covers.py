#!/usr/bin/env python3
"""
Fetch anime cover art and banners from AniList API.
"""
import json
import os
import requests
from config import COVERS_DIR, ANILIST_API, LIBRARY_FILE


def fetch_anilist(search_title):
    """Query AniList for anime metadata."""
    query = """
    query ($search: String) {
        Media(search: $search, type: ANIME) {
            title { romaji english native }
            coverImage { extraLarge large medium }
            bannerImage
            description
            genres
            averageScore
            episodes
        }
    }
    """
    resp = requests.post(ANILIST_API, json={"query": query, "variables": {"search": search_title}})
    if resp.status_code != 200:
        print(f"  AniList error: {resp.status_code}")
        return None
    data = resp.json().get("data", {}).get("Media")
    return data


def download_image(url, filepath):
    """Download image if not already cached."""
    if os.path.exists(filepath) and os.path.getsize(filepath) > 1000:
        return True
    try:
        resp = requests.get(url, timeout=15)
        if resp.status_code == 200 and len(resp.content) > 1000:
            with open(filepath, "wb") as f:
                f.write(resp.content)
            return True
    except Exception as e:
        print(f"  Download failed: {e}")
    return False


def fetch_all():
    """Fetch covers for all series in the library."""
    os.makedirs(COVERS_DIR, exist_ok=True)

    with open(LIBRARY_FILE) as f:
        library = json.load(f)

    for item in library["items"]:
        title = item["title_en"]
        series_id = item["id"]
        print(f"\n=== {title} ===")

        data = fetch_anilist(title)
        if data is None:
            print("  Not found on AniList")
            continue

        print(f"  Found: {data['title']['romaji']} ({data['title'].get('native', '')})")

        # Cover
        cover_url = data["coverImage"].get("extraLarge") or data["coverImage"].get("large")
        if cover_url:
            cover_path = os.path.join(COVERS_DIR, f"{series_id}.jpg")
            if download_image(cover_url, cover_path):
                print(f"  Cover: {os.path.getsize(cover_path) // 1024}KB")

        # Banner
        banner_url = data.get("bannerImage")
        if banner_url:
            banner_path = os.path.join(COVERS_DIR, f"{series_id}-banner.jpg")
            if download_image(banner_url, banner_path):
                print(f"  Banner: {os.path.getsize(banner_path) // 1024}KB")

        # Save AniList metadata
        meta = {
            "anilist_title": data["title"],
            "genres": data.get("genres", []),
            "score": data.get("averageScore"),
            "total_episodes": data.get("episodes"),
            "description": data.get("description", ""),
        }
        meta_path = os.path.join(COVERS_DIR, f"{series_id}-meta.json")
        with open(meta_path, "w") as f:
            json.dump(meta, f, indent=2, ensure_ascii=False)
        print(f"  Metadata saved")


if __name__ == "__main__":
    fetch_all()
