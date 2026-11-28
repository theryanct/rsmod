import sys, os, json
sys.path.insert(0, sys.argv[1]); sys.path.insert(0, os.path.join(sys.argv[1], "..", "npc-anims"))
from maplocs import locs_in_square, load_keys
from jagcache import Cache
cache = Cache(sys.argv[2]); keys = load_keys(sys.argv[3])
names = {int(l.split("\t")[0]): l.strip().split("\t")[1] for l in open(sys.argv[4], encoding="utf-8") if "\t" in l}
rooms = json.loads(sys.argv[5])  # {room: [zx, zz]}
out = {}
for room, (zx, zz) in rooms.items():
    mx, mz = zx // 8, zz // 8
    res = []
    for lid, x, z, plane, shape, rot in locs_in_square(cache, keys, mx, mz):
        if plane == 0 and x // 8 == zx and z // 8 == zz:
            res.append((names.get(lid, str(lid)), x % 8, z % 8, shape, rot))
    out[room] = sorted(res)
json.dump(out, open(sys.argv[6], "w"), indent=0)
for r, v in out.items():
    print("##", r); print("  " + "  ".join(f"{n}@{x},{z}s{s}r{o}" for n, x, z, s, o in v if n.startswith("poh_")))
