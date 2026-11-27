"""Fetch wikitext for a list of OSRS Wiki pages (one title per line) to JSON. Args: titles.txt out.json"""
import json, sys, time, urllib.parse, urllib.request
API = "https://oldschool.runescape.wiki/api.php"
UA = {"User-Agent": "local-dev-tool/1.0 (personal offline project)"}
titles = [t.strip() for t in open(sys.argv[1], encoding="utf-8") if t.strip()]
out = {}
for i in range(0, len(titles), 50):
    params = {"action": "query", "prop": "revisions", "rvprop": "content", "rvslots": "main", "redirects": "1",
              "format": "json", "formatversion": "2", "titles": "|".join(titles[i:i + 50])}
    data = json.load(urllib.request.urlopen(urllib.request.Request(API, data=urllib.parse.urlencode(params).encode(), headers=UA), timeout=60))
    redirects = {r["to"]: r["from"] for r in data["query"].get("redirects", [])}
    for p in data["query"]["pages"]:
        if "revisions" in p:
            text = p["revisions"][0]["slots"]["main"]["content"]
            out[p["title"]] = text
            if p["title"] in redirects: out[redirects[p["title"]]] = text
    print(i, len(out), file=sys.stderr, flush=True)
    time.sleep(0.5)
json.dump(out, open(sys.argv[2], "w", encoding="utf-8"))
print("pages", len(out))
