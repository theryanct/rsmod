"""Second pass over npcs wiki_npcs_to_toml.py skipped: "unmatched: no version with matching combat
level" and "no infobox".

Args: cache_dir npc.sym out_f2p.toml out_members.toml log.tsv skipped.tsv [skipped.tsv ...]
      --owned existing.toml [existing.toml ...]   (map squares already curated by hand)
      --existing spawns.toml [spawns.toml ...]    (spawns already placed; never duplicated)

For each skipped LocLine the candidate npc ids are the page's infobox ids (or, without an infobox,
every cache npc whose name equals the page title). A candidate is accepted when, in order:
  1. the cache's own combat level is one of the LocLine levels (the wiki infobox was stale), or
  2. every candidate has the same models and size (they're visual variants of one npc), or
  3. there are no levels and exactly one candidate.
Anything else stays ambiguous and is logged with the candidate signatures.
"""
import json
import os
import re
import struct
import sys
import time
import urllib.parse
import urllib.request
from collections import defaultdict

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "npc-anims"))
from jagcache import Cache  # noqa: E402

API = "https://oldschool.runescape.wiki/api.php"
UA = {"User-Agent": "local-dev-tool/1.0 (personal offline project)"}
SPECIAL_ID = re.compile(
    r"tut|quest|qip_|dragonslayer|ds2_|cutscene|mcannon|_in_|instance|dummy|_cs_|_cs$|deadman"
    r"|leagues|_dt_|favour|nzone|_vis$|_multi|pvpa|br_"
)
MEMBERS_F2P = {"n", "no", "f", "false"}
# Bosses / event npcs never world-spawned, and locations that only exist during an event.
EXCLUDE_TITLES = {"Mad Angel", "Vampyre Snail", "Brutus", "Zalcano/Strategies"}
EXCLUDE_LOCATION = re.compile(r"\bduring\b|task-only", re.I)
# Sailing sea creatures need a boat to reach; skip them until Sailing exists here.
EXCLUDE_ID = re.compile(r"^sailing_|^gim_|^mag_")
# State/cutscene variants: only used when no plain variant matched.
NOISE_ID = re.compile(r"dead|_anim|initial|cutscene|routefind|_ship|retired|bully|_part|nolure|_tt_|^tt_")


def argv_groups(argv):
    pos, opt, key = [], defaultdict(list), None
    for a in argv:
        if a.startswith("--"):
            key = a[2:]
        elif key:
            opt[key].append(a)
        else:
            pos.append(a)
    return pos, opt


def templates(text, name):
    start = 0
    while (i := text.find("{{" + name, start)) >= 0:
        depth, j = 0, i
        while j < len(text):
            if text.startswith("{{", j):
                depth += 1
                j += 2
            elif text.startswith("}}", j):
                depth -= 1
                j += 2
                if depth == 0:
                    break
            else:
                j += 1
        yield text[i + 2 + len(name):j - 2]
        start = j


def strip_nested(s):
    prev = None
    while prev != s:
        prev, s = s, re.sub(r"\{\{[^{}]*\}\}", "", s)
    s = re.sub(r"<!--.*?-->", "", s, flags=re.S)
    return re.sub(r"<ref[^>]*/>|<ref[^>]*>.*?</ref>", "", s, flags=re.S)


def fields(body):
    return {k.strip().lower(): v.strip()
            for k, v in re.findall(r"\|\s*([A-Za-z_]+\d*)\s*=\s*([^|\n]*)", body)}


def ints(s):
    return [int(x) for x in re.findall(r"\d+", s or "")]


def level_set(s):
    lv = set()
    for a, b in re.findall(r"(\d+)\s*(?:-\s*(\d+))?", s or ""):
        lv |= set(range(int(a), int(b or a) + 1)) if b and int(b) - int(a) < 500 else {int(a)}
    return lv


def decode_npc(d):
    """name, models, size, combat (mirrors tools/npc-anims/cache_dump.py decode_npc)."""
    p = 0
    name, models, size, combat = "", (), 1, None

    def u8():
        nonlocal p
        v = d[p]
        p += 1
        return v

    def u16():
        nonlocal p
        v = struct.unpack_from(">H", d, p)[0]
        p += 2
        return v

    def string():
        nonlocal p
        e = d.index(0, p)
        s = d[p:e].decode("cp1252")
        p = e + 1
        return s

    while True:
        op = u8()
        if op == 0:
            break
        if op == 1:
            n = u8()
            models = tuple(u16() for _ in range(n))
        elif op == 60:
            n = u8()
            p += 2 * n
        elif op in (61, 62):  # rev 243: models / head models with int ids
            n = u8()
            ids = struct.unpack_from(f">{n}i", d, p)
            p += 4 * n
            if op == 61:
                models = tuple(ids)
        elif op in (129, 130, 145, 147, 210):
            pass
        elif op in (126, 146, 204, 211, 213, 214, 215, 216):
            u16()
        elif op in (200, 201, 202, 205, 206, 207, 208, 209, 212):
            u8()
        elif op == 203:
            p += (u8() + 1) * 5
        elif op == 251:
            p += 2
            string()
        elif op == 252:
            p += 1 + 2 + 2 + 4 + 4
            string()
        elif op == 253:
            p += 1 + 2 + 2 + 2 + 4 + 4
            string()
        elif op == 2:
            name = string()
        elif op == 3:
            string()
        elif op == 12:
            size = u8()
        elif op in (13, 14, 15, 16, 18):
            u16()
        elif op == 17:
            u16()
            p += 6
        elif 30 <= op < 35:
            string()
        elif op in (40, 41):
            n = u8()
            p += 4 * n
        elif op == 95:
            combat = u16()
        elif op in (74, 75, 76, 77, 78, 79, 97, 98, 103, 114, 116, 124):
            u16()
        elif op in (115, 117):
            p += 8
        elif op in (93, 99, 107, 109, 111, 122, 123):
            pass
        elif op in (100, 101):
            u8()
        elif op == 102:
            flags = u8()
            i = 0
            while flags >> i:
                if flags & (1 << i):
                    p += 4 if d[p] & 0x80 else 2
                    p += 1 if d[p] < 128 else 2
                i += 1
        elif op in (106, 118):
            p += 6 if op == 118 else 4
            n = u8()
            p += 2 * (n + 1)
        elif op == 249:
            for _ in range(u8()):
                is_str = u8()
                p += 3
                if is_str:
                    string()
                else:
                    p += 4
        else:
            raise ValueError(f"unknown npc opcode {op}")
    return name, models, size, combat


def fetch(titles):
    pages = {}
    titles = sorted(titles)
    for i in range(0, len(titles), 40):
        params = {"action": "query", "format": "json", "formatversion": "2", "redirects": "1",
                  "titles": "|".join(titles[i:i + 40]), "prop": "revisions",
                  "rvprop": "content", "rvslots": "main"}
        req = urllib.request.Request(API + "?" + urllib.parse.urlencode(params), headers=UA)
        data = json.load(urllib.request.urlopen(req, timeout=60))
        for p in data.get("query", {}).get("pages", []):
            if p.get("revisions"):
                pages[p["title"]] = p["revisions"][0]["slots"]["main"]["content"]
        time.sleep(0.5)
    return pages


def infobox_ids(text):
    ids = []
    for body in list(templates(text, "Infobox Monster")) + list(templates(text, "Infobox NPC")):
        f = fields(strip_nested(body))
        for k, v in f.items():
            if re.fullmatch(r"id\d*", k):
                ids += ints(v)
    return ids


def base_title(t):
    return re.sub(r"\s*\(.*\)$", "", t).strip().lower()


def main():
    pos, opt = argv_groups(sys.argv[1:])
    cache_dir, sym_path, out_f2p, out_members, log_path = pos[:5]
    skipped_paths = pos[5:]
    sym = {}
    for line in open(sym_path, encoding="utf-8"):
        p = line.rstrip("\n").split("\t")
        if len(p) == 2 and p[0].isdigit():
            sym[int(p[0])] = p[1]
    cache = Cache(cache_dir)
    npc = {}
    for i, d in cache.group_files(2, 9).items():
        try:
            npc[i] = decode_npc(d)
        except (ValueError, IndexError, struct.error):
            pass
    by_name = defaultdict(list)
    for i, (name, *_rest) in npc.items():
        by_name[name.lower()].append(i)

    owned = set()
    for path in opt["owned"]:
        for c in re.findall(r"coords = '([^']+)'", open(path, encoding="utf-8").read()):
            _, mx, mz, _, _ = c.split("_")
            owned.add((int(mx), int(mz)))
    taken = set()
    for path in opt["existing"]:
        for c in re.findall(r"coords = '([^']+)'", open(path, encoding="utf-8").read()):
            taken.add(c)

    wanted = defaultdict(set)  # title -> {location}
    for path in skipped_paths:
        for line in open(path, encoding="utf-8"):
            p = line.rstrip("\n").split("\t")
            if len(p) >= 3 and (p[2].startswith("unmatched") or p[2] == "no infobox"):
                wanted[p[0]].add(p[1])
    pages = fetch(wanted)

    out = {False: [], True: []}
    log = []
    rotation = defaultdict(int)
    for title in sorted(wanted):
        text = pages.get(title)
        if text is None:
            log.append((title, "", "ambiguous", "page not found"))
            continue
        ids = [i for i in infobox_ids(text) if i in npc]
        source = "infobox"
        if not ids:
            ids = by_name.get(base_title(title), [])
            source = "cache name"
        ids = [i for i in dict.fromkeys(ids) if i in sym and not SPECIAL_ID.search(sym[i])]
        # Only npcs whose cache name matches the page (guards against stale/foreign ids).
        ids = [i for i in ids if npc[i][0].lower() == base_title(title) and not EXCLUDE_ID.search(sym[i])]
        if title in EXCLUDE_TITLES:
            log.append((title, "", "excluded", "boss/event"))
            continue
        any_line = "" in wanted[title]
        for raw in templates(text, "LocLine"):
            clean = strip_nested(raw)
            kv = fields(clean)
            location = re.sub(r"\[\[(?:[^|\]]*\|)?([^\]]*)\]\]", r"\1", kv.get("location", ""))
            if not any_line and location not in wanted[title]:
                continue
            if EXCLUDE_LOCATION.search(location):
                log.append((title, location, "excluded", "event/task-only location"))
                continue
            map_id = ints(kv.get("mapid"))
            if map_id and map_id[0] >= 10000:
                continue
            members = kv.get("members", "").lower() not in MEMBERS_F2P
            plane = (ints(kv.get("plane")) or [0])[0]
            groups = defaultdict(list)
            for x, y, rest in re.findall(
                    r"x:\s*(\d+)(?:\.\d+)?\s*,\s*y:\s*(\d+)(?:\.\d+)?([^|]*)", clean):
                m = re.search(r"levels:\s*([\d,\s-]+)", rest)
                groups[m.group(1).strip() if m else kv.get("levels", "")].append((int(x), int(y)))
            for lv, pts in groups.items():
                levels = level_set(lv)
                pick, how = [], ""
                by_level = [i for i in ids if npc[i][3] is not None and npc[i][3] in levels]
                sigs = {(npc[i][1], npc[i][2]) for i in ids}
                if by_level:
                    pick, how = by_level, "cache combat level"
                elif ids and len(sigs) == 1:
                    pick, how = ids, "same model/size"
                elif not levels and len(ids) == 1:
                    pick, how = ids, "single candidate"
                plain = [i for i in pick if not NOISE_ID.search(sym[i])]
                pick = plain or pick
                if not pick:
                    detail = "; ".join(
                        f"{sym[i]} cb={npc[i][3]} size={npc[i][2]} models={len(npc[i][1])}"
                        for i in ids[:6]) or "no candidate ids"
                    log.append((title, location, "ambiguous", f"levels={lv} ({source}) {detail}"))
                    continue
                placed = 0
                for x, z in pts:
                    mx, mz = x >> 6, z >> 6
                    coords = f"{plane}_{mx}_{mz}_{x & 63}_{z & 63}"
                    if (mx, mz) in owned or coords in taken:
                        continue
                    taken.add(coords)
                    key = (title, location, lv)
                    npc_name = sym[pick[rotation[key] % len(pick)]]
                    rotation[key] += 1
                    out[members].append((npc_name, coords, title))
                    placed += 1
                log.append((title, location, "resolved: " + how,
                            f"levels={lv} placed={placed} ids={','.join(sym[i] for i in pick[:4])}"))

    for members, path in ((False, out_f2p), (True, out_members)):
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            kind = "members" if members else "F2P"
            f.write(f"# {kind} npcs the first wiki pass skipped, resolved by "
                    "tools/wiki-spawns/resolve_skipped_npcs.py\n")
            for name, coords, _ in sorted(out[members], key=lambda t: (t[1], t[0])):
                f.write(f"\n[[spawn]]\nnpc = '{name}'\ncoords = '{coords}'\n")
    with open(log_path, "w", encoding="utf-8", newline="\n") as f:
        f.write("page\tlocation\tresult\tdetail\n")
        for row in log:
            f.write("\t".join(str(r).replace("\t", " ") for r in row) + "\n")
    res = sum(1 for r in log if r[2].startswith("resolved"))
    print("spawns f2p", len(out[False]), "members", len(out[True]),
          "lines resolved", res, "ambiguous", len(log) - res)


if __name__ == "__main__":
    main()
