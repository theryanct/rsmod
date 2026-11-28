"""OSRS Wiki "Superior slayer monster" -> content/skills/slayer slayer_superiors.tsv.

Args: slayer.json npc.sym slayer.tsv out.tsv
  slayer.json  fetch_slayer.py output (its infobox_monster rows give page -> npc ids, categories)
  slayer.tsv   this module's task table (only categories we assign are kept)

Output (tab separated):
  superior <category key> <normal npc names comma> <superior npc name> <slayer xp>
"""
import json, re, sys, urllib.parse, urllib.request

slayer_json, npc_sym_path, tasks_path, out_path = sys.argv[1:5]
data = json.load(open(slayer_json, encoding="utf-8"))
npc_sym = {int(l.split("\t")[0]): l.rstrip("\n").split("\t")[1] for l in open(npc_sym_path, encoding="utf-8") if "\t" in l}
categories = {l.split("\t")[2] for l in open(tasks_path, encoding="utf-8") if l.startswith("task\t")}

by_page = {}
for m in data["monsters"]:
    page = (m.get("page_name") or "").split("#")[0]
    ids = [int(i) for i in (m.get("id") or []) if str(i).isdigit() and int(i) in npc_sym]
    cats = m.get("slayer_category") or []
    if isinstance(cats, str):
        cats = [cats]
    entry = by_page.setdefault(page.lower(), {"ids": [], "cats": set()})
    entry["ids"] += ids
    entry["cats"] |= {c.strip().lower() for c in cats}

API = "https://oldschool.runescape.wiki/api.php"
UA = {"User-Agent": "local-dev-tool/1.0 (personal offline project)"}
url = API + "?" + urllib.parse.urlencode({"action": "parse", "page": "Superior slayer monster", "prop": "wikitext", "format": "json"})
text = json.load(urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60))["parse"]["wikitext"]["*"]
table = text[text.find("{|"):text.find("|}", text.find("{|"))]


def link(cell):
    m = re.search(r"\[\[([^|\]]+)", cell)
    return m.group(1).strip() if m else None


rows, skipped = [], []
superior = xp = None
for chunk in table.split("\n|-")[1:]:
    cells = [c.strip() for c in re.split(r"\n\|(?!\})", "\n" + chunk.strip()) if c.strip()]
    links = [link(c) for c in cells]
    links = [l for l in links if l and not l.lower().startswith("file:")]
    if not links:
        continue
    # Rows with a rowspan superior (cockatrice / moonlight cockatrice) only carry the normal npc.
    if len(links) >= 2:
        normal, superior = links[0], links[1]
        # Slayer XP is the last column; cells may carry a "rowspan=... |" prefix and decimals.
        last = re.search(r"([\d,]+(?:\.\d+)?)\s*$", cells[-1].split("|")[-1])
        xp = float(last.group(1).replace(",", "")) if last else None
    else:
        normal = links[0]
    if not superior or xp is None:
        continue
    norm = by_page.get(normal.lower())
    sup = by_page.get(superior.lower())
    if not norm or not sup or not sup["ids"]:
        skipped.append((normal, superior, "no npc ids"))
        continue
    cats = [c for c in norm["cats"] if c in categories]
    if not cats:
        skipped.append((normal, superior, "category not assigned"))
        continue
    normals = sorted({npc_sym[i] for i in norm["ids"]})
    sup_name = npc_sym[min(i for i in sup["ids"] if not npc_sym[i].startswith(("league_", "deadman_")))
                       ] if any(not npc_sym[i].startswith(("league_", "deadman_")) for i in sup["ids"]) else None
    if not sup_name:
        skipped.append((normal, superior, "only league/deadman ids"))
        continue
    rows.append((cats[0], ",".join(normals), sup_name, f"{xp:g}"))

with open(out_path, "w", encoding="utf-8", newline="\n") as w:
    w.write("# Generated from the OSRS Wiki 'Superior slayer monster' table by tools/slayer/slayer_superiors.py - do not edit.\n")
    for r in rows:
        w.write("\t".join(["superior", *map(str, r)]) + "\n")
print("superiors", len(rows), "skipped", len(skipped))
for s in skipped:
    print("  skip", *s, file=sys.stderr)
