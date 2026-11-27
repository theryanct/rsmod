"""OSRS Wiki {{Thieving info}} pages -> content/skills/thieving thieving.tsv.

Args: thieving_pages.json item_ids.json sym_dir npcs_dump.tsv out.tsv log.tsv [exclude_npcs.txt]
  thieving_pages.json  fetch_wiki_spawns.py thieving_pages.json "Thieving info"
  npcs_dump.tsv        tools/npc-anims/cache_dump.py output (npc ops: keep ids with "Pickpocket")
  exclude_npcs.txt     npc internal names that already have hand-written op handlers

Pickpocket pages: level, xp, stun time, damage, the "Normal" low/high of {{Skilling success
chart}} (fed to statRandom) and the loot table (one weighted roll; Always lines always given).
Stall pages: level, xp, respawn time and a weighted loot roll; each stall version's loc gets the
known empty-stall loc of its region when there is one.

Output (tab separated):
  pickpocket <title> <npc names comma> <level> <xp> <low> <high> <stun ticks> <damage>
  stall      <title> <loc names comma> <level> <xp> <respawn ticks> <empty locs comma|->
  loot       <obj> <min> <max> <num> <den> <always 0|1>   (one weighted roll; always=1 lines always given)
"""
import json, os, re, sys
from collections import Counter
from fractions import Fraction

pages = json.load(open(sys.argv[1], encoding="utf-8"))
item_ids = json.load(open(sys.argv[2], encoding="utf-8"))
sym_dir, npcs_dump, out_path, log_path = sys.argv[3:7]
exclude = set(open(sys.argv[7], encoding="utf-8").read().split()) if len(sys.argv) > 7 else set()


def read_sym(name):
    out = {}
    for line in open(os.path.join(sym_dir, name), encoding="utf-8"):
        p = line.rstrip("\n").split("\t")
        if len(p) == 2 and p[0].isdigit(): out[int(p[0])] = p[1]
    return out


obj_sym, npc_sym, loc_sym = read_sym("obj.sym"), read_sym("npc.sym"), read_sym("loc.sym")
pickpocket_ids = set()
for line in open(npcs_dump, encoding="utf-8").read().splitlines()[1:]:
    p = line.split("\t")
    if any(op.strip().lower() == "pickpocket" for op in p[5:10]): pickpocket_ids.add(int(p[0]))

# Empty-stall loc per stall family (by internal loc name).
EMPTY_STALL = [(re.compile(r"thiefstall$"), "market"), (re.compile(r"^dwarf_market"), "dwarf_market_empty_stall"),
               (re.compile(r"^hos_"), "hos_stall_empty"), (re.compile(r"^prif_marketstall"), "prif_marketstall_empty")]
BAD_OBJ = re.compile(r"fake|dummy|placeholder|_cert|noted|trail_|league|deadman|tut")
log, stats = [], Counter()


def templates(text, name):
    start = 0
    while (i := text.find("{{" + name, start)) >= 0:
        depth, j = 0, i
        while j < len(text):
            if text.startswith("{{", j): depth += 1; j += 2
            elif text.startswith("}}", j):
                depth -= 1; j += 2
                if depth == 0: break
            else: j += 1
        yield i, text[i + 2 + len(name):j - 2]
        start = j


def strip_nested(s):
    prev = None
    while prev != s:
        prev, s = s, re.sub(r"\{\{[^{}]*\}\}", "", s)
    return re.sub(r"<!--.*?-->|<ref[^>]*/>|<ref[^>]*>.*?</ref>", "", s, flags=re.S)


def fields(body):
    return {k.strip().lower(): v.strip() for k, v in re.findall(r"\|\s*([A-Za-z_]+\d*)\s*=\s*([^|]*)", body)}


def norm(s): return re.sub(r"[^a-z0-9]", "", s.lower())


def obj_name(item):
    ids = [i for i in item_ids.get(item, []) if i in obj_sym]
    if not ids: return None
    exact = [i for i in ids if norm(obj_sym[i]) == norm(item)]
    clean = [i for i in ids if not BAD_OBJ.search(obj_sym[i])]
    return obj_sym[min(exact or clean or ids)]


def ticks(s):
    m = re.search(r"(\d+(?:\.\d+)?)\s*(s|sec|second)", s or "")
    return max(1, round(float(m.group(1)) / 0.6)) if m else None


def page_ids(text):
    ids = []
    for _, v in re.findall(r"\|\s*(id\d*)\s*=\s*([0-9][0-9,\s]*)", text):
        ids += [int(x) for x in re.findall(r"\d+", v) if int(x) not in ids]
    return ids


def loot(text, start):
    """Weighted loot lines after `start`, up to the Tertiary section / next level-2 heading."""
    end = len(text)
    for m in re.finditer(r"^(==[^=].*?==|===\s*Tertiary\s*===)\s*$", text[start:], re.M):
        if m.start() > 0: end = start + m.start(); break
    rows = []
    for _, body in templates(text[start:end], "DropsLineSkill"):
        f = fields(strip_nested(body))
        name, rar = f.get("name", ""), f.get("rarity", "").replace(",", "")
        if not name or name.lower() == "nothing": continue
        on = obj_name(name)
        if on is None:
            log.append(("", name, "no obj id")); continue
        q = [int(x) for x in re.findall(r"\d+", re.sub(r"\(.*?\)", "", f.get("quantity", "1")))] or [1]
        if rar.lower() == "always":
            rows.append((on, min(q), max(q), 1, 1, 1)); continue
        m = re.fullmatch(r"(\d+(?:\.\d+)?)\s*/\s*(\d+(?:\.\d+)?)", rar)
        if not m:
            log.append(("", name, f"unparsed rarity {rar}")); continue
        fr = Fraction(m.group(1)) / Fraction(m.group(2))
        rows.append((on, min(q), max(q), fr.numerator, fr.denominator, 0))
    return rows


entries = []
for title, text in sorted(pages.items()):
    for pos, body in templates(text, "Thieving info"):
        info = fields(strip_nested(body))
        kind = info.get("type", "").lower()
        if kind not in ("pickpocket", "stall"): continue
        try:
            level, xp = int(re.match(r"\d+", info["level"]).group(0)), float(re.match(r"[\d.]+", info["xp"]).group(0))
        except (KeyError, AttributeError, ValueError):
            log.append((title, "", "no level/xp")); continue
        if xp <= 0:  # quest-only targets (Berry, Twig, Movario...)
            log.append((title, "", "0 xp (quest-only)")); continue
        rows = loot(text, pos)
        if not rows:
            log.append((title, "", "no loot")); continue
        if kind == "pickpocket":
            npcs = [npc_sym[i] for i in page_ids(text) if i in pickpocket_ids and i in npc_sym and npc_sym[i] not in exclude]
            chart = next((b for _, b in templates(text, "Skilling success chart")), "")
            lo, hi = re.search(r"\|\s*low1\s*=\s*(\d+)", chart), re.search(r"\|\s*high1\s*=\s*(\d+)", chart)
            if not npcs or not lo or not hi:
                log.append((title, "", f"pickpocket skipped (npcs={len(npcs)} chart={bool(lo and hi)})")); continue
            stun = ticks(info.get("time")) or 8
            dmg = int((re.match(r"\d+", info.get("damage", "") or "") or re.match(r"\d", "1")).group(0))
            entries.append(("pickpocket", title, ",".join(npcs), level, xp, int(lo.group(1)), int(hi.group(1)), stun, dmg, rows))
        else:
            locs = [loc_sym[i] for i in page_ids(text) if i in loc_sym]
            if not locs:
                log.append((title, "", "stall without loc ids")); continue
            empties = [next((e for rx, e in EMPTY_STALL if rx.search(l)), "-") for l in locs]
            respawn = ticks(info.get("time")) or 4
            entries.append(("stall", title, ",".join(locs), level, xp, respawn, ",".join(empties), rows))
        stats[kind] += 1
        break  # one entry per page (the first Thieving info / loot table)

with open(out_path, "w", encoding="utf-8", newline="\n") as w:
    w.write("# Generated from OSRS Wiki {{Thieving info}} pages by tools/skills/thieving_to_tsv.py - do not edit.\n")
    for e in entries:
        w.write("\n" + "\t".join(str(x) for x in e[:-1]) + "\n")
        for r in e[-1]: w.write("loot\t" + "\t".join(str(x) for x in r) + "\n")
with open(log_path, "w", encoding="utf-8", newline="\n") as f:
    f.write("page\titem\treason\n")
    for row in sorted(set(log)): f.write("\t".join(row) + "\n")
print(dict(stats))
