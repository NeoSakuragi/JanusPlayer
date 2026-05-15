import struct, mmap, os

DICT_PATH = os.environ.get('JITENDEX_PATH', '/data/janus/jitendex.bin')
f = open(DICT_PATH, 'rb')
mm = mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ)
mask = struct.unpack_from('<i', mm, 8)[0]
dataOffset = struct.unpack_from('<i', mm, 12)[0]

FNV_OFFSET = 0x811c9dc5
FNV_PRIME = 0x01000193

def fnv1a(tb):
    h = FNV_OFFSET
    for b in tb:
        h ^= b
        h = (h * FNV_PRIME) & 0xFFFFFFFF
    if h >= 0x80000000: h -= 0x100000000
    return h

def read_str(pos):
    end = mm.find(b'\x00', pos)
    return mm[pos:end].decode('utf-8', errors='replace'), end + 1

def lookup(term):
    tb = term.encode('utf-8')
    h = fnv1a(tb)
    slot = h & mask
    for _ in range(200):
        off = struct.unpack_from('<i', mm, 16 + slot * 4)[0]
        if off == 0: return None
        pos = dataOffset + off
        match = True
        for i, b in enumerate(tb):
            if pos + i >= len(mm) or mm[pos + i] != b:
                match = False; break
        if not match or mm[pos + len(tb)] != 0:
            slot = (slot + 1) & mask
            continue
        
        # Read entry
        term_s, p = read_str(pos)
        reading, p = read_str(p)
        tags = struct.unpack_from('<Q', mm, p)[0]; p += 8
        jlpt = mm[p]; p += 1
        fb = struct.unpack_from('<H', mm, p)[0]; p += 2
        fj = struct.unpack_from('<H', mm, p)[0]; p += 2
        fi = struct.unpack_from('<H', mm, p)[0]; p += 2
        fa = struct.unpack_from('<H', mm, p)[0]; p += 2
        
        mc = mm[p]; p += 1
        meanings = []
        for _ in range(mc):
            m, p = read_str(p)
            meanings.append(m)
        
        # Skip examples, pitch, nameType
        ec = mm[p]; p += 1
        for _ in range(ec):
            _, p = read_str(p)  # ja
            _, p = read_str(p)  # en
        pitch, p = read_str(p)
        name_type, p = read_str(p)
        
        return {
            "r": reading,
            "m": meanings[:3],
            "jlpt": f"N{jlpt}" if 1 <= jlpt <= 5 else "",
            "freq": max(fb, fj, fi, fa),
            "name": name_type
        }
    return None


# De-potentialize: 放せる→放す, 直せる→直す, etc.
_POT_MAP = {
    'せる': 'す', 'てる': 'つ', 'える': 'う',
    'ける': 'く', 'げる': 'ぐ', 'ねる': 'ぬ',
    'べる': 'ぶ', 'める': 'む', 'れる': 'る',
}

def lookup_with_depotential(term):
    """Try direct lookup, then try de-potentializing."""
    entry = lookup(term)
    if entry: return entry, term, ""
    # Try de-potential
    for suffix, replacement in _POT_MAP.items():
        if term.endswith(suffix) and len(term) > len(suffix):
            candidate = term[:-len(suffix)] + replacement
            entry = lookup(candidate)
            if entry: return entry, candidate, "potential"
    return None, term, ""
