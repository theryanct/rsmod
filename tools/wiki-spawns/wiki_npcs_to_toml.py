"""Convert wiki {{LocLine}} data -> RSMod npcs.toml (F2P only).

Args: pages.json npc.sym out.toml log.tsv existing_npcs.toml [existing_npcs.toml ...]

pages.json comes from `fetch_wiki_spawns.py pages.json LocLine`.

Id mapping: a LocLine gives positions + combat `levels`, not an npc id. Each LocLine is matched to
the page's infobox versions by (in order) an explicit `id`, a version name found in the LocLine
location, then combat level; the first matching version wins and its ids are rotated across the
positions (they are usually visual variants). Anything that cannot be matched is written to the
log, as are all exclusions.

Map squares that already have hand-curated spawns in any given existing npcs.toml are skipped
entirely, so area modules own their squares.
"""
import json, re, sys
from collections import Counter, defaultdict

pages = json.load(open(sys.argv[1], encoding="utf-8"))
sym = {}
for line in open(sys.argv[2], encoding="utf-8"):
    p = line.rstrip("\n").split("\t")
    if len(p) == 2 and p[0].isdigit(): sym[int(p[0])] = p[1]
out_path, log_path = sys.argv[3], sys.argv[4]
owned_squares = set()
for path in sys.argv[5:]:
    for c in re.findall(r"coords = '([^']+)'", open(path, encoding="utf-8").read()):
        _, mx, mz, _, _ = c.split("_")
        owned_squares.add((int(mx), int(mz)))

# Bosses / raid / minigame / quest-only pages that must never be world-spawned.
EXCLUDE_TITLES = {
    "Obor", "Bryophyta", "Elvarg", "Scurrius", "Giant Mole", "King Black Dragon", "Kalphite Queen",
    "Count Draynor", "Delrith", "Wormbrain", "Melzar the Mad", "Lesser demon (Melzar's Maze)",
    "Sir Mordred", "Tool Leprechaun", "Fishing spot", "Rod Fishing spot",
}
MEMBERS_F2P = {"n", "no", "f", "false"}
# Ids reserved for tutorials, quests, cutscenes or interiors; never spawned.
SPECIAL_ID_NAME = re.compile(
    r"tut|quest|qip_|dragonslayer|ds2_|cutscene|mcannon|_in_|instance|dummy|_cs_|_cs$|deadman|leagues|_dt_|stronghold|favour"
    # members-only regions whose ids the wiki lumps into the same version as the F2P npc
    r"|kourend|zeah|varlamore|slayercave|shayzien|hosidius|lovakengj|arceuus|piscarilius|tonali|prif|fossil"
)


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
        yield text[i + 2 + len(name):j - 2]
        start = j


def strip_nested(s):
    prev = None
    while prev != s:
        prev, s = s, re.sub(r"\{\{[^{}]*\}\}", "", s)
    s = re.sub(r"<!--.*?-->", "", s, flags=re.S)
    return re.sub(r"<ref[^>]*/>|<ref[^>]*>.*?</ref>", "", s, flags=re.S)


def fields(body):
    return {k.strip().lower(): v.strip() for k, v in re.findall(r"\|\s*([A-Za-z_]+\d*)\s*=\s*([^|\n]*)", body)}


def ints(s):
    return [int(x) for x in re.findall(r"\d+", s or "")]


def infobox_versions(text):
    """-> list of dicts {idx, name, combat, ids, quest} over every infobox on the page.

    Pages such as Skeleton or Guard carry one infobox per level/area, each with its own versions.
    """
    bodies = list(templates(text, "Infobox Monster")) + list(templates(text, "Infobox NPC"))
    if not bodies: return None
    out = []
    for b, body in enumerate(bodies):
        f = fields(strip_nested(body))
        idxs = sorted({int(m) for k in f for m in re.findall(r"^(?:id|version)(\d+)$", k)}) or [0]
        for n in idxs:
            s = str(n) if n else ""
            ids = ints(f.get("id" + s) or f.get("id"))
            combat = ints(f.get("combat" + s) or f.get("combat"))
            # Versions made only of tutorial/quest/cutscene ids get no ids, so they never match.
            clean = [i for i in ids if i in sym and not SPECIAL_ID_NAME.search(sym[i])]
            out.append({"idx": (b, n), "name": (f.get("version" + s) or "").lower(), "ids": clean,
                        "combat": combat[0] if combat else None, "quest": f.get("quest" + s) or f.get("quest") or ""})
    return out


def level_set(s):
    lv = set()
    for a, b in re.findall(r"(\d+)\s*(?:-\s*(\d+))?", s or ""):
        lv |= set(range(int(a), int(b or a) + 1)) if b and int(b) - int(a) < 500 else {int(a)}
    return lv


# Words in version names that describe a look/level rather than a place.
GENERIC_WORDS = {"level", "normal", "default", "plain", "armed", "unarmed", "male", "female", "variant",
                 "large", "small", "version", "old", "new", "combat", "and", "the", "with"}


def place_word(v):
    """First non-generic word of a version name ('Edgeville 2' -> 'edgeville'), '' if none."""
    for w in re.findall(r"[a-z']+", v["name"]):
        if w not in GENERIC_WORDS and len(w) >= 3: return w
    return ""


def merged(group):
    """Versions that differ only by a variant number rotate across all their ids."""
    ids = [i for v in group for i in v["ids"]]
    return {"idx": group[0]["idx"], "name": group[0]["name"], "ids": ids, "combat": group[0]["combat"],
            "quest": group[0]["quest"]}


def pick_version(versions, kv, location, levels):
    if kv.get("id"):
        want = set(ints(kv["id"]))
        for v in versions:
            if want & set(v["ids"]): return v, "id"
        ids = [i for i in ints(kv["id"]) if i in sym]
        if ids: return {"idx": (-1, -1), "name": "", "ids": ids, "combat": None, "quest": ""}, "id"
    named = kv.get("version", "").lower()
    if named:
        for v in versions:
            if v["name"] == named: return v, "version"
    cands = [v for v in versions if v["ids"] and (not levels or v["combat"] is None or v["combat"] in levels)]
    if not cands: return None, "no version with matching combat level"
    loc = location.lower()
    by_loc = [v for v in cands if place_word(v) and place_word(v) in loc]
    how = "location" if by_loc else "level"
    if not by_loc:  # prefer versions not tied to some other place
        by_loc = [v for v in cands if not place_word(v)] or cands
    first = by_loc[0]
    group = [v for v in by_loc if v["idx"][0] == first["idx"][0] and v["combat"] == first["combat"]
             and place_word(v) == place_word(first)]
    return merged(group), how


spawns, log, stats, seen = [], [], Counter(), set()
rotation = defaultdict(int)
for title, text in sorted(pages.items()):
    lines = list(templates(text, "LocLine"))
    if not lines: continue
    if title in EXCLUDE_TITLES:
        stats["excluded_title"] += len(lines); log.append((title, "", "excluded title", "")); continue
    versions = infobox_versions(text)
    if versions is None:
        stats["no_infobox"] += len(lines); log.append((title, "", "no infobox", "")); continue
    for raw in lines:
        clean = strip_nested(raw)
        kv = fields(clean)
        location = re.sub(r"\[\[(?:[^|\]]*\|)?([^\]]*)\]\]", r"\1", kv.get("location", ""))
        if kv.get("members", "").lower() not in MEMBERS_F2P:
            stats["members"] += 1; continue
        map_id = ints(kv.get("mapid"))
        if map_id and map_id[0] >= 10000:
            stats["instance_map"] += 1; log.append((title, location, "instance mapID", kv.get("mapid"))); continue
        points = re.findall(r"x:\s*(\d+)(?:\.\d+)?\s*,\s*y:\s*(\d+)(?:\.\d+)?([^|]*)", clean)
        if not points: continue
        plane = (ints(kv.get("plane")) or [0])[0]
        groups = defaultdict(list)  # per-point `levels:` overrides the line's levels
        for x, y, rest in points:
            m = re.search(r"levels:\s*([\d,\s-]+)", rest)
            groups[m.group(1).strip() if m else kv.get("levels", "")].append((int(x), int(y)))
        for lv, pts in groups.items():
            v, how = pick_version(versions, kv, location, level_set(lv))
            if v is None:
                stats["unmatched"] += len(pts); log.append((title, location, "unmatched: " + how, f"levels={lv}"))
                continue
            if v["quest"] and v["quest"].lower() not in ("no", "none", ""):
                stats["quest"] += len(pts); log.append((title, location, "quest npc", v["quest"])); continue
            stats["match_" + how] += len(pts)
            for x, z in pts:
                mx, mz = x >> 6, z >> 6
                if (mx, mz) in owned_squares:
                    stats["owned_square"] += 1; continue
                coords = f"{plane}_{mx}_{mz}_{x & 63}_{z & 63}"
                key = (title, coords)
                if key in seen: stats["dup"] += 1; continue
                seen.add(key)
                ids = v["ids"]
                npc = sym[ids[rotation[(title, v["idx"])] % len(ids)]]
                rotation[(title, v["idx"])] += 1
                spawns.append((npc, coords, title))

with open(out_path, "w", encoding="utf-8", newline="\n") as f:
    f.write("# Generated from OSRS Wiki LocLine data (F2P) by tools/wiki-spawns/wiki_npcs_to_toml.py\n")
    for npc, coords, _ in sorted(spawns, key=lambda t: (t[1], t[0])):
        f.write(f"\n[[spawn]]\nnpc = '{npc}'\ncoords = '{coords}'\n")
with open(log_path, "w", encoding="utf-8", newline="\n") as f:
    f.write("page\tlocation\treason\tdetail\n")
    for row in sorted(set(log)):
        f.write("\t".join(r.replace("\t", " ") for r in row) + "\n")
print("spawns", len(spawns), dict(stats))
