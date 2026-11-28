"""Offline opcode walker for OSRS config types in a JS5 disk cache (main_file_cache.*).

Usage: python -I opwalk.py <cache_dir> [obj|loc|npc ...]

Walks every record of the given config groups using opcode tables transcribed from RuneLite
master's loaders, asserting each record ends exactly at its 0 terminator. Prints opcode
histograms (and unknown/overrun errors) so codec changes can be checked without a build.
"""
import bz2
import collections
import os
import struct
import sys
import zlib

CONFIG_ARCHIVE = 2
GROUPS = {'loc': 6, 'npc': 9, 'obj': 10}


class Cache:
    def __init__(self, d):
        self.d = d
        self.dat = open(os.path.join(d, 'main_file_cache.dat2'), 'rb')

    def raw(self, arch, gid):
        with open(os.path.join(self.d, f'main_file_cache.idx{arch}'), 'rb') as f:
            f.seek(gid * 6)
            b = f.read(6)
        size, sector = int.from_bytes(b[:3], 'big'), int.from_bytes(b[3:], 'big')
        out = bytearray()
        while len(out) < size:
            self.dat.seek(sector * 520)
            if gid > 0xFFFF:
                h = self.dat.read(10)
                nxt, dl = int.from_bytes(h[6:9], 'big'), 510
            else:
                h = self.dat.read(8)
                nxt, dl = int.from_bytes(h[4:7], 'big'), 512
            out += self.dat.read(min(dl, size - len(out)))
            sector = nxt
        return bytes(out)

    @staticmethod
    def decompress(b):
        t, ln = b[0], int.from_bytes(b[1:5], 'big')
        if t == 0:
            return b[5:5 + ln]
        data = b[9:9 + ln]
        return bz2.decompress(b'BZh1' + data) if t == 1 else zlib.decompress(data, 31)

    def index(self, arch):
        idx = self.decompress(self.raw(255, arch))
        p = 0
        proto = idx[p]; p += 1
        if proto >= 6:
            p += 4
        flags = idx[p]; p += 1

        def count():
            nonlocal p
            if proto >= 7 and idx[p] & 0x80:
                v = int.from_bytes(idx[p:p + 4], 'big') & 0x7FFFFFFF; p += 4
            else:
                v = int.from_bytes(idx[p:p + 2], 'big'); p += 2
            return v

        n = count(); gids = []; acc = 0
        for _ in range(n):
            acc += count(); gids.append(acc)
        # Layout per RuneLite IndexData: [name hashes] crcs [sizes] revisions.
        if flags & 1: p += 4 * n                      # name hashes
        p += 4 * n                                    # crcs
        if flags & 4: p += 8 * n                      # compressed + decompressed sizes
        p += 4 * n                                    # revisions
        fcounts = [count() for _ in range(n)]
        files = {}
        for g, fc in zip(gids, fcounts):
            acc = 0; ids = []
            for _ in range(fc):
                acc += count(); ids.append(acc)
            files[g] = ids
        return files

    def group_files(self, arch, gid, file_ids):
        data = self.decompress(self.raw(arch, gid))
        if len(file_ids) == 1:
            return {file_ids[0]: data}
        chunks = data[-1]
        n = len(file_ids)
        p = len(data) - 1 - chunks * n * 4
        sizes = [0] * n
        table = []
        for _ in range(chunks):
            acc = 0
            row = []
            for i in range(n):
                acc += struct.unpack('>i', data[p:p + 4])[0]; p += 4
                row.append(acc)
            table.append(row)
        out = [bytearray() for _ in range(n)]
        off = 0
        for row in table:
            for i in range(n):
                out[i] += data[off:off + row[i]]; off += row[i]
        return {fid: bytes(b) for fid, b in zip(file_ids, out)}


class Buf:
    def __init__(self, b):
        self.b, self.p = b, 0

    def u8(self): v = self.b[self.p]; self.p += 1; return v
    def s8(self): v = struct.unpack_from('>b', self.b, self.p)[0]; self.p += 1; return v
    def u16(self): v = struct.unpack_from('>H', self.b, self.p)[0]; self.p += 2; return v
    def s16(self): v = struct.unpack_from('>h', self.b, self.p)[0]; self.p += 2; return v
    def u24(self): v = int.from_bytes(self.b[self.p:self.p + 3], 'big'); self.p += 3; return v
    def i32(self): v = struct.unpack_from('>i', self.b, self.p)[0]; self.p += 4; return v
    def i64(self): v = struct.unpack_from('>q', self.b, self.p)[0]; self.p += 8; return v

    def string(self):
        e = self.b.index(0, self.p); s = self.b[self.p:e]; self.p = e + 1; return s

    def smart_u16(self):  # readUnsignedShortSmart
        return self.u8() if self.b[self.p] < 128 else self.u16() - 0x8000

    def bigsmart2(self):
        return self.i32() & 0x7FFFFFFF if self.b[self.p] >= 128 else self.u16()

    def params(self):
        for _ in range(self.u8()):
            t = self.u8(); self.u24()
            if t == 1: self.string()
            elif t == 2: self.i64()
            else: self.i32()


# --- shared entity op encodings (EntityOpsLoader) ---
def subop(b): b.u8(); b.u8(); b.string()
def condop(b): b.u8(); b.u16(); b.u16(); b.i32(); b.i32(); b.string()
def condsubop(b): b.u8(); b.u16(); b.u16(); b.u16(); b.i32(); b.i32(); b.string()
def recol(b):
    for _ in range(b.u8()): b.u16(); b.u16()
def nothing(b): pass


def obj_op(op, b):
    if op in (1, 4, 5, 6, 7, 8, 94, 95, 97, 98, 99, 110, 111, 112, 139, 140, 148, 149,
              24, 26, 78, 79, 90, 91, 92, 93): b.u16()
    elif op in (2, 3, 9) or 30 <= op < 40: b.string()
    elif op in (11, 15, 16, 65, 160, 251): pass
    elif op == 12: b.i32()
    elif op in (13, 14, 27, 42, 113, 114, 115): b.u8()
    elif op in (23, 25): b.u16(); b.u8()
    elif op in (40, 41): recol(b)
    elif op == 43:  # rsmod/233 format: parent op, then (subop, text) pairs until subop 0
        b.u8()
        while b.u8() != 0: b.string()
    elif op in (44, 46, 47, 49, 50, 51, 52, 53, 54): b.i32()
    elif op in (45, 48): b.i32(); b.u8()
    elif op == 75: b.s16()
    elif 100 <= op < 110: b.u16(); b.u16()
    elif op == 161:
        for _ in range(b.u16()): b.u16()
    elif op == 200: subop(b)
    elif op == 201: condop(b)
    elif op == 202: condsubop(b)
    elif op == 249: params_counting(b)
    else: raise KeyError(op)


def loc_op(op, b):
    if op in (1, 6):
        n = b.u8()
        for _ in range(n):
            (b.u16() if op == 1 else b.i32()); b.u8()
    elif op in (5, 7):
        n = b.u8()
        for _ in range(n): (b.u16() if op == 5 else b.i32())
    elif op == 2 or 30 <= op < 35: b.string()
    elif op in (14, 15, 19, 28, 29, 39, 69, 75, 81, 91, 95, 96): b.u8()
    elif op in (17, 18, 21, 22, 23, 27, 62, 64, 73, 74, 89, 90, 94): pass
    elif op in (24, 42, 61, 65, 66, 67, 68, 70, 71, 72, 82): b.u16()
    elif op in (40, 41): recol(b)
    elif op == 78: b.u16(); b.u8(); b.u8()
    elif op == 79:
        b.u16(); b.u16(); b.u8(); b.u8()
        for _ in range(b.u8()): b.u16()
    elif op in (77, 92):
        b.u16(); b.u16()
        if op == 92: b.u16()
        for _ in range(b.u8() + 1): b.u16()
    elif op == 93: b.u8(); b.u16(); b.u8(); b.u16()
    elif op in (97, 98):
        b.u16(); b.u16()
        if op == 98: b.u24()
        for _ in range(b.u8() + 1): b.u24()
    elif op == 100: subop(b)
    elif op == 101: condop(b)
    elif op == 102: condsubop(b)
    elif op == 249: params_counting(b)
    else: raise KeyError(op)


def npc_op(op, b):
    if op in (1, 60):
        for _ in range(b.u8()): b.u16()
    elif op in (61, 62):
        for _ in range(b.u8()): b.i32()
    elif op == 2 or 30 <= op < 35: b.string()
    elif op in (12, 100, 101): b.u8()
    elif op in (13, 14, 15, 16, 18, 42, 74, 75, 76, 77, 78, 79, 95, 97, 98, 103, 114, 116,
                124, 126, 146): b.u16()
    elif op in (17, 115, 117): b.u16(); b.u16(); b.u16(); b.u16()
    elif op in (40, 41): recol(b)
    elif op in (93, 99, 107, 109, 111, 122, 123, 129, 130, 145, 147): pass
    elif op == 3: b.string()  # desc (old/debug npcs; rsmod reads it too)
    elif op == 102:
        bits = b.u8(); n = bits.bit_length()
        for i in range(n):
            if bits & (1 << i):
                b.bigsmart2(); b.smart_u16()
    elif op in (106, 118):
        b.u16(); b.u16()
        if op == 118: b.u16()
        for _ in range(b.u8() + 1): b.u16()
    elif op == 249: params_counting(b)
    elif op == 251: subop(b)
    elif op == 252: condop(b)
    elif op == 253: condsubop(b)
    else: raise KeyError(op)


WALKERS = {'obj': obj_op, 'loc': loc_op, 'npc': npc_op}


LONG_PARAMS = collections.Counter()


def params_counting(b):
    for _ in range(b.u8()):
        t = b.u8(); pid = b.u24()
        if t == 1: b.string()
        elif t == 2: b.i64(); LONG_PARAMS[pid] += 1
        else: b.i32()


def param_op(op, b):
    if op == 1: b.u8()
    elif op == 2: b.i32()
    elif op == 4: pass
    elif op == 5: b.string()
    elif op == 7: b.i64()
    elif op == 8: b.u8()
    else: raise KeyError(op)


def enum_op(op, b):
    if op in (1, 2): b.u8()
    elif op == 3: b.string()
    elif op == 4: b.i32()
    elif op == 5:
        for _ in range(b.u16()): b.i32(); b.string()
    elif op == 6:
        for _ in range(b.u16()): b.i32(); b.i32()
    elif op == 7:
        for _ in range(b.u16()): b.i32(); b.i64()
    elif op == 8: b.i64()
    else: raise KeyError(op)


def inv_op(op, b):
    if op == 2: b.u16()
    elif op == 249: params_counting(b)
    else: raise KeyError(op)


def spot_op(op, b):
    if op in (1, 2, 4, 5, 6, 42): b.u16()
    elif op == 3: b.i32()
    elif op in (7, 8): b.u8()
    elif op == 9: b.string()
    elif op == 10: pass
    elif op in (40, 41): recol(b)
    else: raise KeyError(op)


def struct_op(op, b):
    if op == 249: params_counting(b)
    else: raise KeyError(op)


WALKERS.update(param=param_op, enum=enum_op, inv=inv_op, spot=spot_op, struct=struct_op)
GROUPS.update(param=11, enum=8, inv=5, spot=13, struct=34)


def walk(cache, name):
    gid = GROUPS[name]
    files = cache.index(CONFIG_ARCHIVE)[gid]
    records = cache.group_files(CONFIG_ARCHIVE, gid, files)
    hist = collections.Counter()
    errors = []
    for fid, data in records.items():
        b = Buf(data)
        try:
            while True:
                op = b.u8()
                if op == 0:
                    break
                hist[op] += 1
                WALKERS[name](op, b)
            if b.p != len(data):
                errors.append((fid, f'{len(data) - b.p} trailing bytes'))
        except Exception as e:  # noqa: BLE001
            errors.append((fid, f'{type(e).__name__}: {e} at {b.p}/{len(data)}'))
    print(f'== {name}: {len(records)} records, {len(errors)} errors')
    print('   opcodes:', ' '.join(f'{k}:{v}' for k, v in sorted(hist.items())))
    for fid, e in errors[:15]:
        print('   ERR', fid, e)
    return hist, errors


if __name__ == '__main__':
    c = Cache(sys.argv[1])
    for t in sys.argv[2:] or list(WALKERS):
        walk(c, t)
    print('long-typed params used (param id: count):', dict(LONG_PARAMS))
