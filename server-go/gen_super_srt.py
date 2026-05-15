import subprocess, json, sys, os, re
from collections import OrderedDict
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import jitendex_reader

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
userdic = sys.argv[3] if len(sys.argv) > 3 else None
mecab_cmd = ['mecab']
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
        inflections = []
        if t['pos'] in ('動詞', '形容詞'):
            j = i + 1
            while j < len(tokens):
                nt = tokens[j]
                if nt['pos'] == '助動詞':
                    gs += nt['surface']
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
                    if nt['surface'] == 'ば': inflections.append('conditional')
                    j += 1
                else: break
            i = j
        else:
            i += 1
        
        # Add conjugation form label
        conj_label = INFLECT_LABELS.get(gconj, '')
        if conj_label and conj_label not in inflections:
            inflections.insert(0, conj_label)
        
        words.append({'surface': gs, 'base': gb, 'reading': gr, 'inflections': inflections})
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
    for line in text.split('\n'):
        for w in group_tokens(mecab_tokens(line)):
            word = {"s": w['surface']}
            if w['inflections']:
                word["i"] = '+'.join(w['inflections'])
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

output = {"dict": list(dict_table.values()), "cues": cues}
json.dump(output, sys.stdout, ensure_ascii=False, separators=(',', ':'))
print(f"\nDict: {len(dict_table)} | Cues: {len(cues)}", file=sys.stderr)
print(f"Words: {stats['total']} | Series: {stats['series']} | Jitendex: {stats['jitendex']} | None: {stats['none']}", file=sys.stderr)
