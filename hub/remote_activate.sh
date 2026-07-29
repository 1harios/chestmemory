#!/bin/bash
set -e
cd /var/www/h001628/data/chestmemory-hub
chmod +x start.sh watchdog.sh clan_hub.py || true

# stop old
for f in hub.pid lt.pid watchdog.pid tunnel.pid lhr.pid; do
  if [ -f "$f" ]; then
    kill "$(cat "$f")" 2>/dev/null || true
  fi
done
rm -rf watchdog.lockdir
sleep 1

bash start.sh
sleep 4

TOKEN=$(tr -d '\r\n' < .clan_token)
# Token in a curl config file (-K), never echoed or on argv: this script's output is
# panel-console/log material, and argv is world-readable via ps on the shared host.
CURL_AUTH="$PWD/.curl_auth"
(
  umask 077
  printf 'header = "X-Clan-Token: %s"\n' "$TOKEN" >"$CURL_AUTH"
)
echo "TOKEN: in .clan_token (deliberately not printed)"
echo "URL=$(cat lt.url 2>/dev/null || true)"
ls -la data || true

echo "=== health ==="
curl -sS -m 8 -K "$CURL_AUTH" "http://127.1.6.129:18787/v1/health" || true
echo

echo "=== create ==="
# {"error":"auth required"} here is EXPECTED and healthy: identity is Mojang-verified
# by default (REQUIRE_AUTH), and this bare curl has no player session. Only a hub
# deliberately started with REQUIRE_AUTH=0 will accept this create.
CREATE=$(curl -sS -m 12 -K "$CURL_AUTH" -H "Content-Type: application/json" \
  -d '{"name":"PersistTest","schemaName":"PersistTest","hostName":"test","hostUuid":"uuid-persist-1","materials":{"minecraft:dirt":10}}' \
  "http://127.1.6.129:18787/v1/sessions" || true)
echo "$CREATE"
echo

ls -la data/sessions.json || true
CODE=$(python3 - <<'PY'
import json
from pathlib import Path
p = Path("data/sessions.json")
if not p.is_file():
    print("")
else:
    d = json.loads(p.read_text(encoding="utf-8"))
    sess = d.get("sessions", d)
    print(next(iter(sess.keys()), ""))
PY
)
echo "CODE=$CODE"

echo "=== kill hub, wait watchdog ==="
if [ -f hub.pid ]; then kill "$(cat hub.pid)" 2>/dev/null || true; fi
sleep 25

echo "=== health after kill ==="
curl -sS -m 8 -K "$CURL_AUTH" "http://127.1.6.129:18787/v1/health" || true
echo

if [ -n "$CODE" ]; then
  echo "=== get session after restart ==="
  curl -sS -m 8 -K "$CURL_AUTH" "http://127.1.6.129:18787/v1/sessions/${CODE}" || true
  echo
fi

# bashrc autostart once
if ! grep -q "chestmemory-hub/start.sh" "$HOME/.bashrc" 2>/dev/null; then
  cat >> "$HOME/.bashrc" <<'EOF'

# Chest Memory clan hub autostart on SSH login
if [ -x /var/www/h001628/data/chestmemory-hub/start.sh ]; then
  /var/www/h001628/data/chestmemory-hub/start.sh >/dev/null 2>&1 &
fi
EOF
  echo "bashrc_autostart=ok"
else
  echo "bashrc_autostart=already"
fi

# PHP config from token. Created under umask 077 so the token is never readable in
# the moment between write and chmod; require_auth stays explicit so nobody has to
# guess the default while debugging on the server.
if [ -f public/config.sample.php ]; then
  rm -f public/config.php
  (
    umask 077
    cat > public/config.php <<EOF
<?php
return [
    'token' => '${TOKEN}',
    'require_auth' => true,
    'ttl_sec' => 7 * 24 * 3600,
    'data_dir' => __DIR__ . '/../data',
];
EOF
  )
  chmod 600 public/config.php
  echo "php_config=ok"
fi

echo DONE
