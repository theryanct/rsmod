"""Fetch OSRS Wiki item name -> ids (bucket infobox_item) to JSON. Args: out.json"""
import json, sys, time, urllib.parse, urllib.request
API = "https://oldschool.runescape.wiki/api.php"
UA = {"User-Agent": "local-dev-tool/1.0 (personal offline project)"}
out, offset, rows = sys.argv[1], 0, []
while True:
    q = f"bucket('infobox_item').select('item_name','item_id').limit(5000).offset({offset}).run()"
    url = API + "?" + urllib.parse.urlencode({"action": "bucket", "format": "json", "query": q})
    data = json.load(urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60))
    batch = data.get("bucket", [])
    rows += batch
    print(offset, len(batch), file=sys.stderr, flush=True)
    if len(batch) < 5000: break
    offset += 5000; time.sleep(0.5)
names = {}
for r in rows:
    for i in r.get("item_id") or []:
        if str(i).isdigit() and r.get("item_name"): names.setdefault(r["item_name"], set()).add(int(i))
json.dump({k: sorted(v) for k, v in names.items()}, open(out, "w", encoding="utf-8"))
print("names", len(names))
