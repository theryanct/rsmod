"""Generate rsmod .sym files from a cache's gameval archive (24).

Usage: python -I gensyms.py <cache_dir> <out_dir>

Mirrors api/cache GameValDecoder: one file per type id whose bytes are the UTF-8 name, except
table types (group 10: table name + column names) and interfaces (group 14: interface name +
component id/name pairs). Writes obj/npc/inv/varp/varbit/loc/seq/spotanim/dbrow/dbtable/dbcol/
interface/component/varc .sym files in the same format as .data/symbols (id<TAB>name).
"""
import os
import struct
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'cache-check'))
from opwalk import Cache  # noqa: E402

GAMEVALS = 24
PLAIN = {0: 'obj', 1: 'npc', 2: 'inv', 3: 'varp', 4: 'varbit', 6: 'loc', 7: 'seq', 8: 'spotanim',
         9: 'dbrow', 15: 'varc'}


def cstring(b, p):
    e = b.index(0, p)
    return b[p:e].decode('cp1252'), e + 1


def main(cache_dir, out_dir):
    c = Cache(cache_dir)
    idx = c.index(GAMEVALS)
    os.makedirs(out_dir, exist_ok=True)
    for gid, name in PLAIN.items():
        if gid not in idx:
            continue
        files = c.group_files(GAMEVALS, gid, idx[gid])
        with open(os.path.join(out_dir, f'{name}.sym'), 'w', newline='\n') as f:
            for fid in sorted(files):
                f.write(f'{fid}\t{files[fid].decode("utf-8")}\n')
    # tables + columns
    files = c.group_files(GAMEVALS, 10, idx[10])
    with open(os.path.join(out_dir, 'dbtable.sym'), 'w', newline='\n') as ft, \
            open(os.path.join(out_dir, 'dbcol.sym'), 'w', newline='\n') as fc:
        for fid in sorted(files):
            b = files[fid]
            p = 1  # leading byte, always 1
            tname, p = cstring(b, p)
            ft.write(f'{fid}\t{tname}\n')
            col = 0
            while p < len(b):
                if b[p] == 0:
                    break
                p += 1
                cname, p = cstring(b, p)
                fc.write(f'{tname}:{col}\t{tname}:{cname}\n')
                col += 1
    # interfaces + components
    files = c.group_files(GAMEVALS, 14, idx[14])
    with open(os.path.join(out_dir, 'interface.sym'), 'w', newline='\n') as fi, \
            open(os.path.join(out_dir, 'component.sym'), 'w', newline='\n') as fcp:
        for fid in sorted(files):
            b = files[fid]
            iname, p = cstring(b, 0)
            fi.write(f'{fid}\t{iname}\n')
            while p + 2 <= len(b):
                cid = struct.unpack_from('>H', b, p)[0]; p += 2
                if cid == 0xFFFF:
                    break
                cname, p = cstring(b, p)
                fcp.write(f'{iname}:{cid}\t{iname}:{cname}\n')
    print('wrote', out_dir)


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
