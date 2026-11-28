"""OSRS Wiki {{Hunter info}} pages -> content/skills/hunter hunter.tsv (bird snares, butterflies, box traps).

Args: hunter_pages.json npc_ids.json item_ids.json sym_dir out.tsv
  hunter_pages.json  fetch_wiki_spawns.py hunter_pages.json "Hunter info"
  npc_ids.json       tools/shops/fetch_npc_ids.py (wiki page -> npc ids)

Output:
  snare <title> <npc names comma> <level> <xp> <low> <high> <trapping loc> <full loc>
  net   <title> <npc names comma> <level> <xp> <low> <high>
  box   <title> <npc names comma> <level> <xp> <low> <high> <trapping loc> <full loc>
  swarm <title> <npc names comma> <level> <xp> 0 0   (calm swarm, then its furious variant)
  loot  <obj> <min> <max>          (all hunter loot is "Always")
low/high are the wiki {{Skilling success chart}} values. Ruby harvest, Sapphire glacialis and
Snowy knight have no chart on the wiki; they reuse Black warlock's curve (flagged in the output).
"""
import json, os, re, sys

pages = json.load(open(sys.argv[1], encoding="utf-8"))
npc_ids = json.load(open(sys.argv[2], encoding="utf-8"))
item_ids = json.load(open(sys.argv[3], encoding="utf-8"))
sym_dir, out_path = sys.argv[4], sys.argv[5]


def read_sym(name):
    return {int(l.split("\t")[0]): l.rstrip("\n").split("\t")[1] for l in open(os.path.join(sym_dir, name), encoding="utf-8") if "\t" in l}


obj_sym, npc_sym = read_sym("obj.sym"), read_sym("npc.sym")
BAD_OBJ = re.compile(r"fake|dummy|placeholder|_cert|noted|league|deadman")

# Birds caught with a snare. The trap loc variant follows the bird's cache name, e.g. crimson swift
# is `hunting_bird_jungle` -> hunting_ojibway_trap_full_jungle; tropical wagtail is
# `multicoloured_bird` -> ..._coloured.
SNARE_BIRDS = ["Crimson swift", "Golden warbler", "Copper longtail", "Cerulean twitch", "Tropical wagtail"]


def snare_locs(npc_name):
    variant = "coloured" if npc_name.startswith("multicoloured") else npc_name.rsplit("_", 1)[-1]
    return f"hunting_ojibway_trap_trapping_{variant}", f"hunting_ojibway_trap_full_{variant}"
BOX_CREATURES = [
    ("Ferret (Hunter)", "hunting_ferret", "hunting_boxtrap_full_ferret", "hunting_ferret", (76, 338),
     "no wiki chart; grey chinchompa curve shifted to level 27"),
    ("Chinchompa (Hunter)", "hunting_chinchompa", "hunting_boxtrap_full_chinchompa", "chinchompa_captured", (6, 268), ""),
    ("Carnivorous chinchompa", "hunting_chinchompa_big", "hunting_boxtrap_full_chinchompa_big", "chinchompa_big_captured", (-78, 228), ""),
    ("Black chinchompa (Hunter)", "hunting_chinchompa_black", "hunting_boxtrap_full_chinchompa_black", "chinchompa_black", (-78, 228), ""),
]
# The Birds and the Wasps (cache multinpcs; Hunter.kt resolves the bird each player sees). No Wiki
# charts and the honey buzzard's feathers are "Varies", so these rows are explicit:
# (snare title, npc, trap variant, chart, loot [(obj, min, max)], note).
BAW_BIRDS = [
    ("Honey Buzzard", "baw_honey_buzzard", "honey_buzzard", (100, 420),
     [("bones", 1, 1), ("baw_brown_feather", 8, 14)], "no wiki chart; crimson swift curve (level 1)"),
    ("Black Honey Buzzard", "baw_black_honey_buzzard", "black_honey_buzzard", (77, 374),
     [("bones", 1, 1), ("baw_black_feather", 10, 24)], "no wiki chart; copper longtail-tropical wagtail curve at level 17"),
]
# Wasp swarms (Wasps.kt): (title, calm npc, furious npc, loot obj).
SWARMS = [
    ("Swarm of wasps", "baw_wasp_swarm", "baw_wasp_swarm_furious", "baw_wasp_sting"),
    ("Swarm of killer wasps", "baw_killer_wasp_swarm", "baw_killer_wasp_swarm_furious", "baw_killer_wasp_sting"),
]
BUTTERFLIES = ["Ruby harvest", "Sapphire glacialis", "Snowy knight", "Black warlock"]
FALLBACK_CHART = "Black warlock"


def norm(s): return re.sub(r"[^a-z0-9]", "", s.lower())


def obj_name(item):
    ids = [i for i in item_ids.get(item, []) if i in obj_sym]
    if not ids: return None
    exact = [i for i in ids if norm(obj_sym[i]) == norm(item)]
    clean = [i for i in ids if not BAD_OBJ.search(obj_sym[i])]
    return obj_sym[min(exact or clean or ids)]


def info(title):
    x = pages[title]
    box = re.search(r"\{\{Hunter info(.*?)\n\}\}", x, re.S).group(1)
    level = int(re.search(r"\|\s*level\s*=\s*(\d+)", box).group(1))
    xp = float(re.search(r"\|\s*xp\s*=\s*([\d.]+)", box).group(1))
    chart = re.search(r"low1=(\d+)\|high1=(\d+)", x)
    loot = []
    for name, qty, rarity in re.findall(r"DropsLineSkill\|name=([^|]*)\|quantity=([^|]*)\|rarity=([^|}]*)", x):
        if rarity.strip().lower() != "always":  # e.g. Hunters' Rumours-only wings / tailfeathers
            continue
        name = name.strip()
        on = obj_name(name) or obj_name(re.sub(r"\s*\(item\)$", "", name))
        if on is None: continue
        q = [int(n) for n in re.findall(r"\d+", qty)] or [1]
        loot.append((on, min(q), max(q)))
    return level, xp, (chart.groups() if chart else None), loot


def npcs(title):
    return [npc_sym[i] for i in npc_ids.get(title, []) if i in npc_sym]


with open(out_path, "w", encoding="utf-8", newline="\n") as w:
    w.write("# Generated from OSRS Wiki {{Hunter info}} pages by tools/skills/hunter_to_tsv.py - do not edit.\n")
    for title in SNARE_BIRDS:
        trapping, full = snare_locs(npcs(title)[0])
        level, xp, chart, loot = info(title)
        w.write(f"\nsnare\t{title}\t{','.join(npcs(title))}\t{level}\t{xp:g}\t{chart[0]}\t{chart[1]}\t{trapping}\t{full}\n")
        for o, lo, hi in loot: w.write(f"loot\t{o}\t{lo}\t{hi}\n")
    fallback = info(FALLBACK_CHART)[2]
    for title in BUTTERFLIES:
        level, xp, chart, loot = info(title)
        note = "" if chart else f"\t# no wiki chart; {FALLBACK_CHART}'s curve"
        lo, hi = chart or fallback
        w.write(f"\nnet\t{title}\t{','.join(npcs(title))}\t{level}\t{xp:g}\t{lo}\t{hi}{note}\n")
        for o, lo2, hi2 in loot: w.write(f"loot\t{o}\t{lo2}\t{hi2}\n")
    # Box traps (PR2): trap locs/npcs/loot by cache name; charts from the Wiki "Chinchompa" page
    # (grey 6/268, red and black -78/228). Ferret has no Wiki chart ("Needs skilling success
    # chart"): grey chinchompa's curve shifted down to level 27 (flagged).
    for title, npc, full, loot_obj, chart, note in BOX_CREATURES:
        level, xp, _, _ = info(title)
        tail = ("\t# " + note) if note else ""
        # Box traps animate per direction (_n/_e/_s/_w), so no single trapping loc: "-".
        cols = ["box", title.split(" (")[0], npc, level, f"{xp:g}", chart[0], chart[1], "-", full]
        w.write("\n" + "\t".join(map(str, cols)) + tail + "\n")
        w.write("\t".join(["loot", loot_obj, "1", "1"]) + "\n")
    for title, npc, variant, chart, loot, note in BAW_BIRDS:
        level, xp, _, _ = info(title)
        cols = ["snare", title, npc, level, f"{xp:g}", chart[0], chart[1],
                f"hunting_ojibway_trap_trapping_{variant}", f"hunting_ojibway_trap_full_{variant}"]
        w.write("\n" + "\t".join(map(str, cols)) + "\t# " + note + "\n")
        for o, lo, hi in loot: w.write(f"loot\t{o}\t{lo}\t{hi}\n")
    for title, calm, furious, sting in SWARMS:
        level, xp, _, _ = info(title)
        w.write(f"\nswarm\t{title}\t{calm},{furious}\t{level}\t{xp:g}\t0\t0\t# netted every tick, no chart\n")
        w.write(f"loot\t{sting}\t1\t1\n")
print("ok")
