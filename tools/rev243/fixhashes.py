"""Rewrite stale identity hashes in find(..., hash) calls from the type verifier's boot output.

Usage: python -I fixhashes.py <server_log_or_verifier_output> [repo_root]

The verifier (api/type/type-verifier ReferenceVerifier) prints lines like:
    Invalid hash: 123 | Cache hash: 456 | Reference: ...
Every `find("...", 123)` / `find("...", 123L)` in Kotlin sources is rewritten to use 456.
Hashes are 64-bit values, so a literal match is unambiguous. Prints a summary.
"""
import os
import re
import sys

LINE = re.compile(r'Invalid hash:\s*(-?\d+)\s*\|\s*Cache hash:\s*(-?\d+)')


def main(log_path, repo='.'):
    pairs = {}
    for line in open(log_path, encoding='utf-8', errors='replace'):
        m = LINE.search(line)
        if m:
            pairs[m.group(1)] = m.group(2)
    if not pairs:
        print('no "Invalid hash" lines found')
        return
    pattern = re.compile(r'(find(?:<[^>]*>)?\(\s*"[^"]+"\s*,\s*)(-?\d+)(L?)(\s*\))')
    changed_files = fixed = 0
    for root, dirs, files in os.walk(repo):
        dirs[:] = [d for d in dirs if d not in ('build', '.git', '.gradle', '.data')]
        for f in files:
            if not f.endswith('.kt'):
                continue
            path = os.path.join(root, f)
            src = open(path, encoding='utf-8').read()
            count = 0

            def repl(m):
                nonlocal count
                new = pairs.get(m.group(2))
                if new is None:
                    return m.group(0)
                count += 1
                return f'{m.group(1)}{new}{m.group(3)}{m.group(4)}'

            out = pattern.sub(repl, src)
            if count:
                open(path, 'w', encoding='utf-8').write(out)
                changed_files += 1
                fixed += count
    print(f'hash pairs in log: {len(pairs)}, refs rewritten: {fixed} in {changed_files} files')
    if fixed < len(pairs):
        print('note: some hashes were not found as find() literals (multi-line finds or generated refs)')


if __name__ == '__main__':
    main(*sys.argv[1:])
