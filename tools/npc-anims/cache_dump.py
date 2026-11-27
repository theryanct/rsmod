"""Dump seq skeleton ids and npc stand/walk anims from the cache (no Gradle needed).

Args: cache_dir out_dir   ->  out_dir/seqs.tsv, out_dir/npcs.tsv

A seq's skeleton is the framemap (base) id stored at the start of its first frame file (archive 0),
or of its keyframe set (archive 22) for newer "maya" animations. Two anims are only compatible
with the same model rig when their skeleton ids match.
"""
import os, struct, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from jagcache import Cache

CONFIG, ANIMS, KEYFRAMES = 2, 0, 22
NPC_GROUP, SEQ_GROUP = 9, 12


class Buf:
    def __init__(self, d): self.d, self.p = d, 0
    def u8(self): v = self.d[self.p]; self.p += 1; return v
    def s8(self): v = struct.unpack_from(">b", self.d, self.p)[0]; self.p += 1; return v
    def u16(self): v = struct.unpack_from(">H", self.d, self.p)[0]; self.p += 2; return v
    def i32(self): v = struct.unpack_from(">i", self.d, self.p)[0]; self.p += 4; return v
    def string(self):
        e = self.d.index(0, self.p); s = self.d[self.p:e].decode("cp1252"); self.p = e + 1; return s


def decode_seq(d):
    b = Buf(d); groups = files = []; keyframe = -1
    while True:
        op = b.u8()
        if op == 0: break
        if op == 1:
            n = b.u16(); [b.u16() for _ in range(n)]
            files = [b.u16() for _ in range(n)]; groups = [b.u16() for _ in range(n)]  # frame id = group << 16 | file
        elif op in (2, 6, 7): b.u16()
        elif op == 3: n = b.u8(); b.p += n
        elif op == 4 or op == 19: pass
        elif op in (5, 8, 9, 10, 11): b.u8()
        elif op == 12: n = b.u8(); b.p += 4 * n
        elif op == 13: keyframe = b.i32()
        elif op == 14: n = b.u16(); b.p += 8 * n
        elif op == 15: b.p += 4
        elif op == 17: n = b.u8(); b.p += n
        elif op == 18: b.string()
        else: break  # unknown opcode: keep what we have
    return groups, files, keyframe


def decode_npc(d):
    """Mirrors api/cache NpcTypeDecoder; opcodes are NOT written in ascending order."""
    b = Buf(d); name, ready, walk, size = "", -1, -1, 1
    ops = [""] * 5

    def big_smart():  # readNullableLargeSmart
        if d[b.p] & 0x80: b.i32()
        else: b.u16()

    def small_smart():  # readUnsignedSmallSmartPlusOne
        if d[b.p] < 128: b.u8()
        else: b.u16()

    while True:
        op = b.u8()
        if op == 0: break
        if op == 1 or op == 60: n = b.u8(); b.p += 2 * n
        elif op == 2: name = b.string()
        elif op == 3: b.string()
        elif op == 12: size = b.u8()
        elif op == 13: ready = b.u16()
        elif op == 14: walk = b.u16()
        elif op in (15, 16, 18): b.u16()
        elif op == 17: walk = b.u16(); b.p += 6
        elif 30 <= op < 35: ops[op - 30] = b.string()
        elif op in (40, 41): n = b.u8(); b.p += 4 * n
        elif op in (74, 75, 76, 77, 78, 79, 95, 97, 98, 103, 114, 116, 124): b.u16()
        elif op in (115, 117): b.p += 8
        elif op in (93, 99, 107, 109, 111, 122, 123): pass
        elif op in (100, 101): b.u8()
        elif op == 102:
            flags = b.u8(); i = 0
            while flags >> i:
                if flags & (1 << i): big_smart(); small_smart()
                i += 1
        elif op in (106, 118):
            b.u16(); b.u16()
            if op == 118: b.u16()
            n = b.u8(); b.p += 2 * (n + 1)
        elif op == 249:
            for _ in range(b.u8()):
                is_str = b.u8(); b.p += 3
                if is_str: b.string()
                else: b.i32()
        else:
            raise ValueError(f"unknown npc opcode {op} at {b.p - 1}")
    return name, ready, walk, size, ops


def main(cache_dir, out_dir):
    c = Cache(cache_dir)
    os.makedirs(out_dir, exist_ok=True)
    frame_groups, keyframe_groups = {}, {}

    def skeleton(groups, files, keyframe):
        try:
            if keyframe != -1:
                g, f = keyframe >> 16, keyframe & 0xFFFF
                if g not in keyframe_groups: keyframe_groups[g] = c.group_files(KEYFRAMES, g)
                data = keyframe_groups[g].get(f)
                return ("keyframe", struct.unpack_from(">H", data, 1)[0]) if data else ("keyframe", -2)
            if groups:
                g, f = groups[0], files[0]
                if g not in frame_groups: frame_groups[g] = c.group_files(ANIMS, g)
                data = frame_groups[g].get(f)
                return ("frames", struct.unpack_from(">H", data, 0)[0]) if data else ("frames", -2)
        except Exception:
            return ("error", -3)
        return ("none", -1)

    seqs = c.group_files(CONFIG, SEQ_GROUP)
    with open(os.path.join(out_dir, "seqs.tsv"), "w", newline="\n") as w:
        w.write("id\tkind\tskeleton\tframes\n")
        for sid in sorted(seqs):
            g, f, k = decode_seq(seqs[sid])
            kind, skel = skeleton(g, f, k)
            w.write(f"{sid}\t{kind}\t{skel}\t{len(g)}\n")
    npcs = c.group_files(CONFIG, NPC_GROUP)
    with open(os.path.join(out_dir, "npcs.tsv"), "w", newline="\n", encoding="utf-8") as w:
        w.write("id\tname\tready\twalk\tsize\top1\top2\top3\top4\top5\n")
        for nid in sorted(npcs):
            try:
                name, ready, walk, size, ops = decode_npc(npcs[nid])
            except Exception as e:
                print("npc", nid, e, file=sys.stderr); name, ready, walk, size, ops = "?", -1, -1, 1, [""] * 5
            w.write(f"{nid}\t{name}\t{ready}\t{walk}\t{size}\t" + "\t".join(ops) + "\n")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
