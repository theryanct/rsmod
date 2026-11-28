"""Generate the collection log's kill-count table from client proc 2735 (collection_category_count).

Usage: python -I tools/collection-log/kill_count_lines.py <cache_dir> > \
    content/interfaces/collection-log/src/main/resources/org/rsmod/content/interfaces/collection/kill_counts.tsv

Proc 2735 switches on the category struct and draws up to three labelled lines from varps 2048,
2941 and 2942 ("Artio kills: N"). Every "<name> kills:" line becomes `struct varp npc,npc...`;
OVERRIDES give lines whose npcs differ from the label (Barrows = the six brothers, revenants).
Lines that aren't kills (completions, chests, clues) are skipped: nothing tracks those yet.
"""
import os
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "cache-check"))
sys.path.insert(0, os.path.join(HERE, "..", "npc-anims"))
from cs2_dump import disasm  # noqa: E402
from jagcache import Cache  # noqa: E402

GET_VARP, SCONST, RETURN, SWITCH = 1, 3, 21, 60
KC_VARPS = (2048, 2941, 2942)

# Label (lowercase, before " kills:"/" opened:") -> npc names whose kills are summed.
OVERRIDES = {
    "barrows chests": ["ahrim the blighted", "dharok the wretched", "guthan the infested",
                       "karil the tainted", "torag the corrupted", "verac the defiled"],
    # Both guardians / both titans die in one kill: count one of them.
    "grotesque guardian": ["dusk"],
    "royal titan": ["branda the fire queen"],
    "revenant": ["revenant imp", "revenant goblin", "revenant pyrefiend", "revenant hobgoblin",
                 "revenant cyclops", "revenant hellhound", "revenant demon", "revenant ork",
                 "revenant dark beast", "revenant knight", "revenant dragon",
                 "revenant maledictus"],
    "whisperer": ["the whisperer"],
    "leviathan": ["the leviathan"],
    "hueycoatl": ["the hueycoatl"],
    "nightmare": ["the nightmare"],
    "mad angel": ["the mad angel", "mad angel"],
    "maggot king": ["the maggot king", "maggot king"],
}


def main(cache_dir):
    data = bytes(next(iter(Cache(cache_dir).group_files(12, 2735).values())))
    _, ops = disasm(data)
    p = len(data) - 2 - struct.unpack(">H", data[-2:])[0] + 1
    count = struct.unpack(">H", data[p:p + 2])[0]
    p += 2
    cases = {}
    for _ in range(count):
        key, offset = struct.unpack(">ii", data[p:p + 8])
        p += 8
        cases[key] = offset
    switch_pc = next(pc for pc, op, _ in ops if op == SWITCH)
    for key, offset in sorted(cases.items()):
        pc, label = switch_pc + 1 + offset, None
        while ops[pc][1] != RETURN:
            op, value = ops[pc][1], ops[pc][2]
            if op == SCONST and value:
                label = value
            elif op == GET_VARP and value in KC_VARPS and label:
                name = label.split(":")[0].lower()
                for suffix in (" kills", " opened"):
                    if name.endswith(suffix):
                        base = name[: -len(suffix)]
                        npcs = OVERRIDES.get(base, [base] if suffix == " kills" else None)
                        if npcs:
                            print(f"{key}\t{value}\t{','.join(npcs)}")
            pc += 1


if __name__ == "__main__":
    main(sys.argv[1])
