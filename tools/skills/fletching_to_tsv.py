"""OSRS Wiki fletching recipes -> content/skills/fletching fletching.tsv.

Args: recipes.json item_ids.json sym_dir out.tsv log.tsv
  recipes.json  fetch_recipes.py Fletching recipes.json
  item_ids.json tools/drop-tables/fetch_item_ids.py

Covers: knife on logs (arrow shafts, unstrung bows, stocks...), bow string on unstrung bows, and
"sets" combining two items (arrow shafts + feathers, headless arrows + arrowtips, dart tips +
feathers, unfinished bolts + feathers - plain feathers only).

Output (tab separated):
  recipe <kind knife|string|combine> <product> <product qty> <level> <xp> <ticks> <anim|->
         <material1> <qty1> [<material2> <qty2>]
For combine recipes qty1/qty2 are the set size; xp is for a full set.
"""
import json, os, re, sys
from collections import Counter

recipes = json.load(open(sys.argv[1], encoding="utf-8"))
item_ids = json.load(open(sys.argv[2], encoding="utf-8"))
sym_dir, out_path, log_path = sys.argv[3:6]


def read_sym(name):
    out = {}
    for line in open(os.path.join(sym_dir, name), encoding="utf-8"):
        p = line.rstrip("\n").split("\t")
        if len(p) == 2 and p[0].isdigit(): out[int(p[0])] = p[1]
    return out


obj_sym, seq_sym = read_sym("obj.sym"), read_sym("seq.sym")
seq_names = set(seq_sym.values())
BAD_OBJ = re.compile(r"fake|dummy|placeholder|_cert|noted|trail_|league|deadman|tut")


def norm(s): return re.sub(r"[^a-z0-9]", "", s.lower())


def obj_name(item):
    ids = [i for i in item_ids.get(item, []) if i in obj_sym]
    if not ids: return None
    exact = [i for i in ids if norm(obj_sym[i]) == norm(item)]
    clean = [i for i in ids if not BAD_OBJ.search(obj_sym[i])]
    return obj_sym[min(exact or clean or ids)]


def skill(r):
    skills = r.get("skills") or []
    if isinstance(skills, dict): skills = [skills]
    for s in skills:
        if isinstance(s, dict) and s.get("name") == "Fletching":
            try: return int(s["level"]), float(s["experience"])
            except (KeyError, ValueError): return None
    return None


def kind_of(r):
    mats = [m["name"] for m in r.get("materials") or []]
    tool = (r.get("tools") or "").lower()
    if "knife" in tool and len(mats) == 1 and mats[0].lower().endswith("logs"): return "knife"
    if len(mats) == 2 and not tool:
        a, b = mats
        if "Bow string" in mats and any(m.endswith("(u)") for m in mats): return "string"
        pair = {a, b}
        if pair == {"Arrow shaft", "Feather"}: return "combine"
        if "Headless arrow" in pair and any(m.endswith("arrowtips") for m in mats): return "combine"
        if "Feather" in pair and any(m.endswith("dart tip") or m.endswith("bolts (unf)") for m in mats): return "combine"
    return None


log, stats, rows, seen = [], Counter(), [], set()
for r in recipes:
    kind = kind_of(r)
    if kind is None: continue
    sk = skill(r)
    out = r.get("output") or {}
    product = obj_name(out.get("name", ""))
    mats = [(obj_name(m["name"]), int(re.match(r"\d+", str(m["quantity"])).group(0))) for m in r["materials"]]
    if sk is None or product is None or any(m[0] is None for m in mats):
        stats["unresolved"] += 1; log.append((r["page"], "unresolved item or skill", str(mats))); continue
    if kind == "string":  # unstrung bow first, then the bow string
        mats.sort(key=lambda m: m[0] == "bow_string")
    if kind == "combine":  # the item used on the other: feathers / tips go second
        mats.sort(key=lambda m: m[0] in ("feather",) or "tip" in m[0] or "arrowheads" in m[0])
    if re.search(r"brew_|thproto|tut", product):  # minigame / quest-only products
        stats["excluded"] += 1; continue
    key = (kind, product, mats[0][0])
    if key in seen: continue
    seen.add(key)
    qty = int(re.match(r"\d+", str(out.get("quantity", "1"))).group(0))
    ticks = int(re.match(r"\d+", str(r.get("ticks") or "0")).group(0)) if re.match(r"\d+", str(r.get("ticks") or "0")) else 0
    if kind == "knife": anim = "human_fletching"
    elif kind == "string" and f"stringing_{product}" in seq_names: anim = f"stringing_{product}"
    else: anim = "-"
    rows.append((kind, product, qty, sk[0], sk[1], max(ticks, 1) if kind != "knife" else max(ticks, 2), anim, mats))
    stats[kind] += 1

# Recipes the Wiki recipe bucket lacks (Adamant bolts has no bucket row): (kind, product, qty, level, xp, ticks, anim, mats)
MANUAL = [("combine", "xbows_crossbow_bolts_adamantite", 10, 61, 70, 1, "-",
           [("xbows_crossbow_bolts_adamantite_unfeathered", 10), ("feather", 10)])]
# Crossbows (Wiki Recipe templates; kind "hammer" = limbs on stock with a hammer in the inventory).
XBOWS = [("bronze", "wood", 9, 12, 6), ("blurite", "oak", 24, 32, 16), ("iron", "willow", 39, 44, 22),
         ("steel", "teak", 46, 54, 27), ("mithril", "maple", 54, 64, 32), ("adamantite", "mahogany", 61, 82, 41),
         ("runite", "yew", 69, 100, 50), ("dragon", "magic", 78, 135, 70)]
for metal, stock, lvl, hammer_xp, string_xp in XBOWS:
    MANUAL.append(("hammer", f"xbows_crossbow_unstrung_{metal}", 1, lvl, hammer_xp, 2, "-",
                   [(f"xbows_crossbow_stock_{stock}", 1), (f"xbows_crossbow_limbs_{metal}", 1)]))
    anim = f"xbows_fletching_{stock}_{metal}"
    MANUAL.append(("string", f"xbows_crossbow_{metal}", 1, lvl, string_xp, 2, anim if anim in seq_names else "-",
                   [(f"xbows_crossbow_unstrung_{metal}", 1), ("xbows_crossbow_string", 1)]))
for m in MANUAL:
    if (m[0], m[1], m[7][0][0]) not in seen: rows.append(m)

with open(out_path, "w", encoding="utf-8", newline="\n") as w:
    w.write("# Generated from the OSRS Wiki recipe bucket by tools/skills/fletching_to_tsv.py - do not edit.\n")
    for kind, product, qty, level, xp, ticks, anim, mats in sorted(rows, key=lambda t: (t[0], t[3], t[1])):
        m = "\t".join(f"{o}\t{q}" for o, q in mats)
        w.write(f"recipe\t{kind}\t{product}\t{qty}\t{level}\t{xp:g}\t{ticks}\t{anim}\t{m}\n")
with open(log_path, "w", encoding="utf-8", newline="\n") as f:
    f.write("page\treason\tdetail\n")
    for row in sorted(set(log)): f.write("\t".join(row) + "\n")
print(dict(stats))
