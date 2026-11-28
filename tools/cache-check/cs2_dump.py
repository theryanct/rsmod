"""Minimal OSRS clientscript (cs2) disassembler for reading a script's args and constants.

Args: cache_dir script_id [script_id ...]

Prints the arg/local counts, then each instruction as `pc opcode operand`. Opcode names are not
mapped (except a few core ones); interface components show as `iface:comp` when an int constant
decodes to a plausible packed component. Layout follows RuneLite's ScriptLoader.
"""
import os, struct, sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "npc-anims"))
from jagcache import Cache

CORE = {0: "iconst", 1: "get_varp", 2: "set_varp", 3: "sconst", 6: "jump", 7: "if_icmpne",
        8: "if_icmpeq", 9: "if_icmplt", 10: "if_icmpgt", 21: "return", 25: "get_varbit",
        27: "set_varbit", 31: "if_icmple", 32: "if_icmpge", 33: "iload", 34: "istore",
        35: "sload", 36: "sstore", 37: "join_string", 38: "pop_int", 39: "pop_string",
        40: "gosub", 42: "get_varc_int", 43: "set_varc_int", 44: "define_array",
        45: "push_array_int", 46: "pop_array_int", 47: "get_varc_string", 48: "set_varc_string",
        60: "switch"}


def disasm(data):
    switch_len = struct.unpack(">H", data[-2:])[0]
    end = len(data) - 2 - switch_len - 12
    n_ops, local_int, local_str, arg_int, arg_str = struct.unpack(">iHHHH", data[end:end + 12])
    p = 0
    name_end = data.index(0, p)
    p = name_end + 1
    out = []
    pc = 0
    while p < end:
        op = struct.unpack(">H", data[p:p + 2])[0]; p += 2
        if op == 3:
            e = data.index(0, p); operand = data[p:e].decode("latin1"); p = e + 1
        elif op < 100 and op not in (21, 38, 39):
            operand = struct.unpack(">i", data[p:p + 4])[0]; p += 4
        else:
            operand = data[p]; p += 1
        out.append((pc, op, operand))
        pc += 1
    return (arg_int, arg_str, local_int, local_str), out


if __name__ == "__main__":
    cache = Cache(sys.argv[1])
    for sid in map(int, sys.argv[2:]):
        raw = cache.group_files(12, sid)
        data = next(iter(raw.values()))
        counts, ops = disasm(bytes(data))
        print(f"== script {sid}: int args {counts[0]}, string args {counts[1]}, "
              f"int locals {counts[2]}, string locals {counts[3]}")
        for pc, op, operand in ops:
            name = CORE.get(op, str(op))
            extra = ""
            if op == 0 and isinstance(operand, int) and operand > 65535:
                extra = f"  ; {operand >> 16}:{operand & 0xFFFF}"
            print(f"{pc:4d} {name:14s} {operand!r}{extra}")
