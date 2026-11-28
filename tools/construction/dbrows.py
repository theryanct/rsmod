import sys, re, collections
def load(path):
    rows = {}; name = None
    for line in open(path, encoding="utf-8", errors="replace"):
        line = line.rstrip("\n")
        m = re.match(r"^\[(.+)\]$", line)
        if m: name = m.group(1); rows[name] = {"table": None, "data": collections.defaultdict(list)}; continue
        if name is None: continue
        if line.startswith("table="): rows[name]["table"] = line[6:]
        elif line.startswith("data="):
            col, _, val = line[5:].partition(",")
            rows[name]["data"][col].append(val)
    return rows
