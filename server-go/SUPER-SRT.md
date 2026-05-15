# Supercharged SRT

Server-side subtitle processing pipeline that produces ready-to-render JSON with word segmentation, dictionary definitions, inflection tags, and series-specific vocabulary.

## Why

The client no longer carries a 50MB dictionary or runs deinflection. The server does all the linguistic heavy lifting once per episode. The client is a pure renderer.

## Pipeline

```
SRT file
  → MeCab (segmentation + base forms + inflection info)
  → Series dict lookup (ateji, character names, attack names — overrides)
  → Jitendex lookup (standard Japanese — fallback)
  → De-potentializing (放せる → 放す if Jitendex misses potential forms)
  → Supercharged SRT JSON
```

## Output Format

```json
{
  "dict": [
    {"t": "聖闘士", "r": "セイント", "m": ["Saint — warrior who serves Athena"], "jlpt": "", "freq": 0},
    {"t": "返す", "r": "かえす", "m": ["to return (something)"], "jlpt": "N5", "freq": 890}
  ],
  "cues": [
    {
      "s": 1053363,
      "e": 1059353,
      "w": [
        {"s": "黄金聖衣", "d": 0},
        {"s": "を"},
        {"s": "返してもらおう", "d": 1, "i": "てもらう+volitional"}
      ]
    }
  ]
}
```

### Fields

**dict[]** — Lookup table, referenced by index from cue words
- `t` — dictionary term (base form)
- `r` — reading
- `m` — meanings (array, max 3)
- `jlpt` — JLPT level (N5–N1 or empty)
- `freq` — frequency rank (lower = more common)

**cues[]** — Subtitle cues
- `s` — start time in milliseconds
- `e` — end time in milliseconds
- `w` — words array

**cues[].w[]** — Words within a cue
- `s` — surface form (what appears on screen)
- `d` — index into dict[] (absent if no definition: punctuation, SFX)
- `i` — inflection tag (absent if dictionary form). Examples: "past", "negative", "polite", "てもらう", "potential+negative"

## MeCab Custom Dictionaries

Per-series user dictionaries for ateji and proper nouns:

```
/data/janus/dicts/saint-seiya.dic      — compiled MeCab user dict
/data/janus/dicts/saint-seiya.csv      — source CSV
/data/janus/dicts/saint-seiya.json     — series definitions
```

Compiled with:
```bash
mecab-dict-index -d /usr/share/mecab/dic/ipadic \
  -u saint-seiya.dic -f utf-8 -t utf-8 saint-seiya.csv
```

## Endpoint

```
GET /api/super-srt/{itemId}/{season}/{episode}
```

Returns the supercharged SRT JSON. Generated on first request, cached in memory.

## Coverage

Tested on Saint Seiya episodes 1–8:
- 99.8% meaningful word coverage (excluding punctuation/SFX)
- 447 unique dict entries per episode (~89KB)
- Series dict: 69 entries (ateji, characters, attacks)
- Inflection tags on all conjugated forms

## What This Replaces on the Client

- JitendexDict.kt (O(1) binary dictionary, 50MB) — REMOVED
- Deinflector.kt (200+ conjugation rules) — REMOVED
- WordScanner.kt (greedy longest-match scanner) — REMOVED
- jitendex.bin in APK assets — REMOVED
- Space-delimited SRT parsing — REPLACED by JSON word array
