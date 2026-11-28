"""OSRS Wiki pets -> content/other/pets pets.tsv.

Args: sym_dir out.tsv

Each pet page's Infobox Item (first id = the pet item) and Infobox NPC (first id = the follower);
boss npc ids from infobox_monster. Skilling rates are 1/(base - 25 * level) per action (Wiki
"Pet"); since pets roll on every xp gain, each skill lists a representative base chance (the
median of its pet page's activity table) and the xp of one typical action, and a gain of `xp`
counts as xp / actionXp actions.

Output (tab separated):
  skill <key> <item> <npc> <stat> <base chance> <action xp>
  boss  <key> <item> <npc> <1/rate> <boss npc names comma>
"""
import json, os, re, sys, urllib.parse, urllib.request

sym_dir, out_path = sys.argv[1:3]
API = "https://oldschool.runescape.wiki/api.php"
UA = {"User-Agent": "local-dev-tool/1.0 (personal offline project)"}


def read_sym(name):
    return {int(l.split("\t")[0]): l.rstrip("\n").split("\t")[1] for l in open(os.path.join(sym_dir, name), encoding="utf-8") if "\t" in l}


obj_sym, npc_sym = read_sym("obj.sym"), read_sym("npc.sym")


def api(params):
    url = API + "?" + urllib.parse.urlencode({**params, "format": "json"})
    return json.load(urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=90))


def wikitext(page):
    return api({"action": "parse", "page": page, "prop": "wikitext", "redirects": 1})["parse"]["wikitext"]["*"]


def box_ids(text, box):
    i = text.find("{{Infobox " + box)
    if i < 0:
        return []
    j = text.find("\n}}", i)
    return [int(x) for v in re.findall(r"\|\s*id\d*\s*=\s*([0-9][0-9,\s]*)", text[i:j]) for x in re.findall(r"\d+", v)]


def monster_ids(name):
    q = f"bucket('infobox_monster').select('id').where('name',{json.dumps(name)}).run()"
    rows = api({"action": "bucket", "query": q}).get("bucket", [])
    return sorted({int(x) for r in rows for x in (r.get("id") or []) if str(x).isdigit()})


# key, page, stat, base chance (median of the pet page's table), typical action xp
SKILL_PETS = [
    ("heron", "Heron", "fishing", 257770, 50),
    ("rock_golem", "Rock golem", "mining", 710000, 35),
    ("beaver", "Beaver", "woodcutting", 243127, 50),
    ("baby_chinchompa", "Baby chinchompa", "hunter", 97135, 200),
    ("giant_squirrel", "Giant squirrel", "agility", 35000, 400),
    ("tangleroot", "Tangleroot", "farming", 98364, 100),
    ("rocky", "Rocky", "thieving", 99175, 50),
    ("rift_guardian", "Rift guardian", "runecrafting", 1022880, 8),
]

# key, page, 1/rate (Wiki), boss monster names
BOSS_PETS = [
    ("prince_black_dragon", "Prince black dragon", 3000, ["King Black Dragon"]),
    ("baby_mole", "Baby mole", 3000, ["Giant Mole"]),
    ("kalphite_princess", "Kalphite princess", 3000, ["Kalphite Queen"]),
    ("pet_dagannoth_prime", "Pet dagannoth prime", 5000, ["Dagannoth Prime"]),
    ("pet_dagannoth_rex", "Pet dagannoth rex", 5000, ["Dagannoth Rex"]),
    ("pet_dagannoth_supreme", "Pet dagannoth supreme", 5000, ["Dagannoth Supreme"]),
    ("pet_chaos_elemental", "Pet chaos elemental", 300, ["Chaos Elemental"]),
    ("pet_kraken", "Pet kraken", 3000, ["Kraken"]),
    ("hellpuppy", "Hellpuppy", 3000, ["Cerberus"]),
    ("pet_smoke_devil", "Pet smoke devil", 3000, ["Thermonuclear smoke devil"]),
    ("pet_snakeling", "Pet snakeling", 4000, ["Zulrah"]),
    ("vorki", "Vorki", 3000, ["Vorkath"]),
    ("sraracha", "Sraracha", 3000, ["Sarachnis"]),
    ("callisto_cub", "Callisto cub", 1500, ["Callisto"]),
    ("venenatis_spiderling", "Venenatis spiderling", 1500, ["Venenatis"]),
    ("vetion_jr", "Vet'ion jr.", 1500, ["Vet'ion"]),
    ("scorpias_offspring", "Scorpia's offspring", 2016, ["Scorpia"]),
    ("pet_general_graardor", "Pet general graardor", 5000, ["General Graardor"]),
    ("pet_zilyana", "Pet zilyana", 5000, ["Commander Zilyana"]),
    ("pet_kreearra", "Pet kree'arra", 5000, ["Kree'arra"]),
    ("pet_kril_tsutsaroth", "Pet k'ril tsutsaroth", 5000, ["K'ril Tsutsaroth"]),
    ("abyssal_orphan", "Abyssal orphan", 2560, ["Abyssal Sire"]),
    ("pet_dark_core", "Pet dark core", 5000, ["Corporeal Beast"]),
    ("ikkle_hydra", "Ikkle hydra", 3000, ["Alchemical Hydra"]),
]

warnings, lines = [], []


def pet_ids(page):
    text = wikitext(page)
    items = [i for i in box_ids(text, "Item") if i in obj_sym]
    npcs = [i for i in box_ids(text, "NPC") if i in npc_sym]
    if not items or not npcs:
        warnings.append(f"{page}: item={items[:2]} npc={npcs[:2]}")
        return None
    # The first NPC version is often the POH menagerie copy; the follower is a non-poh_ id.
    followers = [i for i in npcs if not npc_sym[i].startswith("poh_")] or npcs
    return obj_sym[items[0]], npc_sym[followers[0]]


for key, page, stat, base, action_xp in SKILL_PETS:
    ids = pet_ids(page)
    if ids:
        lines.append(["skill", key, *ids, stat, base, action_xp])

for key, page, rate, bosses in BOSS_PETS:
    ids = pet_ids(page)
    boss_npcs = sorted({npc_sym[i] for b in bosses for i in monster_ids(b)
                        if i in npc_sym and not re.match(r"(clancup|deadman|twocats)_|.*cutscene", npc_sym[i])})
    if not boss_npcs:
        warnings.append(f"{page}: no boss npc ids for {bosses}")
    if ids and boss_npcs:
        lines.append(["boss", key, *ids, rate, ",".join(boss_npcs)])

with open(out_path, "w", encoding="utf-8", newline="\n") as w:
    w.write("# Generated from OSRS Wiki pet pages by tools/pets/pets.py - do not edit.\n")
    for line in lines:
        w.write("\t".join(map(str, line)) + "\n")
print("pets", len(lines))
for warning in warnings:
    print("  warn", warning, file=sys.stderr)
