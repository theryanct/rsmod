"""OSRS Wiki implings -> content/skills/hunter implings.tsv.

Args: sym_dir out.tsv

Levels and Puro-Puro / Gielinor xp from the Wiki "Impling" table; jar loot from each
"<Name> impling jar" page's drop table (weighted lines; "Always" lines always given).

Output (tab separated):
  imp  <type 1-11> <name> <level> <puro xp> <world xp>
  loot <obj> <min> <max> <num> <den> <always 0|1>
"""
import json, os, re, sys, urllib.parse, urllib.request
from fractions import Fraction

sym_dir, out_path = sys.argv[1:3]
API = "https://oldschool.runescape.wiki/api.php"
UA = {"User-Agent": "local-dev-tool/1.0 (personal offline project)"}
obj_sym = {int(l.split("\t")[0]): l.rstrip("\n").split("\t")[1] for l in open(os.path.join(sym_dir, "obj.sym"), encoding="utf-8") if "\t" in l}
BAD = re.compile(r"placeholder|cert_|_noted|fake|dummy|league|deadman|trail_")

# Cache order: ii_impling_type_1..11 / ii_captured_impling_1..11 (crystal = 12, not included).
IMPS = [("Baby", 17, 18, 20), ("Young", 22, 20, 22), ("Gourmet", 28, 22, 24), ("Earth", 36, 25, 27),
        ("Essence", 42, 27, 29), ("Eclectic", 50, 30, 32), ("Nature", 58, 34, 36), ("Magpie", 65, 44, 216),
        ("Ninja", 74, 50, 240), ("Dragon", 83, 65, 300), ("Lucky", 89, 80, 380)]


def api(params):
    url = API + "?" + urllib.parse.urlencode({**params, "format": "json"})
    return json.load(urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=90))


_ids = {}


def obj(name):
    if name not in _ids:
        q = f"bucket('infobox_item').select('item_id').where('item_name',{json.dumps(name)}).run()"
        rows = api({"action": "bucket", "query": q}).get("bucket", [])
        _ids[name] = sorted({int(x) for r in rows for x in (r.get("item_id") or []) if str(x).isdigit()})
    ids = [i for i in _ids[name] if i in obj_sym and not BAD.search(obj_sym[i])]
    return obj_sym[ids[0]] if ids else None


def fields(body):
    return {k.strip().lower(): v.strip() for k, v in re.findall(r"\|\s*([A-Za-z_]+\d*)\s*=\s*([^|}]*)", body)}


lines, warnings = [], []
for index, (name, level, puro, world) in enumerate(IMPS, 1):
    lines.append(["imp", index, f"{name} impling", level, puro, world])
    if name == "Lucky":
        # Lucky implings roll a clue-reward table; give the Wiki's simplest stand-in: none here.
        warnings.append("Lucky impling jar: loot not modelled (clue reward tables)")
        continue
    text = api({"action": "parse", "page": f"{name} impling jar", "prop": "wikitext", "redirects": 1})["parse"]["wikitext"]["*"]
    # "Main drops" only (the 9/10 jar return is handled in code; tertiary clues are left out).
    start = next((text.find(h) for h in ("===Main drops===", "===Standard loot===") if text.find(h) >= 0), -1)
    end = len(text)
    for mark in ("\n===", "\n=="):
        k = text.find(mark, start + 5)
        if k > 0: end = min(end, k)
    section = text[start:end] if start >= 0 else ""
    for m in re.finditer(r"\{\{DropsLine(?:Skill|Reward)?\|([^}]*)\}\}", section):
        f = fields("|" + m.group(1))
        item, rar = f.get("name", ""), f.get("rarity", "").replace(",", "")
        if not item or item.lower() in ("nothing",):
            continue
        o = obj(item)
        if o is None:
            warnings.append(f"{name}: no obj for {item}")
            continue
        q = [int(x) for x in re.findall(r"\d+", re.sub(r"\(.*?\)", "", f.get("quantity", "1")))] or [1]
        if rar.lower() == "always":
            lines.append(["loot", o, min(q), max(q), 1, 1, 1]); continue
        mm = re.fullmatch(r"(\d+(?:\.\d+)?)\s*/\s*(\d+(?:\.\d+)?)", rar)
        if not mm:
            warnings.append(f"{name}: rarity {rar} for {item}")
            continue
        fr = Fraction(mm.group(1)) / Fraction(mm.group(2))
        lines.append(["loot", o, min(q), max(q), fr.numerator, fr.denominator, 0])

with open(out_path, "w", encoding="utf-8", newline="\n") as w:
    w.write("# Generated from OSRS Wiki impling pages by tools/skills/implings.py - do not edit.\n")
    for line in lines:
        w.write("\t".join(map(str, line)) + "\n")
print("imps", sum(l[0] == "imp" for l in lines), "loot", sum(l[0] == "loot" for l in lines))
for warning in warnings:
    print("  warn", warning, file=sys.stderr)
