# Janus Server

Python HTTP server that serves curated anime library to the Janus player.

## What It Does

- Serves video files with HTTP Range support (seeking)
- Serves pre-extracted SRT subtitle files (JA/FR/EN)
- Serves cover art (from AniList)
- Serves library metadata as JSON
- Runs on any machine — local dev, Raspberry Pi, cloud server

## API

| Endpoint | Description |
|----------|-------------|
| `GET /api/library` | Full library JSON (series, episodes, metadata) |
| `GET /api/video/{series}/{file}` | Video stream with Range support |
| `GET /api/subs/{series}/{file}` | SRT subtitle file |
| `GET /api/covers/{file}` | Cover/banner image |
| `GET /api/health` | Health check |

## Management CLI

```bash
python3 manage.py build     # Scan videos, extract subs, generate library.json
python3 manage.py covers    # Fetch cover art from AniList API
python3 manage.py status    # Show library stats
python3 manage.py serve     # Start the server
```

## Library Build Pipeline

When `manage.py build` runs:

1. Scans `videos/{series-id}/` for MKV files
2. Runs `ffprobe` to extract duration, track info (audio, subtitle codecs/languages)
3. Extracts Japanese SRT via `ffmpeg -map 0:{stream_index}` (if embedded)
4. Extracts French and English SRT tracks similarly
5. Uses external SRT files if present in `subs/{series-id}/`
6. Generates `library.json` with full metadata per episode

## Cover Art Pipeline

When `manage.py covers` runs:

1. Queries AniList GraphQL API for each series title
2. Downloads cover image (portrait) and banner image (wide)
3. Saves to `covers/{series-id}.jpg` and `covers/{series-id}-banner.jpg`
4. Saves AniList metadata (genres, score, description) as JSON

## Adding a New Series

1. Create `videos/{series-id}/` and copy MKV files
2. Add entry to `SERIES` list in `build_library.py`:
   ```python
   {
       "id": "series-id",
       "title_en": "English Title",
       "title_ja": "日本語タイトル",
       "type": "TV_SERIES",  # or "MOVIE"
       "video_dir": os.path.join(VIDEOS_DIR, "series-id"),
       "subs_dir": os.path.join(SUBS_DIR, "series-id"),
       "file_pattern": r"regex to extract episode number",
       "ja_sub_stream": "jpn",  # or None for external SRTs
   }
   ```
3. Run `python3 manage.py build`
4. Run `python3 manage.py covers`

## Encoding Recommendations

For efficiency (480p h264):
```bash
ffmpeg -i input.mkv -vf scale=-2:480 -c:v libx264 -preset medium -crf 23 \
    -c:a aac -b:a 128k -map 0:v:0 -map 0:a -map 0:s output.mkv
```

## Configuration

`config.py` — all paths and ports. Override with environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `JANUS_DATA_DIR` | `/data/janus` | Base data directory |
| `JANUS_HOST` | `0.0.0.0` | Listen address |
| `JANUS_PORT` | `8900` | Listen port |

## Directory Structure

```
/data/janus/
├── server/           # This code
├── videos/
│   ├── saint-seiya/  # MKV files
│   ├── maison-ikkoku/
│   └── dbz/
├── subs/
│   ├── saint-seiya/  # ep075_ja.srt, ep075_fr.srt, ep075_en.srt
│   ├── maison-ikkoku/
│   └── dbz/
├── covers/           # AniList art
└── library.json      # Generated metadata
```
