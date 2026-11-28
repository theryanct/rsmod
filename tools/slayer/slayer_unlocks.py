"""Rev 243 slayer_unlock dbrows -> content/skills/slayer slayer_unlocks.tsv.

Args: dump.dbrow slayer.tsv out.tsv
  dump.dbrow  config/dump.dbrow from Joshua-F/osrs-dumps (rev 243)
  slayer.tsv  this module's task table (for the category keys "longer" unlocks extend)

Output (tab separated):
  unlock <bit> <cost> <name> <refundable 0|1> <extends: category keys comma|->
"Longer" unlocks (dbrow slayer_rewards_longer_<x>) are matched to our Wiki category keys by
normalised name; the others are stored as bits only (their effects are separate content).
"""
import re, sys

dbrow_path, tasks_path, out_path = sys.argv[1:4]

categories = sorted({l.split("\t")[2] for l in open(tasks_path, encoding="utf-8") if l.startswith("task\t")})


def norm(s):
    return re.sub(r"[^a-z]", "", s.lower())


ALIASES = {
    "metaldragons": ["bronze dragons", "iron dragons", "steel dragons"],
    "spiritualgwd": ["spiritual creatures"],
    "frostdragons": ["frost dragons"],
}


def extends(suffix):
    key = norm(suffix)
    if key in ALIASES:
        return [c for c in ALIASES[key] if c in categories]
    hits = [c for c in categories if norm(c) == key or norm(c).rstrip("s") == key.rstrip("s")]
    return hits


rows = []
for rec in open(dbrow_path, encoding="utf-8").read().split("\n\n"):
    if not re.search(r"^table=slayer_unlock$", rec, re.M):
        continue
    head = re.search(r"^\[(.+)\]$", rec, re.M).group(1)
    data = dict(re.findall(r"^data=([a-z_]+),(.*)$", rec, re.M))
    bit = int(data["bit"])
    cost = int(data.get("cost", "0"))
    name = data.get("name", head)
    refundable = 1 if data.get("refundable") == "true" else 0
    ext = []
    m = re.match(r"slayer_rewards_longer_(.+)$", head)
    if m:
        ext = extends(m.group(1))
        if not ext:
            print("no category for", head, file=sys.stderr)
    rows.append((bit, cost, name, refundable, ",".join(ext) or "-"))

rows.sort()
with open(out_path, "w", encoding="utf-8", newline="\n") as w:
    w.write("# Generated from the rev 243 slayer_unlock dbrows by tools/slayer/slayer_unlocks.py - do not edit.\n")
    for r in rows:
        w.write("\t".join(["unlock", *map(str, r)]) + "\n")
print("unlocks", len(rows), "extends", sum(1 for r in rows if r[4] != "-"))
