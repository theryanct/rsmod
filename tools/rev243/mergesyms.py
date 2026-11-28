"""Merge regenerated cache symbols into .data/symbols without losing server-only entries.

Usage: python -I mergesyms.py <old_generated_dir> <new_generated_dir> <symbols_dir>

For each cache-derived .sym file:
  - entries the OLD cache generated are cache-derived: replaced by the NEW cache's entries
    (ids renamed/added/removed as the new cache says);
  - every other existing entry (rsmod's own server-only ids, manual names) is kept as-is.
Line endings of the existing file are preserved. Prints a per-file summary.
"""
import os
import sys

FILES = ['obj', 'npc', 'inv', 'varp', 'varbit', 'loc', 'seq', 'spotanim', 'dbrow', 'dbtable',
         'dbcol', 'interface', 'component', 'varc']


def read(path):
    rows = []
    for line in open(path, encoding='utf-8', errors='replace', newline=''):
        parts = line.rstrip('\r\n').split('\t')
        if len(parts) >= 2:
            rows.append((parts[0], parts[1]))
    return rows


def sort_key(key):
    head = key.split(':')[0]
    if head.isdigit():
        return (0, int(head), key)
    tail = key.rsplit(':', 1)[-1]
    return (1, head, int(tail) if tail.isdigit() else 0, key)


def main(old_dir, new_dir, sym_dir):
    for name in FILES:
        path = os.path.join(sym_dir, f'{name}.sym')
        raw = open(path, 'rb').read()
        nl = '\r\n' if b'\r\n' in raw else '\n'
        existing = read(path)
        old = set(read(os.path.join(old_dir, f'{name}.sym')))
        new = read(os.path.join(new_dir, f'{name}.sym'))
        server_only = [row for row in existing if row not in old]
        new_keys = {k for k, _ in new}
        kept = [row for row in server_only if row[0] not in new_keys]
        clash = [row for row in server_only if row[0] in new_keys]
        if all(k.split(':')[0].isdigit() for k, _ in new):
            merged = sorted(new + kept, key=lambda r: sort_key(r[0]))
        else:
            # dbcol/component are grouped by table/interface id: keep the generated order.
            merged = new + kept
        trailing_blank = raw.endswith((nl + nl).encode())
        with open(path, 'w', encoding='utf-8', newline='') as f:
            for k, v in merged:
                f.write(f'{k}\t{v}{nl}')
            if trailing_blank:
                f.write(nl)
        print(f'{name}: cache {len(old)} -> {len(new)}, server-only kept {len(kept)}'
              + (f', CLASH (server id now used by cache) {len(clash)}: {clash[:5]}' if clash else ''))


if __name__ == '__main__':
    main(*sys.argv[1:])
