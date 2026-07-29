#!/bin/bash
# Start persistent hub + watchdog (auto-restart). Prefer this over raw python.
set -e
HUB_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$HUB_DIR"

mkdir -p data
if [ ! -f .clan_token ]; then
  openssl rand -hex 12 >.clan_token 2>/dev/null || head -c 16 /dev/urandom | xxd -p >.clan_token
  chmod 600 .clan_token
fi

export PORT="${PORT:-18787}"
export HOST="${HOST:-0.0.0.0}"
export DATA_DIR="$HUB_DIR/data"
export CLAN_TOKEN="$(tr -d '\r\n' <.clan_token)"
export SESSION_TTL_SEC="${SESSION_TTL_SEC:-$((7*24*3600))}"
export ENABLE_TUNNEL="${ENABLE_TUNNEL:-1}"

# Token in a curl config file (-K), never on the command line: argv is world-readable
# in /proc on a shared host, so `-H "X-Clan-Token: …"` showed it to every `ps`.
CURL_AUTH="$HUB_DIR/.curl_auth"
(
  umask 077
  printf 'header = "X-Clan-Token: %s"\n' "$CLAN_TOKEN" >"$CURL_AUTH"
)

chmod +x watchdog.sh 2>/dev/null || true
# stop old watchdog if any (lock will prevent double)
if [ -f watchdog.pid ] && kill -0 "$(cat watchdog.pid)" 2>/dev/null; then
  echo "watchdog already running pid=$(cat watchdog.pid)"
else
  nohup bash "$HUB_DIR/watchdog.sh" >>"$HUB_DIR/watchdog.log" 2>&1 &
  echo $! >watchdog.pid
  echo "watchdog started pid=$(cat watchdog.pid)"
fi
sleep 2
# Never echo the token: this output lands in watchdog.log / the panel console.
echo "TOKEN: in .clan_token (cat it yourself; deliberately not printed)"
if [ -f lt.url ]; then echo "URL=$(cat lt.url)"; fi
if [ -f data/sessions.json ]; then
  echo "sessions file: data/sessions.json ($(wc -c <data/sessions.json) bytes)"
fi
curl -sS -m 5 -K "$CURL_AUTH" "http://127.1.6.129:${PORT}/v1/health" 2>/dev/null \
  || curl -sS -m 5 -K "$CURL_AUTH" "http://127.0.0.1:${PORT}/v1/health" 2>/dev/null \
  || echo "health: waiting…"
echo
