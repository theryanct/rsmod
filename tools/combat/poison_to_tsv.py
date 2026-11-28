"""OSRS Wiki poisonous monsters (bucket infobox_monster) -> content/generic/generic-poison poison.tsv.

Args: monsters.json npc.sym out.tsv
  monsters.json  bucket('infobox_monster').select('page_name','id','poisonous')
                 .where('poisonous','!=','No') rows (all pages)

"Yes (N)" gives starting poison damage N; a plain "Yes" uses DEFAULT_DAMAGE. Venom, disease and
"No (...)" entries are skipped.

Output (tab separated):  poison <npc internal name> <starting damage>
"""
import json, re, sys

DEFAULT_DAMAGE = 3

rows = json.load(open(sys.argv[1], encoding="utf-8"))
sym = {}
for line in open(sys.argv[2], encoding="utf-8"):
    p = line.rstrip("\n").split("\t")
    if len(p) == 2 and p[0].isdigit():
        sym[int(p[0])] = p[1]

out = {}
for row in rows:
    value = (row.get("poisonous") or "").strip()
    m = re.match(r"^Yes(?:\s*\((\d+)\))?", value)
    if not m or "venom" in value.lower():
        continue
    damage = int(m.group(1)) if m.group(1) else DEFAULT_DAMAGE
    for raw in row.get("id") or []:
        if not str(raw).isdigit():
            continue
        name = sym.get(int(raw))
        if name:
            out.setdefault(name, damage)

with open(sys.argv[3], "w", encoding="utf-8", newline="\n") as f:
    f.write("# Generated from OSRS Wiki infobox_monster 'poisonous' by tools/combat/poison_to_tsv.py\n")
    f.write("# poison <npc> <starting damage>\n")
    for name, damage in sorted(out.items()):
        f.write(f"poison\t{name}\t{damage}\n")
print("poisonous npcs", len(out))
