"""OSRS Wiki clue scroll drop sources -> clue_drops.tsv (api/death resource).

Usage:
  python -I tools/clues/wiki_clue_drops_to_tsv.py <drop_tables.tsv> <clue_<tier>.json>... > clue_drops.tsv

Each json is the wiki bucket query result for one tier:
  api.php?action=bucket&format=json&query=bucket('dropsline').select('page_name','item_name',
      'drop_json').where('item_name','Clue scroll (easy)').limit(5000).run()

The drop-table generator skips {{DropsLineClue}}, so clue drops live in their own file. Sources are
matched to npcs through drop_tables.tsv's `table <ids>  # <page> [/ <version>]` lines, the wiki
"Dropped from" being "<page>[#<version>]". Sources with no npc table (implings, chests, skilling)
and unparsable rarities ("Unknown", "Always") are skipped and listed in the header. Output:

  clue  <tier>  <npc ids comma separated>  <num>  <den>   # <source>
"""
import fractions
import json
import re
import sys


def tables(path):
    out = {}
    for line in open(path, encoding="utf-8"):
        m = re.match(r"^table\t([\d,]+)\t#\s*(.*?)\s*$", line)
        if m:
            out.setdefault(m.group(2).lower(), m.group(1))
    return out


def rarity(text):
    text = text.replace(",", "").strip()
    if "/" not in text:
        return None
    num, den = text.split("/", 1)
    try:
        r = fractions.Fraction(fractions.Fraction(num), fractions.Fraction(den))
    except (ValueError, ZeroDivisionError):
        return None
    return r.limit_denominator(1_000_000)


def main():
    by_source = tables(sys.argv[1])
    rows, skipped = [], []
    for path in sys.argv[2:]:
        tier = re.search(r"clue_(\w+)\.json", path).group(1)
        seen = set()
        for entry in json.load(open(path, encoding="utf-8"))["bucket"]:
            drop = json.loads(entry["drop_json"])
            source = drop.get("Dropped from", "")
            key = source.replace("#", " / ").lower()
            ids = by_source.get(key) or by_source.get(source.split("#")[0].lower())
            r = rarity(drop.get("Rarity", ""))
            if ids is None or r is None:
                skipped.append(f"{tier}:{source}")
                continue
            if (tier, ids) in seen:
                continue
            seen.add((tier, ids))
            rows.append(f"clue\t{tier}\t{ids}\t{r.numerator}\t{r.denominator}\t# {source}")
    print("# Clue scroll drops from the OSRS Wiki (DropsLineClue) by")
    print("# tools/clues/wiki_clue_drops_to_tsv.py - do not edit by hand; regenerate.")
    print(f"# Skipped (no npc table or no rarity): {len(skipped)}")
    for row in rows:
        print(row)
    print(f"{len(rows)} rows, {len(skipped)} skipped", file=sys.stderr)


if __name__ == "__main__":
    main()
