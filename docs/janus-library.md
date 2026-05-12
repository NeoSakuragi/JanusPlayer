# Janus Library Format

All library data lives in SQLite (`janus.db`). The Go server exposes it via REST API.

## Database Tables

### items
| Column | Type | Description |
|--------|------|-------------|
| id | TEXT PK | Slug (e.g. "saint-seiya") |
| type | TEXT | "TV_SERIES" or "MOVIE" |
| title_en | TEXT | English title |
| title_ja | TEXT | Japanese title |
| cover | TEXT | Cover image path |
| episode_count | INT | Total episodes |
| season_count | INT | Number of seasons (series only) |
| duration_min | INT | Runtime in minutes (movies only) |
| synopsis_en/fr/ja | TEXT | Synopsis in 3 languages |
| tmdb_id | INT | TMDB API ID |

### episodes
| Column | Type | Description |
|--------|------|-------------|
| item_id | TEXT FK | References items.id |
| season | INT | Season number |
| episode | INT | Episode number |
| filename | TEXT | Video filename |
| duration_sec | REAL | Duration in seconds |
| title_en | TEXT | Episode title (from TMDB) |
| synopsis_en/fr/ja | TEXT | Episode synopsis (from TMDB) |
| thumb | TEXT | Thumbnail path |
| has_ja/en/fr_subs | INT | Subtitle availability flags |
| ja/en/fr_srt_file | TEXT | SRT filenames |

## API Responses

### GET /api/library (lightweight index)
```json
{
  "version": 2,
  "last_modified": 1678901234,
  "items": [
    {"id": "saint-seiya", "type": "TV_SERIES", "title_en": "Saint Seiya", "title_ja": "聖闘士星矢", "cover": "covers/saint-seiya.jpg", "episode_count": 41, "season_count": 2},
    {"id": "the-running-man", "type": "MOVIE", "title_en": "The Running Man", "title_ja": "バトルランナー", "cover": "covers/the-running-man.jpg", "duration_min": 101}
  ]
}
```

### GET /api/items/{id} (series detail)
```json
{
  "id": "saint-seiya", "type": "TV_SERIES",
  "title_en": "Saint Seiya", "title_ja": "聖闘士星矢",
  "cover": "covers/saint-seiya.jpg", "episode_count": 41,
  "seasons": [{"season": 2, "episode_count": 26}, {"season": 3, "episode_count": 15}]
}
```

### GET /api/items/{id}/season/{n} (episodes)
```json
{
  "season": 2, "episode_count": 26,
  "episodes": [
    {"season": 2, "episode": 74, "filename": "Saint Seiya - S002E074.mkv", "duration_sec": 1448.2, "title_en": "Enemies of the Northern Frontier!", "synopsis_en": "In Asgard...", "thumb": "thumbs/saint-seiya/ep074.jpg", "has_ja_subs": true, "ja_srt_file": "ep074_ja.srt"}
  ]
}
```

## Data Sources
- **Video metadata**: ffprobe at build time
- **Subtitles**: ffmpeg extraction from MKV or Whisper transcription
- **Synopses + thumbnails**: TMDB API (cached in tmdb_cache.json, imported to DB)
- **Covers**: AniList API or TMDB API
