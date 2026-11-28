"""Generate generic shop data (content/generic/generic-shops) from OSRS Wiki store pages.

Args: store_pages.json owner_pages.json npc_ids.json item_ids.json npcs_dump.tsv sym_dir cache_dir
      out_shops.tsv out_npcs.toml log.tsv [--locations Lumbridge,Varrock,...] spawns.toml [...]

  store_pages.json  fetch_wiki_spawns.py store_pages.json StoreLine
  owner_pages.json  fetch_pages.py (wikitext of every Infobox Shop `owner` page)
  npc_ids.json      fetch_npc_ids.py (wiki page -> npc ids, fallback when a page lists no ids)
  item_ids.json     tools/drop-tables/fetch_item_ids.py
  npcs_dump.tsv     tools/npc-anims/cache_dump.py output (npc ops, to find the "Trade" op)
  spawns.toml       every existing npcs*.toml (to know which owners are already spawned)

A store is generated when one of its owners (Infobox Shop `owner`) has an npc id with a "Trade"
op. If that npc is not spawned anywhere yet, it is spawned at the owner page's first {{Map}} point
(a polygon uses its vertex average) into out_npcs.toml. Owners that already have hand-written
content (any NpcReferences in content/) and invs already stocked by hand (axeshop, generalshop1)
are skipped. Each store gets one of the cache's shop invs: general stores take the 40-slot
generalshopN invs; other stores take the unused shop inv whose name shares a word with the store
and whose client size fits the stock (cache sizes match the real shop's stock count).

out_shops.tsv (tab separated):
  shop   <inv> <size> <general 0/1> <sell x10> <buy x10> <delta x10> <title>
  npc    <npc internal name> <op 1-5>
  stock  <obj internal name> <count> <restock ticks>
"""
import glob, json, os, re, struct, sys
from collections import Counter

args = sys.argv[1:]
locations = None
if "--locations" in args:
    i = args.index("--locations")
    locations = [s.strip().lower() for s in args[i + 1].split(",")]
    del args[i:i + 2]
(store_path, owner_pages_path, npc_ids_path, item_ids_path, npcs_dump, sym_dir, cache_dir,
 out_path, spawns_out, log_path) = args[:10]
spawn_files = args[10:]
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "npc-anims"))
from jagcache import Cache

stores = json.load(open(store_path, encoding="utf-8"))
owner_pages = json.load(open(owner_pages_path, encoding="utf-8"))
npc_page_ids = json.load(open(npc_ids_path, encoding="utf-8"))
item_ids = json.load(open(item_ids_path, encoding="utf-8"))


def read_sym(name):
    out = {}
    for line in open(os.path.join(sym_dir, name), encoding="utf-8"):
        p = line.rstrip("\n").split("\t")
        if len(p) == 2 and p[0].isdigit(): out[int(p[0])] = p[1]
    return out


obj_sym, npc_sym, inv_sym = read_sym("obj.sym"), read_sym("npc.sym"), read_sym("inv.sym")
npc_by_name = {v: k for k, v in npc_sym.items()}
spawned = set()
for path in spawn_files:
    text = open(path, encoding="utf-8").read()
    spawned |= {npc_by_name[n] for n in re.findall(r"npc = '([^']+)'", text) if n in npc_by_name}

trade_op, talk_op, npc_display = {}, {}, {}
for line in open(npcs_dump, encoding="utf-8").read().splitlines()[1:]:
    p = line.split("\t")
    npc_display[int(p[0])] = p[1]
    for i, op in enumerate(p[5:10], start=1):
        if op.strip().lower() == "trade" and int(p[0]) not in trade_op:
            trade_op[int(p[0])] = i
        if op.strip().lower() == "talk-to" and int(p[0]) not in talk_op:
            talk_op[int(p[0])] = i

# Npcs referenced by hand-written content already have their own op handlers.
handled = set()
for path in glob.glob("content/**/*.kt", recursive=True):
    text = open(path, encoding="utf-8").read()
    if "NpcReferences()" in text:
        handled |= set(re.findall(r'find\("([^"]+)"', text))
HAND_STOCKED_INVS = {"axeshop", "generalshop1"}
MAX_RESTOCK_CYCLES = 65535  # InvTypeEncoder writes restockCycles with writeShort
MAX_STOCK_ENTRIES = 255     # InvTypeEncoder: "Can only store up to 255 default stock"

# Client inv sizes from the cache (config group 5, opcode 2).
inv_size = {}
for iid, data in Cache(cache_dir).group_files(2, 5).items():
    if len(data) >= 3 and data[0] == 2:
        inv_size[iid] = struct.unpack_from(">H", data, 1)[0]
# Invs reserved for game modes, minigame reward shops or other regions' variants are never used.
SPECIAL_INV = re.compile(r"uim|deadman|league|trailblazer|reward|gotr|_dt_|tal_teklan|fortis|shayzien|viking|ogre|"
                         r"kourend|zeah|varlamore|prif|wint|lms|tob|cox|toa|soul|pvp|bh_|ironman|gim|hcim|magearena")
shop_invs = {inv_sym[i]: s for i, s in inv_size.items()
             if i in inv_sym and "shop" in inv_sym[i] and inv_sym[i] not in HAND_STOCKED_INVS
             and not SPECIAL_INV.search(inv_sym[i])}
# Fallback pool once the standard invs run out: the variants (only used as containers).
spare_invs = {inv_sym[i]: s for i, s in inv_size.items()
              if i in inv_sym and "shop" in inv_sym[i] and inv_sym[i] not in HAND_STOCKED_INVS
              and inv_sym[i] not in shop_invs}

log, stats = [], Counter()


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
    return re.sub(r"<!--.*?-->|<ref[^>]*/>|<ref[^>]*>.*?</ref>", "", s, flags=re.S)


def fields(body):
    return {k.strip().lower(): v.strip() for k, v in re.findall(r"\|\s*([A-Za-z_]+\d*)\s*=\s*([^|]*)", body)}


def norm(s): return re.sub(r"[^a-z0-9]", "", s.lower())


BAD_OBJ = re.compile(r"fake|dummy|placeholder|_cert|noted|trail_|league|deadman|tut")


def obj_name(item):
    ids = [i for i in item_ids.get(item, []) if i in obj_sym]
    if not ids: return None
    exact = [i for i in ids if norm(obj_sym[i]) == norm(item)]
    clean = [i for i in ids if not BAD_OBJ.search(obj_sym[i])]
    return obj_sym[min(exact or clean or ids)]


def words(s):
    return {w for w in re.findall(r"[a-z]+", s.lower()) if len(w) >= 4 and w not in ("shop", "store", "general")}


def store_owners(box):
    """Owner pages from `owner` and versioned `owner1`, `owner2`, ... fields (e.g. Slayer Equipment)."""
    owners = []
    for line in re.findall(r"\|\s*owner\d*\s*=([^\n]*)", box):
        owners += [o.strip() for o in re.findall(r"\[\[([^|\]#]+)", line) if o.strip() not in owners]
    return owners


def owner_ids(page):
    """Owner npc ids in infobox version order (id1 first), falling back to the bucket ids."""
    text = owner_pages.get(page, "")
    ordered = []
    for _, v in re.findall(r"\|\s*(id\d*)\s*=\s*([0-9][0-9,\s]*)", text):
        ordered += [int(x) for x in re.findall(r"\d+", v) if int(x) not in ordered]
    return ordered or npc_page_ids.get(page, [])


def owner_coords(page):
    """-> 'plane_mx_mz_lx_lz' from the first {{Map}} on the owner page, or None."""
    m = re.search(r"\{\{Map\|([^}]*)\}\}", owner_pages.get(page, ""))
    if not m: return None
    body = m.group(1)
    pts = [(int(a), int(b)) for a, b in re.findall(r"(?:^|\|)\s*(\d{3,4})\s*,\s*(\d{3,5})", body)]
    xm, ym = re.search(r"\bx\s*=\s*(\d+)", body), re.search(r"\by\s*=\s*(\d+)", body)
    if xm and ym: pts = [(int(xm.group(1)), int(ym.group(1)))]
    if not pts: return None
    x = round(sum(p[0] for p in pts) / len(pts))
    z = round(sum(p[1] for p in pts) / len(pts))
    pm = re.search(r"\bplane\s*=\s*(\d)", body)
    plane = int(pm.group(1)) if pm else 0
    return f"{plane}_{x >> 6}_{z >> 6}_{x & 63}_{z & 63}"


new_spawns = {}
plans = []
for title, text in sorted(stores.items()):
    box = next(templates(text, "Infobox Shop"), None)
    if box is None: continue
    info = fields(strip_nested(box))
    location = re.sub(r"\[\[(?:[^|\]]*\|)?([^\]]*)\]\]", r"\1", info.get("location", ""))
    if locations and not any(l in location.lower() for l in locations): continue
    owners = store_owners(box)
    npcs = []
    for page in owners:
        ids = owner_ids(page)
        if any(npc_sym.get(i) in handled for i in ids):
            stats["hand_written_owner"] += 1; continue
        traders = [i for i in ids if i in trade_op and npc_sym.get(i)]
        if not traders:
            log.append((title, page, "owner has no npc id with a Trade op", "")); continue
        nid = next((i for i in traders if i in spawned), traders[0])
        if nid not in spawned and any(i in spawned for i in ids):
            # Another id of this owner page is already spawned elsewhere; do not spawn a second one.
            log.append((title, page, "owner spawned elsewhere under a non-Trade id", "")); continue
        if nid not in spawned and nid not in new_spawns:
            coords = owner_coords(page)
            if coords is None:
                log.append((title, page, "owner not spawned and page has no map coords", "")); continue
            new_spawns[nid] = coords
        npcs.append((npc_sym[nid], trade_op[nid]))
    if not npcs:
        stats["no_trader"] += 1; continue
    head = fields(strip_nested(next(templates(text, "StoreTableHead"), "") or ""))
    currency = (head.get("currency") or "coins").strip().lower()
    if currency != "coins":
        stats["non_coin_currency"] += 1; log.append((title, "", "non-coin currency (not supported yet)", currency)); continue
    sell, buy, delta = (int(re.match(r"\d+", head.get(k) or str(d)).group(0))
                        for k, d in (("sellmultiplier", 1300), ("buymultiplier", 400), ("delta", 30)))
    stock = []
    for body in templates(text, "StoreLine"):
        f = fields(strip_nested(body))
        name = f.get("name", "")
        count = re.match(r"\d+", f.get("stock", "") or "")
        restock = re.match(r"\d+", f.get("restock", "") or "")
        on = obj_name(name)
        if on is None or count is None:
            log.append((title, name, "skipped stock line", f"obj={on} stock={f.get('stock', '')}")); continue
        # The inv encoder stores restockCycles as an unsigned short and obj ids as shorts.
        cycles = min(int(restock.group(0)) if restock else 100, MAX_RESTOCK_CYCLES)
        stock.append((on, int(count.group(0)), cycles))
    general = "general store" in info.get("special", "").lower()
    if not stock and not general:
        stats["no_stock"] += 1; log.append((title, "", "no usable stock", "")); continue
    plans.append({"title": title, "npcs": sorted(set(npcs)), "stock": stock, "general": general,
                  "sell": sell, "buy": buy, "delta": delta})

# Inv allocation: general stores -> generalshopN; others -> best name/size match among unused invs.
used = set()
for plan in sorted(plans, key=lambda p: (not p["general"], -len(p["stock"]), p["title"])):
    need = len(plan["stock"])
    tw = words(plan["title"])
    cands = [(n, s) for n, s in shop_invs.items() if n not in used] or         [(n, s) for n, s in spare_invs.items() if n not in used]
    if plan["general"]:
        # Numbered generalshopN first (generalshop2, generalshop3, ...), then any other 40-slot inv.
        cands = [c for c in cands if c[1] >= 40]
        key = lambda c: (bool(re.fullmatch(r"generalshop\d+", c[0])), -int((re.findall(r"\d+", c[0]) or ["999"])[0]))
    else:
        # A name match wins even if the cache size is a little smaller (the inv is sized to the stock).
        key = lambda c: (len(tw & words(c[0].replace("shop", " "))) + (1 if any(w in c[0] for w in tw) else 0),
                         c[1] >= need, c[1] == need, -abs(c[1] - need), c[0])
    if not cands:
        stats["no_inv"] += 1; log.append((plan["title"], "", "no free shop inv", str(need)))
        continue
    best = max(cands, key=key)
    plan["inv"], plan["size"] = best[0], max(best[1], need)
    used.add(best[0])

done = [p for p in plans if p.get("inv")]
# An npc can only have one Trade handler: the first store (by title) keeps it.
registered = set()
for plan in sorted(done, key=lambda p: p["title"]):
    keep = [(n, op) for n, op in plan["npcs"] if n not in registered]
    for n, _ in plan["npcs"]:
        if n in registered: log.append((plan["title"], n, "npc already trades for another store", ""))
    registered |= {n for n, _ in keep}
    plan["npcs"] = keep
done = [p for p in done if p["npcs"]]
with open(out_path, "w", encoding="utf-8", newline="\n") as w:
    w.write("# Generated from OSRS Wiki store pages by tools/shops/wiki_shops_to_tsv.py - do not edit.\n")
    for plan in sorted(done, key=lambda p: p["title"]):
        w.write(f"\nshop\t{plan['inv']}\t{plan['size']}\t{int(plan['general'])}\t{plan['sell']}\t{plan['buy']}"
                f"\t{plan['delta']}\t{plan['title']}\n")
        for n, op in plan["npcs"]: w.write(f"npc\t{n}\t{op}\n")
        for o, c, r in plan["stock"]: w.write(f"stock\t{o}\t{c}\t{r}\n")
        stats["shops"] += 1
# Dialogue vendors: stores whose owner has no Trade op but sells fixed-price, unlimited items
# (bartenders: "Beer - 2 coins"). Talk-to opens a buy dialogue. Stores needing ingredients
# (extra table columns, e.g. the Apothecary) are skipped.
vendors = []
vendor_npcs = set()
for title, text in sorted(stores.items()):
    box = next(templates(text, "Infobox Shop"), None)
    if box is None: continue
    info = fields(strip_nested(box))
    location = re.sub(r"\[\[(?:[^|\]]*\|)?([^\]]*)\]\]", r"\1", info.get("location", ""))
    if locations and not any(l in location.lower() for l in locations): continue
    head = fields(strip_nested(next(templates(text, "StoreTableHead"), "") or ""))
    if (head.get("currency") or "coins").strip().lower() != "coins" or "column1" in head: continue
    items = []
    for body in templates(text, "StoreLine"):
        f = fields(strip_nested(body))
        price = re.fullmatch(r"\d+", f.get("sell", "") or "")
        on = obj_name(f.get("name", ""))
        if f.get("stock", "").lower() != "inf" or not price or int(price.group(0)) <= 0 or on is None:
            items = None
            break
        items.append((on, int(price.group(0)), f.get("name", "")))
    if not items: continue
    for page in store_owners(box):
        ids = owner_ids(page)
        if any(npc_sym.get(i) in handled or npc_sym.get(i) in registered for i in ids): continue
        # Talk-to is on almost every npc, so also require the cache name to match the page name
        # (the wiki id for "Barman (Ratpit bar)" is a gambler in this cache).
        base = re.sub(r"\s*\(.*\)$", "", page).strip().lower()
        talkers = [i for i in ids if i in talk_op and i not in trade_op and npc_sym.get(i)
                   and npc_display.get(i, "").strip().lower() == base]
        if not talkers: continue
        nid = next((i for i in talkers if i in spawned), talkers[0])
        if npc_sym[nid] in vendor_npcs: continue
        if nid not in spawned and any(i in spawned for i in ids): continue
        if nid not in spawned and nid not in new_spawns:
            coords = owner_coords(page)
            if coords is None:
                log.append((title, page, "vendor not spawned and page has no map coords", ""))
                continue
            new_spawns[nid] = coords
        vendor_npcs.add(npc_sym[nid])
        vendors.append((title, npc_sym[nid], talk_op[nid], items[:4]))
        stats["vendors"] += 1
with open(out_path, "a", encoding="utf-8", newline="\n") as w:
    for title, n, op, items in vendors:
        w.write(f"\nvendor\t{n}\t{op}\t{title}\n")
        for o, price, label in items: w.write(f"item\t{o}\t{price}\t{label}\n")

used_npcs = {n for p in done for n, _ in p["npcs"]} | vendor_npcs
with open(spawns_out, "w", encoding="utf-8", newline="\n") as w:
    w.write("# Shopkeepers spawned by tools/shops/wiki_shops_to_tsv.py (wiki {{Map}} point of the owner page).\n")
    for nid, coords in sorted(new_spawns.items(), key=lambda t: t[1]):
        if npc_sym[nid] in used_npcs:
            w.write(f"\n[[spawn]]\nnpc = '{npc_sym[nid]}'\ncoords = '{coords}'\n")
            stats["spawned"] += 1
with open(log_path, "w", encoding="utf-8", newline="\n") as f:
    f.write("store\titem\treason\tdetail\n")
    for row in sorted(set(log)): f.write("\t".join(str(x).replace("\t", " ") for x in row) + "\n")
print(dict(stats))
