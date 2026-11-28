import sys,re
# Flag find("...") calls that run inside `lazy {` / `by lazy {` blocks in References objects
# (they run after ref resolution, so the type stays unresolved -> "internalId must not be null").
hit=0
for p in sys.argv[1:]:
    try: lines=open(p,encoding='utf-8').read().splitlines()
    except OSError: continue
    depth=None
    for n,l in enumerate(lines,1):
        if depth is None:
            m=re.search(r'\blazy\s*\{',l)
            if not m: continue
            depth=0; seg=l[m.end()-1:]
        else: seg=l
        if re.search(r'\bfind\(\s*"',seg):
            print(f"find() inside lazy block: {p}:{n}: {l.strip()[:100]}"); hit=1
        depth+=seg.count('{')-seg.count('}')
        if depth<=0: depth=None
sys.exit(hit)
