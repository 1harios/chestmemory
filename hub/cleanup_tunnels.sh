#!/bin/bash
cd /var/www/h001628/data/chestmemory-hub || exit 1

# Stop tunnel/watchdog leftovers (NOT panel permanent process if named differently)
# Kill by pattern carefully
for pat in localtunnel 'node_modules/.bin/lt' 'npm exec' cloudflared watchdog.sh start_tunnel; do
  pkill -u h001628 -f "$pat" 2>/dev/null || true
done
# node leftover lt
pkill -u h001628 -f '/ispnodejs/bin/node' 2>/dev/null || true

rm -f lt.pid tunnel.pid lhr.pid watchdog.pid
rm -rf watchdog.lockdir
chmod a-x start_tunnel.sh watchdog.sh start.sh 2>/dev/null || true

sleep 1
echo "=== processes (sample) ==="
ps -u h001628 -o pid,rss,args --no-headers 2>/dev/null | grep -E 'python|node|lt|watchdog|tunnel|clan' || echo "(none matching)"

TOKEN=$(tr -d '\r\n' < .clan_token)
echo "TOKEN_LEN=${#TOKEN}"
# Token via curl config file (-K), not argv — argv is world-readable via ps on the
# shared host this runs on.
CURL_AUTH="$PWD/.curl_auth"
(
  umask 077
  printf 'header = "X-Clan-Token: %s"\n' "$TOKEN" >"$CURL_AUTH"
)

echo "=== health 127.0.0.1 ==="
curl -sS -m 5 -K "$CURL_AUTH" "http://127.0.0.1:18787/v1/health" || echo FAIL
echo
echo "=== health 127.1.6.129 ==="
curl -sS -m 5 -K "$CURL_AUTH" "http://127.1.6.129:18787/v1/health" || echo FAIL
echo
echo "=== hub.log tail ==="
tail -8 hub.log 2>/dev/null || true
