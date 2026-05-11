# Janus Library Format

The library is a JSON file describing the curated anime collection.

## Schema

```json
{
  "version": 1,
  "items": [
    {
      "id": "saint-seiya",
      "type": "TV_SERIES",
      "title_en": "Saint Seiya",
      "title_ja": "聖闘士星矢",
      "cover": "covers/saint-seiya.jpg",
      "episode_count": 40,
      "last_watched_episode": null,
      "overall_progress": 0.0,
      "episodes": [
        {
          "season": 2,
          "episode": 75,
          "filename": "Saint Seiya - S002E075.mkv",
          "duration_sec": 1447.6,
          "audio_tracks": [
            { "index": 1, "lang": "jpn", "title": "Japanese", "codec": "aac" },
            { "index": 2, "lang": "fre", "title": "French", "codec": "aac" }
          ],
          "sub_tracks": [
            { "index": 14, "lang": "eng", "codec": "subrip", "is_text": true },
            { "index": 15, "lang": "fre", "codec": "subrip", "is_text": true },
            { "index": 19, "lang": "jpn", "codec": "subrip", "is_text": true }
          ],
          "has_ja_subs": true,
          "has_fr_subs": true,
          "has_en_subs": true,
          "ja_srt_file": "ep075_ja.srt",
          "fr_srt_file": "ep075_fr.srt",
          "en_srt_file": "ep075_en.srt",
          "ja_sub_lines": 220,
          "watch_progress_sec": 0,
          "completed": false
        }
      ]
    }
  ]
}
```

## Item Types

| Type | Description |
|------|-------------|
| `TV_SERIES` | Multiple episodes, organized by season |
| `MOVIE` | Single video file |

## Episode Fields

| Field | Type | Description |
|-------|------|-------------|
| `season` | int | Season number |
| `episode` | int | Episode number (global, not per-season) |
| `filename` | string | MKV filename in the series video directory |
| `duration_sec` | float | Video duration in seconds |
| `audio_tracks` | array | Available audio tracks with codec/language |
| `sub_tracks` | array | Available subtitle tracks (embedded in MKV) |
| `has_ja_subs` | bool | Japanese SRT extracted and available |
| `has_fr_subs` | bool | French SRT available |
| `has_en_subs` | bool | English SRT available |
| `ja_srt_file` | string? | Filename of Japanese SRT in subs directory |
| `fr_srt_file` | string? | Filename of French SRT |
| `en_srt_file` | string? | Filename of English SRT |
| `ja_sub_lines` | int | Number of subtitle cues in Japanese SRT |
| `watch_progress_sec` | float | Last known watch position (from server) |
| `completed` | bool | Whether episode was watched to completion |

## Watch Progress

Watch progress is currently stored **on the device** in Android SharedPreferences:

```
Key: {series_id}_ep{episode_num}_pos → Long (milliseconds)
Key: {series_id}_ep{episode_num}_dur → Long (milliseconds)
Key: {series_id}_last_ep → String (episode number)
Key: {series_id}_last_pos → Long (milliseconds)
```

Saved every 30 seconds during playback + on pause/exit.

## SRT File Naming

```
subs/{series-id}/ep{NNN}_{lang}.srt
```

Examples:
- `subs/saint-seiya/ep075_ja.srt`
- `subs/saint-seiya/ep075_fr.srt`
- `subs/dbz/ep200_en.srt`

## Current Library

| Series | Episodes | JA | FR | EN | Size |
|--------|----------|----|----|-----|------|
| Saint Seiya (聖闘士星矢) | 40 (75-114) | ✓ | ✓ | ✓ | ~20 GB |
| Maison Ikkoku (めぞん一刻) | 11 (10-20) | ✓ | ✗ | ✓ | ~6 GB |
| Dragon Ball Z (ドラゴンボールZ) | 20 (200-219) | ✓ | ✗ | ✓ | ~4 GB |

Total: 71 episodes, ~30 GB
