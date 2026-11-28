"""OSRS Wiki thievable chest pages -> content/skills/thieving thieving_chests.tsv.

Args: sym_dir out.tsv

Each page's {{Thieving info}} (level, xp), Infobox Scenery ids (chest locs) and DropsLineSkill
loot; respawn times from the Wiki "Thieving" chests table (not on the chest pages).

Output (tab separated):
  chest <title> <loc names comma> <level> <xp> <respawn ticks>
  loot  <obj> <min> <max> <num> <den> <always 0|1>   (one weighted roll; always=1 always given)
"""
import json, os, re, sys, urllib.parse, urllib.request
from fractions import Fraction

sym_dir, out_path = sys.argv[1:3]
API = "https://oldschool.runescape.wiki/api.php"
UA = {"User-Agent": "local-dev-tool/1.0 (personal offline project)"}

# Page -> (level, xp, respawn seconds) from the Wiki "Thieving" > Thievable chests table. Pirate/Isle of Souls/Underwater
# chests need content we don't have yet and are left out.
CHESTS = {
    "Chest (10 coins)": (13, 7.8, 3.5),
    "Chest (nature runes)": (28, 25, 8),
    "Chest (50 coins)": (43, 125, 45),
    "Chest (steel arrowtips)": (47, 150, 75),
    "Chest (Dorgesh-Kaan Average)": (52, 200, 90),
    "Chest (Chaos Druid Tower)": (59, 250, 120),
    "Stone chest": (64, 280, 0.6),
    "Chest (Ardougne Castle)": (72, 500, 240),
    "Chest (Dorgesh-Kaan Rich)": (78, 650, 300),
    "Chest (Rogues' Castle)": (84, 701.7, 20.4),
}


def read_sym(name):
    out = {}
    for line in open(os.path.join(sym_dir, name), encoding="utf-8"):
        p = line.rstrip("\n").split("\t")
        if len(p) == 2 and p[0].isdigit():
            out[int(p[0])] = p[1]
    return out


obj_sym, loc_sym = read_sym("obj.sym"), read_sym("loc.sym")
BAD_OBJ = re.compile(r"fake|dummy|placeholder|_cert|noted|trail_|league|deadman|tut")


def api(params):
    url = API + "?" + urllib.parse.urlencode({**params, "format": "json"})
    return json.load(urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=90))


def templates(text, name):
    start = 0
    while (i := text.find("{{" + name, start)) >= 0:
        depth, j = 0, i
        while j < len(text):
            if text.startswith("{{", j):
                depth += 1; j += 2
            elif text.startswith("}}", j):
                depth -= 1; j += 2
                if depth == 0:
                    break
            else:
                j += 1
        yield i, text[i + 2 + len(name):j - 2]
        start = j


def strip_nested(s):
    prev = None
    while prev != s:
        prev, s = s, re.sub(r"\{\{[^{}]*\}\}", "", s)
    return re.sub(r"<!--.*?-->|<ref[^>]*/>|<ref[^>]*>.*?</ref>", "", s, flags=re.S)


def fields(body):
    return {k.strip().lower(): v.strip() for k, v in re.findall(r"\|\s*([A-Za-z_]+\d*)\s*=\s*([^|]*)", body)}


def norm(s):
    return re.sub(r"[^a-z0-9]", "", s.lower())


_item_ids = {}


def obj_name(item):
    if item not in _item_ids:
        q = f"bucket('infobox_item').select('item_name','item_id').where('item_name',{json.dumps(item)}).run()"
        rows = api({"action": "bucket", "query": q}).get("bucket", [])
        _item_ids[item] = sorted({int(x) for r in rows for x in (r.get("item_id") or []) if str(x).isdigit()})
    ids = [i for i in _item_ids[item] if i in obj_sym]
    if not ids:
        return None
    exact = [i for i in ids if norm(obj_sym[i]) == norm(item)]
    clean = [i for i in ids if not BAD_OBJ.search(obj_sym[i])]
    return obj_sym[min(exact or clean or ids)]


warnings, entries = [], []
for title, (level, xp, seconds) in CHESTS.items():
    text = api({"action": "parse", "page": title, "prop": "wikitext"})["parse"]["wikitext"]["*"]
    box = next((b for _, b in templates(text, "Infobox Scenery")), "")
    ids = [int(x) for _, v in re.findall(r"\|\s*(id\d*)\s*=\s*([0-9][0-9,\s]*)", box) for x in re.findall(r"\d+", v)]
    # Closed chests only (the Rogues' Castle page lists its open version as id2).
    locs = [loc_sym[i] for i in ids[:1] if i in loc_sym] if "id2" in box else [loc_sym[i] for i in ids if i in loc_sym]
    locs = [l for l in locs if not l.endswith("_empty")]
    rows = []
    for _, body in templates(text, "DropsLineSkill"):
        f = fields(strip_nested(body))
        name, rar = f.get("name", ""), f.get("rarity", "").replace(",", "")
        if not name or name.lower() == "nothing":
            continue
        on = obj_name(name)
        if on is None:
            warnings.append(f"{title}: no obj for {name}")
            continue
        q = [int(x) for x in re.findall(r"\d+", re.sub(r"\(.*?\)", "", f.get("quantity", "1")))] or [1]
        if rar.lower() == "always":
            rows.append((on, min(q), max(q), 1, 1, 1))
            continue
        m = re.fullmatch(r"(\d+(?:\.\d+)?)\s*/\s*(\d+(?:\.\d+)?)", rar)
        if not m:
            warnings.append(f"{title}: unparsed rarity {rar} for {name}")
            continue
        fr = Fraction(m.group(1)) / Fraction(m.group(2))
        rows.append((on, min(q), max(q), fr.numerator, fr.denominator, 0))
    if not locs or not rows:
        warnings.append(f"{title}: skipped (locs={locs} loot={len(rows)})")
        continue
    entries.append(("chest", title, ",".join(locs), level, f"{xp:g}", max(1, round(seconds / 0.6)), rows))

with open(out_path, "w", encoding="utf-8", newline="\n") as w:
    w.write("# Generated from OSRS Wiki thievable chest pages by tools/skills/thieving_chests.py - do not edit.\n")
    for e in entries:
        w.write("\n" + "\t".join(str(x) for x in e[:-1]) + "\n")
        for r in e[-1]:
            w.write("loot\t" + "\t".join(str(x) for x in r) + "\n")
print("chests", len(entries))
for w_ in warnings:
    print("  warn", w_, file=sys.stderr)
