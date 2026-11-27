"""Fetch OSRS Wiki recipes (bucket 'recipe') that use a skill. Args: Skill out.json"""
import json, sys, time, urllib.parse, urllib.request
API = "https://oldschool.runescape.wiki/api.php"
UA = {"User-Agent": "local-dev-tool/1.0 (personal offline project)"}
skill, out, offset, rows = sys.argv[1], sys.argv[2], 0, []
while True:
    q = (f"bucket('recipe').select('page_name','production_json').where('uses_skill','{skill}')"
         f".limit(500).offset({offset}).run()")
    url = API + "?" + urllib.parse.urlencode({"action": "bucket", "format": "json", "query": q})
    batch = json.load(urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60)).get("bucket", [])
    rows += [{"page": r["page_name"], **json.loads(r["production_json"])} for r in batch if r.get("production_json")]
    if len(batch) < 500: break
    offset += 500; time.sleep(0.5)
json.dump(rows, open(out, "w", encoding="utf-8"), indent=0)
print(skill, len(rows))
