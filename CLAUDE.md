# Janus — Japanese Immersion Video Player

## What This Is

Janus is a video player designed for learning Japanese through anime. It consists of:

1. **Android TV app** (`/app`) — ExoPlayer-based video player with word-level subtitle navigation and dictionary lookup
2. **Media server** (`/server`) — Python HTTP server that serves curated anime library with pre-extracted subtitles
3. **Library format** (`library.json`) — Metadata for series, episodes, subtitles, watch progress

## Project Structure

```
/app                          — Android app (Kotlin + Jetpack Compose)
  src/main/java/com/videoplayer/
    LibraryActivity.kt        — Netflix-style series browser
    ExoPlayerActivity.kt      — Video player with state machine
    WordScanner.kt            — Yomitan-style longest-match word scanner
    Deinflector.kt            — 200+ conjugation rules (ported from Yomitan)
    DictionaryDatabase.kt     — SQLite dictionary (JMdict + JMnedict)
    SrtParser.kt              — SRT subtitle parser
    JanusApi.kt               — HTTP client for Janus server
    AppSettings.kt            — SharedPreferences wrapper
    BrowserActivity.kt        — Legacy SMB file browser (local file mode)

/server                       — Python media server
    server.py                 — HTTP server with Range support
    build_library.py          — Scan videos, extract subs, generate library.json
    fetch_covers.py           — AniList API cover art downloader
    manage.py                 — CLI: build, covers, status, serve
    config.py                 — Configuration

/docs                         — Documentation
    janus-player.md           — Player architecture and features
    janus-server.md           — Server API and setup
    janus-library.md          — Library JSON format

/data/janus                   — Media data (not in git)
    videos/                   — MKV files per series
    subs/                     — Pre-extracted SRT files
    covers/                   — AniList cover art
    library.json              — Generated metadata
```

## Key Design Decisions

### Player State Machine
Every D-pad input has exactly one deterministic outcome. No framework focus system — we track focus indices manually. States: PLAYING → CONTROLS → WORD_NAV → LIST_SELECT.

### WordScanner (No Kuromoji)
Instead of a tokenizer, we scan from each character position using longest-match against the dictionary + deinflector. Same approach as Yomitan. The dictionary IS the tokenizer.

### Pre-extracted SRT
Subtitles are extracted from MKV files at library build time (ffmpeg). The player loads the complete SRT into memory — instant sub-seek, gap calculation, condensed mode. No runtime extraction.

### ExoPlayer over mpv
ExoPlayer gives us native Android buffering, hardware decode, and lifecycle management. mpv caused surface destruction issues, focus conflicts, and seeking freezes on Android TV.

### Server-Client Architecture
Video files and subtitles are served via HTTP from a Python server. The player connects to the server URL (configurable in settings). This decouples content management from playback.

## Running

### Server
```bash
cd /data/janus/server
python3 manage.py build     # Extract subs, generate library
python3 manage.py covers    # Fetch AniList cover art
python3 manage.py serve     # Start on port 8900
```

### App
- Set server URL in Settings (UP from library screen)
- Default: `http://10.0.2.2:8900` (emulator) or `http://192.168.1.29:8900` (LAN)

### Emulator
```bash
~/Android/Sdk/emulator/emulator -avd JanusTV
# Arrow keys = D-pad, Enter = select, Escape = back
```

## Dictionary Setup

The app needs a prebuilt dictionary database (~95MB):
1. Settings → Dictionaries → Quick Setup
2. Or download from `https://neomobiles.duckdns.org/dict/dictionary_lite.db.gz`
3. Contains: JMdict (200k terms) + JMnedict (800k names) + BCCWJ frequency + Kanjium pitch accent

## Current Library

| Series | Episodes | Subtitles |
|--------|----------|-----------|
| Saint Seiya | 40 (75-114) | JA + FR + EN |
| Maison Ikkoku | 11 (10-20) | JA + EN |
| Dragon Ball Z | 20 (200-219) | JA + EN |
