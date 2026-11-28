"""OSRS Wiki cooking recipes -> content/skills/cooking cooking.tsv.

Args: recipes.json item_ids.json sym_dir pages_cache.json out.tsv log.tsv
  recipes.json      fetch_recipes.py Cooking recipes.json
  item_ids.json     tools/drop-tables/fetch_item_ids.py
  pages_cache.json  wikitext cache of cooked-product pages (created/extended by this script)

Covers:
  heat     raw item on a fire or range -> cooked (or burnt) using the product page's
           "Skilling success chart" (Fire or Range / Range only / Lumbridge range / gauntlets).
  combine  two items used together -> product (pie/pizza/stew assembly, doughs, etc.), with the
           water/flour/milk containers handed back.

Skipped on purpose (see the log): gnome cooking, Chambers of Xeric, Mort Myre/quest specials,
cocktails, brewing, churning, Zamorak wine, poison karambwan, sinew/soda ash and anything without a
burn chart or object symbol.

Output (tab separated):
  heat    <raw> <cooked> <burnt|-> <level> <xp> <fire 0|1> <low> <high>
          <lumbridge_low|-> <lumbridge_high|-> <gauntlets_low|-> <gauntlets_high|->
          <hosidius_low|-> <hosidius_high|->
          <returns|-> (container handed back, e.g. cake tin)
  combine <first> <second> <product> <level> <xp> <first_leftover|-> <second_leftover|->
  multi   <product> <level> <xp> <base> <ingredient> <leftover|-> [<ingredient> <leftover|-> ...]
  ferment <unfermented> <fermented> <failed> <level> <xp> <low> <high>
"""
import json, os, re, sys, time, urllib.parse, urllib.request

recipes = json.load(open(sys.argv[1], encoding="utf-8"))
item_ids = json.load(open(sys.argv[2], encoding="utf-8"))
sym_dir, cache_path, out_path, log_path = sys.argv[3:7]
API = "https://oldschool.runescape.wiki/api.php"
UA = {"User-Agent": "local-dev-tool/1.0 (personal offline project)"}


def read_sym(name):
    out = {}
    for line in open(os.path.join(sym_dir, name), encoding="utf-8"):
        p = line.rstrip("\n").split("\t")
        if len(p) == 2 and p[0].isdigit():
            out[int(p[0])] = p[1]
    return out


obj_sym = read_sym("obj.sym")
BAD_OBJ = re.compile(r"fake|dummy|placeholder|_cert|noted|trail_|league|deadman|tut|_unused")


def norm(s):
    return re.sub(r"[^a-z0-9]", "", s.lower())


# Wiki "Item#Version" names (recipe materials/outputs) -> obj id of that version.
VERSIONS = {"Incomplete stew#Potato": 1997, "Incomplete stew#Meat": 1999}


def obj_name(item):
    if item in VERSIONS:
        return obj_sym.get(VERSIONS[item])
    ids = [i for i in item_ids.get(item, []) if i in obj_sym]
    if not ids:
        return None
    exact = [i for i in ids if norm(obj_sym[i]) == norm(item)]
    clean = [i for i in ids if not BAD_OBJ.search(obj_sym[i])]
    return obj_sym[min(exact or clean or ids)]


SKIP = re.compile(
    r"crunch|batta|gnomebowl|bowl \(|half baked|half made|unfinished|chambers of xeric|pysk|suphi|"
    r"leckish|brawk|mycil|roqed|kyren|undead|cocktail|drunk dragon|saturday|blurberry|"
    r"wizard blizzard|short green|fruit blast|pineapple punch|chocolate saturday|soda ash|"
    r"barley malt|sinew|karambwanji|swamp paste|easter|guide cake|nettle|damiana|mystery meat|"
    r"impaler|ugthanki|kebab|spider|chompy|toad|worm|mud pie|thin snail|lean snail|fat snail|"
    r"pitta|jubbly|giant crab|red crab|moss lizard|bream|paddlefish|larupia|graahk|kyatt|"
    r"pyre fox|sunlight antelope|moonlight antelope|dashing kebbit|barb-tailed|wild kebbit|"
    r"spit|hunting|tangled|chilli|egg and tomato|fried|spicy|gout|sliced|chopped|cheese\+tom",
    re.I,
)
BURNT = re.compile(r"^(burnt|ruined)\b|\(burnt\)", re.I)
# "Burnt fish" is one Wiki item name over several objs; map by cooked fish (Wiki item ids).
BURNT_FISH = {"Anchovies": 323, "Sardine": 369, "Herring": 357, "Mackerel": 357, "Trout": 343,
              "Salmon": 343, "Pike": 343, "Cod": 343, "Giant carp": 343, "Tuna": 367, "Bass": 367}
# Range only: OSRS refuses these on a fire.
RANGE_ONLY = re.compile(r"pie|pizza|cake|bread|potato", re.I)
# Alternative heat outputs we don't model (poison karambwan needs the quest's "thin" cook).
NOT_COOKED = {"Poison karambwan"}
KEEPS_CONTAINER = {"Unfermented wine", "Incomplete stew#Meat", "Incomplete stew#Potato"}
# Heat recipes that hand a container back (baked or burnt).
HEAT_RETURNS = {"Cake": "Cake tin"}
# 3+ ingredient recipes: product -> base item the others are used on.
MULTI = {"Uncooked cake": "Cake tin"}
LEFTOVER = {
    "Pot of flour": "Pot",
    "Bucket of water": "Bucket",
    "Jug of water": "Jug",
    "Bowl of water": "Bowl",
    "Bucket of milk": "Bucket",
}


def level_xp(r):
    for s in r.get("skills") or []:
        if isinstance(s, dict) and s.get("name") == "Cooking":
            try:
                return int(s.get("level") or 1), float(s.get("experience") or 0)
            except ValueError:
                return None
    return None


cache = json.load(open(cache_path, encoding="utf-8")) if os.path.exists(cache_path) else {}


def page_text(title):
    if title not in cache:
        url = API + "?" + urllib.parse.urlencode(
            {"action": "parse", "page": title, "prop": "wikitext", "format": "json", "redirects": 1}
        )
        try:
            data = json.load(urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60))
            cache[title] = data.get("parse", {}).get("wikitext", {}).get("*", "")
        except Exception as e:  # noqa: BLE001
            cache[title] = ""
            print("fetch failed", title, e, file=sys.stderr)
        time.sleep(0.3)
    return cache[title]


def charts(title):
    """Returns {label_lower: (low, high)} from the page's cooking Skilling success chart."""
    t = page_text(title)
    out = {}
    for m in re.finditer(r"\{\{Skilling success chart(.*?)\}\}", t, re.S):
        body = m.group(1)
        labels = dict(re.findall(r"\|label(\d+)\s*=\s*([^|\n]+)", body))
        lows = dict(re.findall(r"\|low(\d+)\s*=\s*(\d+)", body))
        highs = dict(re.findall(r"\|high(\d+)\s*=\s*(\d+)", body))
        for k, label in labels.items():
            if k in lows and k in highs:
                out.setdefault(label.strip().lower(), (int(lows[k]), int(highs[k])))
    return out


log, rows = [], []

# --- heat recipes -------------------------------------------------------------------------
by_raw = {}
for r in recipes:
    fac = r.get("facilities") or ""
    mats = r.get("materials") or []
    if fac not in ("Cooking range", "Fire") or len(mats) != 1:
        continue
    raw = mats[0]["name"]
    by_raw.setdefault(raw, []).append(r)

for raw, rs in sorted(by_raw.items()):
    if SKIP.search(raw):
        log.append(f"skip-special\t{raw}")
        continue
    cooked = [r for r in rs if not BURNT.search(r["output"]["name"]) and not SKIP.search(r["output"]["name"])
              and r["output"]["name"] not in NOT_COOKED]
    burnt = [r for r in rs if BURNT.search(r["output"]["name"])]
    names = sorted({r["output"]["name"] for r in cooked})
    if len(names) != 1:
        log.append(f"skip-ambiguous\t{raw}\t{names}")
        continue
    c = cooked[0]
    lx = level_xp(c)
    if not lx:
        log.append(f"skip-no-level\t{raw}")
        continue
    ch = charts(names[0])
    base = next((v for k, v in ch.items() if "fire" in k and "range" in k), None)
    fire = not RANGE_ONLY.search(names[0])
    if base is None:
        base = ch.get("range") or ch.get("fire") or next(
            (v for k, v in ch.items() if k in ("cooking range", "range only")), None
        )
    if base is None:
        log.append(f"skip-no-chart\t{raw}\t{names[0]}")
        continue
    lumb = next((v for k, v in ch.items() if "lumbridge" in k), None)
    gaunt = next((v for k, v in ch.items() if "gauntlet" in k), None)
    # Hosidius kitchen: +5% (the +10% curve needs the Kourend elite diary, not implemented).
    hos = next((v for k, v in ch.items() if "hosidius" in k and "5%" in k), None)
    raw_o, cooked_o = obj_name(raw), obj_name(names[0])
    burnt_o = None
    if names[0] in BURNT_FISH:
        burnt_o = obj_sym.get(BURNT_FISH[names[0]])
    elif burnt:
        burnt_o = obj_name(burnt[0]["output"]["name"])
    if burnt_o is None:
        burnt_o = obj_name("Burnt " + names[0].lower()) or obj_name("Burnt " + names[0].split()[-1].lower())
    if burnt_o is None and names[0] in ("Cooked meat",):
        burnt_o = obj_name("Burnt meat")
    if not raw_o or not cooked_o:
        log.append(f"skip-no-symbol\t{raw}\t{names[0]}")
        continue
    rows.append(
        "\t".join(
            map(
                str,
                [
                    "heat", raw_o, cooked_o, burnt_o or "-", lx[0], lx[1], int(fire), base[0], base[1],
                    lumb[0] if lumb else "-", lumb[1] if lumb else "-",
                    gaunt[0] if gaunt else "-", gaunt[1] if gaunt else "-",
                    hos[0] if hos else "-", hos[1] if hos else "-",
                    obj_name(HEAT_RETURNS[names[0]]) if names[0] in HEAT_RETURNS else "-",
                ],
            )
        )
    )

# --- combine recipes ----------------------------------------------------------------------
# Bread and pastry dough have no Cooking level on the Wiki, so the bucket omits them.
extra = []
for water in ("Bucket of water", "Jug of water", "Bowl of water"):
    for product in ("Bread dough", "Pastry dough"):
        extra.append({"facilities": "", "materials": [{"name": "Pot of flour"}, {"name": water}],
                      "output": {"name": product}, "skills": [{"name": "Cooking", "level": "1", "experience": "0"}]})

seen = set()
for r in recipes + extra:
    mats = r.get("materials") or []
    if (r.get("facilities") or "") != "" or len(mats) != 2:
        continue
    a, b = mats[0]["name"], mats[1]["name"]
    product = r["output"]["name"]
    if SKIP.search(product) or SKIP.search(a) or SKIP.search(b) or "zamorak" in (a + product).lower()             or product in ("Jug of wine", "Jug of bad wine"):
        log.append(f"skip-combine\t{a} + {b} -> {product}")
        continue
    lx = level_xp(r) or (1, 0.0)
    oa, ob, op = obj_name(a), obj_name(b), obj_name(product)
    if not (oa and ob and op):
        log.append(f"skip-no-symbol\t{a} + {b} -> {product}")
        continue
    key = (oa, ob, op)
    if key in seen:
        continue
    seen.add(key)
    # The container stays with the product for wine and stews (no empty jug/bowl back).
    keeps = product in KEEPS_CONTAINER
    la = obj_name(LEFTOVER[a]) if a in LEFTOVER and not keeps else None
    lb = obj_name(LEFTOVER[b]) if b in LEFTOVER and not keeps else None
    rows.append("\t".join(map(str, ["combine", oa, ob, op, lx[0], lx[1], la or "-", lb or "-"])))

# --- multi-ingredient recipes (cake) -------------------------------------------------------
for r in recipes:
    product = (r.get("output") or {}).get("name") if isinstance(r.get("output"), dict) else None
    if product not in MULTI or (r.get("facilities") or ""):
        continue
    base = MULTI[product]
    lx = level_xp(r) or (1, 0.0)
    others = [m["name"] for m in r.get("materials") or [] if m["name"] != base]
    names = [obj_name(n) for n in [product, base] + others]
    if None in names:
        log.append(f"skip-no-symbol	multi	{product}")
        continue
    cols = ["multi", names[0], lx[0], lx[1], names[1]]
    for n, o in zip(others, names[2:]):
        cols += [o, obj_name(LEFTOVER[n]) if n in LEFTOVER else "-"]
    rows.append("	".join(map(str, cols)))

# --- wine fermentation (Jug of wine page: 12 s after the last jug, chart 48/352 from 35) ----
ferment = ["ferment", obj_name("Unfermented wine"), obj_name("Jug of wine"), obj_name("Jug of bad wine")]
wine = charts("Jug of wine").get("jug of wine")
if None not in ferment and wine:
    rows.append("	".join(map(str, ferment + [35, 200.0, wine[0], wine[1]])))
else:
    log.append("skip-ferment	Jug of wine")

# Minigame/quest/servery copies and non-cooking "recipes" that slip through by display name.
JUNK_SYM = re.compile(
    r"spit_|hunting_|hundred_pirate|hosidius_servery|osman_|bim_|forestry_|aluft_|poh_|"
    r"infernal_harpoon|oomlie|woodplank|vodka"
)
kept = []
for row in rows:
    if JUNK_SYM.search(row):
        log.append("skip-junk-symbol\t" + row)
    else:
        kept.append(row)
rows = kept

json.dump(cache, open(cache_path, "w", encoding="utf-8"))
with open(out_path, "w", encoding="utf-8", newline="\n") as w:
    w.write("# Generated from the OSRS Wiki recipe bucket by tools/skills/cooking_to_tsv.py - do not edit.\n")
    w.write("\n".join(rows) + "\n")
with open(log_path, "w", encoding="utf-8", newline="\n") as w:
    w.write("\n".join(log) + "\n")
print("rows", len(rows), "heat", sum(r.startswith("heat") for r in rows), "logged", len(log))
