"""Snapshot OSRS Wiki real-time prices for every Grand Exchange-tradeable obj.

Usage:
    python -I tools/ge/fetch_prices.py [cache_dir] [out_file]

    cache_dir  JS5 disk cache used to decide tradeability (default ~/code/rsmod/.data/cache/vanilla)
    out_file   default api/market/src/main/resources/org/rsmod/api/market/prices.tsv

Sources (OSRS Wiki real-time prices API, https://prices.runescape.wiki):
    /api/v1/osrs/latest   high/low instant-buy/sell prices per item id
    /api/v1/osrs/mapping  item names, store value, GE buy limit

Only objs whose cache config has the stockmarket flag (obj opcode 65) are written, so the file
matches what the in-game GE can trade. Mid price = round((high + low) / 2) when both sides exist,
otherwise whichever side exists; items with no recent trades fall back to the mapping "value".

The output is a TSV with a header comment carrying the fetch time (UTC):
    # fetched=2026-11-27T23:50:00Z source=prices.runescape.wiki
    id  name  mid  high  low  limit
"""
import datetime
import json
import os
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..'))
sys.path.insert(0, os.path.join(REPO, 'tools', 'cache-check'))

API = 'https://prices.runescape.wiki/api/v1/osrs'
# The wiki asks API users for a descriptive User-Agent; keep it generic (no personal details).
USER_AGENT = 'rsmod-local-server ge-price-snapshot (private, non-commercial)'
DEFAULT_CACHE = os.path.expanduser('~/code/rsmod/.data/cache/vanilla')
DEFAULT_OUT = os.path.join(REPO, 'api', 'market', 'src', 'main', 'resources', 'org', 'rsmod',
                           'api', 'market', 'prices.tsv')
OBJ_GROUP = 10
STOCKMARKET_OPCODE = 65


def fetch(path):
    req = urllib.request.Request(f'{API}/{path}', headers={'User-Agent': USER_AGENT})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.load(resp)


def tradeable_ids(cache_dir):
    """Obj ids with the stockmarket flag, using the offline opcode walker's tables."""
    from opwalk import Buf, Cache, obj_op  # noqa: E402  (tools/cache-check)

    cache = Cache(cache_dir)
    files = cache.index(2)[OBJ_GROUP]
    records = cache.group_files(2, OBJ_GROUP, files)
    ids = set()
    for fid, data in records.items():
        buf = Buf(data)
        try:
            while True:
                op = buf.u8()
                if op == 0:
                    break
                if op == STOCKMARKET_OPCODE:
                    ids.add(fid)
                obj_op(op, buf)
        except (KeyError, IndexError):
            # Older-format record the 243 tables don't cover; the flag may still have been seen.
            pass
    return ids


def main(cache_dir=DEFAULT_CACHE, out_file=DEFAULT_OUT):
    tradeable = tradeable_ids(cache_dir)
    latest = fetch('latest')['data']
    mapping = {int(m['id']): m for m in fetch('mapping')}
    fetched = datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0)

    rows = []
    for obj_id in sorted(tradeable):
        meta = mapping.get(obj_id)
        if meta is None:
            continue  # tradeable in the cache but not listed by the wiki (e.g. unreleased)
        price = latest.get(str(obj_id), {})
        high, low = price.get('high'), price.get('low')
        if high and low:
            mid = round((high + low) / 2)
        else:
            mid = high or low or meta.get('value') or 1
        name = meta.get('name', '').replace('\t', ' ')
        rows.append((obj_id, name, mid, high or 0, low or 0, meta.get('limit') or 0))

    os.makedirs(os.path.dirname(out_file), exist_ok=True)
    with open(out_file, 'w', encoding='utf-8', newline='\n') as f:
        f.write(f'# fetched={fetched.isoformat().replace("+00:00", "Z")} '
                'source=prices.runescape.wiki\n')
        f.write('id\tname\tmid\thigh\tlow\tlimit\n')
        for row in rows:
            f.write('\t'.join(str(v) for v in row) + '\n')
    print(f'tradeable in cache: {len(tradeable)}, written: {len(rows)} -> {out_file}')


if __name__ == '__main__':
    main(*sys.argv[1:])
