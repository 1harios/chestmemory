#!/bin/bash
cd /var/www/h001628/data/chestmemory-hub
export NVM_DIR=/var/www/h001628/data/.nvm
# shellcheck disable=SC1090
[ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh"

# Hub
if [ -f hub.pid ] && kill -0 "$(cat hub.pid)" 2>/dev/null; then
  echo "hub ok $(cat hub.pid)"
else
  bash start.sh
  sleep 1
fi

# Localtunnel
if [ -f lt.pid ]; then
  kill "$(cat lt.pid)" 2>/dev/null || true
fi
rm -f lt.log lt.url
nohup npx --yes localtunnel --port 18787 --local-host 127.1.6.129 > lt.log 2>&1 &
echo $! > lt.pid
for i in 1 2 3 4 5 6 7 8 9 10; do
  sleep 1
  if grep -Eo 'https://[a-zA-Z0-9.-]+\.loca\.lt' lt.log >/dev/null 2>&1; then
    break
  fi
done
echo "LT_PID=$(cat lt.pid)"
cat lt.log
URL=$(grep -Eo 'https://[a-zA-Z0-9.-]+\.loca\.lt' lt.log | head -1)
echo "PUBLIC_URL=$URL"
echo "$URL" > lt.url
TOKEN=$(cat .clan_token)
echo "TOKEN=$TOKEN"
if [ -n "$URL" ]; then
  curl -sS -H "X-Clan-Token: $TOKEN" -H "Bypass-Tunnel-Reminder: 1" "$URL/v1/health" || true
  echo
fi
