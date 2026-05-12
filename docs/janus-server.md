# Janus Server

Go HTTP server with SQLite backend. Serves the media library, video streams, and app updates.

## Architecture

- **Go** (`/server-go/main.go`) — concurrent HTTP server, SQLite queries for data
- **SQLite** (`/data/janus/janus.db`) — items, episodes, metadata, watch progress
- **Static files** — videos, subtitles, covers, thumbnails, APK updates served from disk

## API

| Endpoint | Description |
|----------|-------------|
| `GET /api/health` | Server status, uptime, item/episode counts |
| `GET /api/version` | App version info (for self-update) |
| `GET /api/library` | Library index (lightweight, for main page) |
| `GET /api/items/{id}` | Item detail (series with seasons, or movie with episode) |
| `GET /api/items/{id}/season/{n}` | Episodes for a season |
| `GET /api/video/{id}/{file}` | Video stream (Range support via http.ServeContent) |
| `GET /api/subs/{id}/{file}` | SRT subtitle file |
| `GET /api/covers/{file}` | Cover/banner image |
| `GET /api/thumbs/{id}/{file}` | Episode thumbnail |
| `GET /api/update/{file}` | APK for self-update |

## Database Schema

- **items** — id, type, title_en, title_ja, cover, episode_count, season_count, duration_min, synopsis (EN/FR/JA), tmdb_id
- **episodes** — item_id, season, episode, filename, duration_sec, title_en, synopsis (EN/FR/JA), thumb, subtitle flags/files
- **meta** — key/value store (library_version, app_version_code, app_version_name)
- **watch_progress** — series_id, episode_num, position_ms, duration_ms

## Running

```bash
cd /home/bruno/VideoPlayer/server-go
./janus-server                # Listens on 0.0.0.0:8900
```

Environment variables:
- `JANUS_DATA` — data directory (default: `/data/janus`)
- `JANUS_HOST` — bind address (default: `0.0.0.0`)
- `JANUS_PORT` — port (default: `8900`)

## Building

```bash
cd /home/bruno/VideoPlayer/server-go
CGO_ENABLED=1 go build -o janus-server main.go
```

## Importing Data

```bash
./janus-import /data/janus    # Reads JSON files from /data/janus/items/, writes to janus.db
```

## Deploying App Updates

```bash
./deploy.sh                   # Builds APK → /data/janus/updates/janus.apk, updates version in DB
```

## Legacy Python Server

The Python server (`/server/server.py`) is deprecated. It was single-threaded and caused timeouts under load. The Go server replaced it with concurrent request handling and SQLite storage.

Build tools in `/server/` (build_library.py, fetch_covers.py) are still used for content pipeline tasks (subtitle extraction, TMDB fetching) but their output is imported into SQLite via `janus-import`.
