"""List type names in api/content TOML resources (spawns, enricher data) missing from .data/symbols.

Usage: python -I tools/rev243/tomlcheck.py [--fix]
  --fix  drop whole [[spawn]]/[[config]] blocks naming a type that no longer exists.
"""
import collections
import glob
import re
import sys

KIND = {'npc': 'npc', 'obj': 'obj', 'item': 'obj', 'loc': 'loc', 'seq': 'seq', 'anim': 'seq',
        'spotanim': 'spotanim'}
PAT = re.compile(r"^\s*(npc|obj|loc|item|seq|anim|spotanim)\s*=\s*['\"]([^'\"]+)['\"]", re.M)


def syms(kind):
    out = set()
    for line in open(f'.data/symbols/{kind}.sym', encoding='utf-8', errors='replace'):
        parts = line.rstrip('\r\n').split('\t')
        if len(parts) >= 2:
            out.add(parts[1])
    return out


def main(fix):
    names = {k: syms(k) for k in set(KIND.values())}
    files = glob.glob('api/**/*.toml', recursive=True) + glob.glob('content/**/*.toml', recursive=True)
    total = 0
    for path in files:
        if '/build/' in path.replace('\\', '/'):
            continue
        text = open(path, encoding='utf-8').read()
        bad = [(KIND[m.group(1)], m.group(2)) for m in PAT.finditer(text)
               if m.group(2) not in names[KIND[m.group(1)]]]
        if not bad:
            continue
        total += len(bad)
        print(path, len(bad))
        for (kind, name), count in collections.Counter(bad).most_common():
            print('   ', kind, name, count)
        if fix and ('[[spawn]]' in text or '[[config]]' in text):
            blocks = re.split(r'(?m)^(?=\[\[(?:spawn|config)\]\])', text)
            kept = [b for b in blocks
                    if not any(m.group(2) not in names[KIND[m.group(1)]] for m in PAT.finditer(b))]
            newline = '\r\n' if '\r\n' in text else '\n'
            open(path, 'w', encoding='utf-8', newline='').write(''.join(kept))
            print('    fixed: dropped', len(blocks) - len(kept), 'blocks')
    print('TOTAL', total)


if __name__ == '__main__':
    main('--fix' in sys.argv)
