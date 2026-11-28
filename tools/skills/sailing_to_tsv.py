"""Rev 243 cache dump + OSRS Wiki -> content/skills/sailing sailing.tsv (Sailing tier 0).

Args: dump.dbrow Courier_tasks.txt Charting.txt sym_dir out.tsv log.tsv
  dump.dbrow         config/dump.dbrow from Joshua-F/osrs-dumps (rev 243)
  Courier_tasks.txt  wikitext of the Wiki "Courier tasks" page (CourierTaskLine rows: taskId, xp)
  Charting.txt       wikitext of the Wiki "Sea charting" page (SeaChartRow rows: level, location)

The cache has the port_task rows (task id, ports, cargo crate) but not their xp (server-only
column), so xp comes from the Wiki by task id. Port keys tie the sailing_dock dbrows to the
per-port notice board / ledger table locs (`port_task_board_<key>`,
`dock_loading_bay_ledger_table_<key>`).

Output (tab separated):
  port    <key> <dock_dbrow> <board_loc|-> <ledger_loc|->
  courier <task_id> <task_dbrow> <level> <xp> <board_port> <cargo_port> <dest_port> <crate_obj> <qty> <name>
  chartloc <loc> <completion_varbit>      (generic "Chart" locs)
  chart   <x> <z> <level> <type>          (Wiki generic/spyglass rows, matched to locs by position)
"""
import os, re, sys

dbrow_path, courier_path, chart_path, sym_dir, out_path, log_path = sys.argv[1:7]


def names(sym):
    out = set()
    for line in open(os.path.join(sym_dir, sym), encoding="utf-8"):
        p = line.rstrip("\r\n").split("\t")
        if len(p) >= 2:
            out.add(p[1])
    return out


locs, objs, dbrows = names("loc.sym"), names("obj.sym"), names("dbrow.sym")
log, rows = [], []

# --- cache rows -----------------------------------------------------------------------------
records = {}
for rec in open(dbrow_path, encoding="utf-8").read().split("\n\n"):
    head = re.search(r"^\[(.+)\]$", rec, re.M)
    table = re.search(r"^table=(\S+)$", rec, re.M)
    if not head or not table:
        continue
    data = {}
    for key, value in re.findall(r"^data=([a-z_0-9]+),(.*)$", rec, re.M):
        data.setdefault(key, value)
    records[head.group(1)] = (table.group(1), data)

docks = {name: data for name, (table, data) in records.items() if table == "sailing_dock"}


def port_key(dock_row):
    return dock_row[len("sailing_dock_"):]


def loc_for(prefix, key):
    for candidate in (key, key[4:] if key.startswith("the_") else None):
        if candidate and f"{prefix}{candidate}" in locs:
            return f"{prefix}{candidate}"
    return None


used_ports = set()

# --- courier tasks (Wiki xp by task id) -----------------------------------------------------
wiki_xp = {}
for line in open(courier_path, encoding="utf-8"):
    m = re.search(r"\{\{CourierTaskLine\|(.*)\}\}", line)
    if not m:
        continue
    fields = dict(f.split("=", 1) for f in m.group(1).split("|") if "=" in f)
    if "taskId" in fields and "xp" in fields:
        wiki_xp[int(fields["taskId"])] = int(fields["xp"].replace(",", ""))

for name, (table, data) in sorted(records.items(), key=lambda kv: int(kv[1][1].get("task_id", "0") or 0)):
    if table != "port_task" or data.get("task_type") != "0":
        continue
    task_id = int(data["task_id"])
    if name not in dbrows:
        log.append(f"skip-no-dbrow-symbol\t{name}")
        continue
    if task_id not in wiki_xp:
        log.append(f"skip-no-wiki-xp\t{task_id}\t{name}\t{data.get('name')}")
        continue
    cargo = data.get("cargo", "")
    crate, _, qty = cargo.partition(",")
    if crate not in objs:
        log.append(f"skip-no-crate\t{task_id}\t{name}\t{crate}")
        continue
    ports = [data.get(k, "") for k in ("starting_port", "cargo_port", "ending_port")]
    if any(p not in docks for p in ports):
        log.append(f"skip-port\t{task_id}\t{name}\t{ports}")
        continue
    keys = [port_key(p) for p in ports]
    used_ports.update(ports)
    rows.append("\t".join(map(str, ["courier", task_id, name, data.get("level_required", "1"),
                                    wiki_xp[task_id], *keys, crate, qty or "1", data.get("name", "")])))

for dock in sorted(used_ports):
    key = port_key(dock)
    board = loc_for("port_task_board_", key)
    ledger = loc_for("dock_loading_bay_ledger_table_", key)
    if dock not in dbrows:
        log.append(f"port-no-dbrow-symbol\t{dock}")
    if not board or not ledger:
        log.append(f"port-missing-loc\t{key}\tboard={board}\tledger={ledger}")
    rows.insert(0, "\t".join(["port", key, dock, board or "-", ledger or "-"]))

# --- charting locs (cache: loc sailing_charting_generic_X <-> varbit ..._X_complete) ----------
varbits = names("varbit.sym")
CHART_ALIASES = {"sailing_charting_generic_rowboat_strait_of_khazard": "sailing_charting_generic_rowboat_port_khazard_complete"}
for loc in sorted(l for l in locs if l.startswith("sailing_charting_generic_")):
    varbit = CHART_ALIASES.get(loc, loc + "_complete")
    if varbit not in varbits:
        log.append(f"chart-no-varbit	{loc}")
        continue
    rows.append("	".join(["chartloc", loc, varbit]))

# --- charting rows (Wiki) -------------------------------------------------------------------
for line in open(chart_path, encoding="utf-8"):
    m = re.search(r"\{\{SeaChartRow\|(.*)\}\}", line)
    if not m:
        continue
    fields = dict(f.split("=", 1) for f in m.group(1).split("|") if "=" in f)
    kind = fields.get("type", "")
    if kind not in ("Generic", "Spyglass") or "location" not in fields:
        continue
    try:
        x, z = (int(v) for v in fields["location"].split(",")[:2])
        level = int(fields.get("level", "1"))
    except ValueError:
        log.append(f"skip-chart-row\t{fields.get('id')}")
        continue
    rows.append("\t".join(map(str, ["chart", x, z, level, kind.lower()])))

with open(out_path, "w", encoding="utf-8", newline="\n") as w:
    w.write("# Generated by tools/skills/sailing_to_tsv.py from the rev 243 dbrow dump + OSRS Wiki - do not edit.\n")
    w.write("\n".join(rows) + "\n")
with open(log_path, "w", encoding="utf-8", newline="\n") as w:
    w.write("\n".join(sorted(log)) + "\n")
kinds = {}
for r in rows:
    kinds[r.split("\t")[0]] = kinds.get(r.split("\t")[0], 0) + 1
print(kinds, "logged", len(log))
