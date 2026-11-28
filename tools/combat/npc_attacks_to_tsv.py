"""OSRS Wiki infobox_monster -> api/combat npc_attacks.tsv (ranged and magic npcs).

Args: out.tsv log.tsv   (fetches the bucket itself; needs network)

Only npcs whose Wiki attack styles are all ranged or all magic are included (mixed melee/magic
npcs keep their melee attack). Max hit is the first numeric max hit; speed is the Wiki attack
speed in ticks (default 4).

Output (tab separated):  attack <npc internal name> <ranged|magic> <max hit> <speed>
"""
import json, os, re, sys, time, urllib.parse, urllib.request

API = "https://oldschool.runescape.wiki/api.php"
UA = {"User-Agent": "local-dev-tool/1.0 (personal offline project)"}
out_path, log_path = sys.argv[1], sys.argv[2]
sym_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", ".data", "symbols", "npc.sym")

rows, offset = [], 0
while True:
    q = (f"bucket('infobox_monster').select('page_name','id','attack_style','max_hit','attack_speed')"
         f".limit(500).offset({offset}).run()")
    url = API + "?" + urllib.parse.urlencode({"action": "bucket", "format": "json", "query": q})
    batch = json.load(urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60)).get("bucket", [])
    rows += batch
    if len(batch) < 500:
        break
    offset += 500
    time.sleep(0.5)

sym = {}
for line in open(sym_path, encoding="utf-8"):
    p = line.rstrip("\n").split("\t")
    if len(p) == 2 and p[0].isdigit():
        sym[int(p[0])] = p[1]

MELEE = re.compile(r"melee|stab|slash|crush", re.I)
out, log = {}, []
for row in rows:
    styles = [s for s in (row.get("attack_style") or []) if isinstance(s, str)]
    text = " ".join(styles)
    if not styles or MELEE.search(text):
        continue
    ranged, magic = re.search(r"ranged", text, re.I), re.search(r"magic", text, re.I)
    if bool(ranged) == bool(magic):
        log.append(f"skip\t{row.get('page_name')}\t{text}")
        continue
    style = "ranged" if ranged else "magic"
    max_hit = next((int(m) for h in (row.get("max_hit") or []) for m in re.findall(r"\d+", str(h))), None)
    if max_hit is None:
        log.append(f"nomax\t{row.get('page_name')}\t{row.get('max_hit')}")
        continue
    speed = row.get("attack_speed")
    speed = int(speed) if str(speed).isdigit() else 4
    for raw in row.get("id") or []:
        if str(raw).isdigit() and int(raw) in sym:
            out.setdefault(sym[int(raw)], (style, max_hit, speed))

with open(out_path, "w", encoding="utf-8", newline="\n") as f:
    f.write("# Generated from OSRS Wiki infobox_monster by tools/combat/npc_attacks_to_tsv.py\n")
    f.write("# attack <npc> <ranged|magic> <max hit> <speed ticks>\n")
    for name, (style, max_hit, speed) in sorted(out.items()):
        f.write(f"attack\t{name}\t{style}\t{max_hit}\t{speed}\n")
open(log_path, "w", encoding="utf-8", newline="\n").write("\n".join(log) + "\n")
print("npcs", len(out), "log", len(log), "rows", len(rows))
