"""OSRS Wiki reward casket pages -> treasure trail reward table TSVs.

Usage:
  python -I tools/clues/wiki_rewards_to_tsv.py <cache_dir> <obj.sym> <tier> <min_rolls> <max_rolls> \
      < "Reward_casket_(<tier>).txt" > rewards_<tier>.tsv
  (the page's raw wikitext: https://oldschool.runescape.wiki/w/Reward_casket_(easy)?action=raw)

Every {{DropsLineReward}} on the page is a per-roll chance ("The listed rarities are per roll"),
so one roll is a weighted pick over all the main lines (uniques, standard table, shared treasure
trail items); "Tertiary" lines roll independently once per casket. The "Rewards on Entrana"
variant and later sections are skipped. Output:

  rolls     <min> <max>
  roll      <obj sym> <min qty> <max qty> <noted 0/1> <num> <den>
  tertiary  <obj sym> <min qty> <max qty> <noted 0/1> <num> <den>
  milestone <obj sym> <min qty> <max qty> <noted 0/1> <completion count>  (e.g. scroll cases)

Item names resolve to the lowest unnoted, non-placeholder obj id with that exact name.
"""
import fractions
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "cache-check"))
import opwalk as W  # noqa: E402

LINE = re.compile(r"\{\{DropsLineReward\|(.*?)\}\}\s*$")
EXPR = re.compile(r"\{\{#expr:([^{}]*)\}\}")
MILESTONE = re.compile(r"on their (\d+)(?:st|nd|rd|th) completed")
# Sections skipped but not ending the parse (the Mimic boss has its own loot).
SKIP_SECTIONS = ("Mimic",)
SKIP_FROM = ("Rewards on Entrana", "Changes", "Gallery", "Notes", "References")


def obj_names(cache_dir):
    cache = W.Cache(cache_dir)
    records = cache.group_files(2, 10, cache.index(2)[10])
    names = {}
    for fid, data in sorted(records.items()):
        b = W.Buf(data)
        name, cert, placeholder = None, False, False
        try:
            while True:
                op = b.u8()
                if op == 0:
                    break
                if op == 2:
                    name = b.string().decode("latin1")
                elif op == 98:
                    b.u16()
                    cert = True
                elif op == 149:
                    b.u16()
                    placeholder = True
                elif op == 249:
                    for _ in range(b.u8()):
                        t = b.u8()
                        b.u24()
                        b.string() if t == 1 else (b.i64() if t == 2 else b.i32())
                else:
                    W.obj_op(op, b)
        except Exception:  # noqa: BLE001
            continue
        if name and not cert and not placeholder:
            names.setdefault(name.lower(), fid)
    return names


def syms(path):
    out = {}
    for line in open(path, encoding="latin1"):
        line = line.rstrip("\r\n")
        if "\t" in line:
            i, n = line.split("\t", 1)
            out[int(i)] = n
    return out


def params(body):
    out = {}
    for part in body.split("|"):
        if "=" in part:
            k, v = part.split("=", 1)
            out[k.strip()] = v.strip()
    return out


def expr(body):
    """A wiki {{#expr:}} body: arithmetic, optionally ending in "round N" (ignored: exact)."""
    body = re.sub(r"\s+round\s+\d+\s*$", "", body)
    value = eval(body, {"__builtins__": {}})  # noqa: S307 - wiki arithmetic only
    return str(fractions.Fraction(value).limit_denominator(10**9))


def rarity(text):
    if text.strip().lower() == "always":
        return fractions.Fraction(1)
    text = EXPR.sub(lambda m: expr(m.group(1)), text)
    text = text.replace(",", "")
    if "/" in text:
        num, den = text.split("/", 1)
        return fractions.Fraction(fractions.Fraction(num.strip()), fractions.Fraction(den.strip()))
    return fractions.Fraction(text)


def quantity(text):
    noted = "noted" in text.lower()
    text = re.sub(r"\(.*?\)", "", text).replace(",", "").strip()
    if "-" in text or "–" in text:
        lo, hi = re.split("[-–]", text, maxsplit=1)
        return int(lo), int(hi), noted
    return int(text), int(text), noted


def main():
    cache_dir, sym_path, tier, lo, hi = sys.argv[1:6]
    names = obj_names(cache_dir)
    sym = syms(sym_path)
    section, rows, missing, total = "", [], [], fractions.Fraction(0)
    skipping = False
    for raw in sys.stdin.read().splitlines():
        heading = re.match(r"^=+\s*(.*?)\s*=+\s*$", raw)
        if heading:
            section = heading.group(1)
            if any(section.startswith(s) for s in SKIP_FROM):
                break
            skipping = any(section.startswith(s) for s in SKIP_SECTIONS)
            continue
        if skipping:
            continue
        m = LINE.search(raw)
        if not m:
            continue
        p = params(m.group(1))
        name = p.get("name", "")
        obj = names.get(name.lower())
        if obj is None:  # wiki disambiguation, e.g. "Zombie head (Treasure Trails)"
            obj = names.get(re.sub(r"\s*\(.*?\)$", "", name).lower())
        if obj is None:
            missing.append(name)
            continue
        qlo, qhi, noted = quantity(p.get("quantity", "1"))
        milestone = MILESTONE.search(p.get("raritynotes", ""))
        if p.get("rarity", "").strip().lower() == "always" and milestone:
            rows.append(f"milestone	{sym.get(obj, obj)}	{qlo}	{qhi}	{int(noted)}	{milestone.group(1)}")
            continue
        r = rarity(p.get("rarity", "0"))
        kind = "tertiary" if section.lower().startswith("tertiary") else "roll"
        if kind == "roll":
            total += r
        rows.append(f"{kind}\t{sym.get(obj, obj)}\t{qlo}\t{qhi}\t{int(noted)}\t{r.numerator}\t{r.denominator}")
    print(f"# Reward casket ({tier}) from the OSRS Wiki (per-roll rarities) by")
    print("# tools/clues/wiki_rewards_to_tsv.py - do not edit by hand; regenerate.")
    print(f"# Main-roll chances sum to {float(total):.4f}; unresolved names: {missing or 'none'}")
    print(f"rolls\t{lo}\t{hi}")
    for row in rows:
        print(row)


if __name__ == "__main__":
    main()
