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

DATA_DIR = "/data/janus"
VIDEOS_DIR = os.path.join(DATA_DIR, "videos")
SUBS_DIR = os.path.join(DATA_DIR, "subs")
LIBRARY_FILE = os.path.join(DATA_DIR, "library.json")

SERIES = [
    {
        "id": "saint-seiya",
        "title_en": "Saint Seiya",
        "title_ja": "聖闘士星矢",
        "type": "TV_SERIES",
        "video_dir": os.path.join(VIDEOS_DIR, "saint-seiya"),
        "subs_dir": os.path.join(SUBS_DIR, "saint-seiya"),
        "file_pattern": r"Saint Seiya - S(\d+)E(\d+)\.mkv",
        "ja_sub_stream": "jpn",  # language code to find
    },
    {
        "id": "maison-ikkoku",
        "title_en": "Maison Ikkoku",
        "title_ja": "めぞん一刻",
        "type": "TV_SERIES",
        "video_dir": os.path.join(VIDEOS_DIR, "maison-ikkoku"),
        "subs_dir": os.path.join(SUBS_DIR, "maison-ikkoku"),
        "file_pattern": r"Maison Ikkoku - (\d+)",
        "ja_sub_stream": None,  # external SRT files
    },
    {
        "id": "dbz",
        "title_en": "Dragon Ball Z",
        "title_ja": "ドラゴンボールZ",
        "type": "TV_SERIES",
        "video_dir": os.path.join(VIDEOS_DIR, "dbz"),
        "subs_dir": os.path.join(SUBS_DIR, "dbz"),
        "file_pattern": r"Dragon Ball Z - (\d+)",
        "ja_sub_stream": None,  # external SRT files
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
    }


def main():
    library = {
        "version": 1,
        "items": []
    }

    for config in SERIES:
        print(f"\n=== {config['title_en']} ===")
        episodes = build_series(config)

        item = {
            "id": config["id"],
            "type": config["type"],
            "title_en": config["title_en"],
            "title_ja": config["title_ja"],
            "cover": f"covers/{config['id']}.jpg",
            "episode_count": len(episodes),
            "episodes": episodes,
            "last_watched_episode": None,
            "overall_progress": 0.0,
        }
        library["items"].append(item)
        print(f"  {len(episodes)} episodes processed")

    for config in MOVIES:
        print(f"\n=== {config['title_en']} (Movie) ===")
        episode = build_movie(config)
        if episode is None:
            print(f"  Skipped (file not found or error)")
            continue

        item = {
            "id": config["id"],
            "type": "MOVIE",
            "title_en": config["title_en"],
            "title_ja": config["title_ja"],
            "cover": f"covers/{config['id']}.jpg",
            "episode_count": 1,
            "episodes": [episode],
            "last_watched_episode": None,
            "overall_progress": 0.0,
        }
        library["items"].append(item)
        print(f"  Movie processed ({round(episode['duration_sec']/60)}min)")

    with open(LIBRARY_FILE, "w", encoding="utf-8") as f:
        json.dump(library, f, indent=2, ensure_ascii=False)

    print(f"\nLibrary written to {LIBRARY_FILE}")
    print(f"Total items: {len(library['items'])}")
    for item in library["items"]:
        if item["type"] == "MOVIE":
            dur = round(item["episodes"][0]["duration_sec"] / 60)
            print(f"  {item['title_en']} (Movie): {dur}min, JA subs: {item['episodes'][0]['has_ja_subs']}")
        else:
            ja_count = sum(1 for ep in item["episodes"] if ep["has_ja_subs"])
            print(f"  {item['title_en']}: {item['episode_count']} episodes, {ja_count} with JA subs")


if __name__ == "__main__":
    main()
