"""Motherlode Mine map scan -> content/skills/mining motherlode.tsv.

Args: cache_dir xteas.json loc.sym out.tsv

The map only places depleted ore veins (the server makes them minable), the struts and the water
wheels; this lists their tiles so the script can set them up without hand-typed coordinates.

Output (tab separated):
  vein   <depleted loc> <x> <z> <level>
  strut  <loc> <x> <z> <level>
  wheel  <loc> <x> <z> <level>
"""
import os, sys

here = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(here, "..", "agility"))
sys.path.insert(0, os.path.join(here, "..", "npc-anims"))
from maplocs import locs_in_square, load_keys
from jagcache import Cache

cache_dir, keys_path, loc_sym, out_path = sys.argv[1:5]
cache = Cache(cache_dir)
keys = load_keys(keys_path)
names = {int(l.split("\t")[0]): l.strip().split("\t")[1] for l in open(loc_sym, encoding="utf-8") if "\t" in l}

rows = []
for mx in range(57, 60):
    for mz in range(87, 90):
        for lid, x, z, plane, shape, rot in locs_in_square(cache, keys, mx, mz):
            n = names.get(lid, "")
            if n.startswith("motherlode_depleted_"):
                rows.append(("vein", n, x, z, plane))
            elif n.startswith("motherlode_wheel_strut_"):
                rows.append(("strut", n, x, z, plane))
            elif n.startswith("motherlode_wheel_") :
                rows.append(("wheel", n, x, z, plane))

rows.sort()
with open(out_path, "w", encoding="utf-8", newline="\n") as w:
    w.write("# Generated from the cache map by tools/mining/motherlode_scan.py - do not edit.\n")
    for r in rows:
        w.write("\t".join(map(str, r)) + "\n")
print({k: sum(r[0] == k for r in rows) for k in ("vein", "strut", "wheel")})
