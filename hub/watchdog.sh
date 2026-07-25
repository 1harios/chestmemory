#!/bin/bash
# Keeps Python hub (+ optional localtunnel) alive across crashes.
# Sessions are on disk — restart does NOT wipe clan gathers.
set -u
HUB_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$HUB_DIR" || exit 1

LOG="$HUB_DIR/watchdog.log"
LOCKDIR="$HUB_DIR/watchdog.lockdir"

# single instance (mkdir is atomic; no flock required)
if ! mkdir "$LOCKDIR" 2>/dev/null; then
  # stale lock if no process
  if [ -f "$HUB_DIR/watchdog.pid" ] && kill -0 "$(cat "$HUB_DIR/watchdog.pid")" 2>/dev/null; then
    echo "$(date -Is) already running" >>"$LOG"
    exit 0
  fi
  rm -rf "$LOCKDIR"
  mkdir "$LOCKDIR" 2>/dev/null || exit 0
fi
trap 'rm -rf "$LOCKDIR"' EXIT

export PORT="${PORT:-18787}"
export HOST="${HOST:-0.0.0.0}"
export DATA_DIR="${DATA_DIR:-$HUB_DIR/data}"
if [ -f "$HUB_DIR/.clan_token" ]; then
  export CLAN_TOKEN="$(tr -d '\r\n' <"$HUB_DIR/.clan_token")"
fi
export SESSION_TTL_SEC="${SESSION_TTL_SEC:-$((7*24*3600))}"

mkdir -p "$DATA_DIR"

log() { echo "$(date -Is) $*" >>"$LOG"; }

start_hub() {
  if [ -f hub.pid ] && kill -0 "$(cat hub.pid)" 2>/dev/null; then
    return 0
  fi
  nohup python3 "$HUB_DIR/clan_hub.py" >>"$HUB_DIR/hub.log" 2>&1 &
  echo $! >hub.pid
  log "started hub pid=$(cat hub.pid)"
  sleep 1
}

start_tunnel() {
  # Optional public URL via localtunnel (changes on restart — prefer real domain+PHP)
  if [ "${ENABLE_TUNNEL:-1}" != "1" ]; then
    return 0
  fi
  if [ -f lt.pid ] && kill -0 "$(cat lt.pid)" 2>/dev/null; then
    return 0
  fi
  export NVM_DIR="${NVM_DIR:-$HOME/.nvm}"
  # shellcheck disable=SC1090
  [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh"
  if ! command -v npx >/dev/null 2>&1; then
    log "npx missing — skip tunnel"
    return 0
  fi
  nohup npx --yes localtunnel --port "$PORT" --local-host 127.1.6.129 >lt.log 2>&1 &
  echo $! >lt.pid
  sleep 4
  URL="$(grep -Eo 'https://[a-zA-Z0-9.-]+\.loca\.lt' lt.log | head -1 || true)"
  if [ -n "$URL" ]; then
    echo "$URL" >lt.url
    echo "URL=$URL" >PUBLIC.txt
    echo "TOKEN=$CLAN_TOKEN" >>PUBLIC.txt
    log "tunnel $URL"
  else
    log "tunnel start failed — see lt.log"
  fi
}

log "watchdog start dir=$HUB_DIR port=$PORT"
while true; do
  start_hub
  start_tunnel
  # health
  if [ -n "${CLAN_TOKEN:-}" ]; then
    curl -sS -m 3 -H "X-Clan-Token: $CLAN_TOKEN" "http://127.1.6.129:${PORT}/v1/health" >/dev/null 2>&1 \
      || curl -sS -m 3 -H "X-Clan-Token: $CLAN_TOKEN" "http://127.0.0.1:${PORT}/v1/health" >/dev/null 2>&1 \
      || log "health check failed"
  fi
  sleep 20
done
