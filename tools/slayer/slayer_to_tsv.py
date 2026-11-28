"""Slayer data from the OSRS Wiki -> content/skills/slayer slayer.tsv + enricher slayer_req lines.

Args: slayer.json npc.sym out.tsv enricher_npcs.toml log.tsv spawns.toml [spawns.toml ...]
  slayer.json  fetch_slayer.py slayer.json Turael Mazchna Vannaka Chaeldar Nieve Duradel

Writes:
  master   <key> <npc name> <spawn coord x,z,plane> <combat req> <points> <milestones n:pts;...> <slayer req>
  task     <master> <category> <min> <max> <weight> <slayer req> <combat req> <ext min|-> <ext max|->
  category <category> <display name> <npc names comma>
  xp       <npc name> <slayer xp per kill>
Tasks whose category has no spawned npc are skipped (logged). Quest requirements are ignored (no
quests yet); level requirements are kept. The enricher npcs.toml gets `slayer_req` for every
npc with a Slayer level requirement above 1 (combat checks it before attacking).
"""
import json, re, sys
from collections import defaultdict

data = json.load(open(sys.argv[1], encoding="utf-8"))
npc_sym = {int(l.split("\t")[0]): l.rstrip("\n").split("\t")[1] for l in open(sys.argv[2], encoding="utf-8") if "\t" in l}
out_path, enricher_path, log_path = sys.argv[3:6]
spawned = set()
for path in sys.argv[6:]:
    spawned |= set(re.findall(r"npc = '([^']+)'", open(path, encoding="utf-8").read()))

# Master npcs (wiki ids checked against the cache: op3 = Assignment) and wiki map positions.
# Spria (10432) is a transforming npc without a stand anim and shares Turael's list, so she is left out.
# Points: Wiki "Slayer reward point" table (base per task; every 10th/50th/100th/250th/1,000th task
# gives 5x/15x/25x/35x/50x). Quest unlocks (Zanaris for Chaeldar, Shilo for Duradel) are skipped.
MASTERS = {
    "Turael": ("slayer_master_1_tureal", "2931,3536,0", 0, 0, ""),
    "Mazchna": ("slayer_master_2_mazchna", "3511,3509,0", 20, 6, "10:30;50:90;100:150;250:210;1000:300"),
    "Vannaka": ("slayer_master_3", "3147,9914,0", 40, 8, "10:40;50:120;100:200;250:280;1000:400"),
    "Chaeldar": ("slayer_master_4", "2444,4431,0", 70, 10, "10:50;50:150;100:250;250:350;1000:500"),
    "Nieve": ("slayer_master_nieve", "2432,3423,0", 85, 12, "10:60;50:180;100:300;250:420;1000:600"),
    "Duradel": ("slayer_master_5_duradel", "2869,2982,1", 100, 15, "10:75;50:225;100:375;250:525;1000:750"),
}
# Masters that also need a Slayer level (Wiki: Duradel needs 50 Slayer).
MASTER_SLAYER_REQ = {"Duradel": 50}
log = []

by_category = defaultdict(set)
slayer_req, slayer_xp = {}, {}
for m in data["monsters"]:
    cats = m.get("slayer_category") or []
    if isinstance(cats, str): cats = [cats]
    for raw_id in m.get("id") or []:
        if not str(raw_id).isdigit() or int(raw_id) not in npc_sym: continue
        name = npc_sym[int(raw_id)]
        for c in cats: by_category[c.strip().lower()].add(name)
        lvl = m.get("slayer_level")
        if isinstance(lvl, int) and lvl > 1: slayer_req[name] = lvl
        xp = m.get("slayer_experience")
        hp = m.get("hitpoints")
        if cats and isinstance(xp, (int, float)) and xp > 0: slayer_xp[name] = xp
        elif cats and isinstance(hp, (int, float)) and hp > 0: slayer_xp[name] = hp


def rows(text):
    # Masters whose list is on their main page (Vannaka, Chaeldar) have a points table first.
    start = text.find("==Tasks==")
    table = text[text.find("{|", max(start, 0)):]
    for chunk in table.split("\n|-")[1:]:
        cells = [c.strip() for c in re.split(r"\n\|(?!\})", "\n" + chunk.strip()) if c.strip()]
        if len(cells) >= 5: yield cells


tasks = []
used_categories = {}
for master, (npc, coord, cmb, pts, ms) in MASTERS.items():
    for cells in rows(data["masters"].get(master, "")):
        # "[[Banshee]]s" / "[[Slayer task/Bats|Bats]]" -> "Banshees" / "Bats"
        display = re.sub(r"\[\[(?:[^|\]]*\|)?([^\]]+)\]\]", r"\1", cells[0]).strip()
        key = display.lower()
        for cand in (key, key[:-1] if key.endswith("s") else key, key + "s"):
            if cand in by_category: key = cand; break
        amount = re.findall(r"\d+", cells[1])
        row = "\n".join(cells)
        weight = re.search(r"weight\|([\d.]+)", row)
        # Requirements only from the "Unlock requirement" cell (it carries data-sort-value).
        req_cell = next((c for c in cells if "data-sort-value" in c), "")
        sreq = re.search(r"SCP\|Slayer\|(\d+)", req_cell)
        creq = re.search(r"SCP\|Combat\|(\d+)", req_cell)
        if not amount or not weight:
            log.append((master, display, "unparsed row")); continue
        npcs = sorted(by_category.get(key, set()))
        live = [n for n in npcs if n in spawned]
        if not live:
            log.append((master, display, "no spawned npc in this category")); continue
        lo, hi = int(amount[0]), int(amount[-1])
        # Extended amount (Slayer reward "longer" unlocks) is the next cell when it is a range.
        ext = re.fullmatch(r"\s*(\d+)\s*[-–]\s*(\d+)\s*", cells[2]) if len(cells) > 2 else None
        ext_lo, ext_hi = (ext.group(1), ext.group(2)) if ext else ("-", "-")
        tasks.append((master, key, lo, hi, float(weight.group(1)), int(sreq.group(1)) if sreq else 1,
                      int(creq.group(1)) if creq else 1, ext_lo, ext_hi))
        used_categories[key] = (display, npcs)

with open(out_path, "w", encoding="utf-8", newline="\n") as w:
    w.write("# Generated from the OSRS Wiki by tools/slayer/slayer_to_tsv.py - do not edit.\n")
    for master, (npc, coord, cmb, pts, ms) in MASTERS.items():
        w.write(f"master\t{master}\t{npc}\t{coord}\t{cmb}\t{pts}\t{ms or '-'}\t{MASTER_SLAYER_REQ.get(master, 1)}\n")
    for t in tasks:
        w.write("task\t" + "\t".join(f"{x:g}" if isinstance(x, float) else str(x) for x in t) + "\n")
    for key, (display, npcs) in sorted(used_categories.items()):
        w.write(f"category\t{key}\t{display}\t{','.join(npcs)}\n")
    for key, (_, npcs) in sorted(used_categories.items()):
        for n in npcs:
            if n in slayer_xp: w.write(f"xp\t{n}\t{slayer_xp[n]:g}\n")

# Enricher: add slayer_req to existing npc config blocks.
text = open(enricher_path, encoding="utf-8").read()
blocks = text.split("[[config]]")
added = 0
for i, blk in enumerate(blocks[1:], start=1):
    m = re.search(r"^npc = '([^']+)'", blk, re.M)
    if not m or m.group(1) not in slayer_req or re.search(r"^slayer_req = ", blk, re.M): continue
    blocks[i] = blk.rstrip("\n") + f"\nslayer_req = {slayer_req[m.group(1)]}\n\n"
    added += 1
open(enricher_path, "w", encoding="utf-8", newline="\n").write("[[config]]".join(blocks))

with open(log_path, "w", encoding="utf-8", newline="\n") as f:
    f.write("master\ttask\treason\n")
    for row in log: f.write("\t".join(row) + "\n")
print("tasks", len(tasks), "categories", len(used_categories), "slayer_req added", added, "skipped", len(log))
