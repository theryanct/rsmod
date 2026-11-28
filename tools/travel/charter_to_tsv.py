"""OSRS Wiki charter ships -> content/travel/charter charter.tsv.

Args: charter_ship.txt charter_fares.txt out.tsv
  charter_ship.txt   wikitext of the Wiki page "Charter ship" (destination map pins)
  charter_fares.txt  wikitext of "Template:Charter ship fares" (origin x destination fare table)

Output (tab separated):
  dest <key> <name> <x> <z>        dock pin from the Wiki map (arrival is the nearest walkable tile)
  fare <from_key> <to_key> <coins> (base fare; Cabin Fever / ring of charos discounts not applied)
"""
import re, sys

ship_path, fares_path, out_path = sys.argv[1:4]


def key(name):
    name = re.sub(r"\[\[(?:[^|\]]*\|)?([^\]]*)\]\]", r"\1", name)
    return re.sub(r"[^a-z0-9]+", "_", name.lower()).strip("_"), name.strip()


rows = []
ship = open(ship_path, encoding="utf-8").read()
names = {}
for m in re.finditer(r"\|x:(\d+),y:(\d+),title:(\[\[.*?\]\]|[^,|]+)", ship):
    k, n = key(m.group(3))
    names[k] = n
    rows.append(f"dest\t{k}\t{n}\t{m.group(1)}\t{m.group(2)}")

fares = open(fares_path, encoding="utf-8").read()
header, _, body = fares.partition("! rowspan=")
destinations = [key(h)[0] for h in re.findall(r"^!(\[\[.*?\]\])\s*$", header, re.M)]
count = 0
for block in re.split(r"\n\|-\n", body):
    origin = re.search(r'! style="text-align:right;" \|(\[\[.*?\]\])', block)
    if not origin:
        continue
    src = key(origin.group(1))[0]
    cells = re.findall(r"^\|([^\n|]*)$", block, re.M)
    for dst, cell in zip(destinations, cells):
        value = cell.replace(",", "").replace("*", "").strip()
        row = f"fare\t{src}\t{dst}\t{value}"
        if value.isdigit() and row not in rows:
            rows.append(row)
            count += 1

missing = sorted({d for d in destinations if d not in names})
with open(out_path, "w", encoding="utf-8", newline="\n") as w:
    w.write("# Generated from the OSRS Wiki by tools/travel/charter_to_tsv.py - do not edit.\n")
    w.write("\n".join(rows) + "\n")
print("destinations", len(names), "fares", count, "fare columns without a pin", missing)
