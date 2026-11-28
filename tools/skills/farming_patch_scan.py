"""Find every farming patch loc placement in the cache map.

Args: cache_dir xteas.json loc.sym out.json   (prints name -> [(x, z, level)])
"""
import json, os, sys
here = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(here, "..", "agility")); sys.path.insert(0, os.path.join(here, "..", "npc-anims"))
from maplocs import locs_in_square, load_keys
from jagcache import Cache

cache_dir, keys_path, loc_sym, out_path = sys.argv[1:5]
cache = Cache(cache_dir); keys = load_keys(keys_path)
names = {int(l.split("\t")[0]): l.strip().split("\t")[1] for l in open(loc_sym, encoding="utf-8") if "\t" in l}
wanted = {i for i, n in names.items() if n.startswith("farming_") and "_patch" in n and not n.endswith("_canopy")}
found = {}
for key in keys:
    if not key.startswith("l"): continue
    mx, mz = (int(v) for v in key[1:].split("_"))
    for lid, x, z, plane, shape, rot in locs_in_square(cache, keys, mx, mz):
        if lid in wanted: found.setdefault(names[lid], []).append((x, z, plane))
json.dump(found, open(out_path, "w"), indent=1, sort_keys=True)
print(len(found), "patch locs")
