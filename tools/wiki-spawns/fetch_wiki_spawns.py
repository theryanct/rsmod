"""Fetch every OSRS Wiki page that uses a template and dump raw wikitext to JSON.

Args: out.json [template]  (template defaults to ItemSpawnLine; use LocLine for npc spawns)
"""
import json, sys, time, urllib.parse, urllib.request
API = "https://oldschool.runescape.wiki/api.php"
UA = {"User-Agent": "local-dev-tool/1.0 (personal offline project)"}
out = sys.argv[1]
template = sys.argv[2] if len(sys.argv) > 2 else "ItemSpawnLine"
pages, cont = {}, {}
while True:
    params = {"action": "query", "format": "json", "formatversion": "2",
              "generator": "embeddedin", "geititle": "Template:" + template,
              "geinamespace": "0", "geilimit": "50",
              "prop": "revisions", "rvprop": "content", "rvslots": "main", **cont}
    req = urllib.request.Request(API + "?" + urllib.parse.urlencode(params), headers=UA)
    data = json.load(urllib.request.urlopen(req, timeout=60))
    for p in data.get("query", {}).get("pages", []):
        revs = p.get("revisions")
        if revs:
            pages[p["title"]] = revs[0]["slots"]["main"]["content"]
    print(len(pages), file=sys.stderr, flush=True)
    if "continue" not in data:
        break
    cont = data["continue"]
    time.sleep(0.5)
json.dump(pages, open(out, "w", encoding="utf-8"))
print("pages", len(pages))
