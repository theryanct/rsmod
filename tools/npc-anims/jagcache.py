"""Minimal read-only reader for a Jagex JS5 disk cache (main_file_cache.dat2 + idxN).

Enough to read config/anim groups without Gradle: sector chains, containers (none/bzip2/gzip),
reference tables (idx255) and multi-file groups.
"""
import bz2, gzip, os, struct, zlib


class Cache:
    def __init__(self, path):
        self.path = path
        self.dat = open(os.path.join(path, "main_file_cache.dat2"), "rb")
        self.idx = {}
        self.tables = {}

    def _idx(self, archive):
        if archive not in self.idx:
            with open(os.path.join(self.path, f"main_file_cache.idx{archive}"), "rb") as f:
                self.idx[archive] = f.read()
        return self.idx[archive]

    def raw(self, archive, group):
        idx = self._idx(archive)
        off = group * 6
        if off + 6 > len(idx): return None
        size = int.from_bytes(idx[off:off + 3], "big")
        sector = int.from_bytes(idx[off + 3:off + 6], "big")
        if size == 0 or sector == 0: return None
        big = group > 0xFFFF
        hdr, body = (10, 510) if big else (8, 512)
        out = bytearray()
        chunk = 0
        while len(out) < size:
            self.dat.seek(sector * 520)
            block = self.dat.read(520)
            if big:
                g, c, nxt, a = struct.unpack(">IH", block[:6]) + (int.from_bytes(block[6:9], "big"), block[9])
            else:
                g, c = struct.unpack(">HH", block[:4]); nxt = int.from_bytes(block[4:7], "big"); a = block[7]
            if g != group or c != chunk or a != archive:
                raise IOError(f"sector chain mismatch archive={archive} group={group}")
            take = min(body, size - len(out))
            out += block[hdr:hdr + take]
            sector, chunk = nxt, chunk + 1
        return bytes(out)

    @staticmethod
    def decompress(data):
        ctype = data[0]; clen = struct.unpack(">i", data[1:5])[0]
        if ctype == 0: return data[5:5 + clen]
        ulen = struct.unpack(">i", data[5:9])[0]
        payload = data[9:9 + clen]
        if ctype == 1: return bz2.decompress(b"BZh1" + payload)
        if ctype == 2: return zlib.decompress(payload, 16 + zlib.MAX_WBITS)
        raise IOError(f"unsupported compression {ctype}")

    def table(self, archive):
        if archive in self.tables: return self.tables[archive]
        d = self.decompress(self.raw(255, archive)); p = 0
        proto = d[p]; p += 1
        if proto >= 6: p += 4
        flags = d[p]; p += 1

        def count():
            nonlocal p
            if proto >= 7:
                if d[p] & 0x80: v = struct.unpack(">I", d[p:p + 4])[0] & 0x7FFFFFFF; p += 4
                else: v = struct.unpack(">H", d[p:p + 2])[0]; p += 2
                return v
            v = struct.unpack(">H", d[p:p + 2])[0]; p += 2
            return v

        n = count(); groups = []; acc = 0
        for _ in range(n): acc += count(); groups.append(acc)
        if flags & 1: p += 4 * n           # name hashes
        p += 4 * n                          # crcs
        if flags & 8: p += 4 * n           # uncompressed checksums
        if flags & 2: p += 64 * n          # whirlpool
        if flags & 4: p += 8 * n           # sizes
        p += 4 * n                          # versions
        fcounts = [count() for _ in range(n)]
        files = {}
        for g, fc in zip(groups, fcounts):
            ids = []; acc = 0
            for _ in range(fc): acc += count(); ids.append(acc)
            files[g] = ids
        self.tables[archive] = files
        return files

    def group_files(self, archive, group):
        """-> {file_id: bytes} for one group."""
        ids = self.table(archive).get(group)
        if ids is None: return {}
        raw = self.raw(archive, group)
        if raw is None: return {}
        data = self.decompress(raw)
        if len(ids) == 1: return {ids[0]: data}
        chunks = data[-1]
        n = len(ids)
        tbl = len(data) - 1 - chunks * n * 4
        sizes = [0] * n; p = tbl
        layout = []
        for _ in range(chunks):
            acc = 0; row = []
            for i in range(n):
                acc += struct.unpack(">i", data[p:p + 4])[0]; p += 4
                row.append(acc); sizes[i] += acc
            layout.append(row)
        out = {i: bytearray() for i in ids}; p = 0
        for row in layout:
            for i, sz in enumerate(row):
                out[ids[i]] += data[p:p + sz]; p += sz
        return {k: bytes(v) for k, v in out.items()}
