"""Fill npc spawns from OSRS Wiki infobox maps (`|map = {{Map|...}}`) for pages with no LocLine.

Args: pages.json cache_dir npc.sym out_f2p.toml out_members.toml log.tsv
      --spawns <toml...>   existing [[spawn]] tomls (all branches) used for dedupe
      --owned <toml...>    area-module tomls whose map squares are hand-curated (logged, not written)
      --exclude <file>     npc names spawned at runtime by content (never world-spawned)
      --force <file>       page titles to place even in owned squares / without a cache-name match
                           (e.g. ui's missing clue npc list), one per line

pages.json comes from `fetch_wiki_spawns.py pages.json "Infobox NPC"`.

The LocLine import (wiki_npcs_to_toml.py) covered npcs listed with LocLine; unique npcs (shopkeepers,
quest givers, clue npcs) only have an infobox map. Per infobox version: `mapN`/`map` gives the
position (`mtype=pin`: one spawn per pair; rectangle/polygon/line: one spawn at the centre, logged
as approx), `plane=` the level. The id is the first version id whose cache name matches the page
title, after dropping tutorial/quest/cutscene/instance ids; multinpc children spawn as their base.
Skipped as already spawned: any npc with the same cache name or multinpc family within RADIUS tiles
of an existing spawn on any branch, or a second version of the same page on the same spot.
"""
import json, os, re, struct, sys
from collections import Counter, defaultdict

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "npc-anims"))
from jagcache import Cache  # noqa: E402

RADIUS = 16
SPECIAL_ID = re.compile(
    r"tut|quest|qip_|dragonslayer|ds2_|cutscene|mcannon|_in_|instance|dummy|_cs_|_cs$|deadman"
    r"|leagues|_dt_|favour|nzone|_vis$|pvpa|br_|^sailing_|^gim_|^mag_|_vis_|poh_|_postquest_fake"
)
# Bosses, minigame/instance npcs, pets and npcs that exist only during an event or quest stage.
EXCLUDE_TITLES = {
    "Obor", "Bryophyta", "Elvarg", "Scurrius", "Giant Mole", "King Black Dragon", "Kalphite Queen",
    "Count Draynor", "Delrith", "Wormbrain", "Melzar the Mad", "Sir Mordred", "Tool Leprechaun",
    "Fishing spot", "Rod Fishing spot", "Treasure Trail", "Uri", "Double agent", "Zamorak wizard",
    "Saradomin wizard", "Armadylean guard", "Bandosian guard", "Ancient Wizard", "Brassican Mage",
    "Strange Old Man", "Drunken Dwarf", "Evil Bob", "Sandwich lady", "Genie", "Mysterious Old Man",
    "Rick Turpentine", "Frog (Kiss the frog)", "Bee keeper", "Freaky Forester", "Quiz Master",
    "Pillory Guard", "Dr Jekyll", "Flippa", "Leo", "Sergeant Damien", "Capt' Arnav", "Niles",
    "Miles", "Giles", "Postie Pete", "Prison Pete", "Mime", "Evil twin", "Gravedigger", "Dunce",
    "Tree spirit", "Ent", "Giant lobster", "Swarm", "Shade", "Zombie (Shades of Mort'ton)",
    "Crate (Gertrude's Cat)", "Escaping slave", "Effigy", "The Fisher King",
    "Homunculus",  # Tower of Life spawns it in the basement (shown at tol_homonc_pres=1).
}
EXCLUDE_TEXT = re.compile(
    r"random event|cutscene|instance|minigame|\bduring\b|\bpet\b|\bboss|\braids?\b|follower|summoned|"
    r"Deadman|Leagues|Last Man Standing|Castle Wars|Soul Wars|Pest Control|Barbarian Assault|"
    r"Theatre of Blood|Chambers of Xeric|Tombs of Amascut|Nightmare Zone|Fight Pit|Inferno|"
    r"TzHaar Fight Cave|Fortis Colosseum|Puro-Puro|Mage Training Arena|Trouble Brewing|"
    r"Tempoross|Wintertodt|Guardians of the Rift|Temple of the Eye|Gauntlet|Sailing|Bounty Hunter|"
    r"Nightmare|Leviathan|Whisperer|Vardorvis|Duke Sucellus|Muspah|Nex\b|Zulrah|Vorkath|Corporeal|"
    r"Alchemical Hydra|Hallowed Sepulchre|Tutorial Island|Birthday|Halloween|Christmas|\bEaster\b|holiday",
    re.I,
)
MEMBERS_F2P = {"n", "no", "f", "false"}
# Lead-paragraph phrases marking npcs that only exist during a quest stage or event.
TEMPORARY = re.compile(r"only (?:appears?|seen|found|present|exists?) (?:during|in the|when)|"
                       r"during the cutscene|appears? only during|no longer appears", re.I)
# ObjectLocLine pages (fishing spots and a few others): ones that belong to quests/minigames/events.
OBJLINE_EXCLUDE = re.compile(r"Tutorial Island|Tempoross|Birthday|Boat fishing|Frenzied|Swan Song|"
                             r"Hemenster|Hallowed Sepulchre|Iceberg|Chair \(|Elemental rock|Odd tree", re.I)
# Fishing spot types the Fishing binder would resolve to the wrong catch (op labels alone can't tell
# them apart): held back until skilling special-cases them. Remove an entry here once it's bound.
FISHING_HELD = re.compile(r"0_37_59_tuna|minnow_|camdozaal_fish|lanternfish|tempoross_.*fishingspot")
SQUARE = re.compile(r"^(?:\d_)?(\d+)_(\d+)_")
# Hand fixes, by Wiki page title: (npc sym or None to keep, coords or None to keep).
# Rantz: the infobox id is the Recipe for Disaster multinpc; Bugs/Fycie: the map pin is on the
# surface but they live in Rantz's cave (by the ogre benches).
OVERRIDES = {
    "Rantz": ("rantz", None),
    "Bugs": (None, "0_41_146_17_49"),
    "Fycie": (None, "0_41_146_26_49"),
}


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


def top_fields(body):
    """Top-level `|key = value` pairs of a template body (nested templates kept intact)."""
    parts, depth, cur, k = [], 0, [], 0
    while k < len(body):
        if body.startswith("{{", k) or body.startswith("[[", k): depth += 1; cur.append(body[k:k + 2]); k += 2
        elif body.startswith("}}", k) or body.startswith("]]", k): depth -= 1; cur.append(body[k:k + 2]); k += 2
        elif body[k] == "|" and depth == 0: parts.append("".join(cur)); cur = []; k += 1
        else: cur.append(body[k]); k += 1
    parts.append("".join(cur))
    out = {}
    for p in parts[1:]:
        if "=" in p:
            key, val = p.split("=", 1)
            out[key.strip().lower()] = val.strip()
    return out


def ints(s):
    return [int(x) for x in re.findall(r"\d+", s or "")]


def parse_map(val):
    """-> (points, plane, mtype, mapid) for the first {{Map}} in a field, or None."""
    maps = list(templates(val, "Map"))
    if not maps: return None
    body = maps[0]
    args = [a.strip() for a in body.split("|")[1:]]
    named = {}
    pts = []
    for a in args:
        m = re.fullmatch(r"(?:x:\s*)?(\d+)(?:\.\d+)?\s*,\s*(?:y:\s*)?(\d+)(?:\.\d+)?(?:\s*,.*)?", a)
        if m: pts.append((int(m.group(1)), int(m.group(2)))); continue
        if "=" in a:
            key, v = a.split("=", 1)
            named[key.strip().lower()] = v.strip()
    if not pts and "x" in named and "y" in named:
        pts = [(ints(named["x"])[0], ints(named["y"])[0])]
    plane = (ints(named.get("plane")) or [0])[0]
    mapid = named.get("mapid", "")
    return pts, plane, named.get("mtype", "pin").lower(), mapid


def decode_npc(d):
    """-> (name, ops, multi children) for a rev-243 npc config."""
    p = 0
    name, ops, kids = "", [None] * 5, []

    def u8():
        nonlocal p
        v = d[p]; p += 1; return v

    def u16():
        nonlocal p
        v = struct.unpack_from(">H", d, p)[0]; p += 2; return v

    def string():
        nonlocal p
        e = d.index(0, p); s = d[p:e].decode("cp1252"); p = e + 1; return s

    while True:
        op = u8()
        if op == 0: break
        if op == 1: n = u8(); p += 2 * n
        elif op == 60: n = u8(); p += 2 * n
        elif op in (61, 62): n = u8(); p += 4 * n
        elif op in (129, 130, 145, 147, 210): pass
        elif op in (126, 146, 204, 211, 213, 214, 215, 216): u16()
        elif op in (200, 201, 202, 205, 206, 207, 208, 209, 212): u8()
        elif op == 203: n = u8(); p += (n + 1) * 5
        elif op == 251: p += 2; string()
        elif op == 252: p += 1 + 2 + 2 + 4 + 4; string()
        elif op == 253: p += 1 + 2 + 2 + 2 + 4 + 4; string()
        elif op == 2: name = string()
        elif op == 3: string()
        elif op == 12: u8()
        elif op in (13, 14, 15, 16, 18): u16()
        elif op == 17: u16(); p += 6
        elif 30 <= op < 35: ops[op - 30] = string()
        elif op in (40, 41): n = u8(); p += 4 * n
        elif op in (74, 75, 76, 77, 78, 79, 95, 97, 98, 103, 114, 116, 124): u16()
        elif op in (115, 117): p += 8
        elif op in (93, 99, 107, 109, 111, 122, 123): pass
        elif op in (100, 101): u8()
        elif op == 102:
            flags = u8(); i = 0
            while flags >> i:
                if flags & (1 << i):
                    p += 4 if d[p] & 0x80 else 2
                    p += 1 if d[p] < 128 else 2
                i += 1
        elif op in (106, 118):
            p += 4
            default = u16() if op == 118 else 0xFFFF
            n = u8()
            kids = [u16() for _ in range(n + 1)] + [default]
            kids = [k for k in kids if k != 0xFFFF]
        elif op == 249:
            for _ in range(u8()):
                is_str = u8(); p += 3
                if is_str: string()
                else: p += 4
        else:
            raise ValueError(f"unknown npc opcode {op}")
    return name, ops, kids


def argv_groups(argv):
    pos, opt, cur = [], defaultdict(list), None
    for a in argv:
        if a.startswith("--"): cur = a[2:]; continue
        (opt[cur] if cur else pos).append(a)
    return pos, opt


def main():
    pos, opt = argv_groups(sys.argv[1:])
    pages_path, cache_dir, sym_path, out_f2p, out_mem, log_path = pos
    pages = json.load(open(pages_path, encoding="utf-8"))
    sym = {}
    for line in open(sym_path, encoding="utf-8"):
        q = line.rstrip("\n").split("\t")
        if len(q) == 2 and q[0].isdigit(): sym[int(q[0])] = q[1]
    by_sym = {v: k for k, v in sym.items()}
    npc = {}
    for i, d in Cache(cache_dir).group_files(2, 9).items():
        try: npc[i] = decode_npc(d)
        except (ValueError, IndexError, struct.error): pass
    parent = {}
    for i, (_, _, kids) in npc.items():
        for k in kids: parent.setdefault(k, i)

    def family(i):
        base = parent.get(i, i)
        return base, {base, *npc.get(base, ("", [], []))[2]}

    def cname(i):
        return (npc.get(i, ("",))[0] or "").lower()

    # Existing spawns: by family base and by cache name -> coords (level, x, z).
    spawned_fam, spawned_name = defaultdict(list), defaultdict(list)
    for path in opt["spawns"]:
        for n, c in re.findall(r"npc = '([^']+)'\s*\ncoords = '([^']+)'", open(path, encoding="utf-8").read()):
            if n not in by_sym: continue
            lv, mx, mz, lx, lz = map(int, c.split("_"))
            xyz = (lv, (mx << 6) | lx, (mz << 6) | lz)
            i = by_sym[n]
            spawned_fam[family(i)[0]].append(xyz)
            for f in family(i)[1]:
                if cname(f) not in ("", "null"): spawned_name[cname(f)].append(xyz)
    owned = set()
    for path in opt["owned"]:
        for c in re.findall(r"coords = '([^']+)'", open(path, encoding="utf-8").read()):
            _, mx, mz, _, _ = c.split("_")
            owned.add((int(mx), int(mz)))
    exclude = set()
    for path in opt["exclude"]:
        exclude |= {line.strip() for line in open(path, encoding="utf-8") if line.strip()}
    force = set()
    for path in opt["force"]:
        force |= {line.strip() for line in open(path, encoding="utf-8") if line.strip()}

    def near(lst, xyz):
        return any(a[0] == xyz[0] and abs(a[1] - xyz[1]) <= RADIUS and abs(a[2] - xyz[2]) <= RADIUS for a in lst)

    spawns, log, stats = [], [], Counter()

    def objline_pass(title, text, boxes, forced):
        """Per-point ObjectLocLine data: one spawn per point, id picked by its map-square name."""
        if OBJLINE_EXCLUDE.search(title) and not forced:
            log.append((title, "", "excluded objectlocline page", "")); stats["excluded_title"] += 1; return
        base = title.split(" (")[0].lower()
        ids = set()
        for body in boxes:
            for k, v in top_fields(body).items():
                if re.fullmatch(r"id\d*", k): ids |= {i for i in ints(v) if i in sym}
        cands = [i for i in sorted(ids) if not SPECIAL_ID.search(sym[i]) and sym[i] not in exclude
                 and not re.search(r"fake|deal_|fishing_boat_|inactive|newbie|contest|compofish|sinister|"
                                   r"bigdave|joshua|rag3_|tempoross", sym[i])
                 and (cname(i) == base or cname(parent.get(i, i)) == base)]
        if not cands:
            log.append((title, "", "objectlocline: no ids", ",".join(sym[i] for i in sorted(ids))[:120]))
            stats["no_id"] += 1; return
        def sq(i):
            m = SQUARE.match(sym[i]); return (int(m.group(1)), int(m.group(2))) if m else None
        placed = []
        for raw in templates(text, "ObjectLocLine"):
            f = top_fields("|" + raw.split("|", 1)[1] if "|" in raw else "")
            loc = re.sub(r"\[\[(?:[^|\]]*\|)?([^\]]*)\]\]", r"\1", f.get("location", ""))
            if re.search(r"Only on Free-to-play", loc, re.I) or (EXCLUDE_TEXT.search(loc) and not forced):
                log.append((title, "", "objectlocline excluded location", loc[:80])); continue
            if ints(f.get("mapid")) and ints(f.get("mapid"))[0] >= 10000:
                log.append((title, "", "instance map", f.get("mapid"))); continue
            plane = (ints(f.get("plane")) or [0])[0]
            members = (f.get("members") or "yes").lower() not in MEMBERS_F2P
            for x, z in re.findall(r"x:\s*(\d+)(?:\.\d+)?\s*,\s*y:\s*(\d+)", raw):
                x, z = int(x), int(z)
                xyz = (plane, x, z)
                def dist(i):
                    q = sq(i)
                    return 10**6 if q is None else abs(q[0] - (x >> 6)) + abs(q[1] - (z >> 6))
                chosen = min(cands, key=lambda i: (dist(i), i))
                if FISHING_HELD.search(sym[chosen]):
                    log.append((title, "", "held spot type", f"{sym[chosen]} {plane},{x},{z}")); continue
                fam_base, fam = family(chosen)
                names = {cname(k) for k in fam} - {"", "null"}
                near2 = lambda lst: any(a[0] == plane and abs(a[1] - x) <= 2 and abs(a[2] - z) <= 2 for a in lst)
                if any(near2(spawned_name[nm]) for nm in names) or near2(placed):
                    stats["already_spawned"] += 1; continue
                placed.append(xyz)
                for nm in names: spawned_name[nm].append(xyz)
                coords = f"{plane}_{x >> 6}_{z >> 6}_{x & 63}_{z & 63}"
                spawns.append((members, sym[chosen], coords, title))
                stats["objline_members" if members else "objline_f2p"] += 1
    for title, text in sorted(pages.items()):
        if "{{LocLine" in text: stats["has_locline"] += 1; continue
        boxes = list(templates(text, "Infobox NPC")) + list(templates(text, "Infobox Monster"))
        if not boxes: continue
        forced = title in force
        if not forced and (title in EXCLUDE_TITLES or EXCLUDE_TEXT.search(title)):
            log.append((title, "", "excluded title", "")); stats["excluded_title"] += 1; continue
        start = text.find("}}" + chr(10), text.find("{{Infobox"))
        lead = re.split(chr(10) + "==", text[start:], maxsplit=1)[0]
        if not forced and TEMPORARY.search(lead):
            log.append((title, "", "temporary (lead text)", TEMPORARY.search(lead).group(0)))
            stats["temporary"] += 1; continue
        placed_here = []  # (level, x, z) placed for this page, so versions on one spot spawn once
        if "{{ObjectLocLine" in text:
            objline_pass(title, text, boxes, forced)
            continue
        for b, body in enumerate(boxes):
            f = top_fields(body)
            idxs = sorted({int(m) for k in f for m in re.findall(r"^(?:id|version|map)(\d+)$", k)}) or [0]
            for n in idxs:
                s = str(n) if n else ""
                get = lambda key: f.get(key + s) if f.get(key + s) is not None else f.get(key, "")
                where = f"v{n}" if n else ""
                mp = parse_map(get("map") or "")
                if not mp or not mp[0]:
                    continue
                pts, plane, mtype, mapid = mp
                loc = re.sub(r"\[\[(?:[^|\]]*\|)?([^\]]*)\]\]", r"\1", get("location") or "")
                if not forced and EXCLUDE_TEXT.search(loc + " " + (f.get("version" + s) or "")):
                    log.append((title, where, "excluded location/version", loc[:80])); stats["excluded_loc"] += 1
                    continue
                if ints(mapid) and ints(mapid)[0] >= 10000 or any(x >= 6400 or y >= 13000 for x, y in pts):
                    log.append((title, where, "instance map", mapid)); stats["instance"] += 1; continue
                ids = [i for i in ints(get("id")) if i in sym]
                good = [i for i in ids if not SPECIAL_ID.search(sym[i]) and sym[i] not in exclude
                        and not FISHING_HELD.search(sym[i])
                        and sym[parent.get(i, i)] not in exclude]
                tl = title.split(" (")[0].lower()
                named = [i for i in good if cname(i) == tl or cname(parent.get(i, i)) == tl
                         or any(cname(k) == tl for k in family(i)[1])]
                pick = named or (good if forced else [])
                if not pick:
                    why = "no ids" if not ids else ("special/runtime ids only" if not good else "cache name mismatch")
                    log.append((title, where, why, ",".join(sym.get(i, str(i)) for i in ids)[:120]))
                    stats["no_id"] += 1; continue
                bases = [parent[i] for i in pick if i in parent and parent[i] in sym
                         and not SPECIAL_ID.search(sym[parent[i]])]
                chosen = bases[0] if bases else min(pick)
                fam_base, fam = family(chosen)
                members = (get("members") or "").lower() not in MEMBERS_F2P
                if mtype in ("pin", "") or len(pts) == 1 and mtype not in ("rectangle", "polygon", "line"):
                    targets, approx = pts, False
                else:
                    if mtype == "rectangle" or len(pts) == 1: c = pts[0]
                    else: c = (round(sum(x for x, _ in pts) / len(pts)), round(sum(y for _, y in pts) / len(pts)))
                    targets, approx = [c], True
                # A quest npc pinned in several far-apart places is one npc moving between quest
                # stages: spawn it once, at its first pin.
                quest = (get("quest") or "").strip().lower()
                spread = len(targets) > 1 and any(abs(x - targets[0][0]) > RADIUS or abs(z - targets[0][1]) > RADIUS
                                                  for x, z in targets)
                if quest not in ("", "no", "none", "<nowiki/>") and spread and not forced:
                    log.append((title, where, "quest npc, several places: first pin only", str(targets[1:4])))
                    targets = targets[:1]
                for x, z in targets[:12]:
                    xyz = (plane, x, z)
                    names = {cname(k) for k in fam} - {"", "null"}
                    if near(spawned_fam[fam_base], xyz) or any(near(spawned_name[nm], xyz) for nm in names):
                        stats["already_spawned"] += 1; continue
                    if near(placed_here, xyz):
                        stats["same_page_dup"] += 1; continue
                    if (x >> 6, z >> 6) in owned and not forced:
                        log.append((title, where, "area-owned square", f"{plane},{x},{z} {sym[chosen]}"))
                        stats["area_owned"] += 1; continue
                    placed_here.append(xyz)
                    for nm in names: spawned_name[nm].append(xyz)
                    spawned_fam[fam_base].append(xyz)
                    coords = f"{plane}_{x >> 6}_{z >> 6}_{x & 63}_{z & 63}"
                    spawns.append((members, sym[chosen], coords, title))
                    stats["spawn_members" if members else "spawn_f2p"] += 1
                    if approx: log.append((title, where, "approx centre (" + mtype + ")", f"{plane},{x},{z}"))

    spawns = [
        (m, OVERRIDES.get(t, (None, None))[0] or n, OVERRIDES.get(t, (None, None))[1] or c, t)
        for m, n, c, t in spawns
    ]
    for path, mem in ((out_f2p, False), (out_mem, True)):
        with open(path, "w", encoding="utf-8", newline="\n") as fo:
            fo.write("# Generated from OSRS Wiki infobox maps (pages without LocLine) by "
                     "tools/wiki-spawns/infobox_npc_spawns.py\n")
            for _, n, c, t in sorted((s for s in spawns if s[0] == mem), key=lambda s: (s[2], s[1])):
                fo.write(f"\n# {t}\n[[spawn]]\nnpc = '{n}'\ncoords = '{c}'\n")
    with open(log_path, "w", encoding="utf-8", newline="\n") as fo:
        fo.write("page\tversion\treason\tdetail\n")
        for row in sorted(set(log)):
            fo.write("\t".join(str(r).replace("\t", " ") for r in row) + "\n")
        for _, n, c, t in sorted(spawns, key=lambda s: s[3]):
            fo.write(f"{t}\t\tspawned\t{n} {c}\n")
    print("spawns", len(spawns), dict(stats))


if __name__ == "__main__":
    main()
