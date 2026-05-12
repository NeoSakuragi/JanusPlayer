# Janus Player

Android app for Japanese immersion learning through anime and movies.

## Architecture

- **ExoPlayer** (Media3) via **PlayerManager** singleton — one player instance shared between preview and playback
- **Jetpack Compose** UI — no XML layouts for player/library (SettingsActivity uses XML)
- **Dual input** — D-pad state machine + touch callbacks, both update the same state
- **Pre-extracted SRT subtitles** — full timeline in memory, instant sub-seek
- **AppNavigator** — app-level navigation state machine (MAIN, SETTINGS, ITEM_DETAIL, VIDEO_PLAYER)

## Navigation

### App Screens
```
MAIN ←→ SETTINGS (separate Activity)
MAIN → ITEM_DETAIL → VIDEO_PLAYER → ITEM_DETAIL
MAIN → DOWNLOADS
```

### Player States
| State | Description |
|-------|-------------|
| PLAYING | Video playing, LEFT/RIGHT = sub-seek, CENTER/DOWN = pause + word nav |
| CONTROLS | Seekbar + buttons (Back, Audio, Subs, Aa, Font, Condensed, HW/SW, Download) |
| WORD_NAV | Cursor moves through Japanese chars, dictionary lookup per word |
| LIST_SELECT | Slide-in panel for track selection |

### Library Rows (D-pad)
```
HEADER (Settings button)
    ↕
CONTINUE (resume watching)
    ↕
SERIES
    ↕
MOVIES
```

## Item Detail Page (Prime Video-style)
- Hero area with backdrop image/banner
- Video preview fades in after 3 seconds (first episode at 8min mark)
- Title, play/resume button, download all button, synopsis, metadata
- Episode grid (4 columns, D-pad up/down jumps by row)
- TMDB thumbnails + synopses on episode cards

## Word Navigation
Uses **WordScanner** (Yomitan-style longest match):
1. Cursor = index into Japanese character positions
2. At each position, try longest substring against dictionary
3. **Deinflector** resolves conjugated forms (200+ rules)
4. Dictionary lookup returns term, reading, meanings, frequency, tags

### Subtitle Character Tap (Touch)
AABB bounding boxes computed from TextLayoutResult at render time. Tap checks which rect contains the pointer — pixel-perfect hit testing.

## Features
- **Condensed mode** — skips >2s gaps between subtitles
- **Sub-seek** — LEFT/RIGHT jumps between subtitle cues
- **Font cycling** — 4 Japanese fonts, 3 sizes
- **HW/SW decode toggle** — switch between hardware and software decoding mid-playback
- **Per-episode download** — download button in player control bar
- **Offline playback** — automatically uses local files when available
- **Self-update** — checks server for new APK version
- **Watch progress** — saved every 30s, resume on reopen
- **Drag-to-seek** — press + drag on seekbar for scrubbing

## Offline Downloads
- **DownloadService** — foreground service, sequential queue, resume support
- **DownloadManager** — state tracking (QUEUED/DOWNLOADING/COMPLETED/FAILED), JSON persistence
- **Downloads screen** — accessible from Settings, shows all downloads grouped by series
- **Airplane mode** — "Watch offline" button on connection screen when downloads exist
