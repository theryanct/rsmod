"""Fetch slayer data from the OSRS Wiki: monster slayer fields (bucket infobox_monster) and each
master's "<Master>/Slayer assignments" table wikitext. Args: out.json Master [Master ...]"""
import json, sys, time, urllib.parse, urllib.request
API = "https://oldschool.runescape.wiki/api.php"
UA = {"User-Agent": "local-dev-tool/1.0 (personal offline project)"}


def get(params):
    url = API + "?" + urllib.parse.urlencode({"format": "json", **params})
    return json.load(urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60))


monsters, offset = [], 0
while True:
    q = ("bucket('infobox_monster').select('page_name','id','slayer_category','slayer_level',"
         f"'slayer_experience','hitpoints','combat_level').limit(5000).offset({offset}).run()")
    rows = get({"action": "bucket", "query": q}).get("bucket", [])
    monsters += rows
    if len(rows) < 5000: break
    offset += 5000; time.sleep(0.5)
masters = {}
for m in sys.argv[2:]:
    d = get({"action": "parse", "page": f"{m}/Slayer assignments", "prop": "wikitext", "formatversion": "2"})
    text = d.get("parse", {}).get("wikitext", "")
    if not text:
        # Vannaka and Chaeldar keep their task table on the master's own page.
        d = get({"action": "parse", "page": m, "prop": "wikitext", "formatversion": "2"})
        text = d.get("parse", {}).get("wikitext", "")
    masters[m] = text
    time.sleep(0.5)
json.dump({"monsters": monsters, "masters": masters}, open(sys.argv[1], "w", encoding="utf-8"))
print("monsters", len(monsters), "masters", {k: len(v) for k, v in masters.items()})
