"""OSRS Wiki crafting recipes -> content/skills/crafting crafting.tsv.

Args: recipes.json item_ids.json sym_dir out.tsv log.tsv
  recipes.json  fetch_recipes.py Crafting recipes.json
  item_ids.json tools/drop-tables/fetch_item_ids.py

Covers (low-to-mid tiers, one mechanic each):
  spin     spinning wheel (wool, flax, sinew/roots, hair, hemp)
  loom     loom (cloth, linen, sacks, drift net, basket, canvas)
  wheel    potter's wheel (soft clay -> unfired pottery)
  oven     pottery oven (unfired -> fired pottery)
  furnace  gold/silver jewellery with a mould (tool column = mould), molten glass
  needle   needle + leather / hard leather (thread used once per 5 items, handled in code)
  chisel   uncut gem -> cut gem
  pipe     glassblowing pipe + molten glass
  string   ball of wool + unstrung amulet/symbol
  combine  clay + water container -> soft clay (container returned)

Skipped (see the log): quest/minigame items, dragonhide/snakeskin/yak (later PR), crushed-gem
failure rolls (semi-precious gems never crush here), golden wool, Lunar "String Jewellery".

Output (tab separated):
  recipe <station> <tool|-> <product> <count> <level> <xp> <ticks> <anim|-> <leftover|->
         <material1> <qty1> [<material2> <qty2> ...]
"""
import json, os, re, sys

recipes = json.load(open(sys.argv[1], encoding="utf-8"))
item_ids = json.load(open(sys.argv[2], encoding="utf-8"))
sym_dir, out_path, log_path = sys.argv[3:6]


def read_sym(name):
    out = {}
    for line in open(os.path.join(sym_dir, name), encoding="utf-8"):
        p = line.rstrip("\n").split("\t")
        if len(p) == 2 and p[0].isdigit():
            out[int(p[0])] = p[1]
    return out


obj_sym, seq_sym = read_sym("obj.sym"), read_sym("seq.sym")
seq_names = set(seq_sym.values())
BAD_OBJ = re.compile(r"fake|dummy|placeholder|_cert|noted|trail_|league|deadman|tut|_unused|_dummy")


def norm(s):
    return re.sub(r"[^a-z0-9]", "", s.lower())


def obj_name(item):
    ids = [i for i in item_ids.get(item, []) if i in obj_sym]
    if not ids:
        return None
    exact = [i for i in ids if norm(obj_sym[i]) == norm(item)]
    clean = [i for i in ids if not BAD_OBJ.search(obj_sym[i])]
    return obj_sym[min(exact or clean or ids)]


def level_xp(r):
    for s in r.get("skills") or []:
        if isinstance(s, dict) and s.get("name") == "Crafting":
            try:
                return int(s.get("level") or 1), float(s.get("experience") or 0)
            except ValueError:
                return None
    return None


STATIONS = {
    "Spinning wheel": "spin",
    "Loom": "loom",
    "Potter's Wheel": "wheel",
    "Pottery Oven": "oven",
    "Furnace": "furnace",
}
# Rev 243 renamed seq 894 (the spinning wheel anim, `human_spinningwheel` in 233).
ANIM = {
    "spin": "ghost_update_tendrill_ready_thrall",
    "loom": "farming_useloom",
    "wheel": "human_potterywheel",
    "oven": "human_furnace",
    "furnace": "human_furnace",
    "needle": "human_leather_crafting",
    "pipe": "human_glassblowing",
    "string": "-",
    "combine": "-",
}
GEM_ANIM = {
    "Opal": "human_opalcutting", "Jade": "human_jadecutting", "Red topaz": "human_redtopazcutting",
    "Sapphire": "human_sapphirecutting", "Emerald": "human_emeraldcutting",
    "Ruby": "human_rubycutting", "Diamond": "human_diamondcutting",
    "Dragonstone": "human_dragonstonecutting", "Onyx": "human_dragonstonecutting",
    "Zenyte": "human_dragonstonecutting",
}
SKIP = re.compile(
    r"golden wool|golden fleece|bronze key|'perfect'|conductor|silvthrill|demonic sigil|"
    r"m'speak|salve|xerician|pheasant|gem pouch|gem satchel|clothes pouch|fur pouch|meat pouch|"
    r"balloon|token|light orb|slayer ring|bellator|magus|ultor|venator|rabbit foot|eternal",
    re.I,
)
NEEDLE_MATS = {"Leather", "Hard leather", "Thread"}
PIPE_PRODUCTS = {"Beer glass", "Empty candle lantern", "Empty oil lamp", "Vial", "Empty fishbowl",
                 "Unpowered orb", "Lantern lens"}

log, rows, seen = [], [], set()


def emit(station, tool, product, count, lvl, xp, ticks, anim, leftover, mats):
    names = [obj_name(m) for m, _ in mats]
    prod, tool_o = obj_name(product), (obj_name(tool) if tool != "-" else "-")
    left = obj_name(leftover) if leftover != "-" else "-"
    if not prod or None in names or not tool_o or not left:
        log.append(f"skip-no-symbol\t{station}\t{product}\t{[m for m, _ in mats]}\t{tool}")
        return
    if anim != "-" and anim not in seq_names:
        log.append(f"no-anim\t{station}\t{product}\t{anim}")
        anim = "-"
    key = (station, tool_o, prod, tuple(names))
    if key in seen:
        return
    seen.add(key)
    cols = ["recipe", station, tool_o, prod, count, lvl, xp, ticks, anim, left]
    for n, (_, q) in zip(names, mats):
        cols += [n, q]
    rows.append("\t".join(map(str, cols)))


def qty(m):
    try:
        return int(float(m.get("quantity") or 1))
    except ValueError:
        return 1


for r in recipes:
    out = r.get("output")
    if not isinstance(out, dict) or not out.get("name"):
        continue
    product = out["name"]
    lx = level_xp(r)
    mats = [(m["name"], qty(m)) for m in r.get("materials") or []]
    fac = (r.get("facilities") or "").strip()
    tools = r.get("tools") or ""
    if not lx or not mats:
        continue
    if SKIP.search(product) or any(SKIP.search(m) for m, _ in mats):
        log.append(f"skip-special\t{product}")
        continue
    try:
        ticks = int(r.get("ticks") or 3)
    except ValueError:
        ticks = 3
    count = int(float(out.get("quantity") or 1))
    lvl, xp = lx
    station = STATIONS.get(fac)
    if station == "furnace":
        mould = re.search(r"([A-Z][a-z]+ mould)", tools or "")
        if mould:
            emit("furnace", mould.group(1), product, count, lvl, xp, ticks or 3, ANIM["furnace"], "-", mats)
        elif product == "Molten glass":
            emit("furnace", "-", product, count, lvl, xp, ticks or 3, ANIM["furnace"], "-", mats)
        else:
            log.append(f"skip-furnace\t{product}")
        continue
    if station:
        emit(station, "-", product, count, lvl, xp, ticks or 3, ANIM[station], "-", mats)
        continue
    if fac not in ("", "N/A"):
        log.append(f"skip-facility\t{fac}\t{product}")
        continue
    mat_names = {m for m, _ in mats}
    if tools.strip() == "Needle" and mat_names <= NEEDLE_MATS and "Thread" in mat_names:
        leather = [(m, q) for m, q in mats if m != "Thread"]
        emit("needle", "Needle", product, count, lvl, xp, ticks or 3, ANIM["needle"], "-", leather)
    elif tools.strip() == "Chisel" and product in GEM_ANIM and len(mats) == 1 and mats[0][0].startswith("Uncut"):
        emit("chisel", "Chisel", product, count, lvl, xp, ticks or 2, GEM_ANIM[product], "-", mats)
    elif tools.strip() == "Glassblowing pipe" and product in PIPE_PRODUCTS:
        emit("pipe", "Glassblowing pipe", product, count, lvl, xp, ticks or 3, ANIM["pipe"], "-", mats)
    elif not tools.strip() and "Ball of wool" in mat_names and len(mats) == 2:
        other = [(m, q) for m, q in mats if m != "Ball of wool"]
        emit("string", "Ball of wool", product, count, lvl, xp, ticks or 2, ANIM["string"], "-", other)
    else:
        log.append(f"skip-other\t{product}\t{tools}\t{sorted(mat_names)}")

# Soft clay: no Crafting level/xp, so the bucket has no Crafting recipe for it.
for water, empty in (("Jug of water", "Jug"), ("Bucket of water", "Bucket"), ("Bowl of water", "Bowl")):
    emit("combine", water, "Soft clay", 1, 1, 0.0, 1, "-", empty, [("Clay", 1)])

with open(out_path, "w", encoding="utf-8", newline="\n") as w:
    w.write("# Generated from the OSRS Wiki recipe bucket by tools/skills/crafting_to_tsv.py - do not edit.\n")
    w.write("\n".join(sorted(rows, key=lambda r: (r.split("\t")[1], int(r.split("\t")[5]), r))) + "\n")
with open(log_path, "w", encoding="utf-8", newline="\n") as w:
    w.write("\n".join(sorted(log)) + "\n")
from collections import Counter
print("rows", len(rows), Counter(r.split("\t")[1] for r in rows), "logged", len(log))
