"""OSRS Wiki "Transcript:<Quest>" page -> quest dialogue tree resource (*.dialogue).

Args: "<Wiki transcript page title>" out.dialogue

The wiki transcript is a nested bullet list; each bullet becomes one tab-separated line:
  <depth> <kind> <a> [<b>]
kinds:
  section <title>          a "==Heading==" (depth 0)
  line    <speaker> <text> '''Speaker:''' text  (speaker "Player" for the player)
  select  <title>          {{tselect|...}}  title for the option group that follows
  option  <label>          {{topt|...}}      a dialogue option; its children follow, deeper
  cond    <text>           {{tcond|...}}     a branch taken when the quest's condition holds
  box     <text>           {{tbox|...}}      an item/message box
  action  <tag>            {{tact|...}}      e.g. "end"
Wiki links, bold/italics and inline templates are stripped; "[player name]" becomes {player}.
The quest framework's QuestDialogueRunner walks this tree at runtime.
"""
import json, re, sys, urllib.parse, urllib.request

API = "https://oldschool.runescape.wiki/api.php"
UA = {"User-Agent": "local-dev-tool/1.0 (personal offline project)"}
title, out_path = sys.argv[1], sys.argv[2]
url = API + "?" + urllib.parse.urlencode(
    {"action": "parse", "page": title, "prop": "wikitext", "format": "json", "redirects": 1})
text = json.load(urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60))
text = text["parse"]["wikitext"]["*"]


def clean(s):
    s = re.sub(r"\[\[(?:[^|\]]*\|)?([^\]]*)\]\]", r"\1", s)
    s = re.sub(r"\{\{(?:sic|!)(?:\|[^{}]*)?\}\}", "", s)
    s = re.sub(r"\{\{[^{}|]*\|([^{}]*)\}\}", r"\1", s)
    s = re.sub(r"\{\{[^{}]*\}\}", "", s)
    s = re.sub(r"'''?", "", s)
    s = re.sub(r"<br\s*/?>", " ", s)
    s = re.sub(r"<[^>]+>", "", s)
    s = s.replace("[player name]", "{player}").replace("[Player name]", "{player}")
    return re.sub(r"\s+", " ", s).replace("\t", " ").strip()


def template_arg(body, name):
    m = re.match(r"\{\{" + name + r"\|(.*)\}\}\s*$", body)
    if not m:
        return None
    args = [a for a in m.group(1).split("|") if "=" not in a.split("|")[0][:12] or not re.match(r"^\w+=", a)]
    return clean(args[-1] if args else "")


rows = []
for raw in text.splitlines():
    line = raw.rstrip()
    sec = re.match(r"^==+\s*(.*?)\s*==+$", line)
    if sec:
        rows.append(("0", "section", clean(sec.group(1))))
        continue
    m = re.match(r"^(\*+)\s*(.*)$", line)
    if not m:
        continue
    depth, body = str(len(m.group(1))), m.group(2).strip()
    for kind, name in (("option", "topt"), ("select", "tselect"), ("cond", "tcond"),
                       ("box", "tbox"), ("action", "tact")):
        arg = template_arg(body, name)
        if arg is not None:
            rows.append((depth, kind, arg))
            break
    else:
        body = re.sub(r"</?nowiki>", "", body)
        sp = re.match(r"^'''(.+?):'''\s*(.*)$", body)
        if sp:
            rows.append((depth, "line", clean(sp.group(1)), clean(sp.group(2))))

with open(out_path, "w", encoding="utf-8", newline="\n") as f:
    f.write(f"# Generated from the OSRS Wiki page \"{title}\" by tools/quests/transcript_to_dialogue.py\n")
    for row in rows:
        f.write("\t".join(row) + "\n")
print(title, len(rows))
