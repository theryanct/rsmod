"""List find("name") refs in main sources that match no name in any .data/symbols/*.sym file.

Usage: python -I unresolved.py [repo_root]
Skips build dirs, test/integration sources, and modules disabled in settings.gradle.kts.
Not type-aware (a name valid for another type passes), so treat it as a coarse first gate.
"""
import glob
import os
import re
import sys

SKIP = ()
FIND = re.compile(r'find(?:<[^>]*>)?\(\s*"([^"]+)"')


def main(repo='.'):
    names = set()
    for f in glob.glob(os.path.join(repo, '.data', 'symbols', '*.sym')):
        for line in open(f, encoding='utf-8', errors='replace'):
            parts = line.rstrip('\r\n').split('\t')
            if len(parts) >= 2:
                names.add(parts[1])
    missing = []
    for root, dirs, files in os.walk(repo):
        norm = root.replace(os.sep, '/')
        dirs[:] = [d for d in dirs if d not in ('build', '.git', '.gradle', '.data')]
        if any(s in norm for s in SKIP) or '/src/integration' in norm or '/src/test' in norm:
            continue
        for f in files:
            if not f.endswith('.kt'):
                continue
            path = os.path.join(root, f)
            for n, line in enumerate(open(path, encoding='utf-8', errors='replace'), 1):
                for ref in FIND.findall(line):
                    if ref not in names:
                        missing.append((path, n, ref))
    print('unresolved refs:', len(missing))
    for path, n, ref in missing:
        print(f'  {path}:{n}  {ref}')


if __name__ == '__main__':
    main(*sys.argv[1:])
