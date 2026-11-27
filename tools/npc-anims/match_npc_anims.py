"""Fill missing npc combat anims/sounds in the cache-enricher npcs.toml, matched by skeleton.

Args: dump_dir seq.sym npc.sym enricher_npcs.toml log.tsv spawns.toml [spawns.toml ...]

dump_dir comes from cache_dump.py. For every spawned npc whose enricher config has no death_anim:
  1. copy anims + sounds from a configured npc with the same stand anim (same creature rig + look),
  2. else copy from a configured npc whose stand anim has the same skeleton,
  3. else pick seqs by name among seqs on the npc's skeleton: the death anim's family must be the
     only one on that skeleton or share a word with the npc's internal/display name; attack and
     block come from the same family when it has them.
Npcs on the player skeleton (0) are left alone: the human punch/block/death fallback is correct
for them. Anything unmatched is logged and keeps the fallback.
"""
import re, sys
from collections import defaultdict

dump, seq_sym, npc_sym, enricher_path, log_path = sys.argv[1:6]
spawn_files = sys.argv[6:]
HUMAN_SKELETON = 0
FIELDS = ("attack_anim", "defend_anim", "death_anim", "attack_sound", "defend_sound", "death_sound")


def read_sym(path):
    out = {}
    for line in open(path, encoding="utf-8"):
        p = line.rstrip("\n").split("\t")
        if len(p) == 2 and p[0].isdigit(): out[int(p[0])] = p[1]
    return out


def read_tsv(path):
    lines = open(path, encoding="utf-8").read().splitlines()
    head = lines[0].split("\t")
    return [dict(zip(head, l.split("\t"))) for l in lines[1:]]


seq_names = read_sym(seq_sym)
npc_names = read_sym(npc_sym)
npc_ids = {v: k for k, v in npc_names.items()}
seq_skel = {int(r["id"]): int(r["skeleton"]) for r in read_tsv(f"{dump}/seqs.tsv")}
npc_rows = read_tsv(f"{dump}/npcs.tsv")
npc_ready = {int(r["id"]): int(r["ready"]) for r in npc_rows}
npc_display = {int(r["id"]): r["name"] for r in npc_rows}

text = open(enricher_path, encoding="utf-8").read()
blocks = text.split("[[config]]")
config = {}  # npc name -> (block index, {field: value})
for i, blk in enumerate(blocks[1:], start=1):
    m = re.search(r"^npc = '([^']+)'", blk, re.M)
    if not m: continue
    vals = {k: int(v) for k, v in re.findall(r"^(\w+_(?:anim|sound)) = (-?\d+)", blk, re.M)}
    config[m.group(1)] = (i, vals)

donors_by_ready, donors_by_skel = defaultdict(list), defaultdict(list)
for name, (_, vals) in config.items():
    if "death_anim" not in vals or name not in npc_ids: continue
    ready = npc_ready.get(npc_ids[name], -1)
    donors_by_ready[ready].append(name)
    donors_by_skel[seq_skel.get(ready, -1)].append(name)

seqs_by_skel = defaultdict(list)
for sid, sk in seq_skel.items():
    if sid in seq_names: seqs_by_skel[sk].append(sid)

ROLE = {
    "attack_anim": re.compile(r"attack|_atk|punch|slash|stab|bite|claw|kick|swipe|strike"),
    "defend_anim": re.compile(r"block|defend|defence|_def\b|hit$|_hit_|flinch"),
    "death_anim": re.compile(r"death|_die|dying|_dead"),
}


def words(s): return {w for w in re.split(r"[_\d\s()'-]+", s.lower()) if len(w) >= 3}


def family(seq_name, field):
    """Anim family = seq name before its role word: 'bear_rework_death' -> 'bear_rework'."""
    m = ROLE[field].search(seq_name)
    return seq_name[:m.start()].rstrip("_") if m else seq_name


def by_name(npc, display, skel):
    """Pick anims by seq name on the npc's skeleton. Returns ({field: seq}, reason)."""
    pool = seqs_by_skel.get(skel, [])
    deaths = [s for s in pool if ROLE["death_anim"].search(seq_names[s])]
    if not deaths: return {}, f"no named death seq on skeleton {skel}"
    fams = {family(seq_names[s], "death_anim") for s in deaths}
    want = words(npc) | words(display)
    scored = sorted(deaths, key=lambda s: (-len(words(seq_names[s]) & want), s))
    best = scored[0]
    if len(fams) > 1 and not (words(seq_names[best]) & want):
        return {}, f"ambiguous: {len(fams)} anim families on skeleton {skel}, none named like the npc"
    fam = family(seq_names[best], "death_anim")
    picked = {"death_anim": best}
    for field in ("attack_anim", "defend_anim"):
        cands = [s for s in pool if ROLE[field].search(seq_names[s]) and not ROLE["death_anim"].search(seq_names[s])]
        same = [s for s in cands if family(seq_names[s], field) == fam]
        cands = same or sorted(cands, key=lambda s: (-len(words(seq_names[s]) & (want | words(fam))), s))
        if cands: picked[field] = cands[0]
    return picked, "unique family" if len(fams) == 1 else "name match"


spawned = set()
for path in spawn_files:
    spawned |= set(re.findall(r"npc = '([^']+)'", open(path, encoding="utf-8").read()))

log, edits = [], {}
for npc in sorted(spawned):
    have = config.get(npc, (None, {}))[1]
    if "death_anim" in have: continue
    nid = npc_ids.get(npc)
    if nid is None or npc not in config:
        log.append((npc, "skip", "no enricher config block")); continue
    ready = npc_ready.get(nid, -1); skel = seq_skel.get(ready, -1)
    if ready == -1 or skel < 0:
        log.append((npc, "unmatched", f"no stand anim/skeleton (ready={ready})")); continue
    if skel == HUMAN_SKELETON:
        log.append((npc, "human", "player skeleton: human fallback anims are correct")); continue
    donor = (donors_by_ready.get(ready) or [None])[0]
    how = "same stand anim"
    if donor is None:
        donor = (donors_by_skel.get(skel) or [None])[0]; how = "same skeleton"
    if donor is not None:
        vals = {k: v for k, v in config[donor][1].items() if k in FIELDS}
        edits[npc] = vals; log.append((npc, "copied", f"{how} as {donor}")); continue
    picked, why = by_name(npc, npc_display.get(nid, ""), skel)
    if picked:
        edits[npc] = picked
        log.append((npc, "named", why + ": " + ", ".join(f"{k}={seq_names[v]}" for k, v in picked.items())))
    else:
        log.append((npc, "unmatched", why))

for npc, vals in edits.items():
    i, _ = config[npc]
    blk = blocks[i].rstrip("\n")
    add = "".join(f"\n{k} = {v}" for k, v in vals.items() if not re.search(rf"^{k} = ", blk, re.M))
    blocks[i] = blk + add + "\n\n"
out = "[[config]]".join(blocks)
open(enricher_path, "w", encoding="utf-8", newline="\n").write(out)

with open(log_path, "w", encoding="utf-8", newline="\n") as f:
    f.write("npc\tresult\tdetail\n")
    for row in log: f.write("\t".join(row) + "\n")
counts = defaultdict(int)
for _, r, _ in log: counts[r] += 1
print("edited", len(edits), dict(counts))
