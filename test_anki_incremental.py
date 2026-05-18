#!/usr/bin/env python3
"""
AnkiWeb incremental sync — push cards without downloading the collection.

Protocol: hostKey → meta (308 redirect) → start → applyChanges → finish
Transport: v10 protocol, anki-sync JSON header, zstd-compressed body

Usage:
    python test_anki_incremental.py                    # push a test card
    python test_anki_incremental.py --dry-run          # auth + meta only
    python test_anki_incremental.py --word 食べる --reading たべる --meaning "to eat" --sentence "魚を食べる"
"""

import argparse
import hashlib
import json
import sys
import time
import uuid

import requests
import zstandard

SYNC_URL = "https://sync.ankiweb.net"
EMAIL = "neo.buraq@gmail.com"
PASSWORD = "Buraq-2025"

# Stable IDs — reused across syncs so the deck/model aren't duplicated
DECK_ID = 1778789751000
MODEL_ID = 1778789751001

MODEL = {
    "id": MODEL_ID, "name": "Janus Mining", "type": 0,
    "mod": 0, "usn": -1, "sortf": 0, "did": DECK_ID,
    "tmpls": [{
        "name": "Recognition", "ord": 0, "did": None, "bafmt": "", "bqfmt": "",
        "qfmt": (
            "<div style='font-size:2em;color:#bb86fc'>{{Word}}</div>"
            "<div style='color:#aaa'>{{Reading}}</div><br>"
            "<div style='color:#ccc'>{{Sentence}}</div>"
        ),
        "afmt": (
            "{{FrontSide}}<hr>"
            "<div style='font-size:1.3em'>{{Meaning}}</div>"
            "<div style='color:#666;margin-top:10px'>{{Source}}</div>"
        ),
    }],
    "flds": [
        {"name": "Word",     "ord": 0, "sticky": False, "rtl": False, "font": "Noto Sans JP", "size": 20, "media": []},
        {"name": "Reading",  "ord": 1, "sticky": False, "rtl": False, "font": "Noto Sans JP", "size": 16, "media": []},
        {"name": "Meaning",  "ord": 2, "sticky": False, "rtl": False, "font": "Arial",        "size": 16, "media": []},
        {"name": "Sentence", "ord": 3, "sticky": False, "rtl": False, "font": "Noto Sans JP", "size": 14, "media": []},
        {"name": "Source",   "ord": 4, "sticky": False, "rtl": False, "font": "Arial",        "size": 12, "media": []},
    ],
    "css": ".card{font-family:'Noto Sans JP',sans-serif;background:#1a1a2e;color:white;text-align:center;padding:20px}",
    "tags": [], "vers": [], "req": [[0, "all", [0]]],
}

DECK = {
    "id": DECK_ID, "name": "Janus Mining", "mod": 0, "usn": -1,
    "collapsed": False, "desc": "", "dyn": 0, "conf": 1,
    "extendNew": 0, "extendRev": 0,
    "lrnToday": [0, 0], "newToday": [0, 0], "revToday": [0, 0], "timeToday": [0, 0],
}


class AnkiWebSync:
    def __init__(self):
        self.session_key = str(uuid.uuid4())
        self.hkey = ""
        self.base_url = SYNC_URL
        self.zc = zstandard.ZstdCompressor()
        self.zd = zstandard.ZstdDecompressor()

    def _header(self):
        return json.dumps({
            "v": 10, "k": self.hkey,
            "c": "anki,25.02.5,lin", "s": self.session_key,
        })

    def _request(self, method, data):
        compressed = self.zc.compress(json.dumps(data).encode())
        resp = requests.post(
            f"{self.base_url}/sync/{method}",
            data=compressed,
            headers={"anki-sync": self._header(), "Content-Type": "application/octet-stream"},
            allow_redirects=False,
            timeout=30,
        )
        if resp.status_code == 308:
            loc = resp.headers.get("Location", "")
            idx = loc.find("/sync/")
            self.base_url = loc[:idx] if idx > 0 else loc.rstrip("/")
            self.session_key = str(uuid.uuid4())
            return self._request(method, data)
        if resp.status_code != 200:
            raise RuntimeError(f"{method}: HTTP {resp.status_code} — {resp.content[:200].decode(errors='replace')}")
        try:
            return json.loads(self.zd.decompress(resp.content))
        except Exception:
            try:
                return json.loads(resp.content)
            except Exception:
                return resp.content

    def authenticate(self, email, password):
        result = self._request("hostKey", {"u": email, "p": password})
        self.hkey = result["key"]
        return self.hkey

    def meta(self):
        return self._request("meta", {"v": 10, "cv": "anki,25.02.5,lin"})

    def start(self, server_usn):
        return self._request("start", {
            "minUsn": server_usn, "lnewer": False,
            "graves": {"cards": [], "notes": [], "decks": []},
        })

    def push_cards(self, cards_data):
        """Push cards to AnkiWeb. Each card is a dict with word, reading, meaning, sentence, source."""
        now = int(time.time())
        now_ms = int(time.time() * 1000)

        MODEL["mod"] = now
        DECK["mod"] = now

        notes = []
        cards = []
        for i, c in enumerate(cards_data):
            note_id = now_ms + i * 2
            card_id = now_ms + i * 2 + 1
            guid = uuid.uuid4().hex[:10]

            fields = "\x1f".join([
                c.get("word", ""),
                c.get("reading", ""),
                c.get("meaning", ""),
                c.get("sentence", ""),
                c.get("source", ""),
            ])

            notes.append({
                "id": note_id, "guid": guid, "mid": MODEL_ID,
                "mod": now, "usn": -1, "tags": "janus",
                "flds": fields, "sfld": c.get("word", ""),
                "csum": int(hashlib.sha1(c.get("word", "").encode()).hexdigest()[:8], 16),
                "flags": 0, "data": "",
            })
            cards.append({
                "id": card_id, "nid": note_id, "did": DECK_ID, "ord": 0,
                "mod": now, "usn": -1, "type": 0, "queue": 0, "due": note_id,
                "ivl": 0, "factor": 0, "reps": 0, "lapses": 0, "left": 0,
                "odue": 0, "odid": 0, "flags": 0, "data": "",
            })

        return self._request("applyChanges", {
            "changes": {
                "models": [MODEL],
                "decks": [[DECK], []],
                "tags": ["janus"],
                "notes": notes,
                "cards": cards,
            }
        })

    def finish(self):
        return self._request("finish", {})

    def abort(self):
        try:
            self._request("abort", {})
        except Exception:
            pass


def main():
    parser = argparse.ArgumentParser(description="Push cards to AnkiWeb via incremental sync")
    parser.add_argument("--dry-run", action="store_true", help="Auth + meta only, don't push")
    parser.add_argument("--word", default="走る")
    parser.add_argument("--reading", default="はしる")
    parser.add_argument("--meaning", default="to run")
    parser.add_argument("--sentence", default="毎朝公園を走る。")
    parser.add_argument("--source", default="Janus test")
    args = parser.parse_args()

    sync = AnkiWebSync()

    print("1. AUTH")
    hkey = sync.authenticate(EMAIL, PASSWORD)
    print(f"   hkey: {hkey}")

    print("\n2. META")
    meta = sync.meta()
    server_usn = meta.get("usn", 0)
    print(f"   node: {sync.base_url}")
    print(f"   usn={server_usn} empty={meta.get('empty')} mod={meta.get('mod')}")

    if args.dry_run:
        print("\n--dry-run: stopping here")
        return

    print(f"\n3. START (minUsn={server_usn})")
    graves = sync.start(server_usn)
    print(f"   graves: {graves}")

    print(f"\n4. PUSH: {args.word}")
    result = sync.push_cards([{
        "word": args.word,
        "reading": args.reading,
        "meaning": args.meaning,
        "sentence": args.sentence,
        "source": args.source,
    }])
    print(f"   server returned: {list(result.keys()) if isinstance(result, dict) else result}")

    print("\n5. FINISH")
    ts = sync.finish()
    print(f"   timestamp: {ts}")

    print(f"\nDONE — '{args.word}' pushed to AnkiWeb!")


if __name__ == "__main__":
    main()
