"""Fetch OSRS Wiki npc page -> npc ids (bucket infobox_npc + infobox_monster) to JSON. Args: out.json"""
import json, sys, time, urllib.parse, urllib.request
API = "https://oldschool.runescape.wiki/api.php"
UA = {"User-Agent": "local-dev-tool/1.0 (personal offline project)"}
pages = {}
for bucket, idcol in (("infobox_npc", "npc_id"), ("infobox_monster", "id")):
    offset = 0
    while True:
        q = f"bucket('{bucket}').select('page_name','{idcol}').limit(5000).offset({offset}).run()"
        url = API + "?" + urllib.parse.urlencode({"action": "bucket", "format": "json", "query": q})
        rows = json.load(urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60)).get("bucket", [])
        for r in rows:
            for i in r.get(idcol) or []:
                if str(i).isdigit() and r.get("page_name"): pages.setdefault(r["page_name"], set()).add(int(i))
        print(bucket, offset, len(rows), file=sys.stderr, flush=True)
        if len(rows) < 5000: break
        offset += 5000; time.sleep(0.5)
json.dump({k: sorted(v) for k, v in pages.items()}, open(sys.argv[1], "w", encoding="utf-8"))
print("pages", len(pages))
