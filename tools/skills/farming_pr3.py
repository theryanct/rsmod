"""Farming PR3 (fruit trees, bushes) -> content/skills/farming farming_pr3.tsv.

Args: dump_dir patches.json obj.sym out.tsv
  dump_dir     osrs-dumps rev243 dump.loc (patch multiloc tables: value -> stage loc)
  patches.json tools/skills/farming_patch_scan.py output (patch loc -> map tiles)

Crop data from each crop page's {{Farming info}} (level, payment, cycles x minutes, plant /
check-health / harvest xp, seed, crop); stage values from the patch's multiloc (seedling,
growing stages, fully grown, fruit/berry 1..N, check-health, stump) - explicit per crop.

Output (tab separated):
  crop  <key> <kind> <seed> <produce> <level> <plantXp> <checkXp> <harvestXp> <cycles> <minutes>
        <seed value> <grown value> <first fruit value> <max fruit> <check value> <stump value|-> <payment obj|-> <payment count>
  patch <key> <kind> <loc> <transmit varbit> <x> <z> <level>
"""
import json, os, re, sys, urllib.parse, urllib.request

dump_dir, patches_path, obj_sym_path, out_path = sys.argv[1:5]
API = "https://oldschool.runescape.wiki/api.php"
UA = {"User-Agent": "local-dev-tool/1.0 (personal offline project)"}
obj_sym = {int(l.split("\t")[0]): l.rstrip("\n").split("\t")[1] for l in open(obj_sym_path, encoding="utf-8") if "\t" in l}


def api(params):
    url = API + "?" + urllib.parse.urlencode({**params, "format": "json"})
    return json.load(urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=90))


_ids = {}


def obj(name):
    if name not in _ids:
        q = f"bucket('infobox_item').select('item_id','item_name').where('item_name',{json.dumps(name)}).run()"
        rows = api({"action": "bucket", "query": q}).get("bucket", [])
        _ids[name] = sorted({int(x) for r in rows for x in (r.get("item_id") or []) if str(x).isdigit()})
    ids = [i for i in _ids[name] if i in obj_sym and not re.search(r"placeholder|cert_|_noted", obj_sym[i])]
    return obj_sym[ids[0]] if ids else None


# Multilocs: loc -> {value: child}; child -> (name, ops)
multiloc, info, cur = {}, {}, None
for line in open(os.path.join(dump_dir, "dump.loc"), encoding="utf-8", errors="replace"):
    line = line.rstrip("\n")
    m = re.match(r"^\[(.+)\]$", line)
    if m:
        cur = m.group(1); multiloc[cur] = {}; info[cur] = ["", []]; continue
    if cur is None:
        continue
    if line.startswith("multiloc="):
        v, n = line[9:].split(",", 1)
        if v.isdigit(): multiloc[cur][int(v)] = n
    elif line.startswith("multivar="):
        info[cur].append(line[9:])
    elif re.match(r"op\d=", line):
        info[cur][1].append(line.split("=", 1)[1])

KINDS = {
    "FruitTree": ("farming_fruit_tree_patch_1", ["Apple tree", "Banana tree (Farming)", "Orange tree", "Curry tree", "Pineapple plant",
                                                 "Papaya tree", "Palm tree", "Dragonfruit tree"]),
    "Bush": ("farming_bush_patch_1", ["Redberry bush", "Cadavaberry bush", "Dwellberry bush", "Jangerberry bush",
                                      "Whiteberry bush", "Poison ivy bush"]),
    # PR3b
    "Cactus": ("farming_cactus_patch", ["Cactus (Farming)", "Potato cactus (plant)"]),
    "Hardwood": ("farming_hardwood_tree_patch_1", ["Teak tree (Farming)", "Mahogany tree (Farming)"]),
    "Belladonna": ("farming_belladonna_patch", ["Belladonna"]),
    "Mushroom": ("farming_mushroom_patch", ["Bittercap Mushrooms"]),
    # PR3c (growing only; spirit tree travel is a separate feature)
    "SpiritTree": ("farming_spirit_tree_patch_1", ["Spirit tree (Farming)"]),
}
FRUIT_KINDS = {"FruitTree", "Bush", "Cactus"}
# Hardwood chopping (Wiki Woodcutting): level, xp; logs obj.
WOOD = {"Teak tree (Farming)": (35, 85, "teak_logs"), "Mahogany tree (Farming)": (50, 125, "mahogany_logs")}
# Harvest lives / fixed yields (Wiki): bittercap 6 picks, belladonna 1 nightshade.
LIVES = {"Bittercap Mushrooms": 6}
FIXED = {"Belladonna": 1}
# Mainland patches in PR3a (Farming Guild / Varlamore / Prifddinas ones need areas we don't have).
PATCHES = {
    "fruit_gnome_stronghold": "farming_fruit_tree_patch_1",
    "fruit_tree_gnome_village": "farming_fruit_tree_patch_2",
    "fruit_brimhaven": "farming_fruit_tree_patch_3",
    "fruit_catherby": "farming_fruit_tree_patch_4",
    "fruit_lletya": "farming_fruit_tree_patch_5",
    "bush_champions_guild": "farming_bush_patch_1",
    "bush_rimmington": "farming_bush_patch_2",
    "bush_etceteria": "farming_bush_patch_3",
    "bush_ardougne": "farming_bush_patch_4",
    # PR3b
    "cactus_al_kharid": "farming_cactus_patch",
    "hardwood_fossil_1": "farming_hardwood_tree_patch_1",
    "hardwood_fossil_2": "farming_hardwood_tree_patch_2",
    "hardwood_fossil_3": "farming_hardwood_tree_patch_3",
    "belladonna_draynor": "farming_belladonna_patch",
    "mushroom_canifis": "farming_mushroom_patch",
    # PR3c
    "spirit_port_sarim": "farming_spirit_tree_patch_1",
    "spirit_etceteria": "farming_spirit_tree_patch_2",
    "spirit_brimhaven": "farming_spirit_tree_patch_3",
}
PATCH_KIND = {"fruit_tree": "FruitTree", "bush": "Bush", "cactus": "Cactus", "hardwood": "Hardwood",
              "belladonna": "Belladonna", "mushroom": "Mushroom",
              "spirit_tree": "SpiritTree"}


# Wiki page -> stage loc name prefix where they differ.
LOC_PREFIX = {"Pineapple plant": "pineapple_tree", "Poison ivy bush": "poisonivy_bush",
              "Cactus (Farming)": "cactus", "Potato cactus (plant)": "potato_cactus",
              "Teak tree (Farming)": "teak_tree", "Mahogany tree (Farming)": "mahogany_tree",
              "Bittercap Mushrooms": "bittercap", "Spirit tree (Farming)": "spirit_tree"}


def payment_item(value):
    """'{{plink|Cabbages|txt=sacks of cabbages}}' -> 'Cabbages(10)'; baskets -> '(5)'."""
    name = plink(value)
    if "(" in name:
        return name
    txt = value.lower()
    if "sack" in txt:
        return name + "(10)"
    if "basket" in txt:
        return name + "(5)"
    return name


def field(box, key):
    m = re.search(r"\|\s*" + key + r"\s*=\s*([^\n]*)", box)
    return m.group(1).strip() if m else ""


def plink(value):
    m = re.search(r"\{\{plink\|([^}|]+)", value)
    return m.group(1).strip() if m else re.sub(r"\[\[|\]\]", "", value).strip()


lines, warnings = [], []
for kind, (base, pages) in KINDS.items():
    table = multiloc[base]
    for page in pages:
        text = api({"action": "parse", "page": page, "prop": "wikitext", "redirects": 1})["parse"]["wikitext"]["*"]
        i = text.find("{{Farming info"); box = text[i:text.find("\n}}", i)]
        prefix = LOC_PREFIX.get(page, page.lower().replace(" (farming)", "").replace(" ", "_"))
        vals = {}
        for v, child in table.items():
            if child.startswith(prefix + "_"):
                vals.setdefault(child[len(prefix) + 1:], []).append(v)
        growing = sorted(v for s, vs in vals.items() if re.fullmatch(r"\d+", s) for v in vs)
        fruits = sorted(v for s, vs in vals.items() if re.fullmatch(r"(fruit|berry|spines)_\d+", s) for v in vs)
        first = lambda *keys: next((min(vals[k]) for k in keys if vals.get(k)), None)
        seedv = first("seedling", "seed")
        claim = first("claim_xp", "claimxp", "fullygrown_claimxp")
        stump = first("stump")
        if kind in ("Hardwood", "SpiritTree"):
            # Tree layout: grown (waiting for the check) = claim stage, checked = choppable tree.
            grown, check, fruits = claim, first("fullygrown"), [0]
        elif kind in FRUIT_KINDS:
            grown, check = first("fullygrown"), claim
        else:
            grown = min((v for s, vs in vals.items() if s.startswith("fullygrown") for v in vs), default=None)
            check, fruits = grown, [0]
        if seedv is None or grown is None or not fruits:
            warnings.append(f"{page}: stages {sorted(vals)[:12]}")
            continue
        if check is None:
            warnings.append(f"{page}: no check-health stage (uses last fruit)")
            check = fruits[-1]
        max_fruit = len(fruits) if kind in FRUIT_KINDS else 0
        tm = re.search(r"(\d+)\s*x\s*(\d+)\s*min", field(box, "time"))
        cycles, minutes = (int(tm.group(1)), int(tm.group(2))) if tm else (len(growing) + 1, 0)
        pay = field(box, "payment")
        pm = re.match(r"(\d+)\s*(.*)", pay)
        pay_obj = obj(payment_item(pm.group(2))) if pm and plink(pm.group(2)) else None
        if pay_obj is None and pay:
            # "sacks of cabbages" / "baskets of apples": the Wiki links the item name.
            warnings.append(f"{page}: payment '{pay}' not mapped")
        seed_name = plink(field(box, "sapling")) if kind in ("FruitTree", "Hardwood", "SpiritTree") else plink(field(box, "seed"))
        seed = obj(seed_name)
        # Belladonna's Wiki crop resolves to the stackable variant; the patch gives the plain item.
        produce = obj(seed_name) if kind == "SpiritTree" else WOOD[page][2] if page in WOOD else ("nightshade" if page == "Belladonna" else obj(plink(field(box, "crop"))))
        if not seed or not produce:
            warnings.append(f"{page}: seed={seed_name}->{seed} crop={field(box, 'crop')}->{produce}")
            continue
        key = prefix.replace("_tree", "").replace("_plant", "").replace("_bush", "").replace("poisonivy", "poison_ivy")
        num = lambda v: re.match(r"[\d.]+", v.replace(",", "")).group(0) if re.match(r"[\d.]+", v.replace(",", "")) else "0"
        wood = WOOD.get(page, (0, 0, "-"))
        if kind == "Hardwood":
            produce = wood[2]
        lines.append(["crop", key, kind, seed, produce, field(box, "level"), num(field(box, "plantxp")), num(field(box, "checkxp")),
                      num(field(box, "harvestxp")), cycles, minutes, seedv, grown, fruits[0], max_fruit, check,
                      stump if stump is not None else "-", pay_obj or "-", pm.group(1) if pm and pay_obj else 0,
                      wood[0], wood[1], wood[2], LIVES.get(page, 3), FIXED.get(page, 0)])

tiles = json.load(open(patches_path))
for key, loc in PATCHES.items():
    letter = info[loc][2] if len(info[loc]) > 2 else None
    x, z, level = tiles[loc][0]
    kind = next(v for k, v in PATCH_KIND.items() if loc.startswith("farming_" + k))
    lines.append(["patch", key, kind, loc, letter, x, z, level])

with open(out_path, "w", encoding="utf-8", newline="\n") as w:
    w.write("# Generated by tools/skills/farming_pr3.py (cache multilocs + Wiki Farming info) - do not edit.\n")
    for line in lines:
        w.write("\t".join(map(str, line)) + "\n")
print("crops", sum(l[0] == "crop" for l in lines), "patches", sum(l[0] == "patch" for l in lines))
for warning in warnings:
    print("  warn", warning, file=sys.stderr)
