#!/usr/bin/env bash
# Cut the local server over from rev 233 to rev 243, or roll back.
#
#   scripts/cutover-243.sh prepare   # stop server, back up .data, install cache 2777, add RSProx target
#   scripts/cutover-243.sh rollback  # restore the 233 .data backup and stop using the 243 target
#
# Intended order (Ryan logged out):
#   1. scripts/cutover-243.sh prepare
#   2. merge feat/rev-243 into main and `git -C ~/code/rsmod pull`
#   3. start the server loop again (it re-packs: the pack hash is cleared here)
#   4. in RSProx pick the "RSMod 243" target and log in
# Never touches the RuneLite profile (~/.runelite) or the existing "RSMod Local" target.
set -euo pipefail

LIVE=~/code/rsmod
DATA="$LIVE/.data"
STOP_FILE=~/code/rsmod-server.stop
PACK_HASH=~/code/.rsmod-last-pack-hash
TARGETS=~/.rsprox/proxy-targets.yaml
BACKUP_ROOT=~/code/rsmod-backup-233
CACHE_ID=2777
CACHE_URL="https://archive.openrs2.org/caches/runescape/$CACHE_ID/disk.zip"
UA="Mozilla/5.0 (generic client)"

server_running() {
  powershell -NoProfile -Command \
    "Get-CimInstance Win32_Process -Filter \"name='java.exe'\" | Where-Object { \$_.CommandLine -match 'GameServerKt' } | Measure-Object | Select-Object -ExpandProperty Count" \
    | tr -d '\r'
}

stop_server() {
  if [ "$(server_running)" = "0" ]; then
    echo "server is not running"
    return
  fi
  echo "touching $STOP_FILE: type ::reboot in-game (or wait for a restart) so the loop exits"
  touch "$STOP_FILE"
  for _ in $(seq 1 120); do
    [ "$(server_running)" = "0" ] && { echo "server stopped"; return; }
    sleep 5
  done
  echo "server still running after 10 minutes; aborting" >&2
  exit 1
}

prepare() {
  stop_server
  local stamp backup
  stamp=$(date +%Y%m%d-%H%M%S)
  backup="$BACKUP_ROOT-$stamp"
  echo "backing up $DATA -> $backup"
  mkdir -p "$backup"
  cp -a "$DATA/saves" "$backup/"
  cp -a "$DATA/cache" "$backup/"
  cp -a "$DATA/server.toml" "$DATA/client.key" "$DATA/game.key" "$backup/" 2>/dev/null || true
  [ -f "$PACK_HASH" ] && cp -a "$PACK_HASH" "$backup/"
  echo "$backup" > "$BACKUP_ROOT.latest"

  echo "installing cache $CACHE_ID"
  local tmp
  tmp=$(mktemp -d)
  curl -fsSL -A "$UA" -o "$tmp/disk.zip" "$CACHE_URL"
  rm -rf "$DATA/cache/vanilla" "$DATA/cache/game" "$DATA/cache/js5" "$DATA/cache/enriched"
  mkdir -p "$DATA/cache/vanilla"
  unzip -q "$tmp/disk.zip" -d "$tmp/x"
  # The archive nests the files under cache/.
  if [ -d "$tmp/x/cache" ]; then mv "$tmp/x/cache/"* "$DATA/cache/vanilla/"; else mv "$tmp/x/"* "$DATA/cache/vanilla/"; fi
  # Rev 237+ maps are unencrypted: an empty key list.
  echo '[ ]' > "$DATA/cache/xteas.json"
  rm -rf "$tmp"
  rm -f "$PACK_HASH"   # force the server loop to packCache on next start

  add_target
  rm -f "$STOP_FILE"
  echo
  echo "prepared. Next: merge feat/rev-243, pull ~/code/rsmod, start the server loop, use 'RSMod 243' in RSProx."
  echo "rollback: scripts/cutover-243.sh rollback"
}

add_target() {
  if grep -q "name: RSMod 243" "$TARGETS"; then
    echo "RSProx target 'RSMod 243' already present"
    return
  fi
  local modulus
  modulus=$(grep -m1 "modulus:" "$TARGETS" | sed 's/.*modulus: *//')
  cp -a "$TARGETS" "$TARGETS.bak-233"
  cat >> "$TARGETS" <<EOF
  - name: RSMod 243
    jav_config_url: https://client.blurite.io/jav_local_243.ws
    revision: 243.1
    modulus: $modulus
EOF
  echo "added RSProx target 'RSMod 243' (no RuneLite pin: 243 is the live revision)"
}

rollback() {
  local backup
  backup=$(cat "$BACKUP_ROOT.latest" 2>/dev/null || true)
  [ -n "$backup" ] && [ -d "$backup" ] || { echo "no backup found ($BACKUP_ROOT.latest)" >&2; exit 1; }
  stop_server
  echo "restoring $backup -> $DATA"
  rm -rf "$DATA/saves" "$DATA/cache"
  cp -a "$backup/saves" "$DATA/"
  cp -a "$backup/cache" "$DATA/"
  [ -f "$backup/.rsmod-last-pack-hash" ] && cp -a "$backup/.rsmod-last-pack-hash" "$PACK_HASH" || rm -f "$PACK_HASH"
  rm -f "$STOP_FILE"
  echo "restored. Check out the last 233 commit on main (git -C ~/code/rsmod log) before restarting,"
  echo "and use the 'RSMod Local' target in RSProx."
}

case "${1:-}" in
  prepare) prepare ;;
  rollback) rollback ;;
  *) echo "usage: $0 prepare|rollback" >&2; exit 2 ;;
esac
