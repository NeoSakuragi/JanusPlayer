#!/usr/bin/env python3
"""Test AnkiWeb sync protocol — authenticate, download collection, add a test card, upload."""

import gzip
import hashlib
import io
import json
import random
import sqlite3
import struct
import sys
import time

import requests

SYNC_URL = "https://sync.ankiweb.net/sync"
BOUNDARY = "Anki-sync-boundary"

def build_multipart(fields: dict, data: bytes) -> bytes:
    out = io.BytesIO()
    for k, v in fields.items():
        out.write(f"--{BOUNDARY}\r\n".encode())
        out.write(f'Content-Disposition: form-data; name="{k}"\r\n\r\n'.encode())
        out.write(f"{v}\r\n".encode())
    out.write(f"--{BOUNDARY}\r\n".encode())
    out.write(b'Content-Disposition: form-data; name="data"; filename="data"\r\n')
    out.write(b"Content-Type: application/octet-stream\r\n\r\n")
    out.write(data)
    out.write(f"\r\n--{BOUNDARY}--\r\n".encode())
    return out.getvalue()

def sync_request(endpoint, payload, hkey=None, skey=None, compress=False, raw=False):
    fields = {}
    if hkey: fields["k"] = hkey
    if skey: fields["s"] = skey
    fields["c"] = "1" if compress else "0"

    data = gzip.compress(payload) if compress else payload
    body = build_multipart(fields, data)

    resp = requests.post(
        f"{SYNC_URL}/{endpoint}",
        data=body,
        headers={
            "Content-Type": f"multipart/form-data; boundary={BOUNDARY}",
            "User-Agent": "Anki 2.1.15",
        },
        timeout=120,
    )
    resp.raise_for_status()

    if raw:
        return resp.content

    try:
        return gzip.decompress(resp.content)
    except:
        return resp.content

def authenticate(email, password):
    payload = json.dumps({"u": email, "p": password}).encode()
    resp = sync_request("hostKey", payload)
    data = json.loads(resp)
    return data["key"]

def download_collection(hkey):
    skey = hashlib.md5(str(random.random()).encode()).hexdigest()[:8]
    return sync_request("download", b"{}", hkey, skey, raw=True)

def upload_collection(hkey, db_bytes):
    skey = hashlib.md5(str(random.random()).encode()).hexdigest()[:8]
    resp = sync_request("upload", db_bytes, hkey, skey, raw=True)
    return resp.decode().strip()

def field_checksum(field):
    h = hashlib.sha1(field.encode("utf-8")).digest()
    return struct.unpack(">I", h[:4])[0]

def generate_guid():
    chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    return "".join(random.choice(chars) for _ in range(10))


def main():
    if len(sys.argv) < 3:
        print("Usage: python test_anki_sync.py <email> <password> [--dry-run]")
        sys.exit(1)

    email = sys.argv[1]
    password = sys.argv[2]
    dry_run = "--dry-run" in sys.argv

    # 1. Authenticate
    print(f"Authenticating as {email}...")
    hkey = authenticate(email, password)
    print(f"  hkey: {hkey[:16]}...")

    # 2. Download collection
    print("Downloading collection...")
    db_bytes = download_collection(hkey)
    db_path = "/tmp/anki_test_sync.db"
    with open(db_path, "wb") as f:
        f.write(db_bytes)
    print(f"  Downloaded: {len(db_bytes)} bytes")

    # 3. Inspect current state
    db = sqlite3.connect(db_path)
    cur = db.cursor()

    cur.execute("SELECT models FROM col")
    models = json.loads(cur.fetchone()[0])
    print(f"\n  Models ({len(models)}):")
    for mid, m in models.items():
        fld_names = [f["name"] for f in m["flds"]]
        print(f"    {m['name']} (id={mid}): {fld_names}")

    cur.execute("SELECT decks FROM col")
    decks = json.loads(cur.fetchone()[0])
    print(f"\n  Decks ({len(decks)}):")
    for did, d in decks.items():
        print(f"    {d['name']} (id={did})")

    cur.execute("SELECT count(*) FROM notes")
    note_count = cur.fetchone()[0]
    cur.execute("SELECT count(*) FROM cards")
    card_count = cur.fetchone()[0]
    print(f"\n  Notes: {note_count}, Cards: {card_count}")

    cur.execute("SELECT id, sfld, tags FROM notes ORDER BY id DESC LIMIT 5")
    print("\n  Recent notes:")
    for row in cur.fetchall():
        print(f"    {row[0]}: {row[1]} [{row[2]}]")

    if dry_run:
        print("\n--dry-run: skipping card creation and upload")
        db.close()
        return

    # 4. Add a test card
    # Find "Immersion Sentences" model
    model_id = None
    for mid, m in models.items():
        if m["name"] in ("Immersion Sentences", "Janus Immersion Card"):
            model_id = int(mid)
            field_names = [f["name"] for f in m["flds"]]
            break
    if not model_id:
        model_id = int(list(models.keys())[0])
        field_names = [f["name"] for f in models[str(model_id)]["flds"]]

    # Find target deck
    deck_id = 1
    for did, d in decks.items():
        if d["name"] in ("Janus Mining", "Immersion"):
            deck_id = int(did)
            break

    now_ms = int(time.time() * 1000)
    now_s = int(time.time())
    note_id = now_ms
    card_id = now_ms + 1
    guid = generate_guid()

    # Build fields for "Immersion Sentences" type
    test_word = "試験"
    test_fields = {
        "Front": test_word,
        "Back": "test; trial; examination",
        "Reading": "<ruby>試験<rt>しけん</rt></ruby>",
        "Sentence": "これは試験カードです",
        "Kanji": "試験",
        "Source": "Janus sync test",
    }

    values = []
    for fn in field_names:
        values.append(test_fields.get(fn, ""))
    flds = "\x1f".join(values)
    sfld = test_word
    csum = field_checksum(sfld)

    # Get next due position
    cur.execute("SELECT conf FROM col")
    conf = json.loads(cur.fetchone()[0])
    next_pos = conf.get("nextPos", 1)
    conf["nextPos"] = next_pos + 1

    print(f"\n  Adding test card: {test_word} (note={note_id}, card={card_id})")
    print(f"  Model: {models[str(model_id)]['name']}, Deck: {decks[str(deck_id)]['name']}")
    print(f"  Fields: {field_names}")

    cur.execute(
        "INSERT INTO notes (id, guid, mid, mod, usn, tags, flds, sfld, csum, flags, data) VALUES (?, ?, ?, ?, -1, ?, ?, ?, ?, 0, '')",
        (note_id, guid, model_id, now_s, "janus test", flds, sfld, csum),
    )
    cur.execute(
        "INSERT INTO cards (id, nid, did, ord, mod, usn, type, queue, due, ivl, factor, reps, lapses, left, odue, odid, flags, data) VALUES (?, ?, ?, 0, ?, -1, 0, 0, ?, 0, 0, 0, 0, 0, 0, 0, 0, '')",
        (card_id, note_id, deck_id, now_s, next_pos),
    )
    cur.execute("UPDATE col SET mod = ?, conf = ?", (now_ms, json.dumps(conf)))
    db.commit()

    # Verify
    cur.execute("SELECT count(*) FROM notes")
    new_note_count = cur.fetchone()[0]
    print(f"  Notes after: {new_note_count} (was {note_count})")

    db.close()

    # 5. Upload
    print("\nUploading modified collection...")
    with open(db_path, "rb") as f:
        modified_bytes = f.read()
    result = upload_collection(hkey, modified_bytes)
    print(f"  Upload result: {result}")

    if result == "OK":
        print("\nSUCCESS — card pushed to AnkiWeb!")
        print(f"Open Anki desktop/mobile and sync to see '{test_word}' in your deck.")
    else:
        print(f"\nFAILED: {result}")


if __name__ == "__main__":
    main()
