# Supercharged SRT

Server-side subtitle processing that turns raw SRT files into ready-to-render JSON with word segmentation, dictionary definitions, inflection tags, and series-specific vocabulary. The client is a pure renderer — no dictionary, no linguistic processing.

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                       SERVER                             │
│                                                          │
│  Original SRT                                            │
│       │                                                  │
│       ▼                                                  │
│  ┌─────────────────────────────────────┐                │
│  │  MeCab + Custom User Dictionary     │                │
│  │  (segmentation, base forms,         │                │
│  │   readings, inflection info)        │                │
│  └──────────────┬──────────────────────┘                │
│                 │                                        │
│                 ▼                                        │
│  ┌─────────────────────────────────────┐                │
│  │  Dictionary Lookup (per word)       │                │
│  │                                     │                │
│  │  1. Series Dict  (overrides)        │                │
│  │     ateji, character names,         │                │
│  │     attack names, custom terms      │                │
│  │                                     │                │
│  │  2. Jitendex     (standard JP)      │                │
│  │     200K entries, JMdict-based,     │                │
│  │     JLPT, frequency, meanings       │                │
│  │                                     │                │
│  │  3. De-potential (fallback)         │                │
│  │     放せる→放す if Jitendex         │                │
│  │     misses potential verb forms     │                │
│  │                                     │                │
│  │  4. No match                        │                │
│  │     surface + reading only          │                │
│  └──────────────┬──────────────────────┘                │
│                 │                                        │
│                 ▼                                        │
│  ┌─────────────────────────────────────┐                │
│  │  Supercharged SRT JSON              │                │
│  │  - dict[]: shared lookup table      │                │
│  │  - cues[]: timed word arrays        │                │
│  │  - version tracking                 │                │
│  └──────────────┬──────────────────────┘                │
│                 │                                        │
│            cached in memory                              │
└─────────────────┬───────────────────────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────────────────────┐
│                       CLIENT                             │
│                                                          │
│  Receives JSON → renders words → tap = array index       │
│  No dictionary. No deinflection. No parsing.             │
└─────────────────────────────────────────────────────────┘
```

## Three Layers

### Layer 1: MeCab Segmentation

MeCab tokenizes raw Japanese text into morphemes with part-of-speech, base form, reading, and conjugation info.

**Custom user dictionaries** per series handle ateji and proper nouns that MeCab's standard dictionary (ipadic) doesn't know:

```csv
聖闘士,,,10,名詞,固有名詞,一般,*,*,*,聖闘士,セイント,セイント
聖衣,,,10,名詞,一般,*,*,*,*,聖衣,クロス,クロス
小宇宙,,,10,名詞,一般,*,*,*,*,小宇宙,コスモ,コスモ
```

Without custom dict: `聖闘士` → `聖`(Kiyoshi) + `闘士`(fighter) — wrong  
With custom dict: `聖闘士` → `聖闘士`(セイント/Saint) — correct

**Word grouping** combines verb stems with auxiliaries:
- `返してもらおう` = 返し(verb) + て(particle) + もらお(aux) + う(aux)  
- Grouped as one word, base form = `返す`

**Inflection tracking** from MeCab's conjugation fields + auxiliary analysis:
- `ない` → negative
- `た` → past
- `ます` → polite
- `てもらう`, `てくれる`, `てしまう`, etc. → grammar tags

### Layer 2: Dictionary Lookup

For each word, lookup happens in priority order:

**1. Series dict (override)** — curated per series, always wins  
Purpose: ateji readings, character descriptions, attack names, custom terms  
Example: `聖衣` → reading `クロス`, meaning "Cloth — sacred armor worn by Saints"  
Without series dict, Jitendex would show `せいい` (standard reading)

**2. Jitendex (standard Japanese)** — 200K entries from JMdict  
Purpose: standard vocabulary with JLPT levels, frequency ranks, English definitions  
Example: `返す` → reading `かえす`, meaning "to return", JLPT N5, freq 890

**3. De-potentializing (fallback)** — godan potential forms  
Purpose: MeCab sometimes gives potential forms as base (放せる, 直せる) that Jitendex doesn't have  
Maps: `放せる`→`放す`, `直せる`→`直す`, `食らえる`→`食らう`

**4. No match** — punctuation, sound effects, rare terms  
Surface form displayed, no popup on tap

### Layer 3: Cue Assembly

The original SRT text is preserved exactly. The generator:
1. Strips spaces for MeCab processing only
2. Maps MeCab tokens back to character positions in the original text
3. Preserves original spaces and newlines as separator markers
4. Display text is character-identical to the original SRT

## Output Format

```json
{
  "v": 5,
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

### Top-level
- `v` — pipeline version (bump to invalidate all cached outputs)

### dict[]
Shared lookup table. Each word in a cue references an entry by index.
- `t` — dictionary term (base form)
- `r` — reading (katakana/hiragana)
- `m` — meanings (array, max 3)
- `jlpt` — JLPT level (N5–N1 or empty)
- `freq` — frequency rank (lower = more common, 0 = unknown)

Same word appearing 50 times across an episode = 1 dict entry, 50 index references.

### cues[]
- `s` — start time in milliseconds
- `e` — end time in milliseconds
- `w` — words array

### cues[].w[]
- `s` — surface form (exact text from original SRT, including any original spaces)
- `d` — index into dict[] (absent = no definition: punctuation, SFX, spaces, newlines)
- `i` — inflection tag (absent = dictionary form)

Special surface values:
- `"\n"` — line break within a multi-line cue
- `" "` — original space from the SRT (preserved, not tappable)

### Inflection Tags
Compound tags joined with `+`:
- `past`, `negative`, `polite`, `conditional`, `imperative`
- `passive`, `causative`, `potential`
- `てもらう`, `てくれる`, `てあげる`, `てしまう`, `ておく`, `てみる`, `ていく`, `てくる`, `ている`
- `negative volitional`

Example: `食べさせられなかった` → `i: "causative+passive+negative+past"`

## Versioning

```go
const superSRTVersion = 5  // in supersrt.go
```

- Cache key includes version: `v5:saint-seiya:1:1`
- Generated JSON includes `"v": 5`
- Bump the constant → all cached outputs stale → regenerated on next request
- Client can check `v` to invalidate local cache

Bump when: series dict changes, MeCab dict updated, Jitendex updated, inflection logic changes, output format changes.

## File Layout

```
/data/janus/
  jitendex.bin              — Jitendex O(1) hash binary (63MB, FNV-1a, JDC4 format)
  dicts/
    saint-seiya.json        — series definitions (69 entries)
    saint-seiya.csv         — MeCab user dict source
    saint-seiya.dic         — compiled MeCab user dict

server-go/
  supersrt.go               — Go endpoint, caching, Python subprocess
  gen_super_srt.py           — generator: MeCab → dict lookup → JSON
  jitendex_reader.py         — Jitendex binary reader (FNV-1a hash, JDC4 format)
  SUPER-SRT.md               — this document
```

## Endpoint

```
GET /api/super-srt/{itemId}/{season}/{episode}
Authorization: Bearer <token>
```

Returns supercharged SRT JSON. First request runs the generator (~600ms), subsequent requests served from memory cache (~60µs).

## Coverage (Saint Seiya ep1-8)

| Source | Words | % |
|--------|-------|---|
| Jitendex | 1192 | 79.6% |
| Series dict | 86 | 5.7% |
| No match (punct/SFX) | 219 | 14.6% |
| **Real gaps** | **3** | **0.2%** |

- 447 unique dict entries per episode
- 89KB JSON per episode
- 69 series dict entries cover all Saint Seiya-specific terms

## Client Contract

The client receives the JSON and:
1. Parses cues into timed subtitle entries
2. At each video position, finds the active cue
3. Renders the display text from concatenated `w[].s` values
4. Stores character bounding boxes in screen coordinates at layout time
5. On tap: checks boxes → finds character index → maps to word span → reads `d` index → shows dict entry
6. On D-pad: navigates between word spans, shows dict entry for focused word
7. Inflection tag shown alongside the definition

No dictionary. No deinflection. No tokenization. Pure rendering.

## Adding a New Series

1. Watch a few episodes, note custom terms (ateji, names, attacks)
2. Create `dicts/{series-id}.csv` with MeCab entries
3. Compile: `mecab-dict-index -d /usr/share/mecab/dic/ipadic -u {series-id}.dic -f utf-8 -t utf-8 {series-id}.csv`
4. Create `dicts/{series-id}.json` with English definitions
5. Upload to server, bump `superSRTVersion`
6. All episodes for that series get custom tokenization + definitions

Series without custom dicts still work — standard MeCab + Jitendex covers 94%+ of words.
