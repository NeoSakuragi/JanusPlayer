import subprocess, json, sys, os, re
from collections import OrderedDict
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import jitendex_reader

# ── Furigana helpers ──
def _is_kanji(c):
    cp = ord(c)
    return (0x4E00 <= cp <= 0x9FFF or 0x3400 <= cp <= 0x4DBF or
            0xF900 <= cp <= 0xFAFF or 0x20000 <= cp <= 0x2A6DF)

def _is_kana(c):
    cp = ord(c)
    return (0x3040 <= cp <= 0x309F or 0x30A0 <= cp <= 0x30FF)

def _kata_to_hira(s):
    return ''.join(chr(ord(c) - 0x60) if 0x30A1 <= ord(c) <= 0x30F6 else c for c in s)

def _hira_normalize(c):
    if 0x30A1 <= ord(c) <= 0x30F6:
        return chr(ord(c) - 0x60)
    return c

_VOICING = {
    'か': 'が', 'き': 'ぎ', 'く': 'ぐ', 'け': 'げ', 'こ': 'ご',
    'さ': 'ざ', 'し': 'じ', 'す': 'ず', 'せ': 'ぜ', 'そ': 'ぞ',
    'た': 'だ', 'ち': 'ぢ', 'つ': 'づ', 'て': 'で', 'と': 'ど',
    'は': 'ば', 'ひ': 'び', 'ふ': 'ぶ', 'へ': 'べ', 'ほ': 'ぼ',
}
_SEMI_VOICING = {
    'は': 'ぱ', 'ひ': 'ぴ', 'ふ': 'ぷ', 'へ': 'ぺ', 'ほ': 'ぽ',
}

def _voiced_variants(reading):
    """Generate voicing/semi-voicing variants of a reading (rendaku)."""
    vs = [reading]
    if reading and reading[0] in _VOICING:
        vs.append(_VOICING[reading[0]] + reading[1:])
    if reading and reading[0] in _SEMI_VOICING:
        vs.append(_SEMI_VOICING[reading[0]] + reading[1:])
    return vs

def _try_split_kanji_reading(kanji_str, reading, kr_table):
    """Try to split a reading across multiple kanji using the readings table.
    Returns list of per-kanji readings or None."""
    n = len(kanji_str)
    def bt(ki, ri):
        if ki == n:
            return [] if ri == len(reading) else None
        possible = kr_table.get(kanji_str[ki], [])
        # Try longest readings first to prefer specific matches (e.g. ふか over ふ)
        for r in sorted(possible, key=len, reverse=True):
            for v in _voiced_variants(r):
                if reading[ri:ri+len(v)] == v:
                    rest = bt(ki + 1, ri + len(v))
                    if rest is not None:
                        return [v] + rest
        return None
    return bt(0, 0)

def compute_furigana(surface, reading_kata, kr_table):
    """Compute per-kanji furigana for a word.
    Returns list of [char_index, hiragana_reading] or None if no kanji."""
    if not reading_kata:
        return None
    reading = _kata_to_hira(reading_kata)

    kanji_indices = [i for i, c in enumerate(surface) if _is_kanji(c)]
    if not kanji_indices:
        return None

    # If surface == reading in hiragana, no furigana needed
    if _kata_to_hira(surface) == reading:
        return None

    # Step 1: Kana-matching — walk surface and reading in parallel
    segments = []
    si, ri = 0, 0
    ok = True
    while si < len(surface) and ri < len(reading):
        if _is_kana(surface[si]):
            surface_hira = _hira_normalize(surface[si])
            if ri < len(reading) and reading[ri] == surface_hira:
                si += 1
                ri += 1
            else:
                ok = False
                break
        elif _is_kanji(surface[si]):
            kanji_start = si
            while si < len(surface) and _is_kanji(surface[si]):
                si += 1
            if si < len(surface) and _is_kana(surface[si]):
                next_hira = _hira_normalize(surface[si])
                match_pos = -1
                # Start from ri+1: kanji must consume at least one reading char
                for rj in range(ri + 1, len(reading)):
                    if reading[rj] == next_hira:
                        match_pos = rj
                        break
                if match_pos >= 0:
                    segments.append((kanji_start, si, reading[ri:match_pos]))
                    ri = match_pos
                else:
                    ok = False
                    break
            else:
                segments.append((kanji_start, si, reading[ri:]))
                ri = len(reading)
        else:
            si += 1
            ri += 1

    if not ok or not segments:
        if kanji_indices:
            return [[kanji_indices[0], reading]]
        return None

    # Step 2: For each kanji span, try per-kanji split using readings table
    result = []
    for start, end, span_reading in segments:
        kanji_count = end - start
        if kanji_count == 1:
            result.append([start, span_reading])
        else:
            split = _try_split_kanji_reading(surface[start:end], span_reading, kr_table)
            if split:
                for i, rdg in enumerate(split):
                    result.append([start + i, rdg])
            else:
                # Fallback: assign entire reading to first kanji in span
                result.append([start, span_reading])

    return result if result else None

# ── Load kanji readings table ──
_kr_path = os.environ.get('KANJI_READINGS_PATH', '/data/janus/kanji_readings.json')
_kanji_readings = {}
if os.path.exists(_kr_path):
    _kanji_readings = json.load(open(_kr_path))

# ── Inflection labels from MeCab conjugation forms ──
INFLECT_LABELS = {
    '未然形': '', '連用形': '', '終止形': '', '連体形': '', '仮定形': 'conditional',
    '命令ｅ': 'imperative', '命令ｉ': 'imperative', '命令ｒｏ': 'imperative',
    '基本形': '', '体言接続': '', '仮定縮約１': 'conditional',
}

# ── Load series dict ──
series_dict = {}
dict_path = sys.argv[2] if len(sys.argv) > 2 else None
if dict_path and os.path.exists(dict_path):
    for entry in json.load(open(dict_path)):
        series_dict[entry["term"]] = {
            "r": entry["reading"], "m": [entry["meaning"]], "jlpt": "", "freq": 0
        }

# ── MeCab ──
mecab_cmd = ['mecab']
# Load common dict (always) + series dict (if exists)
common_dic = os.environ.get('COMMON_DIC_PATH', '/data/janus/dicts/common.dic')
if os.path.exists(common_dic): mecab_cmd += ['-u', common_dic]
userdic = sys.argv[3] if len(sys.argv) > 3 else None
if userdic and os.path.exists(userdic): mecab_cmd += ['-u', userdic]

# ── Parse SRT ──
with open(sys.argv[1], 'r') as sf: srt = sf.read()

ts_re = re.compile(r'(\d{2}):(\d{2}):(\d{2})[,.](\d{3})')
def parse_ts(s):
    m = ts_re.match(s.strip())
    return int(m[1])*3600000 + int(m[2])*60000 + int(m[3])*1000 + int(m[4]) if m else 0

def mecab_tokens(text):
    result = subprocess.run(mecab_cmd, input=text, capture_output=True, text=True)
    tokens = []
    for line in result.stdout.strip().split('\n'):
        if line == 'EOS': break
        parts = line.split('\t')
        if len(parts) < 2: continue
        info = parts[1].split(',')
        tokens.append({
            'surface': parts[0],
            'base': info[6] if len(info) > 6 and info[6] != '*' else parts[0],
            'reading': info[7] if len(info) > 7 and info[7] != '*' else '',
            'pos': info[0], 'pos2': info[1] if len(info) > 1 else '',
            'conj': info[5] if len(info) > 5 and info[5] != '*' else '',
        })
    return tokens

def group_tokens(tokens):
    words = []
    i = 0
    while i < len(tokens):
        t = tokens[i]
        gs, gb, gr, gconj = t['surface'], t['base'], t['reading'], t['conj']
        sub_readings = [t['reading']]  # per-token readings for novice/intermediate splitting
        inflections = []
        if t['pos'] in ('動詞', '形容詞'):
            j = i + 1
            while j < len(tokens):
                nt = tokens[j]
                if nt['pos'] == '助動詞':
                    gs += nt['surface']
                    gr += nt['reading']
                    sub_readings.append(nt['reading'])
                    base = nt['base']
                    if base == 'ない': inflections.append('negative')
                    elif base == 'た': inflections.append('past')
                    elif base == 'ます': inflections.append('polite')
                    elif base == 'れる' or base == 'られる': inflections.append('passive')
                    elif base == 'せる' or base == 'させる': inflections.append('causative')
                    elif base == 'まい': inflections.append('negative volitional')
                    j += 1
                elif nt['pos'] == '動詞' and nt['pos2'] in ('接尾', '非自立'):
                    gs += nt['surface']
                    gr += nt['reading']
                    sub_readings.append(nt['reading'])
                    base = nt['base']
                    if base in ('しまう',): inflections.append('てしまう')
                    elif base in ('もらう',): inflections.append('てもらう')
                    elif base in ('くれる',): inflections.append('てくれる')
                    elif base in ('あげる',): inflections.append('てあげる')
                    elif base in ('おく',): inflections.append('ておく')
                    elif base in ('みる',): inflections.append('てみる')
                    elif base in ('いく',): inflections.append('ていく')
                    elif base in ('くる',): inflections.append('てくる')
                    elif base in ('いる',): inflections.append('ている')
                    j += 1
                elif nt['pos'] == '助詞' and nt['pos2'] == '接続助詞' and nt['surface'] in ('て', 'で', 'ば'):
                    gs += nt['surface']
                    gr += nt['reading']
                    sub_readings.append(nt['reading'])
                    if nt['surface'] == 'ば': inflections.append('conditional')
                    j += 1
                else: break
            i = j
        else:
            i += 1

        conj_label = INFLECT_LABELS.get(gconj, '')
        if conj_label and conj_label not in inflections:
            inflections.insert(0, conj_label)

        words.append({'surface': gs, 'base': gb, 'reading': gr, 'sub_readings': sub_readings, 'inflections': inflections})
    return words

# ── Build ──
dict_table = OrderedDict()
dict_keys = []
cues = []
stats = {"total": 0, "series": 0, "jitendex": 0, "none": 0}

def get_dict_idx(key, entry):
    if key not in dict_table:
        dict_table[key] = {"t": key.split("|")[0], **entry}
        dict_keys.append(key)
    return dict_keys.index(key)

for block in srt.strip().split('\n\n'):
    lines = block.split('\n')
    if len(lines) < 3: continue
    ts_parts = lines[1].split('-->')
    if len(ts_parts) != 2: continue
    start, end = parse_ts(ts_parts[0]), parse_ts(ts_parts[1])
    text = '\n'.join(lines[2:]).strip()
    
    cue_words = []
    text_lines = text.split('\n')
    for line_idx, line in enumerate(text_lines):
        if line_idx > 0:
            cue_words.append({"s": "\n"})
        # Strip spaces for MeCab, keep original for display
        original = line
        clean = original.replace(' ', '')
        grouped = group_tokens(mecab_tokens(clean))
        # Map MeCab words to character ranges in the original string
        oi = 0
        for w in grouped:
            # Emit spaces from original before this word
            space_start = oi
            while oi < len(original) and original[oi] == ' ':
                oi += 1
            if oi > space_start:
                cue_words.append({"s": original[space_start:oi]})
            word_start = oi
            # Match characters (skipping any embedded spaces)
            matched = 0
            while matched < len(w['surface']) and oi < len(original):
                if original[oi] == ' ':
                    oi += 1
                    continue
                matched += 1
                oi += 1
            surface = original[word_start:oi]
            word = {"s": surface}
            if w['reading']:
                word["r"] = w['reading']
            if len(w['sub_readings']) > 1:
                word["sr"] = w['sub_readings']
            if w['inflections']:
                word["i"] = '+'.join(w['inflections'])
            # Compute per-kanji furigana
            if w['reading'] and _kanji_readings:
                furi = compute_furigana(surface, w['reading'], _kanji_readings)
                if furi:
                    word["f"] = furi
            stats["total"] += 1
            
            # 1. Series dict
            sd = series_dict.get(w['surface']) or series_dict.get(w['base'])
            if sd:
                key = (w['surface'] if w['surface'] in series_dict else w['base']) + "|" + sd["r"]
                word["d"] = get_dict_idx(key, sd)
                stats["series"] += 1
            else:
                # 2. Jitendex (with de-potentializing)
                entry, resolved, pot_tag = jitendex_reader.lookup_with_depotential(w['base'])
                if not entry:
                    entry, resolved, pot_tag = jitendex_reader.lookup_with_depotential(w['surface'])
                if entry:
                    key = resolved + "|" + entry["r"]
                    word["d"] = get_dict_idx(key, entry)
                    if pot_tag and 'i' not in word:
                        word["i"] = pot_tag
                    elif pot_tag:
                        word["i"] = pot_tag + '+' + word["i"]
                    stats["jitendex"] += 1
                else:
                    stats["none"] += 1
            
            cue_words.append(word)
    cues.append({"s": start, "e": end, "w": cue_words})

version = int(os.environ.get('SUPER_SRT_VERSION', '1'))
output = {"v": version, "dict": list(dict_table.values()), "cues": cues}
json.dump(output, sys.stdout, ensure_ascii=False, separators=(',', ':'))
print(f"\nDict: {len(dict_table)} | Cues: {len(cues)}", file=sys.stderr)
print(f"Words: {stats['total']} | Series: {stats['series']} | Jitendex: {stats['jitendex']} | None: {stats['none']}", file=sys.stderr)
