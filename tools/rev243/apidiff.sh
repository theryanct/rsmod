#!/usr/bin/env bash
# Diff public signatures of every net.rsprot class imported by the repo, rsprot 233 vs 243.
set -u
REPO=~/code/rsmod-ui
S="C:/Users/under/AppData/Local/Temp/claude/acf856b3c5d0b20b/ad560e90-640c-45fd-827c-1b0da37e998d/scratchpad"
OUT="$S/apidiff"
J243="$S/rsprot243"
JAVAP="/c/Program Files/Eclipse Adoptium/jdk-21.0.13.10002-hotspot/bin/javap"
G=~/.gradle/caches/modules-2/files-2.1
mkdir -p "$OUT"
win() { cygpath -m "$1"; }
CP233=""
for f in $(find "$G/net.rsprot" -name "*-1.0.0-ALPHA-20250909.jar" ! -name "*sources*"); do CP233="$CP233;$(win "$f")"; done
CP243=""
for f in "$J243"/*.jar; do CP243="$CP243;$f"; done
COMMON=""
for f in $(find "$G" \( -path "*io.netty*" -o -path "*kotlin-stdlib*" -o -path "*net.rsprot/haproxy*" -o -path "*jire*" \) -name "*.jar" ! -name "*sources*"); do COMMON="$COMMON;$(win "$f")"; done
grep -rhoE "^import net\.rsprot\.[A-Za-z0-9_.]+" --include=*.kt "$REPO/api" "$REPO/server" "$REPO/engine" "$REPO/content" \
  | sed 's/^import //' | sort -u > "$OUT/imports.txt"
: > "$OUT/report.txt"
: > "$OUT/missing.txt"
while read -r cls; do
  a=$("$JAVAP" -cp "${CP233#;}$COMMON" "$cls" 2>&1 | grep -v "^Compiled from")
  b=$("$JAVAP" -cp "${CP243#;}$COMMON" "$cls" 2>&1 | grep -v "^Compiled from")
  case "$a" in *"class not found"*) echo "233-missing $cls" >> "$OUT/missing.txt"; continue ;; esac
  case "$b" in *"class not found"*) echo "REMOVED-IN-243 $cls" >> "$OUT/report.txt"; continue ;; esac
  if [ "$a" != "$b" ]; then
    {
      echo "### $cls"
      diff <(echo "$a") <(echo "$b") | grep '^[<>]' | head -40
    } >> "$OUT/report.txt"
  fi
done < "$OUT/imports.txt"
echo "imports: $(wc -l < "$OUT/imports.txt")  changed: $(grep -c '^###' "$OUT/report.txt")  removed: $(grep -c '^REMOVED' "$OUT/report.txt")  233-missing: $(wc -l < "$OUT/missing.txt")"
