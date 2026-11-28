"""POH rooms + furniture -> content/skills/construction poh_rooms.tsv.

Args: dump_dir scan.json loc.sym obj.sym out.tsv
  dump_dir   Joshua-F/osrs-dumps rev 243 dumps (dump.dbrow, dump.loc): the cache's poh_room,
             poh_hotspot and furniture dbrows (names, room cost/level, door edges, hotspot
             options, furniture levels and materials).
  scan.json  poh_scan.py output: every loc in each Rimmington-style room template zone.

The OSRS Wiki gives each furniture's xp and its built loc ids (Infobox Construction id1..N);
loc names follow the cache's tier families, checked against the Wiki ids where both exist.

Output (tab separated):
  room   <key> <name> <level> <coins> <zoneX> <zoneZ> <door edges 0=N 1=E 2=S 3=W>
  spot   <room> <hotspot loc> <group index> <suffix>
  option <room> <group> <tier> <name> <level> <xp> <materials obj:n,..> <nails> <icon obj> <suffix=loc;..>
"""
import json, os, re, sys, urllib.parse, urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from dbrows import load

dump_dir, scan_path, loc_sym_path, obj_sym_path, out_path = sys.argv[1:6]
rows = load(os.path.join(dump_dir, "dump.dbrow"))
scan = json.load(open(scan_path))
loc_syms = {int(l.split("\t")[0]): l.rstrip("\n").split("\t")[1] for l in open(loc_sym_path, encoding="utf-8") if "\t" in l}
loc_names = set(loc_syms.values())
obj_names = {l.rstrip("\n").split("\t")[1] for l in open(obj_sym_path, encoding="utf-8") if "\t" in l}

BASE_ZONE = (232, 712)  # poh_room source_offset (0,0): the Rimmington template block

# room key -> (cache room dbrow, scan key, hotspot loc prefix)
ROOMS = {
    "garden": ("poh_dummy_garden", "garden", None),
    "parlour": ("poh_dummy_parlour", "parlour", "poh_parlour_"),
    "kitchen": ("poh_dummy_kitchen", "kitchen", "poh_kitchen_"),
    "dining": ("poh_dummy_dining_room", "dining", "poh_dining_room_"),
    "workshop": ("poh_dummy_workshop", "workshop", "poh_workshop_"),
    "chapel": ("poh_dummy_chapel", "chapel", "poh_chapel_"),
    "portal": ("poh_dummy_portalroom", "portal", "poh_teleroom_"),
    "hall": ("poh_dummy_hall1", "hall", "poh_hall1_"),
}


# Template hotspot number -> poh_room hotspot index where they differ (the portal room lists three
# null hotspots before its three portal spaces).
SPOT_GROUPS = {"portal": {0: 3, 1: 4, 2: 5}}


def n(fmt):
    return lambda tier, suffix: fmt.format(n=tier, s=suffix)


# hotspot dbrow -> rule(tier, suffix) -> built loc name (None = leave the hotspot)
RUG = lambda tier, suffix: f"poh_rug{suffix.strip('_')}{tier}" if tier <= 3 and suffix else None
FAMILIES = {
    "armchair_hotspot": n("poh_chair{n}"),
    "bookcase_hotspot": n("poh_bookcase{n}"),
    "rug_hotspot": RUG,
    "fireplace_hotspot": n("poh_fireplace_{n}"),
    "curtains_hotspot": lambda t, s: f"poh_curtains_{t}" if t <= 3 else None,
    "stove_hotspot": n("poh_stove_{n}"),
    "kitchen_shelf_hotspot": lambda t, s: f"poh_kitchen_crockery_{t}" if s == "_crockery" else f"poh_kitchen_shelves_{t}",
    "barrel_hotspot": n("poh_barrel_{n}"),
    "larder_hotspot": n("poh_larder_{n}"),
    "sink_hotspot": n("poh_sink_{n}"),
    "kitchen_table_hotspot": n("poh_kitchentable_{n}"),
    "spice_rack_hotspot": n("poh_kitchen_rack_{n}"),
    "dining_table_hotspot": n("poh_diningtable_{n}"),
    "dining_chairs_hotspot": n("poh_diningchairs_{n}"),
    "simple_wall_crest_hotspot": lambda t, s: f"poh_wall_deco_{t}" if t <= 3 else None,
    "bellpull_hotspot": n("poh_bellpull_{n}"),
    "workbench_hotspot": lambda t, s: None if s else f"poh_workbench_{t}",
    "crafting_table_hotspot": lambda t, s: None if s else f"poh_clockmaking_{t}",
    # Tool store N fills the first N tool spaces (3a..3e) with poh_tools1..N.
    "tool_store_hotspot": lambda t, s: f"poh_tools{'abcde'.index(s) + 1}" if s and "abcde".index(s) < t else None,
    "repair_bench_hotspot": n("poh_repair_{n}"),
    "heraldry_bench_hotspot": lambda t, s: f"poh_repair_{t + 3}",
    "icon_hotspot": lambda t, s: f"poh_icon_{t}" if t <= 8 else None,
    # Altars follow the Saradomin set (the Wiki's version 1); other gods' icons not mapped yet.
    "altar_hotspot": n("poh_altar_saradomin_{n}"),
    "incense_burner_hotspot": lambda t, s: f"poh_torch_{t}" if t <= 7 else None,
    "statue_hotspot": lambda t, s: ["poh_statue_monk", "poh_statue_angel", "poh_statue_saradomin"][t - 1],
    "chapel_instrument_hotspot": n("poh_musical_thing_{n}"),
    "portal_hotspot": lambda t, s: ["poh_portal_teak_empty", "poh_portal_mag_empty", "poh_portal_marble_empty"][t - 1] if t <= 3 else None,
    "portal_focus_hotspot": lambda t, s: ["poh_teleport_centrepiece", "poh_teleport_centrepiece_grand", "poh_scrying_pool"][t - 1] if t <= 3 else None,
}

# Wiki infobox: xp + built loc ids per furniture name.
API = "https://oldschool.runescape.wiki/api.php"
UA = {"User-Agent": "local-dev-tool/1.0 (personal offline project)"}


def wiki_infoboxes(titles):
    out = {}
    titles = sorted(set(titles))
    for i in range(0, len(titles), 40):
        params = {"action": "query", "prop": "revisions", "rvprop": "content", "rvslots": "main",
                  "titles": "|".join(titles[i:i + 40]), "redirects": 1, "format": "json", "formatversion": 2}
        j = json.load(urllib.request.urlopen(urllib.request.Request(API + "?" + urllib.parse.urlencode(params), headers=UA), timeout=90))
        redirects = {r["to"]: r["from"] for r in j["query"].get("redirects", [])}
        for page in j["query"]["pages"]:
            if "revisions" not in page:
                continue
            text = page["revisions"][0]["slots"]["main"]["content"]
            m = re.search(r"\{\{Infobox Construction(.*?)\n\}\}", text, re.S)
            if not m:
                continue
            box = m.group(1)
            xp = re.search(r"\|\s*experience\d*\s*=\s*([\d,.]+)", box)
            ids = [int(x) for v in re.findall(r"\|\s*id\d*\s*=\s*([\d,\s]+)", box) for x in re.findall(r"\d+", v)]
            key = redirects.get(page["title"], page["title"])
            out[key.lower()] = (float(xp.group(1).replace(",", "")) if xp else None, ids)
    return out


def furniture_name(dbrow):
    return rows[dbrow]["data"]["name"][0]


# Wiki page names that differ from the cache's furniture names.
WIKI_TITLES = {
    "Asgarnian Ale": "Asgarnian ale barrel",
    "Greenman's Ale": "Greenman's ale barrel",
    "Dragon bitter": "Dragon bitter barrel",
    "Chef's Delight": "Chef's delight barrel",
    "Gnome Child icon": "Gnome child icon (Construction)",
    "Medium statues": "Medium statue",
}


def wiki_title(name):
    return WIKI_TITLES.get(name, name)


all_options = []
for room, (dbrow, _, _) in ROOMS.items():
    for hotspot in rows[dbrow]["data"]["hotspot"]:
        all_options += rows[hotspot]["data"].get("builddata", [])
wiki = wiki_infoboxes([wiki_title(furniture_name(o)) for o in all_options if o in rows])

lines, warnings = [], []
for room, (dbrow, scan_key, prefix) in ROOMS.items():
    d = rows[dbrow]["data"]
    ox, oz = (int(v) for v in d["source_offset"][0].split(","))
    zone = (BASE_ZONE[0] + ox // 8, BASE_ZONE[1] + oz // 8)
    placed = scan[scan_key]
    # Door edges from the template's door hotspots (local x/z 0 or 7).
    edges = set()
    for name, x, z, shape, rot in placed:
        if name.startswith("poh_hotspot_door"):
            edges.add(0 if z == 7 else 1 if x == 7 else 2 if z == 0 else 3)
    cache_edges = {int(e) for e in d.get("door_locations", [])} or {2}
    if edges != cache_edges:
        warnings.append(f"{room}: scanned doors {sorted(edges)} != cache {sorted(cache_edges)}")
    name = d["name_uppercase"][0]
    level = int(d["level_requirement"][0].split(",")[1])
    lines.append(["room", room, name, level, int(d["cost"][0]), zone[0], zone[1], ",".join(map(str, sorted(edges)))])
    if prefix is None:
        continue
    remap = SPOT_GROUPS.get(room, {})
    spots = sorted({(nm, remap.get(int(m.group(1)) - 1, int(m.group(1)) - 1), m.group(2)) for nm, *_ in placed
                    for m in [re.match(re.escape(prefix) + r"(\d+)(.*)$", nm)] if m})
    for nm, group, suffix in spots:
        lines.append(["spot", room, nm, group, suffix])
    suffixes = {}
    for nm, group, suffix in spots:
        suffixes.setdefault(group, set()).add(suffix)
    for group, hotspot in enumerate(d["hotspot"]):
        rule = FAMILIES.get(hotspot)
        if rule is None or group not in suffixes:
            continue
        for tier, option in enumerate(rows[hotspot]["data"].get("builddata", []), 1):
            od = rows[option]["data"]
            fname = od["name"][0]
            xp, ids = wiki.get(wiki_title(fname).lower(), (None, []))
            wiki_locs = sorted({loc_syms[i] for i in ids if i in loc_syms and not loc_syms[i].endswith("_lit")})
            locs = []
            for suffix in sorted(suffixes[group]):
                built = rule(tier, suffix)
                # Single-tile furniture: the Wiki's built loc wins over the family guess
                # (crests pick the Saradomin variant to match the altars).
                if built is not None and not suffix and wiki_locs and built not in wiki_locs:
                    pick = [l for l in wiki_locs if l.endswith("_saradomin")] or wiki_locs
                    if len(pick) == 1:
                        built = pick[0]
                if built is None:
                    continue
                if built not in loc_names:
                    warnings.append(f"{room}/{hotspot} {fname}: no loc {built}")
                    continue
                locs.append(f"{suffix}={built}")
            if not locs or xp is None:
                if xp is None:
                    warnings.append(f"{room}/{hotspot} {fname}: no wiki xp")
                continue
            if wiki_locs and not any(l.split("=", 1)[1] in wiki_locs for l in locs):
                warnings.append(f"{room}/{hotspot} {fname}: wiki ids {wiki_locs} vs {locs}")
            level = int(od["level_requirement"][0].split(",")[1])
            materials, nails = [], 0
            for cost in od.get("material_cost", []):
                obj, count = cost.rsplit(",", 1)
                if obj == "any_nails":
                    nails = int(count)
                elif obj in obj_names:
                    materials.append(f"{obj}:{count}")
                else:
                    warnings.append(f"{fname}: unknown material {obj}")
            icon = od["model_obj"][0].strip(chr(34))
            lines.append(["option", room, group, tier, fname, level, f"{xp:g}", ",".join(materials), nails, icon, ";".join(locs)])

with open(out_path, "w", encoding="utf-8", newline="\n") as w:
    w.write("# Generated by tools/construction/poh_data.py (cache poh_room/poh_hotspot/furniture dbrows,\n")
    w.write("# Rimmington template scan, OSRS Wiki furniture xp) - do not edit.\n")
    for line in lines:
        w.write("\t".join(map(str, line)) + "\n")
print("rooms", sum(l[0] == "room" for l in lines), "spots", sum(l[0] == "spot" for l in lines), "options", sum(l[0] == "option" for l in lines))
for warning in warnings:
    print("  warn", warning, file=sys.stderr)
