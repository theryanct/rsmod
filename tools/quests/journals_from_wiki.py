"""OSRS Wiki quest journal transcripts -> quest journal resources (one file per quest).

Usage: python -I tools/quests/journals_from_wiki.py [--offline]

For every quest in content/quests/.../framework/quest_ids.txt that has a Wiki journal transcript
("Transcript:<Quest>/Journal"), fetch it (cached in ~/code/wiki-cache/journals/<Quest>.wiki;
--offline uses only the cache), and write
content/quests/src/main/resources/org/rsmod/content/quests/framework/journals/<dbrow>.journal.

The Wiki writes a journal as snapshots: one section per milestone ("After starting the quest",
"After handing the talisman to Sedridor", ...), each listing every line shown at that point, with
the finished ones struck through (<s>). Those become `step` blocks in order; the server shows the
last step whose stage the player has reached (QuestJournal). A step's stage is written as `-`
(spread evenly over the quest's states at runtime); set a number to pin it.

File format (tab separated):
  before  <text>          a line of the not-started page (start text, difficulty, ...)
  req     <text>          a requirement line (the server strikes it through when met)
  step    <n> <stage|->   <section title>   starts snapshot n
  line    <text>          a line of the current snapshot
  done    <text>          the quest-complete page

Text keeps the journal's colours as <col=rrggbb> and struck-through text as <str>.
Also writes journals/_missing.txt: quests in quest_ids.txt without a Wiki journal.
"""
import json
import os
import re
import sys
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RES = os.path.join(ROOT, "content/quests/src/main/resources/org/rsmod/content/quests/framework")
OUT = os.path.join(RES, "journals")
CACHE = os.path.join(os.path.expanduser("~"), "code", "wiki-cache", "journals")
API = "https://oldschool.runescape.wiki/api.php"
UA = {"User-Agent": "local-dev-tool/1.0 (personal offline project)"}
OFFLINE = "--offline" in sys.argv


def fetch(page):
    safe = re.sub(r'[\\/:*?"<>|]', "_", page)
    path = os.path.join(CACHE, safe + ".wiki")
    if os.path.exists(path):
        return open(path, encoding="utf-8").read() or None
    if OFFLINE:
        return None
    url = API + "?" + urllib.parse.urlencode(
        {"action": "parse", "page": page, "prop": "wikitext", "format": "json", "redirects": 1})
    try:
        data = json.load(urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60))
        text = data["parse"]["wikitext"]["*"]
    except Exception:
        text = ""
    os.makedirs(CACHE, exist_ok=True)
    open(path, "w", encoding="utf-8").write(text)
    return text or None


def colour(s):
    # {{Colour|#000080|text}} -> <col=000080>text</col>, innermost first.
    pat = re.compile(r"\{\{(?:Colour|Color)\|#?([0-9A-Fa-f]{6})\|([^{}]*)\}\}")
    while True:
        s2 = pat.sub(lambda m: f"<col={m.group(1).lower()}>{m.group(2)}</col>", s)
        if s2 == s:
            return s
        s = s2


def clean(s):
    s = colour(s)
    s = re.sub(r"\[\[(?:[^|\]]*\|)?([^\]]*)\]\]", r"\1", s)
    s = re.sub(r"\{\{[^{}|]*\|([^{}]*)\}\}", r"\1", s)
    s = re.sub(r"\{\{[^{}]*\}\}", "", s)
    s = re.sub(r"<s>", "<str>", s)
    s = re.sub(r"</s>", "</str>", s)
    s = re.sub(r"<br\s*/?>", " ", s)
    s = re.sub(r"'''?", "", s)
    s = s.replace("[player name]", "{player}").replace("[Player name]", "{player}")
    s = re.sub(r"<(?!/?col|/?str)[^>]+>", "", s)
    return re.sub(r"\s+", " ", s).replace("\t", " ").strip()


def plain(s):
    return re.sub(r"<[^>]+>", "", s).strip()


def parse(text):
    """-> (before lines [(kind, text)], steps [(title, [lines])], done lines)."""
    sections = []
    title, body, skip = None, [], False
    for raw in text.splitlines():
        line = raw.rstrip()
        h = re.match(r"^(=+)\s*(.*?)\s*=+$", line)
        if h:
            if len(h.group(1)) <= 2:
                if title is not None:
                    sections.append((title, body))
                title, body, skip = h.group(2), [], False
            else:
                # "===If the player ...===" variants of a snapshot: keep the main one only.
                skip = True
            continue
        if title is None or skip:
            continue
        body.append(line)
    if title is not None:
        sections.append((title, body))

    before, steps, done = [], [], []
    for title, body in sections:
        low = title.lower()
        if "missing" in low:
            continue
        kind = "before" if ("before" in low and "start" in low) else (
            "done" if "complet" in low and "after" in low else "step")
        lines, in_reqs = [], False
        for raw in body:
            if raw.strip() == "----" or raw.strip() == "":
                if lines and lines[-1] != "":
                    lines.append("")
                continue
            m = re.match(r"^(\*+)\s*(.*)$", raw)
            if not m:
                continue
            depth, content = len(m.group(1)), m.group(2).strip()
            if content.startswith("''") and depth > 1:
                continue  # "(After collecting the milk)" variants and "If the player has ..." notes
            text_ = clean(content)
            if not text_ or plain(text_) in ("Show on Map",):
                continue
            if kind == "before":
                p = plain(text_)
                if depth == 1:
                    in_reqs = p.rstrip(":") in ("Requirements", "Recommendations")
                    lines.append(("before", text_))
                elif in_reqs and p.lower() != "none":
                    lines.append(("req", p))
                else:
                    lines.append(("before", text_))
            else:
                lines.append(text_)
        while lines and lines[-1] == "":
            lines.pop()
        if kind == "before":
            before = [x if x != "" else ("before", "") for x in lines]
        elif kind == "done":
            done = lines
        else:
            steps.append((title, lines))
    return before, steps, done


def journal_pages():
    """Every "Transcript:*/Journal" page (one allpages listing), cached as _pages.txt."""
    path = os.path.join(CACHE, "_pages.txt")
    if os.path.exists(path) and (OFFLINE or "--refresh" not in sys.argv):
        return set(open(path, encoding="utf-8").read().splitlines())
    pages, cont = [], None
    while True:
        q = {"action": "query", "list": "allpages", "apnamespace": "120", "aplimit": 500, "format": "json"}
        if cont:
            q["apcontinue"] = cont
        r = json.load(urllib.request.urlopen(urllib.request.Request(API + "?" + urllib.parse.urlencode(q), headers=UA)))
        pages += [p["title"] for p in r["query"]["allpages"] if p["title"].endswith("/Journal")]
        cont = r.get("continue", {}).get("apcontinue")
        if not cont:
            break
    os.makedirs(CACHE, exist_ok=True)
    open(path, "w", encoding="utf-8", newline="\n").write("\n".join(pages))
    return set(pages)


def main():
    known = journal_pages()
    ids =[l.rstrip("\n").split("\t") for l in open(os.path.join(RES, "quest_ids.txt"), encoding="utf-8")
           if l.strip() and not l.startswith("#")]
    os.makedirs(OUT, exist_ok=True)
    missing, written = [], 0
    for qid, row, name in (x[:3] for x in ids if len(x) >= 3):
        existing = os.path.join(OUT, row + ".journal")
        if os.path.exists(existing) and not open(existing, encoding="utf-8").readline().startswith("# Generated"):
            continue  # hand-written (Authored, not Wiki): keep it, it isn't missing
        page = f"Transcript:{name}/Journal"
        text = fetch(page) if page in known else None
        if not text:
            missing.append(f"{row}\t{name}")
            continue
        before, steps, done = parse(text)
        if not steps and not done:
            missing.append(f"{row}\t{name}")
            continue
        out_path = os.path.join(OUT, row + ".journal")
        pins = {}
        if os.path.exists(out_path):
            # Keep hand-set stages across regenerations.
            for old in open(out_path, encoding="utf-8"):
                q = old.rstrip("\n").split("\t")
                if q[0] == "step" and len(q) > 2 and q[2] != "-":
                    pins[q[1]] = q[2]
        with open(out_path, "w", encoding="utf-8", newline="\n") as f:
            f.write(f"# Generated from the OSRS Wiki page \"Transcript:{name}/Journal\" by "
                    "tools/quests/journals_from_wiki.py; set a step's stage to pin it.\n")
            for k, t in before:
                f.write(f"{k}\t{t}\n")
            for n, (title, lines) in enumerate(steps, 1):
                f.write(f"step\t{n}\t{pins.get(str(n), '-')}\t{title}\n")
                for t in lines:
                    f.write(f"line\t{t}\n")
            for t in done:
                f.write(f"done\t{t}\n")
        written += 1
    with open(os.path.join(OUT, "_missing.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write("# Quests without a Wiki journal transcript (the server shows a summary instead).\n")
        f.write("\n".join(missing) + "\n")
    print(f"journals: {written} written, {len(missing)} missing")


if __name__ == "__main__":
    main()
