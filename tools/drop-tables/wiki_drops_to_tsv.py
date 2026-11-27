"""Convert OSRS Wiki drop tables -> api/death drop_tables.tsv (read by NpcDropTables at runtime).

Args: drops_pages.json item_ids.json obj.sym npc.sym out.tsv log.tsv spawns.toml [spawns.toml ...]

  drops_pages.json  fetch_wiki_spawns.py drops_pages.json DropsLine
  item_ids.json     fetch_item_ids.py item_ids.json

Only npcs that appear in the given spawn files get a table. Per wiki page, drops are grouped by
the DropsTableHead `dropversion` (e.g. "Drop table 1"); each infobox version's `dropversion` picks
its table (default: the page's first table). Section headings decide how a line rolls:
  "100%"/rarity Always -> always; "Tertiary" -> independent roll; anything else -> main table
(one roll, the remainder is nothing). {{GemDropTable|r}}, {{RareDropTable|r}} and DropsLines named
after the shared tables become main-table slots that roll the shared table.

Output (tab separated, '#' starts a comment):
  table     <npc ids comma separated>   # page / dropversion
  shared    <gem|rdt|megarare>
  always    <obj> <min> <max> <noted>
  main      <obj> <min> <max> <noted> <num> <den>
  tertiary  <obj> <min> <max> <noted> <num> <den>
  sharedref <gem|rdt|megarare> <num> <den>
"""
import json, re, sys
from collections import Counter, defaultdict
from fractions import Fraction

# Optional `--templates templates.json`: wikitext of transcluded drop-table templates such as
# "Template:Human drop table" (used by Man, Woman, ...), expanded into the pages that use them.
drop_templates = {}
if "--templates" in sys.argv:
    i = sys.argv.index("--templates")
    drop_templates = json.load(open(sys.argv[i + 1], encoding="utf-8"))
    del sys.argv[i:i + 2]
pages = json.load(open(sys.argv[1], encoding="utf-8"))
item_ids = json.load(open(sys.argv[2], encoding="utf-8"))


def expand_drop_templates(text):
    """Inline {{Human drop table}}-style templates (their <includeonly> part, {{{dropversion}}} set)."""
    for title, body in drop_templates.items():
        name = title.split(":", 1)[1]
        inner = re.search(r"<includeonly>(.*?)</includeonly>", body, re.S)
        inner = inner.group(1) if inner else body

        def sub(m):
            dv = re.search(r"dropversion\s*=\s*([^|}]*)", m.group(1) or "")
            return inner.replace("{{{dropversion|}}}", dv.group(1).strip() if dv else "")

        text = re.sub(r"\{\{\s*" + re.escape(name) + r"\s*(\|[^{}]*)?\}\}", sub, text)
    return text


pages = {t: expand_drop_templates(x) for t, x in pages.items()}


def read_sym(path):
    out = {}
    for line in open(path, encoding="utf-8"):
        p = line.rstrip("\n").split("\t")
        if len(p) == 2 and p[0].isdigit(): out[int(p[0])] = p[1]
    return out


obj_sym, npc_sym = read_sym(sys.argv[3]), read_sym(sys.argv[4])
npc_ids = {v: k for k, v in npc_sym.items()}
out_path, log_path = sys.argv[5], sys.argv[6]
spawned = set()
for path in sys.argv[7:]:
    spawned |= {npc_ids[n] for n in re.findall(r"npc = '([^']+)'", open(path, encoding="utf-8").read()) if n in npc_ids}

# "Nothing" is the implicit remainder; trading sticks replace coins only on Karamja (listed alongside).
SKIP_ITEMS = {"nothing", "trading sticks"}
SHARED = {"gem drop table": "gem", "rare drop table": "rdt", "mega-rare drop table": "megarare"}
# Sub-table templates on monster pages -> shared table key (contents from the wiki page "Drop table").
SUBTABLE_TEMPLATES = {
    "GemDropTable": "gem", "RareDropTable": "rdt",
    "HerbDropTableInfo": "herb", "UsefulHerbDropTableInfo": "usefulherb", "CombatHerbDropTableInfo": "combatherb",
    "GeneralSeedDropTableInfo": "generalseed", "RareSeedDropTableInfo": "rareseed",
    "UncommonSeedDropTableInfo": "uncommonseed", "AllotmentSeedDropTableInfo": "allotmentseed",
    "TreeHerbSeedDropTableInfo": "treeherbseed",
}
# Shared table key -> section heading on the "Drop table" page. Only the first DropsTableHead block
# of each section is used: the general seed table has combat-level bands and this takes the first.
DROP_TABLE_PAGE_SECTIONS = {
    "herb": "Herb drop table", "usefulherb": "Useful herb drop table", "combatherb": "Combat herb drop table",
    "generalseed": "General seed drop table", "rareseed": "Rare seed drop table",
    "uncommonseed": "Uncommon seed drop table", "allotmentseed": "Allotment seed drop table",
    "treeherbseed": "Tree-herb seed drop table",
}
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
    s = re.sub(r"<!--.*?-->", "", s, flags=re.S)
    return re.sub(r"<ref[^>]*/>|<ref[^>]*>.*?</ref>", "", s, flags=re.S)


def fields(body):
    return {k.strip().lower(): v.strip() for k, v in re.findall(r"\|\s*([A-Za-z_]+\d*)\s*=\s*([^|]*)", body)}


def rarity(s):
    s = (s or "").replace(",", "").replace("~", "").strip()
    if s.lower() == "always": return "always"
    m = re.fullmatch(r"(\d+(?:\.\d+)?)\s*/\s*(\d+(?:\.\d+)?)", s)
    if not m: return None
    f = Fraction(m.group(1)) / Fraction(m.group(2))
    return f if 0 < f <= 1 else None


def quantity(s):
    s = (s or "1").replace("–", "-").replace("—", "-")
    noted = "noted" in s.lower()
    nums = [int(x) for x in re.findall(r"\d+", re.sub(r"\(.*?\)", "", s))] or [1]
    return min(nums), max(nums), noted


BAD_OBJ_NAME = re.compile(r"fake|dummy|placeholder|_cert|noted|trail_|league|deadman|_dt_|tut")


def norm(s): return re.sub(r"[^a-z0-9]", "", s.lower())


def obj_id(name):
    """Prefer the obj whose symbol matches the item name ('Coins' -> coins, not fake_coins)."""
    ids = [i for i in item_ids.get(name, []) if i in obj_sym]
    if not ids: return None
    exact = [i for i in ids if norm(obj_sym[i]) == norm(name)]
    clean = [i for i in ids if not BAD_OBJ_NAME.search(obj_sym[i])]
    return min(exact or clean or ids)


def parse_lines(text):
    """-> list of (dropversion, section, kind, payload) in page order."""
    events = []
    for m in re.finditer(r"^(={2,4})\s*([^=\n]+?)\s*\1\s*$", text, re.M):
        events.append((m.start(), "heading", (len(m.group(1)), m.group(2).strip())))
    for name in ("DropsTableHead", "DropsTableBottom", "DropsLineReward", "DropsLine", *SUBTABLE_TEMPLATES):
        for pos, body in templates(text, name):
            if name == "DropsLine" and re.match(r"\w", body): continue  # DropsLineClue/Skill/... handled elsewhere
            events.append((pos, name, body))
    events.sort(key=lambda e: e[0])
    out, section, dv = [], "", ""
    for _, kind, body in events:
        if kind == "heading":
            level, title = body
            if level <= 3: section = title
            continue
        if kind == "DropsTableHead":
            dv = fields(strip_nested(body)).get("dropversion", ""); continue
        if kind == "DropsTableBottom":
            dv = ""; continue
        out.append((dv, section, kind, body))
    return out


def build_table(lines, page):
    always, main, tertiary = [], [], []
    for dv, section, kind, body in lines:
        if kind in SUBTABLE_TEMPLATES:
            clean = strip_nested(body)
            first = clean.split("|")[1].strip() if "|" in clean else ""
            r = rarity(first)
            if not isinstance(r, Fraction):  # e.g. |override=There is a 1/128 chance of rolling ...
                m = re.search(r"(\d+(?:\.\d+)?)\s*/\s*(\d+(?:\.\d+)?)", body)
                r = rarity(m.group(0)) if m else None
            if isinstance(r, Fraction): main.append(("sharedref", SUBTABLE_TEMPLATES[kind], r))
            else: log.append((page, kind, "unparsed rarity", first))
            if re.search(r"rolls\s*=\s*[2-9]", body): log.append((page, kind, "extra rolls ignored", ""))
            continue
        if "{{(f)}}" in body:  # free-to-play-only line; this server is a members world
            continue
        f = fields(strip_nested(body))
        name = f.get("name", "")
        if not name or name.lower() in SKIP_ITEMS: continue
        r = rarity(f.get("rarity"))
        if r is None:
            stats["bad_rarity"] += 1; log.append((page, name, "unparsed rarity", f.get("rarity", ""))); continue
        if name.lower() in SHARED:
            if isinstance(r, Fraction): main.append(("sharedref", SHARED[name.lower()], r))
            continue
        tert = "tertiary" in section.lower()
        if tert and r == "always":
            stats["conditional"] += 1; log.append((page, name, "skipped conditional tertiary (Always)", section)); continue
        oid = obj_id(name)
        if oid is None:
            stats["no_obj"] += 1; log.append((page, name, "no obj id in this cache", "")); continue
        lo, hi, noted = quantity(f.get("quantity"))
        entry = (oid, lo, hi, noted)
        if r == "always": always.append(entry)
        elif tert: tertiary.append((entry, r))
        else: main.append(("main", entry, r))
    return always, main, tertiary


def infobox_versions(text):
    out = []
    for name in ("Infobox Monster", "Infobox NPC"):
        for _, body in templates(text, name):
            f = fields(strip_nested(body))
            idxs = sorted({int(m) for k in f for m in re.findall(r"^(?:id|version)(\d+)$", k)}) or [0]
            for n in idxs:
                s = str(n) if n else ""
                ids = [int(x) for x in re.findall(r"\d+", f.get("id" + s) or f.get("id") or "")]
                out.append((ids, f.get("dropversion" + s) or f.get("dropversion") or ""))
    return out


def fmt_entry(e): return f"{e[0]}\t{e[1]}\t{e[2]}\t{int(e[3])}"


def write_table(w, always, main, tertiary, title):
    total = sum(r for k, _, r in main)
    if total > 1:
        log.append((title, "", f"main table sums to {float(total):.3f}; scaled to 1", ""))
        main = [(k, p, r / total) for k, p, r in main]
    for e in always: w.write(f"always\t{fmt_entry(e)}\t# {obj_sym[e[0]]}\n")
    for kind, payload, r in main:
        if kind == "sharedref": w.write(f"sharedref\t{payload}\t{r.numerator}\t{r.denominator}\n")
        else: w.write(f"main\t{fmt_entry(payload)}\t{r.numerator}\t{r.denominator}\t# {obj_sym[payload[0]]}\n")
    for e, r in tertiary: w.write(f"tertiary\t{fmt_entry(e)}\t{r.numerator}\t{r.denominator}\t# {obj_sym[e[0]]}\n")


with open(out_path, "w", encoding="utf-8", newline="\n") as w:
    w.write("# Generated from OSRS Wiki drop tables by tools/drop-tables/wiki_drops_to_tsv.py - do not edit.\n")
    # Shared tables: gem (incl. mega-rare slot), rare drop table, mega-rare.
    for key, page, heading in (("gem", "Gem drop table", "Gem Drop Table"), ("megarare", "Gem drop table", "Mega-rare drop table"),
                               ("rdt", "Rare drop table", "Rare drop table")):
        lines = [l for l in parse_lines(pages[page]) if l[1].lower() == heading.lower()
                 or (key == "rdt" and l[1].lower() in ("runes and ammunition", "weapons and armour", "other", "sub-tables"))]
        a, m, t = build_table(lines, page)
        w.write(f"\nshared\t{key}\n"); write_table(w, a, m, t, page)
    drop_table_page = pages["Drop table"]
    for key, heading in DROP_TABLE_PAGE_SECTIONS.items():
        m = re.search(r"^={2,4}\s*" + re.escape(heading) + r"\s*={2,4}\s*$", drop_table_page, re.M | re.I)
        if not m:
            log.append(("Drop table", heading, "section not found", "")); continue
        end = drop_table_page.find("{{DropsTableBottom}}", m.end())
        a, mm, t = build_table(parse_lines(drop_table_page[m.end():end]), "Drop table / " + heading)
        w.write(f"\nshared\t{key}\n"); write_table(w, a, mm, t, "Drop table / " + heading)
    tables = 0
    assigned = set()  # an npc id listed on two pages keeps the first (alphabetical) page's table
    for title, text in sorted(pages.items()):
        if title in ("Gem drop table", "Rare drop table", "Drop table"): continue
        versions = infobox_versions(text)
        ids_here = {i for ids, _ in versions for i in ids} & spawned
        if not ids_here: continue
        by_dv = defaultdict(list)
        for line in parse_lines(text): by_dv[line[0]].append(line)
        if not by_dv: continue
        # A drop-table template we could not expand (not in --templates) hides the main table.
        unexpanded = re.search(r"\{\{\s*[^|{}]*drop table(?! sources)[^|{}]*\s*(\||\}\})", text, re.I)
        order = list(by_dv)
        assign = defaultdict(set)
        for ids, dv in versions:
            key = dv if dv in by_dv else ("" if "" in by_dv else order[0])
            assign[key] |= set(ids) & spawned
        for dv, npcs in assign.items():
            for dup in sorted(npcs & assigned):
                log.append((title, npc_sym.get(dup, str(dup)), "npc already has a table from another page", ""))
            npcs = npcs - assigned
            if not npcs: continue
            a, m, t = build_table(by_dv[dv], title)
            if not (a or m) and (unexpanded or not t):
                # The main table lives in a template we could not expand: emit no table so the npc
                # keeps the default bones drop instead of dropping nothing. (Monsters that really
                # only have tertiary drops, e.g. spiders, keep their tertiary-only table.)
                if t: log.append((title, dv, "main table in unexpanded template; npc keeps bones", unexpanded.group(0)))
                continue
            assigned |= npcs
            w.write(f"\ntable\t{','.join(map(str, sorted(npcs)))}\t# {title}" + (f" / {dv}" if dv else "") + "\n")
            write_table(w, a, m, t, title + (f" / {dv}" if dv else "")); tables += 1; stats["npcs"] += len(npcs)
with open(log_path, "w", encoding="utf-8", newline="\n") as f:
    f.write("page\titem\treason\tdetail\n")
    for row in sorted(set(log)): f.write("\t".join(str(x).replace("\t", " ") for x in row) + "\n")
print("tables", tables, dict(stats))
