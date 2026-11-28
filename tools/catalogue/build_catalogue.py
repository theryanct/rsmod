"""Unused-asset catalogue: cache npcs, locs and objs that nothing in this server uses yet.

Usage: python -I tools/catalogue/build_catalogue.py <cache_dir> <symbols_dir> <repo_root>

"Unused" (heuristic, see tools/catalogue/summary.md):
- npc: named, and its symbol name appears nowhere in content/ (Kotlin, toml spawns, tsv data).
- loc: named, not placed on the map (archive 5 l-files), and its symbol isn't referenced in content/.
- obj: named, not a cert/placeholder, and its symbol isn't referenced in content/.

Writes <id>\t<symbol>\t<name> TSVs into the gallery module's resources, tools/catalogue/summary.md,
and prints candidate gallery map squares (terrain present, plane 0 walkable, no locs, no spawns).
"""
import collections
import os
import re
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "npc-anims"))
sys.path.insert(0, os.path.join(HERE, "..", "cache-check"))
from jagcache import Cache  # noqa: E402
import opwalk  # noqa: E402

CONFIG, MAPS = 2, 5
GROUPS = {"npc": 9, "loc": 6, "obj": 10}
OUT_DIR = "content/other/gallery/src/main/resources/org/rsmod/content/other/gallery"


def config_names(cache, kind):
    """id -> display name. Opcode 2 is the name for npc/loc/obj; it comes early, so stop there."""
    out = {}
    gid = GROUPS[kind]
    for fid, data in cache.group_files(CONFIG, gid).items():
        name = scan_name(kind, data)
        if name and name.lower() != "null":
            out[fid] = name
    return out


def scan_name(kind, data):
    """Walk the record with opwalk's opcode tables; opcode 2 is the name."""
    b = opwalk.Buf(data)
    try:
        while True:
            op = b.u8()
            if op == 0:
                return None
            if op == 2:
                return b.string().decode("latin1")
            opwalk.WALKERS[kind](op, b)
    except Exception:  # noqa: BLE001
        return None


def sym(path):
    out = {}
    for line in open(path, encoding="latin1"):
        line = line.rstrip("\r\n")
        if "\t" in line:
            k, v = line.split("\t", 1)
            if k.isdigit():
                out[int(k)] = v
    return out


def content_tokens(root):
    tokens = set()
    rx = re.compile(r"[a-z0-9_]+")
    for base, dirs, files in os.walk(os.path.join(root, "content")):
        dirs[:] = [d for d in dirs if d not in ("build", ".gradle")]
        if "gallery" in base.replace("\\", "/").split("/"):
            continue
        for f in files:
            if f.endswith((".kt", ".toml", ".tsv", ".json")):
                text = open(os.path.join(base, f), encoding="utf-8", errors="replace").read()
                tokens.update(rx.findall(text))
    return tokens


def map_square(cache, mx, mz):
    """(terrain, locs) bytes of a rev 237+ map square: group = (mx << 8) | mz, files 0 / 1."""
    files = cache.group_files(MAPS, (mx << 8) | mz)
    return files.get(0), files.get(1)


def placed_locs(buf):
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

    out = []
    loc_id = -1
    while True:
        d = incr_smart()
        if d == 0:
            break
        loc_id += d
        while True:
            dp = usmart()
            if dp == 0:
                break
            p += 1
            out.append(loc_id)
    return out


def terrain_stats(buf):
    """Plane-0 tile counts of an m-file: (with an underlay, blocked by settings bit 1, with an overlay)."""
    p = 0
    under = blocked = over = 0
    for plane in range(4):
        for _x in range(64):
            for _z in range(64):
                has_under = has_over = False
                settings = 0
                while True:
                    op = struct.unpack_from(">H", buf, p)[0]; p += 2
                    if op == 0:
                        break
                    if op == 1:
                        p += 1
                        break
                    if op <= 49:
                        p += 2
                        has_over = True
                    elif op <= 81:
                        settings = op - 49
                    else:
                        has_under = True
                if plane == 0:
                    under += has_under
                    blocked += settings & 1
                    over += has_over
    return under, blocked, over


def main():
    cache_dir, sym_dir, root = sys.argv[1:4]
    cache = Cache(cache_dir)
    tokens = content_tokens(root)
    syms = {k: sym(os.path.join(sym_dir, f"{k}.sym")) for k in ("npc", "loc", "obj")}

    placed = set()
    squares = {}
    for mx in range(20, 64):
        for mz in range(20, 200):
            try:
                mbuf, lbuf = map_square(cache, mx, mz)
            except Exception:  # noqa: BLE001
                continue
            if lbuf is None and mbuf is None:
                continue
            locs = placed_locs(lbuf) if lbuf else []
            placed.update(locs)
            if mbuf:
                try:
                    under, blocked, over = terrain_stats(mbuf)
                except Exception:  # noqa: BLE001
                    under, blocked, over = 0, 4096, 4096
                squares[(mx, mz)] = (len(locs), under, blocked, over, locs)

    spawn_squares = set()
    for base, dirs, files in os.walk(os.path.join(root, "content")):
        dirs[:] = [d for d in dirs if d != "build"]
        for f in files:
            if f.endswith(".toml"):
                text = open(os.path.join(base, f), encoding="utf-8", errors="replace").read()
                for lvl, mx, mz, lx, lz in re.findall(r"'(\d+)_(\d+)_(\d+)_(\d+)_(\d+)'", text):
                    spawn_squares.add((int(mx), int(mz)))

    rows = {}
    counts = collections.OrderedDict()
    for kind in ("npc", "loc", "obj"):
        names = config_names(cache, kind)
        out = []
        for i, name in sorted(names.items()):
            s = syms[kind].get(i, "")
            if not s or s in tokens:
                continue
            if kind == "obj" and (s.startswith(("cert_", "placeholder_")) or "_dummy" in s):
                continue
            if kind == "loc" and i in placed:
                continue
            out.append((i, s, name))
        rows[kind] = out
        counts[kind] = (len(out), len(names))

    os.makedirs(os.path.join(root, OUT_DIR), exist_ok=True)
    for kind, out in rows.items():
        with open(os.path.join(root, OUT_DIR, f"{kind}s.tsv"), "w", encoding="utf-8", newline="\n") as fh:
            fh.write("# id\tsymbol\tname (generated by tools/catalogue/build_catalogue.py)\n")
            for i, s, name in out:
                fh.write(f"{i}\t{s}\t{name}\n")

    loc_names = config_names(cache, "loc")
    cands = []
    for (mx, mz), (nlocs, under, blocked, over, locs) in squares.items():
        if nlocs <= 40 and under >= 4000 and blocked <= 50 and over <= 300:
            if (mx, mz) in spawn_squares:
                continue
            seen = collections.Counter(loc_names.get(i, "?") for i in locs)
            cands.append((nlocs + over, mx, mz, blocked, over, seen.most_common(4)))
    cands.sort()

    with open(os.path.join(HERE, "summary.md"), "w", encoding="utf-8", newline="\n") as fh:
        fh.write("# Unused-asset catalogue\n\n")
        fh.write("Generated by `tools/catalogue/build_catalogue.py` from the 243 cache. ")
        fh.write("Browse it in game with `::gallery` (see content/other/gallery).\n\n")
        fh.write("| kind | unused | named in cache |\n|---|---|---|\n")
        for kind, (n, total) in counts.items():
            fh.write(f"| {kind} | {n} | {total} |\n")
        fh.write("\n## Rules (heuristics)\n\n")
        fh.write("- Named in the cache (not \"null\") and has a symbol name.\n")
        fh.write("- Its symbol name appears in no content/ `.kt`, `.toml`, `.tsv` or `.json` file ")
        fh.write("(scripts, spawns, drop/shop data). Cache-driven uses that never name the symbol ")
        fh.write("(enums, structs, dbrows, transforms) are not seen, so some listed assets are used.\n")
        fh.write("- Locs: also not placed anywhere on the map.\n")
        fh.write("- Objs: certs, placeholders and dummies are left out.\n")
        fh.write("\n## Gallery map square candidates\n\n")
        fh.write("Few locs and overlays, no npc spawns, plane 0 has an underlay everywhere and is ")
        fh.write("walkable.\n\n")
        for _, mx, mz, blocked, over, seen in cands[:15]:
            fh.write(f"- {mx}_{mz} (tiles {mx * 64},{mz * 64}): blocked {blocked}, ")
            fh.write(f"overlay tiles {over}, locs {seen}\n")
    for kind, (n, total) in counts.items():
        print(kind, n, "/", total)
    for cand in cands[:20]:
        print("cand", cand[1:])


if __name__ == "__main__":
    main()
