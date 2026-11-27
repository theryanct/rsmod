"""Convert wiki ItemSpawnLine data -> RSMod objs.toml.

Args: pages.json obj.sym out.toml existing_objs.toml [existing_objs.toml ...]

Spawns whose (coords, obj) already exist in any of the given existing toml files are skipped, so
hand-curated area modules take precedence. Pass every content objs.toml except the output file.
"""
import json, re, sys
from collections import Counter

pages = json.load(open(sys.argv[1], encoding="utf-8"))
sym = {}
for line in open(sys.argv[2], encoding="utf-8"):
    p = line.rstrip("\n").split("\t")
    if len(p) == 2 and p[0].isdigit(): sym[int(p[0])] = p[1]
out_path = sys.argv[3]
existing = set()
for path in sys.argv[4:]:
    text = open(path, encoding="utf-8").read()
    existing |= set(re.findall(r"obj = '([^']+)'\n(?:count = \d+\n)?coords = '([^']+)'", text))


def templates(text, name):
    """Yield the inner text of every {{name ...}} with balanced braces (nested templates kept)."""
    start = 0
    while (i := text.find("{{" + name, start)) >= 0:
        depth, j = 0, i
        while j < len(text):
            if text.startswith("{{", j): depth += 1; j += 2
            elif text.startswith("}}", j):
                depth -= 1; j += 2
                if depth == 0: break
            else: j += 1
        yield text[i + 2 + len(name):j - 2]
        start = j


def strip_nested(s):
    """Remove nested templates and <ref> tags so splitting on '|' is safe."""
    prev = None
    while prev != s:
        prev, s = s, re.sub(r"\{\{[^{}]*\}\}", "", s)
    return re.sub(r"<ref[^>]*/>|<ref[^>]*>.*?</ref>", "", s, flags=re.S)


def infobox_ids(text):
    body = next(templates(text, "Infobox Item"), None)
    if body is None: return None, {}
    first = None; byname = {}
    for k, v in re.findall(r"\|\s*(id\d*)\s*=\s*([0-9][0-9, ]*)", body):
        i = int(v.split(",")[0].strip())
        if first is None: first = i
        n = k[2:]
        nm = re.search(r"\|\s*name%s\s*=\s*([^\n|]+)" % n, body) if n else None
        vm = re.search(r"\|\s*version%s\s*=\s*([^\n|]+)" % n, body) if n else None
        for x in (nm, vm):
            if x: byname[x.group(1).strip().lower()] = i
    return first, byname


out, skipped, seen = [], Counter(), set()
for title, text in pages.items():
    first, byname = infobox_ids(text)
    for inner in templates(text, "ItemSpawnLine"):
        parts = [p.strip() for p in strip_nested(inner).split("|")[1:]]
        kv = {p.split("=", 1)[0].strip(): p.split("=", 1)[1].strip() for p in parts if "=" in p and not re.match(r"^\d", p)}
        name = kv.get("name", title).lower()
        oid = byname.get(name) or byname.get(name.replace(title.lower(), "").strip(" ()")) or first
        if oid is None: skipped["noid"] += 1; continue
        if oid not in sym: skipped["nosym"] += 1; continue
        line_plane = int(kv.get("plane", "0") or 0)
        for p in parts:
            c = re.match(r"^(\d+)\s*,\s*(\d+)(.*)$", p)
            if not c: continue
            x, z, rest = int(c.group(1)), int(c.group(2)), c.group(3)
            q = re.search(r"qty:(\d+)", rest); qty = int(q.group(1)) if q else 1
            pl = re.search(r"plane:(\d)", rest); plane = int(pl.group(1)) if pl else line_plane
            coords = f"{plane}_{x>>6}_{z>>6}_{x&63}_{z&63}"
            key = (sym[oid], coords)
            if key in existing: skipped["existing"] += 1; continue
            if key in seen: skipped["dup"] += 1; continue
            seen.add(key)
            out.append((sym[oid], qty, coords))
with open(out_path, "w", encoding="utf-8", newline="\n") as f:
    f.write("# Generated from OSRS Wiki ItemSpawnLine data by tools/wiki-spawns/wiki_spawns_to_toml.py\n")
    for o, q, c in sorted(out, key=lambda t: (t[2], t[0])):
        f.write(f"\n[[spawn]]\nobj = '{o}'\n" + (f"count = {q}\n" if q > 1 else "") + f"coords = '{c}'\n")
print("spawns", len(out), "skipped", dict(skipped))
