"""Decode map loc placements (archive 5 "l<x>_<z>" groups, XTEA encrypted) from a JS5 disk cache.

Usage as a module: locs_in_square(cache, keys, mx, mz) -> [(loc_id, x, z, plane, shape, rotation)].
"""
import json, os, struct, sys
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "npc-anims"))
from jagcache import Cache

DELTA, ROUNDS = 0x9E3779B9, 32


def xtea_decrypt(data, key):
    out = bytearray(data)
    k = [x & 0xFFFFFFFF for x in key]
    for off in range(0, len(data) - len(data) % 8, 8):
        v0, v1 = struct.unpack_from(">II", data, off)
        s = (DELTA * ROUNDS) & 0xFFFFFFFF
        for _ in range(ROUNDS):
            v1 = (v1 - ((((v0 << 4) ^ (v0 >> 5)) + v0) ^ (s + k[(s >> 11) & 3]))) & 0xFFFFFFFF
            s = (s - DELTA) & 0xFFFFFFFF
            v0 = (v0 - ((((v1 << 4) ^ (v1 >> 5)) + v1) ^ (s + k[s & 3]))) & 0xFFFFFFFF
        struct.pack_into(">II", out, off, v0, v1)
    return bytes(out)


def load_keys(path):
    return {e["name"]: (e["group"], e["key"]) for e in json.load(open(path)) if e.get("archive") == 5}


def locs_in_square(cache, keys, mx, mz):
    entry = keys.get(f"l{mx}_{mz}")
    if entry is None: return []
    group, key = entry
    raw = cache.raw(5, group)
    if raw is None: return []
    ctype = raw[0]
    clen = struct.unpack(">i", raw[1:5])[0]
    enc_len = clen + (4 if ctype else 0)
    data = raw[:5] + xtea_decrypt(raw[5:5 + enc_len], key) + raw[5 + enc_len:]
    buf = Cache.decompress(data)
    p = 0

    def usmart():
        nonlocal p
        if buf[p] < 128:
            v = buf[p]; p += 1
        else:
            v = struct.unpack_from(">H", buf, p)[0] - 32768; p += 2
        return v

    def incr_smart():
        total, v = 0, usmart()
        while v == 32767:
            total += 32767; v = usmart()
        return total + v

    out, loc_id = [], -1
    while True:
        d = incr_smart()
        if d == 0: break
        loc_id += d
        pos = 0
        while True:
            dp = usmart()
            if dp == 0: break
            pos += dp - 1
            lz, lx, plane = pos & 63, (pos >> 6) & 63, pos >> 12
            attrs = buf[p]; p += 1
            out.append((loc_id, mx * 64 + lx, mz * 64 + lz, plane, attrs >> 2, attrs & 3))
    return out


if __name__ == "__main__":
    cache_dir, keys_path, x, z, r = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4]), int(sys.argv[5])
    c, keys = Cache(cache_dir), load_keys(keys_path)
    names = {int(l.split("\t")[0]): l.strip().split("\t")[1] for l in open(sys.argv[6], encoding="utf-8") if "\t" in l} if len(sys.argv) > 6 else {}
    squares = {((x + dx) >> 6, (z + dz) >> 6) for dx in (-r, r) for dz in (-r, r)}
    for mx, mz in sorted(squares):
        for loc in locs_in_square(c, keys, mx, mz):
            if abs(loc[1] - x) <= r and abs(loc[2] - z) <= r and (len(sys.argv) <= 7 or sys.argv[7] in names.get(loc[0], "")):
                print(loc, names.get(loc[0], ""))
