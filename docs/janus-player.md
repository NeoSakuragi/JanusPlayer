# Janus Player

Android TV app for Japanese immersion learning through anime.

## Architecture

- **ExoPlayer** (Media3) for video playback — hardware decoding, buffered seeking, range requests
- **Jetpack Compose** UI — no XML layouts, single ComposeView over ExoPlayer surface
- **Custom D-pad state machine** — deterministic navigation, no framework focus system
- **Pre-extracted SRT subtitles** — full timeline in memory, instant sub-seek, no mpv dependency

## State Machine

```
PLAYING → CONTROLS → WORD_NAV → LIST_SELECT
                ↕
            SETTINGS (from Library)
```

### Player States

| State | Description |
|-------|-------------|
| PLAYING | Video playing, LEFT/RIGHT = sub-seek, CENTER/DOWN = pause + word nav |
| CONTROLS | Seekbar + buttons (Audio, Subs, FontSize, Font, Condensed) |
| WORD_NAV | Cursor moves through Japanese chars, dictionary lookup per word |
| LIST_SELECT | Slide-in panel for track selection |

### Vertical Navigation (top → bottom)

```
Buttons row (Audio, Subs, Aa, F, Cond)
        ↕
Dictionary popup
        ↕
Subtitle words (cursor navigation)
        ↕
Seekbar
```

## Word Navigation

Uses **WordScanner** (Yomitan-style longest match):
1. Cursor = index into Japanese character positions
2. At each position, try longest substring against dictionary
3. **Deinflector** resolves conjugated forms (200+ rules ported from Yomitan)
4. Dictionary lookup returns term, reading, meanings, frequency, tags

No Kuromoji tokenizer. The dictionary IS the tokenizer.

## Features

- **Condensed mode** — skips >2s gaps between subtitles using pre-parsed SRT timeline
- **Sub-seek** — LEFT/RIGHT jumps between subtitle cues (instant from SRT data)
- **Font cycling** — 4 Japanese fonts (Noto Sans/Serif, Kosugi, Shippori), 3 sizes (24/32/44sp)
- **Black outline** on subtitle text for readability
- **Per-episode watch progress** — saved every 30s, resume on reopen
- **Audio/subtitle track selection** — ExoPlayer native track switching
- **Bitmap sub detection** — PGS/VOBSUB rendered by ExoPlayer, text subs by our overlay

## Dictionary

- **1.2M entries** — JMdict (terms) + JMnedict (800k names) + BCCWJ (frequency) + Kanjium (pitch)
- **Prebuilt SQLite DB** — downloaded once, ~95MB compressed
- **hasEntry()** — fast `SELECT 1 ... LIMIT 1` for word scanning
- **lookup()** — full entry with meanings, reading, tags, frequency
- **Deinflector** — 200+ suffix rules for verb/adjective conjugation resolution

## Dependencies

```
androidx.media3:media3-exoplayer:1.5.1
androidx.media3:media3-ui:1.5.1
androidx.compose (BOM 2024.02.00)
com.squareup.okhttp3:okhttp:4.12.0
io.coil-kt:coil-compose:2.6.0
```
