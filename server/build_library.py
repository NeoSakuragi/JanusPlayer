#!/usr/bin/env python3
"""
Build the Janus library JSON from video files.
Extracts metadata, subtitles, and generates library.json.
"""
import json
import os
import subprocess
import re
import sys
import requests
import time

DATA_DIR = "/data/janus"
VIDEOS_DIR = os.path.join(DATA_DIR, "videos")
SUBS_DIR = os.path.join(DATA_DIR, "subs")
THUMBS_DIR = os.path.join(DATA_DIR, "thumbs")
LIBRARY_FILE = os.path.join(DATA_DIR, "library.json")
TMDB_API_KEY = "86d0df9095f6e5d35ba68f7660189c27"
TMDB_IMG_BASE = "https://image.tmdb.org/t/p/w400"

SERIES = [
    {
        "id": "saint-seiya",
        "title_en": "Saint Seiya",
        "title_ja": "聖闘士星矢",
        "type": "TV_SERIES",
        "video_dir": os.path.join(VIDEOS_DIR, "saint-seiya"),
        "subs_dir": os.path.join(SUBS_DIR, "saint-seiya"),
        "file_pattern": r"Saint Seiya - S(\d+)E(\d+)\.mkv",
        "ja_sub_stream": "jpn",
        "tmdb_id": 42444,
        "tmdb_season": 1,
    },
    {
        "id": "maison-ikkoku",
        "title_en": "Maison Ikkoku",
        "title_ja": "めぞん一刻",
        "type": "TV_SERIES",
        "video_dir": os.path.join(VIDEOS_DIR, "maison-ikkoku"),
        "subs_dir": os.path.join(SUBS_DIR, "maison-ikkoku"),
        "file_pattern": r"Maison Ikkoku - (\d+)",
        "ja_sub_stream": None,
        "tmdb_id": 43018,
        "tmdb_season": 1,
    },
    {
        "id": "dbz",
        "title_en": "Dragon Ball Z",
        "title_ja": "ドラゴンボールZ",
        "type": "TV_SERIES",
        "video_dir": os.path.join(VIDEOS_DIR, "dbz"),
        "subs_dir": os.path.join(SUBS_DIR, "dbz"),
        "file_pattern": r"Dragon Ball Z - (\d+)",
        "ja_sub_stream": None,
        "tmdb_id": 12971,
        "tmdb_season": 7,
        "tmdb_ep_offset": -194,
    },
]

MOVIES = [
    {
        "id": "the-running-man",
        "title_en": "The Running Man",
        "title_ja": "バトルランナー",
        "type": "MOVIE",
        "video_dir": os.path.join(VIDEOS_DIR, "the-running-man"),
        "subs_dir": os.path.join(SUBS_DIR, "the-running-man"),
        "filename": "the-running-man.mkv",
        "tmdb_id": 865,
    },
]


def get_video_info(filepath):
    """Get duration and subtitle tracks via ffprobe."""
    cmd = [
        "ffprobe", "-v", "quiet", "-print_format", "json",
        "-show_format", "-show_streams", filepath
    ]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        return None
    data = json.loads(result.stdout)

    duration = float(data.get("format", {}).get("duration", 0))

    audio_tracks = []
    sub_tracks = []
    ja_sub_index = -1
    fr_sub_index = -1
    en_sub_index = -1

    for stream in data.get("streams", []):
        codec_type = stream.get("codec_type")
        index = stream.get("index", 0)
        lang = stream.get("tags", {}).get("language", "")
        title = stream.get("tags", {}).get("title", "")
        codec = stream.get("codec_name", "")

        if codec_type == "audio":
            audio_tracks.append({
                "index": index,
                "lang": lang,
                "title": title,
                "codec": codec,
            })
        elif codec_type == "subtitle":
            is_text = codec in ("subrip", "srt", "ass", "ssa", "webvtt", "mov_text")
            sub_tracks.append({
                "index": index,
                "lang": lang,
                "title": title,
                "codec": codec,
                "is_text": is_text,
            })
            if lang == "jpn" and is_text and ja_sub_index < 0:
                ja_sub_index = index
            if lang in ("fre", "fra") and is_text and fr_sub_index < 0:
                fr_sub_index = index
            if lang == "eng" and is_text and en_sub_index < 0:
                en_sub_index = index

    return {
        "duration": duration,
        "audio_tracks": audio_tracks,
        "sub_tracks": sub_tracks,
        "ja_sub_index": ja_sub_index,
        "fr_sub_index": fr_sub_index,
        "en_sub_index": en_sub_index,
    }


def strip_srt_tags(filepath):
    """Strip HTML and ASS override tags from an SRT file."""
    with open(filepath, encoding="utf-8", errors="replace") as f:
        content = f.read()
    cleaned = re.sub(r"<[^>]+>", "", content)
    cleaned = re.sub(r"\{[^}]+\}", "", cleaned)
    with open(filepath, "w", encoding="utf-8") as f:
        f.write(cleaned)


def extract_srt(video_path, stream_index, output_path):
    """Extract a subtitle stream as SRT, stripping markup tags."""
    if os.path.exists(output_path) and os.path.getsize(output_path) > 100:
        return True  # already extracted
    cmd = [
        "ffmpeg", "-v", "quiet", "-y",
        "-i", video_path,
        "-map", f"0:{stream_index}",
        output_path
    ]
    result = subprocess.run(cmd, capture_output=True)
    if result.returncode == 0 and os.path.exists(output_path):
        strip_srt_tags(output_path)
    return result.returncode == 0


TMDB_CACHE_FILE = os.path.join(DATA_DIR, "tmdb_cache.json")
_tmdb_cache = None

def _load_tmdb_cache():
    global _tmdb_cache
    if _tmdb_cache is None:
        if os.path.exists(TMDB_CACHE_FILE):
            with open(TMDB_CACHE_FILE) as f:
                _tmdb_cache = json.load(f)
        else:
            _tmdb_cache = {}
    return _tmdb_cache

def _save_tmdb_cache():
    if _tmdb_cache is not None:
        with open(TMDB_CACHE_FILE, "w") as f:
            json.dump(_tmdb_cache, f, ensure_ascii=False)

def fetch_tmdb_episode(tmdb_id, season, ep_num):
    """Fetch episode synopsis in EN/FR/JA and thumbnail from TMDB. Cached."""
    cache = _load_tmdb_cache()
    key = f"tv_{tmdb_id}_s{season}_e{ep_num}"
    if key in cache:
        return cache[key]
    result = {"synopsis_en": "", "synopsis_fr": "", "synopsis_ja": "", "tmdb_thumb": None, "tmdb_title_en": ""}
    try:
        for lang, lkey in [("en", "synopsis_en"), ("fr", "synopsis_fr"), ("ja", "synopsis_ja")]:
            url = f"https://api.themoviedb.org/3/tv/{tmdb_id}/season/{season}/episode/{ep_num}?api_key={TMDB_API_KEY}&language={lang}"
            resp = requests.get(url, timeout=10)
            if resp.status_code == 200:
                data = resp.json()
                result[lkey] = data.get("overview", "")
                if lang == "en":
                    result["tmdb_title_en"] = data.get("name", "")
                    still = data.get("still_path")
                    if still:
                        result["tmdb_thumb"] = still
            time.sleep(0.03)
    except Exception as e:
        print(f"    TMDB error: {e}")
    if result["synopsis_en"]:
        cache[key] = result
        _save_tmdb_cache()
    return result


def fetch_tmdb_movie(tmdb_id):
    """Fetch movie synopsis in EN/FR/JA from TMDB. Cached."""
    cache = _load_tmdb_cache()
    key = f"movie_{tmdb_id}"
    if key in cache:
        return cache[key]
    result = {"synopsis_en": "", "synopsis_fr": "", "synopsis_ja": "", "tmdb_backdrop": None}
    try:
        for lang, lkey in [("en", "synopsis_en"), ("fr", "synopsis_fr"), ("ja", "synopsis_ja")]:
            url = f"https://api.themoviedb.org/3/movie/{tmdb_id}?api_key={TMDB_API_KEY}&language={lang}"
            resp = requests.get(url, timeout=10)
            if resp.status_code == 200:
                data = resp.json()
                result[lkey] = data.get("overview", "")
                if lang == "en":
                    result["tmdb_backdrop"] = data.get("backdrop_path")
            time.sleep(0.03)
    except Exception as e:
        print(f"    TMDB error: {e}")
    if result["synopsis_en"]:
        cache[key] = result
        _save_tmdb_cache()
    return result


def download_thumb(url, filepath):
    """Download a thumbnail if not already cached."""
    if os.path.exists(filepath) and os.path.getsize(filepath) > 1000:
        return True
    try:
        resp = requests.get(url, timeout=15)
        if resp.status_code == 200 and len(resp.content) > 1000:
            with open(filepath, "wb") as f:
                f.write(resp.content)
            return True
    except Exception:
        pass
    return False


def extract_video_thumb(video_path, output_path, seek_sec=360):
    """Extract a thumbnail from video at seek_sec using ffmpeg."""
    if os.path.exists(output_path) and os.path.getsize(output_path) > 1000:
        return True
    cmd = [
        "ffmpeg", "-v", "quiet", "-y",
        "-ss", str(seek_sec),
        "-i", video_path,
        "-vframes", "1", "-q:v", "2",
        output_path
    ]
    result = subprocess.run(cmd, capture_output=True)
    return result.returncode == 0


def parse_episode_number(filename, pattern):
    """Extract episode number from filename."""
    m = re.search(pattern, filename)
    if not m:
        return None, None
    groups = m.groups()
    if len(groups) == 2:
        return int(groups[0]), int(groups[1])  # season, episode
    elif len(groups) == 1:
        return 1, int(groups[0])  # default season 1
    return None, None


def build_series(config):
    """Build episode list for a series."""
    video_dir = config["video_dir"]
    subs_dir = config["subs_dir"]
    os.makedirs(subs_dir, exist_ok=True)

    episodes = []
    files = sorted(os.listdir(video_dir)) if os.path.exists(video_dir) else []

    for filename in files:
        if not filename.endswith(".mkv"):
            continue

        filepath = os.path.join(video_dir, filename)
        season, ep_num = parse_episode_number(filename, config["file_pattern"])
        if ep_num is None:
            print(f"  SKIP: {filename} (no episode number)")
            continue

        print(f"  Processing: S{season:02d}E{ep_num:03d} - {filename}")

        info = get_video_info(filepath)
        if info is None:
            print(f"    ERROR: ffprobe failed")
            continue

        # Extract subtitle tracks (JA, FR, EN)
        srt_ja = os.path.join(subs_dir, f"ep{ep_num:03d}_ja.srt")
        srt_fr = os.path.join(subs_dir, f"ep{ep_num:03d}_fr.srt")
        srt_en = os.path.join(subs_dir, f"ep{ep_num:03d}_en.srt")
        has_ja_subs = False
        has_fr_subs = False
        has_en_subs = False

        if config["ja_sub_stream"] and info["ja_sub_index"] >= 0:
            if extract_srt(filepath, info["ja_sub_index"], srt_ja):
                has_ja_subs = True
                print(f"    Extracted JA subs (stream {info['ja_sub_index']})")
        elif os.path.exists(srt_ja):
            has_ja_subs = True
            print(f"    Using external JA subs")

        if info["fr_sub_index"] >= 0:
            if extract_srt(filepath, info["fr_sub_index"], srt_fr):
                has_fr_subs = True
                print(f"    Extracted FR subs (stream {info['fr_sub_index']})")

        if info["en_sub_index"] >= 0:
            if extract_srt(filepath, info["en_sub_index"], srt_en):
                has_en_subs = True
                print(f"    Extracted EN subs (stream {info['en_sub_index']})")

        # Count subtitle lines
        sub_count = 0
        if has_ja_subs and os.path.exists(srt_ja):
            with open(srt_ja, encoding="utf-8", errors="replace") as f:
                sub_count = sum(1 for line in f if "-->" in line)

        # TMDB episode data
        tmdb_data = {}
        tmdb_id = config.get("tmdb_id")
        if tmdb_id:
            tmdb_season = config.get("tmdb_season", 1)
            tmdb_ep = ep_num + config.get("tmdb_ep_offset", 0)
            tmdb_data = fetch_tmdb_episode(tmdb_id, tmdb_season, tmdb_ep)
            if tmdb_data.get("tmdb_title_en"):
                print(f"    TMDB: {tmdb_data['tmdb_title_en']}")

        # Thumbnail: prefer TMDB, fallback to ffmpeg at 6min
        thumb_dir = os.path.join(THUMBS_DIR, config["id"])
        os.makedirs(thumb_dir, exist_ok=True)
        thumb_file = f"ep{ep_num:03d}.jpg"
        thumb_path = os.path.join(thumb_dir, thumb_file)
        if tmdb_data.get("tmdb_thumb"):
            download_thumb(f"{TMDB_IMG_BASE}{tmdb_data['tmdb_thumb']}", thumb_path)
        if not os.path.exists(thumb_path) or os.path.getsize(thumb_path) < 1000:
            extract_video_thumb(filepath, thumb_path, seek_sec=360)

        episode = {
            "season": season,
            "episode": ep_num,
            "filename": filename,
            "duration_sec": round(info["duration"], 1),
            "audio_tracks": info["audio_tracks"],
            "sub_tracks": info["sub_tracks"],
            "has_ja_subs": has_ja_subs,
            "has_fr_subs": has_fr_subs,
            "has_en_subs": has_en_subs,
            "ja_srt_file": f"ep{ep_num:03d}_ja.srt" if has_ja_subs else None,
            "fr_srt_file": f"ep{ep_num:03d}_fr.srt" if has_fr_subs else None,
            "en_srt_file": f"ep{ep_num:03d}_en.srt" if has_en_subs else None,
            "ja_sub_lines": sub_count,
            "watch_progress_sec": 0,
            "completed": False,
            "title_en": tmdb_data.get("tmdb_title_en", ""),
            "synopsis_en": tmdb_data.get("synopsis_en", ""),
            "synopsis_fr": tmdb_data.get("synopsis_fr", ""),
            "synopsis_ja": tmdb_data.get("synopsis_ja", ""),
            "thumb": f"thumbs/{config['id']}/{thumb_file}" if os.path.exists(thumb_path) else None,
        }
        episodes.append(episode)

    return episodes


def build_movie(config):
    """Build a single-episode entry for a movie."""
    video_dir = config["video_dir"]
    subs_dir = config["subs_dir"]
    os.makedirs(subs_dir, exist_ok=True)

    filepath = os.path.join(video_dir, config["filename"])
    if not os.path.exists(filepath):
        print(f"  NOT FOUND: {filepath}")
        return None

    print(f"  Processing: {config['filename']}")
    info = get_video_info(filepath)
    if info is None:
        print(f"    ERROR: ffprobe failed")
        return None

    # Find all Japanese sub tracks (movie_ja.srt, movie_ja1.srt, movie_ja2.srt, ...)
    ja_srt_files = []
    for f in sorted(os.listdir(subs_dir)):
        if f.startswith("movie_ja") and f.endswith(".srt"):
            ja_srt_files.append(f)
            print(f"    Found JA subs: {f}")

    # Also check for embedded subs
    srt_ja = os.path.join(subs_dir, "movie_ja.srt")
    if not ja_srt_files and info["ja_sub_index"] >= 0:
        if extract_srt(filepath, info["ja_sub_index"], srt_ja):
            ja_srt_files.append("movie_ja.srt")
            print(f"    Extracted JA subs (stream {info['ja_sub_index']})")

    has_ja_subs = len(ja_srt_files) > 0

    srt_en = os.path.join(subs_dir, "movie_en.srt")
    has_en_subs = False
    if info["en_sub_index"] >= 0:
        if extract_srt(filepath, info["en_sub_index"], srt_en):
            has_en_subs = True
            print(f"    Extracted EN subs (stream {info['en_sub_index']})")

    sub_count = 0
    if has_ja_subs:
        first_ja = os.path.join(subs_dir, ja_srt_files[0])
        with open(first_ja, encoding="utf-8", errors="replace") as f:
            sub_count = sum(1 for line in f if "-->" in line)

    # TMDB movie data
    tmdb_data = {}
    tmdb_id = config.get("tmdb_id")
    if tmdb_id:
        tmdb_data = fetch_tmdb_movie(tmdb_id)
        if tmdb_data.get("synopsis_en"):
            print(f"    TMDB: synopsis found in {sum(1 for k in ['synopsis_en','synopsis_fr','synopsis_ja'] if tmdb_data.get(k))} languages")

    return {
        "season": 1,
        "episode": 1,
        "filename": config["filename"],
        "duration_sec": round(info["duration"], 1),
        "audio_tracks": info["audio_tracks"],
        "sub_tracks": info["sub_tracks"],
        "has_ja_subs": has_ja_subs,
        "has_fr_subs": False,
        "has_en_subs": has_en_subs,
        "ja_srt_file": ja_srt_files[0] if has_ja_subs else None,
        "ja_srt_files": ja_srt_files,
        "fr_srt_file": None,
        "en_srt_file": "movie_en.srt" if has_en_subs else None,
        "ja_sub_lines": sub_count,
        "watch_progress_sec": 0,
        "completed": False,
        "synopsis_en": tmdb_data.get("synopsis_en", ""),
        "synopsis_fr": tmdb_data.get("synopsis_fr", ""),
        "synopsis_ja": tmdb_data.get("synopsis_ja", ""),
    }


def main():
    items_dir = os.path.join(DATA_DIR, "items")
    os.makedirs(items_dir, exist_ok=True)

    library = {
        "version": 2,
        "items": []
    }

    for config in SERIES:
        print(f"\n=== {config['title_en']} ===")
        episodes = build_series(config)

        # Group episodes by season
        seasons = {}
        for ep in episodes:
            s = ep["season"]
            if s not in seasons:
                seasons[s] = []
            seasons[s].append(ep)

        # Write per-season JSON
        series_dir = os.path.join(items_dir, config["id"])
        os.makedirs(series_dir, exist_ok=True)

        season_list = []
        for s_num in sorted(seasons.keys()):
            s_eps = seasons[s_num]
            season_file = f"season-{s_num}.json"
            season_data = {
                "season": s_num,
                "episode_count": len(s_eps),
                "episodes": s_eps,
            }
            with open(os.path.join(series_dir, season_file), "w", encoding="utf-8") as f:
                json.dump(season_data, f, indent=2, ensure_ascii=False)
            season_list.append({"season": s_num, "episode_count": len(s_eps)})
            print(f"  Season {s_num}: {len(s_eps)} episodes")

        # Write series info.json
        ja_count = sum(1 for ep in episodes if ep["has_ja_subs"])
        info = {
            "id": config["id"],
            "type": config["type"],
            "title_en": config["title_en"],
            "title_ja": config["title_ja"],
            "cover": f"covers/{config['id']}.jpg",
            "episode_count": len(episodes),
            "seasons": season_list,
        }
        with open(os.path.join(series_dir, "info.json"), "w", encoding="utf-8") as f:
            json.dump(info, f, indent=2, ensure_ascii=False)

        # Add to library index (lightweight)
        library["items"].append({
            "id": config["id"],
            "type": config["type"],
            "title_en": config["title_en"],
            "title_ja": config["title_ja"],
            "cover": f"covers/{config['id']}.jpg",
            "episode_count": len(episodes),
            "season_count": len(season_list),
        })
        print(f"  {len(episodes)} episodes, {ja_count} with JA subs")

    for config in MOVIES:
        print(f"\n=== {config['title_en']} (Movie) ===")
        episode = build_movie(config)
        if episode is None:
            print(f"  Skipped (file not found or error)")
            continue

        # Write movie JSON
        movie_data = {
            "id": config["id"],
            "type": "MOVIE",
            "title_en": config["title_en"],
            "title_ja": config["title_ja"],
            "cover": f"covers/{config['id']}.jpg",
            "episode": episode,
        }
        with open(os.path.join(items_dir, f"{config['id']}.json"), "w", encoding="utf-8") as f:
            json.dump(movie_data, f, indent=2, ensure_ascii=False)

        library["items"].append({
            "id": config["id"],
            "type": "MOVIE",
            "title_en": config["title_en"],
            "title_ja": config["title_ja"],
            "cover": f"covers/{config['id']}.jpg",
            "duration_min": round(episode["duration_sec"] / 60),
        })
        print(f"  Movie processed ({round(episode['duration_sec']/60)}min)")

    with open(LIBRARY_FILE, "w", encoding="utf-8") as f:
        json.dump(library, f, indent=2, ensure_ascii=False)

    print(f"\nLibrary written to {LIBRARY_FILE}")
    print(f"Total items: {len(library['items'])}")


if __name__ == "__main__":
    main()
