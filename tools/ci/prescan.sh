#!/usr/bin/env bash
# prescan.sh [base=origin/main] - static checks on the integ branch's changed .kt files for the
# compile/boot bugs workers keep shipping. Run from the worktree before every build. Exit 1 on hits.
set -u
base=${1:-origin/main}
hits=0
for f in $(git diff --name-only "$base" HEAD -- '*.kt'); do
  [ -f "$f" ] || continue
  if grep -qE '(Map|HashMap|mutableMapOf)<Long,' "$f" && grep -q 'uid\.packed' "$f"; then
    echo "LONG-MAP keyed by uid.packed (Int): $f"; hits=1; fi
  if grep -nE 'launchLenient|\.statMap\b' "$f" >/dev/null && ! grep -q 'OptIn(InternalApi' "$f"; then
    echo "@InternalApi without OptIn: $f:$(grep -nE 'launchLenient|\.statMap\b' "$f" | head -1 | cut -d: -f1)"; hits=1; fi
  if grep -nE '\bmapClock\.cycle' "$f" | grep -vq 'this@'; then
    echo "mapClock.cycle (ProtectedAccess.mapClock is Int): $f"; hits=1; fi
  if grep -q 'MapAreaBuilder()' "$f"; then
    for a in $(grep -oE 'area\([A-Za-z]+\.([a-z_0-9]+)' "$f" | sed 's/.*\.//' | sort -u); do
      git grep -q "build(\"$a\")" -- '*.kt' || { echo "custom area '$a' has no AreaBuilder build(): $f"; hits=1; }
    done
  fi
done
kts=$(git diff --name-only "$base" HEAD -- '*.kt' | while read -r f; do [ -f "$f" ] && echo "$f"; done)
[ -n "$kts" ] && { python3 -I "$(dirname "$0")/lazyfind.py" $kts || hits=1; }
exit $hits
