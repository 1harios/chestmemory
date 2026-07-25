#!/bin/bash
set -e
HUB=/var/www/h001628/data/chestmemory-hub
cd "$HUB"

# Ensure key
mkdir -p /var/www/h001628/data/.ssh
chmod 700 /var/www/h001628/data/.ssh
if [ ! -f /var/www/h001628/data/.ssh/id_ed25519 ]; then
  ssh-keygen -t ed25519 -N '' -f /var/www/h001628/data/.ssh/id_ed25519
fi

# Ensure hub running
if [ -f hub.pid ] && kill -0 "$(cat hub.pid)" 2>/dev/null; then
  echo "hub already running pid=$(cat hub.pid)"
else
  bash start.sh
  sleep 1
fi

# Stop old tunnel
if [ -f lhr.pid ]; then
  kill "$(cat lhr.pid)" 2>/dev/null || true
fi

nohup ssh \
  -o StrictHostKeyChecking=accept-new \
  -o UserKnownHostsFile=/var/www/h001628/data/.ssh/known_hosts \
  -o ServerAliveInterval=30 \
  -o ExitOnForwardFailure=yes \
  -i /var/www/h001628/data/.ssh/id_ed25519 \
  -N -R 80:127.1.6.129:18787 \
  nokey@localhost.run \
  > lhr.log 2>&1 &
echo $! > lhr.pid
sleep 8
echo "LHR_PID=$(cat lhr.pid)"
echo "----- lhr.log -----"
cat lhr.log
echo "----- health local -----"
TOKEN=$(cat .clan_token)
curl -sS -H "X-Clan-Token: $TOKEN" http://127.1.6.129:18787/v1/health || true
echo
