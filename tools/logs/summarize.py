"""Summarize .data/logs/gameplay.log (GameplayLog lines) into ticket-ready groups.

Usage: python -I tools/logs/summarize.py [log ...]   (default: .data/logs/gameplay*.log)

Groups NOTHING ("Nothing interesting happens") hits by what was clicked (loc/npc/obj/button
id and op), and counts UNHANDLED, REFUSED, DIALOG and MODAL lines, newest examples first.
"""
import collections
import glob
import re
import sys

KINDS = ('NOTHING', 'UNHANDLED', 'REFUSED', 'DIALOG', 'MODAL', 'SESSION')
LINE = re.compile(r'^(\S+ \S+) (' + '|'.join(KINDS) + r') (.*)$')
TARGET = re.compile(r'target=(.*)$')
KEY = re.compile(r"(loc=\d+|npc=\d+ '[^']*'|obj=\d+|button=\S+|op=\d+|sub=-?\d+|use=\S+)")


def target_key(rest):
    m = TARGET.search(rest)
    target = m.group(1) if m else rest
    parts = KEY.findall(target)
    return ' '.join(parts) if parts else target


def main(paths):
    files = paths or sorted(glob.glob('.data/logs/gameplay*.log'))
    nothing = collections.Counter()
    examples = {}
    other = collections.defaultdict(collections.Counter)
    for path in files:
        if 'problems' in path:
            continue
        for line in open(path, encoding='utf-8', errors='replace'):
            m = LINE.match(line.rstrip('\n'))
            if not m:
                continue
            when, kind, rest = m.groups()
            if kind == 'NOTHING':
                key = target_key(rest)
                nothing[key] += 1
                examples[key] = f'{when} {rest}'
            elif kind != 'SESSION':
                other[kind][re.sub(r'player=\S+ ', '', rest)[:120]] += 1
    print(f'## "Nothing interesting happens" ({sum(nothing.values())} hits, {len(nothing)} targets)')
    for key, count in nothing.most_common():
        print(f'{count:5}  {key}')
        print(f'       e.g. {examples[key]}')
    for kind in ('UNHANDLED', 'REFUSED', 'DIALOG', 'MODAL'):
        counts = other.get(kind)
        if not counts:
            continue
        print(f'\n## {kind} ({sum(counts.values())})')
        for key, count in counts.most_common(25):
            print(f'{count:5}  {key}')
    script_errors(paths)


SCRIPT_ERROR = re.compile(r'^(\S+ \S+) .*SCRIPT_ERROR (.*)$')


def script_errors(paths):
    """SCRIPT_ERROR lines (PlayerMainProcess recoveries) from gameplay-problems*.log, grouped by
    exception + top content frame so each distinct bug is one row."""
    files = [p for p in (paths or sorted(glob.glob('.data/logs/gameplay-problems*.log'))) if 'problems' in p]
    groups = collections.Counter()
    last = {}
    for path in files:
        for line in open(path, encoding='utf-8', errors='replace'):
            m = SCRIPT_ERROR.match(line.rstrip('\n'))
            if not m:
                continue
            when, rest = m.groups()
            ex = re.search(r'ex=([^:]+)', rest)
            frame = re.search(r'frames=\[([^\] <]*)', rest)
            key = f"{ex.group(1) if ex else '?'} @ {frame.group(1) if frame and frame.group(1) else '(no content frame)'}"
            groups[key] += 1
            last[key] = f'{when} {rest[:160]}'
    if not groups:
        return
    print(f'\n## script-errors ({sum(groups.values())} recoveries, {len(groups)} distinct)')
    for key, count in groups.most_common(25):
        print(f'{count:5}  {key}')
        print(f'       last {last[key]}')


if __name__ == '__main__':
    main(sys.argv[1:])
