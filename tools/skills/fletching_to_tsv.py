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
# Gem bolt tips (chisel) and tipped bolts (Wiki Recipe templates).
MANUAL += [
    ("chisel", "opal_bolttips", 12, 11, 1.5, 5, "human_opalcutting", [("opal", 1)]),
    ("chisel", "xbows_bolt_tips_jade", 12, 26, 2, 5, "human_jadecutting", [("jade", 1)]),
    ("chisel", "pearl_bolttips", 6, 41, 3.2, 5, "xbows_fletching_pearl", [("smalloysterpearls", 1)]),
    ("chisel", "pearl_bolttips", 24, 41, 3.2, 5, "xbows_fletching_pearl", [("bigoysterpearls", 1)]),
    ("chisel", "xbows_bolt_tips_redtopaz", 12, 48, 3.9, 5, "human_redtopazcutting", [("red_topaz", 1)]),
    ("chisel", "xbows_bolt_tips_sapphire", 12, 56, 4, 5, "human_sapphirecutting", [("sapphire", 1)]),
    ("chisel", "xbows_bolt_tips_emerald", 12, 58, 5.5, 5, "human_emeraldcutting", [("emerald", 1)]),
    ("chisel", "xbows_bolt_tips_ruby", 12, 63, 6.3, 5, "human_rubycutting", [("ruby", 1)]),
    ("chisel", "xbows_bolt_tips_diamond", 12, 65, 7, 5, "human_diamondcutting", [("diamond", 1)]),
    ("chisel", "xbows_bolt_tips_dragonstone", 12, 71, 8.2, 5, "human_dragonstonecutting", [("dragonstone", 1)]),
    ("chisel", "xbows_bolt_tips_onyx", 24, 73, 9.4, 5, "human_onyxcutting", [("onyx", 1)]),
    ("combine", "opal_bolt", 10, 11, 16, 2, "human_fletching_add_bolt_tips_bronze", [("bolt", 10), ("opal_bolttips", 10)]),
    ("combine", "xbows_crossbow_bolts_blurite_tipped_jade", 10, 26, 24, 2, "human_fletching_add_bolt_tips_blurite", [("xbows_crossbow_bolts_blurite", 10), ("xbows_bolt_tips_jade", 10)]),
    ("combine", "pearl_bolt", 10, 41, 32, 2, "human_fletching_add_bolt_tips_iron", [("xbows_crossbow_bolts_iron", 10), ("pearl_bolttips", 10)]),
    ("combine", "xbows_crossbow_bolts_steel_tipped_redtopaz", 10, 48, 39, 2, "human_fletching_add_bolt_tips_steel", [("xbows_crossbow_bolts_steel", 10), ("xbows_bolt_tips_redtopaz", 10)]),
    ("combine", "xbows_crossbow_bolts_mithril_tipped_sapphire", 10, 56, 47, 2, "human_fletching_add_bolt_tips_mithril", [("xbows_crossbow_bolts_mithril", 10), ("xbows_bolt_tips_sapphire", 10)]),
    ("combine", "xbows_crossbow_bolts_mithril_tipped_emerald", 10, 58, 55, 2, "human_fletching_add_bolt_tips_mithril", [("xbows_crossbow_bolts_mithril", 10), ("xbows_bolt_tips_emerald", 10)]),
    ("combine", "xbows_crossbow_bolts_adamantite_tipped_ruby", 10, 63, 63, 2, "human_fletching_add_bolt_tips_adamant", [("xbows_crossbow_bolts_adamantite", 10), ("xbows_bolt_tips_ruby", 10)]),
    ("combine", "xbows_crossbow_bolts_adamantite_tipped_diamond", 10, 65, 70, 2, "human_fletching_add_bolt_tips_adamant", [("xbows_crossbow_bolts_adamantite", 10), ("xbows_bolt_tips_diamond", 10)]),
    ("combine", "xbows_crossbow_bolts_runite_tipped_dragonstone", 10, 71, 82, 2, "human_fletching_add_bolt_tips_rune", [("xbows_crossbow_bolts_runite", 10), ("xbows_bolt_tips_dragonstone", 10)]),
    ("combine", "xbows_crossbow_bolts_runite_tipped_onyx", 10, 73, 94, 2, "human_fletching_add_bolt_tips_rune", [("xbows_crossbow_bolts_runite", 10), ("xbows_bolt_tips_onyx", 10)]),
]
# Amethyst ammunition (Wiki Recipe templates).
MANUAL += [
    ("combine", "amethyst_arrow", 15, 82, 202.5, 2, "-", [("headless_arrow", 15), ("amethyst_arrowheads", 15)]),
    ("combine", "slayer_broad_bolt_amethyst", 10, 76, 106, 2, "-", [("slayer_broad_bolt", 10), ("xbows_bolt_tips_amethyst", 10)]),
    ("combine", "amethyst_dart", 10, 90, 210, 1, "-", [("amethyst_dart_tip", 10), ("feather", 10)]),
    ("combine", "amethyst_javelin", 15, 84, 202.5, 1, "-", [("javelin_shaft", 15), ("amethyst_javelin_head", 15)]),
]
# Ogre arrows + brutal arrows (Wiki Recipe templates; Big Chompy Bird Hunting).
MANUAL += [
    ("knife", "ogre_arrow_shaft", 4, 5, 6.4, 3, "human_fletching", [("achey_tree_logs", 1)]),
    ("string", "ogre_headless_arrow", 6, 5, 5.4, 2, "-", [("ogre_arrow_shaft", 6), ("feather", 24)]),
    ("string", "ogre_arrow", 6, 5, 6, 2, "-", [("ogre_headless_arrow", 6), ("wolfbone_arrowheads", 6)]),
    ("chisel", "wolfbone_arrowheads", 4, 5, 10, 2, "-", [("wolf_bones", 1)]),
] + [("hammer", f"zogre_brutal_{m}", 6, lv, xp, 2, "-", [("ogre_headless_arrow", 6), (n, 6)]) for m, n, lv, xp in [
    ("bronze", "nails_bronze", 7, 8.4), ("iron", "nails_iron", 18, 15.6), ("steel", "nails", 33, 30.6),
    ("black", "nails_black", 38, 39), ("mithril", "nails_mithril", 49, 45), ("adamant", "nails_adamant", 62, 61.2),
    ("rune", "nails_rune", 77, 75)]]
# Wasp darts (The Birds and the Wasps; Wiki Recipe templates): wasp stings with any of ten feather
# kinds. The bucket rows aren't plain-feather sets, so kind_of() skips them; Fletching.kt gates
# both on the quest varbit `baw`.
BAW_FEATHERS = ["feather", "hunting_desert_feather", "hunting_woodland_feather", "hunting_jungle_feather",
                "hunting_polar_feather", "baw_brown_feather", "baw_black_feather", "hunting_stripy_bird_feather",
                "gryphon_feather", "stymphike_feather"]
MANUAL += [("combine", "baw_wasp_dart", 10, 1, 5, 1, "human_fletching_add_dart_feathers_wasp",
            [("baw_wasp_sting", 10), (f, 10)]) for f in BAW_FEATHERS]
MANUAL += [("combine", "baw_killer_wasp_dart", 10, 15, 25, 1, "human_fletching_add_dart_feathers_killer_wasp",
            [("baw_killer_wasp_sting", 10), (f, 10)]) for f in BAW_FEATHERS]
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
