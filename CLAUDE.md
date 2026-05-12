# Janus — Japanese Immersion Video Player

## What This Is

Janus is a video player designed for learning Japanese through anime and movies. It consists of:

1. **Android app** (`/app`) — ExoPlayer-based video player with word-level subtitle navigation, dictionary lookup, touch + D-pad dual input
2. **Go media server** (`/server-go`) — SQLite-backed REST API + static file server for video, subs, covers, thumbnails, app updates
3. **Build tools** (`/server`) — Python scripts for subtitle extraction, TMDB metadata, Whisper transcription

## Project Structure

```
/app                          — Android app (Kotlin + Jetpack Compose)
  src/main/java/com/videoplayer/
    LibraryActivity.kt        — Main UI: library browser, item detail (Prime Video-style), downloads
    ExoPlayerActivity.kt      — Video player with state machine
    PlayerManager.kt          — Singleton ExoPlayer instance (shared between preview + playback)
    AppNavigator.kt           — App-level navigation state machine
    AppUpdater.kt             — Self-update from server
    DownloadManager.kt        — Offline episode download state + queue
    DownloadService.kt        — Foreground service for background downloads
    WordScanner.kt            — Yomitan-style longest-match word scanner
    Deinflector.kt            — 200+ conjugation rules (ported from Yomitan)
    DictionaryDatabase.kt     — SQLite dictionary (JMdict + JMnedict)
    SrtParser.kt              — SRT subtitle parser
    JanusApi.kt               — HTTP client (split API: library index, item detail, season data)
    AppSettings.kt            — SharedPreferences wrapper
    SettingsActivity.kt       — Settings: updates, downloads, server URL, playback, Anki

/server-go                    — Go media server (production)
    main.go                   — HTTP server, SQLite backend, REST API
    import.go                 — JSON-to-SQLite migration tool
    go.mod / go.sum           — Go modules

/server                       — Python build tools (not runtime)
    build_library.py          — Scan videos, extract subs, fetch TMDB, generate library
    fetch_covers.py           — AniList API cover art downloader
    manage.py                 — CLI: build, covers, status
    config.py                 — Configuration

/data/janus                   — Media data (not in git)
    janus.db                  — SQLite database (items, episodes, meta, watch progress)
    videos/                   — MKV files per series
    subs/                     — Pre-extracted SRT files
    covers/                   — Cover art + banners
    thumbs/                   — Episode thumbnails (from TMDB)
    updates/                  — APK files for self-update
```

## Key Design Decisions

### Player State Machine
Every D-pad input has exactly one deterministic outcome. No framework focus system — we track focus indices manually. States: PLAYING → CONTROLS → WORD_NAV → LIST_SELECT.

### Touch + D-pad Dual Input
Touch callbacks call the same action functions as D-pad. Both update the same focus state. Cursor shown by default, hidden on touch input, revealed on D-pad input.

### PlayerManager Singleton
One ExoPlayer instance shared between preview player (item detail hero) and full playback (ExoPlayerActivity). No dual-player conflicts.

### WordScanner (No Kuromoji)
Scans from each character position using longest-match against the dictionary + deinflector. Same approach as Yomitan. The dictionary IS the tokenizer.

### Subtitle Character Hit Testing
AABB bounding boxes computed from TextLayoutResult at render time. Tap position checked against actual character rects — pixel-perfect accuracy.

### SQLite Everything
All metadata in SQLite (janus.db). No JSON files for data storage. The Go server queries the DB and returns JSON over REST. Build tools write to the DB via import tool.

### Self-Hosted Updates
App checks the Janus server (/api/version) for new versions. APK served from /api/update/janus.apk. No external dependencies. deploy.sh builds APK and copies to /data/janus/updates/.

### Offline Downloads
DownloadService (foreground) downloads episodes to device storage. DownloadManager tracks state (QUEUED/DOWNLOADING/COMPLETED/FAILED). Playback automatically uses local files when available. Download button in player control bar.

## Running

### Server (Go)
```bash
cd /home/bruno/VideoPlayer/server-go
./janus-server                # Starts on port 8900, reads /data/janus/janus.db
```

### Import existing data
```bash
./janus-import /data/janus    # Migrates JSON files into SQLite
```

### Deploy app update
```bash
./deploy.sh                   # Builds APK, copies to /data/janus/updates/, updates DB version
```

### App
- Set server URL in Settings
- Default: `http://10.0.2.2:8900` (emulator) or `http://192.168.1.29:8900` (LAN)

### Emulator
```bash
~/Android/Sdk/emulator/emulator -avd JanusTV -gpu host
# Arrow keys = D-pad, Enter = select, Escape = back, Mouse = touch
```

## REST API

| Endpoint | Description |
|----------|-------------|
| GET /api/health | Server status, uptime, item/episode counts |
| GET /api/version | App version info for self-update |
| GET /api/library | Library index (lightweight, for main page) |
| GET /api/items/{id} | Item detail (series info + seasons, or movie + episode) |
| GET /api/items/{id}/season/{n} | Episodes for a season |
| GET /api/video/{id}/{file} | Video streaming (Range support) |
| GET /api/subs/{id}/{file} | Subtitle files |
| GET /api/covers/{file} | Cover art |
| GET /api/thumbs/{id}/{file} | Episode thumbnails |
| GET /api/update/{file} | APK downloads for self-update |

## Current Library

| Title | Type | Episodes | Subtitles |
|-------|------|----------|-----------|
| Saint Seiya | TV (2 seasons) | 41 (74-114) | JA + FR + EN |
| Maison Ikkoku | TV (1 season) | 48 (1-48) | JA + EN |
| Dragon Ball Z | TV (1 season) | 20 (200-219) | JA + EN |
| The Running Man | Movie | 1 | JA (4 dubs, Whisper-generated) |
