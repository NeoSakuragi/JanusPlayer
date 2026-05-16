#!/usr/bin/env python3
"""Extract per-kanji readings from MeCab's ipadic dictionary.

Produces a JSON map: { "食": ["た", "く", "しょく", ...], ... }

Sources:
1. Single-kanji base forms -> reading is directly that kanji's reading
2. Mixed kanji+kana base forms -> kana-matching to isolate kanji readings
   e.g. 食べる (たべる) -> 食=た
3. Two-kanji compound bootstrapping -> if one kanji's reading is known,
   infer the other (skips name/place CSVs to reduce noise)
"""

import glob, json, sys, os

def is_kanji(c):
    cp = ord(c)
    return (0x4E00 <= cp <= 0x9FFF or 0x3400 <= cp <= 0x4DBF or
            0xF900 <= cp <= 0xFAFF or 0x20000 <= cp <= 0x2A6DF)

def is_kana(c):
    cp = ord(c)
    return (0x3040 <= cp <= 0x309F or 0x30A0 <= cp <= 0x30FF)

def kata_to_hira(s):
    return ''.join(chr(ord(c) - 0x60) if 0x30A1 <= ord(c) <= 0x30F6 else c for c in s)

# CSV files containing mostly proper nouns — skip for compound bootstrapping
_NAME_FILES = {'Noun.name.csv', 'Noun.place.csv', 'Noun.proper.csv', 'Noun.org.csv'}

def extract_readings(ipadic_dir='/usr/share/mecab/dic/ipadic'):
    kanji_readings = {}
    two_kanji_compounds = []  # collected for step 3

    for csvpath in sorted(glob.glob(os.path.join(ipadic_dir, '*.csv'))):
        fname = os.path.basename(csvpath)
        is_name_file = fname in _NAME_FILES
        with open(csvpath, 'r', encoding='euc-jp', errors='replace') as fh:
            for line in fh:
                parts = line.strip().split(',')
                if len(parts) < 12:
                    continue
                base = parts[10]
                reading_kata = parts[11]
                if not reading_kata or reading_kata == '*':
                    continue
                reading = kata_to_hira(reading_kata)

                # 1. Single kanji base form
                if len(base) == 1 and is_kanji(base):
                    kanji_readings.setdefault(base, set()).add(reading)
                    continue

                # Collect 2-kanji pure compounds for step 3 (skip name files)
                if (not is_name_file and len(base) == 2
                        and all(is_kanji(c) for c in base)):
                    two_kanji_compounds.append((base, reading))

                # Skip if no kanji or no kana (can't do kana-matching)
                if not any(is_kanji(c) for c in base):
                    continue
                if not any(is_kana(c) for c in base):
                    continue

                # 2. Mixed kanji+kana: walk both in parallel to isolate kanji readings
                bi, ri = 0, 0
                segments = []
                ok = True
                while bi < len(base) and ri < len(reading):
                    if is_kana(base[bi]):
                        hira = kata_to_hira(base[bi])
                        if ri < len(reading) and reading[ri] == hira:
                            bi += 1
                            ri += 1
                        else:
                            ok = False
                            break
                    elif is_kanji(base[bi]):
                        kanji_start = bi
                        while bi < len(base) and is_kanji(base[bi]):
                            bi += 1
                        if bi < len(base) and is_kana(base[bi]):
                            next_hira = kata_to_hira(base[bi])
                            rj = ri
                            while rj < len(reading) and reading[rj] != next_hira:
                                rj += 1
                            if rj < len(reading):
                                kanji_span = base[kanji_start:bi]
                                reading_span = reading[ri:rj]
                                if len(kanji_span) == 1 and reading_span:
                                    segments.append((kanji_span, reading_span))
                                ri = rj
                            else:
                                ok = False
                                break
                        else:
                            kanji_span = base[kanji_start:bi]
                            reading_span = reading[ri:]
                            if len(kanji_span) == 1 and reading_span:
                                segments.append((kanji_span, reading_span))
                            ri = len(reading)
                    else:
                        bi += 1
                        ri += 1

                if ok:
                    for kanji, rdg in segments:
                        kanji_readings.setdefault(kanji, set()).add(rdg)

    # 3. Two-kanji compound bootstrapping
    # If one kanji's reading is already known, infer the other's reading.
    # Use a snapshot of initial readings to avoid cascading errors.
    initial = {k: set(v) for k, v in kanji_readings.items()}
    bootstrap_count = 0
    for base, reading in two_kanji_compounds:
        k1, k2 = base[0], base[1]
        r1_known = initial.get(k1, set())
        r2_known = initial.get(k2, set())

        for r1 in r1_known:
            if reading.startswith(r1) and len(reading) > len(r1):
                r2_cand = reading[len(r1):]
                if len(r2_cand) <= 3 and r2_cand not in kanji_readings.get(k2, set()):
                    kanji_readings.setdefault(k2, set()).add(r2_cand)
                    bootstrap_count += 1

        for r2 in r2_known:
            if reading.endswith(r2) and len(reading) > len(r2):
                r1_cand = reading[:len(reading) - len(r2)]
                if len(r1_cand) <= 3 and r1_cand not in kanji_readings.get(k1, set()):
                    kanji_readings.setdefault(k1, set()).add(r1_cand)
                    bootstrap_count += 1

    # Convert sets to sorted lists
    result = {k: sorted(v) for k, v in sorted(kanji_readings.items())}
    return result, bootstrap_count

if __name__ == '__main__':
    output_path = sys.argv[1] if len(sys.argv) > 1 else '/data/janus/kanji_readings.json'
    readings, bootstrapped = extract_readings()
    with open(output_path, 'w', encoding='utf-8') as f:
        json.dump(readings, f, ensure_ascii=False, indent=1)
    print(f"Extracted readings for {len(readings)} kanji ({bootstrapped} bootstrapped) -> {output_path}",
          file=sys.stderr)
