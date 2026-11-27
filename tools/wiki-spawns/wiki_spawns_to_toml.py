"""Convert wiki ItemSpawnLine data -> RSMod objs.toml. Args: pages.json obj.sym existing_objs.toml out.toml"""
import json, re, sys
pages = json.load(open(sys.argv[1], encoding="utf-8"))
sym = {}
for line in open(sys.argv[2], encoding="utf-8"):
    p = line.rstrip("\n").split("\t")
    if len(p) == 2 and p[0].isdigit(): sym[int(p[0])] = p[1]
existing = set(re.findall(r"coords = '([^']+)'", open(sys.argv[3], encoding="utf-8").read()))

def infobox_ids(text):
    m = re.search(r"\{\{Infobox Item(.*?)\n\}\}", text, re.S)
    if not m: return None, {}
    body = m.group(1); first = None; byname = {}
    for k, v in re.findall(r"\|\s*(id\d*)\s*=\s*([0-9][0-9, ]*)", body):
        i = int(v.split(",")[0].strip())
        if first is None: first = i
        n = k[2:]
        nm = re.search(r"\|\s*name%s\s*=\s*([^\n|]+)" % n, body) if n else None
        vm = re.search(r"\|\s*version%s\s*=\s*([^\n|]+)" % n, body) if n else None
        for x in (nm, vm):
            if x: byname[x.group(1).strip().lower()] = i
    return first, byname

out, skipped, seen = [], {"noid": 0, "nosym": 0, "dup": 0}, set()
for title, text in pages.items():
    first, byname = infobox_ids(text)
    for m in re.finditer(r"\{\{ItemSpawnLine(.*?)\}\}", text, re.S):
        parts = [p.strip() for p in m.group(1).split("|")[1:]]
        kv = {p.split("=", 1)[0].strip(): p.split("=", 1)[1].strip() for p in parts if "=" in p and not re.match(r"^\d", p)}
        name = kv.get("name", title).lower()
        oid = byname.get(name) or byname.get(name.replace(title.lower(), "").strip(" ()")) or first
        if oid is None: skipped["noid"] += 1; continue
        if oid not in sym: skipped["nosym"] += 1; continue
        plane = int(kv.get("plane", "0") or 0)
        for p in parts:
            c = re.match(r"^(\d+)\s*,\s*(\d+)(.*)$", p)
            if not c: continue
            x, z, rest = int(c.group(1)), int(c.group(2)), c.group(3)
            q = re.search(r"qty:(\d+)", rest); qty = int(q.group(1)) if q else 1
            coords = f"{plane}_{x>>6}_{z>>6}_{x&63}_{z&63}"
            key = (coords, oid)
            if coords in existing or key in seen: skipped["dup"] += 1; continue
            seen.add(key)
            out.append((sym[oid], qty, coords))
with open(sys.argv[4], "w", encoding="utf-8") as f:
    f.write("# Generated from OSRS Wiki ItemSpawnLine data by rsmod-tools/wiki_spawns_to_toml.py\n")
    for o, q, c in sorted(out, key=lambda t: t[2]):
        f.write(f"\n[[spawn]]\nobj = '{o}'\n" + (f"count = {q}\n" if q > 1 else "") + f"coords = '{c}'\n")
print("spawns", len(out), "skipped", skipped)
