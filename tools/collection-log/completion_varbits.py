"""Extract the collection log's category -> "completed" varbit table from client proc 4103.

Usage: python -I tools/collection-log/completion_varbits.py <cache_dir> > \
    content/interfaces/collection-log/src/main/resources/org/rsmod/content/interfaces/collection/completion_varbits.tsv
(then re-add the two header comment lines).

Proc 4103 (script4103) is `switch (category struct) { case N: return get_varbit(V) }`; it colours
category names green when complete. The server sets those varbits, so it needs the same table.
"""
import os
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "cache-check"))
sys.path.insert(0, os.path.join(HERE, "..", "npc-anims"))
from cs2_dump import disasm  # noqa: E402
from jagcache import Cache  # noqa: E402

GET_VARBIT, SWITCH = 25, 60


def main(cache_dir, script=4103):
    data = bytes(next(iter(Cache(cache_dir).group_files(12, script).values())))
    _, ops = disasm(data)
    switch_len = struct.unpack(">H", data[-2:])[0]
    p = len(data) - 2 - switch_len
    tables = []
    table_count = data[p] if switch_len else 0
    p += 1
    for _ in range(table_count):
        count = struct.unpack(">H", data[p:p + 2])[0]
        p += 2
        table = {}
        for _ in range(count):
            key, offset = struct.unpack(">ii", data[p:p + 8])
            p += 8
            table[key] = offset
        tables.append(table)
    by_pc = {pc: (op, v) for pc, op, v in ops}
    switch_pc = next(pc for pc, op, _ in ops if op == SWITCH)
    for key, offset in sorted(tables[0].items()):
        op, value = by_pc[switch_pc + 1 + offset]
        if op != GET_VARBIT:
            raise SystemExit(f"case {key}: expected get_varbit, got opcode {op}")
        print(f"{key}\t{value}")


if __name__ == "__main__":
    main(sys.argv[1])
