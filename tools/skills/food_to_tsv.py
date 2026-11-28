"""OSRS Wiki "Food/All food" table -> content/generic/generic-food food.tsv.

Args: allfood.json item_ids.json sym_dir cache_dir out.tsv log.tsv
  allfood.json  api.php?action=parse&page=Food/All_food&prop=wikitext&format=json
  item_ids.json tools/drop-tables/fetch_item_ids.py

Every listed food whose cache obj has an "Eat" or "Drink" inventory op becomes a row; multi-bite
foods ("6 x 2" heals) become one row per bite, chained to the next bite's obj (pie -> half pie,
pizza -> 1/2 pizza, cake -> 2/3 cake -> slice) and finally to the leftover (pie dish, bowl, jug,
glass, cup) where OSRS gives one.

Output (tab separated):
  food <obj> <heal> <eat|drink> <next obj|-> <combo 0|1>
"""
import json, os, re, sys

allfood_path, ids_path, sym_dir, cache_dir, out_path, log_path = sys.argv[1:7]
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "npc-anims"))
from jagcache import Cache

text = json.load(open(allfood_path, encoding="utf-8"))["parse"]["wikitext"]["*"]
item_ids = json.load(open(ids_path, encoding="utf-8"))
sym = {}
for line in open(os.path.join(sym_dir, "obj.sym"), encoding="utf-8"):
    p = line.rstrip("\n").split("\t")
    if len(p) == 2 and p[0].isdigit():
        sym[int(p[0])] = p[1]
by_name = {v: k for k, v in sym.items()}
objs = Cache(cache_dir).group_files(2, 10)
BAD = re.compile(r"placeholder|cert_|_cert|dummy|fake|league|deadman|br_|trail_|tut|^keg_|^brutal_")


def iops(obj_id):
    data = objs.get(obj_id, b"")
    return [m.decode("latin1") for m in re.findall(rb"[\x23-\x27]([A-Z][A-Za-z \-]{1,20})\x00", data)]


def edible(obj_id):
    ops = iops(obj_id)
    if "Eat" in ops:
        return "eat"
    if "Drink" in ops:
        return "drink"
    return None


# Next-bite rules by cache name; the last bite's leftover by name.
BITE_RULES = [
    (re.compile(r"^(?!half_)(.*_pie)$"), lambda m: next(
        (n for n in (f"half_a_{m.group(1)}", f"half_an_{m.group(1)}", f"half_{m.group(1)}")
         if n in by_name), None)),
    (re.compile(r"^(?!half_)(.*_pizza)$"), lambda m: f"half_{m.group(1)}"),
    (re.compile(r"^cake$"), lambda m: "partial_cake"),
    (re.compile(r"^partial_cake$"), lambda m: "cake_slice"),
    (re.compile(r"^chocolate_cake$"), lambda m: "partial_chocolate_cake"),
    (re.compile(r"^partial_chocolate_cake$"), lambda m: "chocolate_slice"),
]
LEFTOVERS = [
    (re.compile(r"_pie$"), "piedish"),
    (re.compile(r"stew|curry|_bowl$|^bowl_"), "bowl_empty"),
    (re.compile(r"wine"), "jug_empty"),
    (re.compile(r"beer|ale$|cider|stout|asgarnian|wizards_mind|dwarven|greenmans|moonlight"), "beer_glass"),
    (re.compile(r"cup_of_tea|^tea$"), "cup_empty"),
]

log, rows = [], {}
for row in re.split(r"\n\|-\n", text):
    m = re.search(r"\{\{plinkt\|([^}|]+)", row)
    if not m:
        continue
    name = m.group(1).strip()
    cells = row.split("\n|")
    heal_cell = cells[1] if len(cells) > 1 else ""
    heal_cell = re.sub(r"data-sort-value=[^|]*\|", "", heal_cell).strip()
    hm = re.match(r"^(\d+)\s*(?:\D+\s*(\d+))?", heal_cell)
    if not hm:
        log.append(f"skip\t{name}\theal={heal_cell[:30]}")
        continue
    per_bite = int(hm.group(1))
    bites = int(hm.group(2)) if hm.group(2) else 1
    ids = item_ids.get(name) or item_ids.get(name[:1].upper() + name[1:]) or []
    matched = False
    for obj_id in ids:
        obj = sym.get(obj_id)
        if not obj or BAD.search(obj):
            continue
        kind = edible(obj_id)
        if not kind:
            continue
        matched = True
        chain = [obj]
        for _ in range(bites - 1):
            nxt = None
            for rule, make in BITE_RULES:
                mm = rule.match(chain[-1])
                if mm:
                    nxt = make(mm)
                    break
            if not nxt or nxt not in by_name:
                log.append(f"chain\t{name}\t{chain[-1]} -> {nxt}")
                break
            chain.append(nxt)
        for i, bite in enumerate(chain):
            nxt = chain[i + 1] if i + 1 < len(chain) else None
            if nxt is None:
                nxt = next((left for rule, left in LEFTOVERS if rule.search(bite)), None)
                if nxt and nxt not in by_name:
                    nxt = None
            combo = 1 if "karambwan" in bite and "cooked" in bite else 0
            rows.setdefault(bite, (bite, per_bite, kind, nxt or "-", combo))
    if not matched:
        log.append(f"nomatch\t{name}\tids={ids}")

with open(out_path, "w", encoding="utf-8", newline="\n") as f:
    f.write("# Generated from the OSRS Wiki \"Food/All food\" table by tools/skills/food_to_tsv.py\n")
    f.write("# food <obj> <heal> <eat|drink> <next obj|-> <combo 0|1>\n")
    for bite, heal, kind, nxt, combo in sorted(rows.values()):
        f.write(f"food\t{bite}\t{heal}\t{kind}\t{nxt}\t{combo}\n")
open(log_path, "w", encoding="utf-8", newline="\n").write("\n".join(log) + "\n")
print("foods", len(rows), "log", len(log))
