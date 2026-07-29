#!/bin/bash
# One-shot install of the Chest Memory clan hub on a clean Ubuntu VPS (22.04+).
#
# Run as root from the directory that contains clan_hub.py, clan_auth.py,
# clan_ratelimit.py (e.g. an unpacked hub bundle):
#
#   DOMAIN=chestmemory-clan.duckdns.org EMAIL=you@example.com bash deploy_vps.sh
#
# What it sets up — everything the shared-hosting deployment could not do:
#   * /opt/chestmemory-hub, its own system user, data/ on disk
#   * systemd unit with Restart=always — a crash or reboot never needs a human
#   * nginx on :443 with a free Let's Encrypt certificate, proxying to the hub
#     bound to 127.0.0.1 only — no tunnels, no extra moving parts
#
# Migrating from the old host: drop the old sessions.json into
# /opt/chestmemory-hub/data/ and the old .clan_token into /opt/chestmemory-hub/,
# then `systemctl restart chestmemory-hub` — active gathers survive the move.
# Finally point the domain at this VPS (duckdns example at the bottom).
set -euo pipefail

DOMAIN="${DOMAIN:-chestmemory-clan.duckdns.org}"
EMAIL="${EMAIL:-}"
ALLOW_HTTP="${ALLOW_HTTP:-}"
HUB_DIR=/opt/chestmemory-hub
PORT=18787

if [ "$(id -u)" -ne 0 ]; then
  echo "run as root" >&2
  exit 1
fi
# Fail closed on TLS: without EMAIL there is no certbot run, and the old behaviour
# was to leave the hub proxied over plain :80 — token, Mojang-derived session tokens
# and every clan's data crossing the internet in the clear.
if [ -z "$EMAIL" ] && [ "$ALLOW_HTTP" != "1" ]; then
  echo "EMAIL обязателен (для certbot / Let's Encrypt). Запустите:" >&2
  echo "  DOMAIN=$DOMAIN EMAIL=you@example.com bash deploy_vps.sh" >&2
  echo "Без сертификата хаб не будет отдавать трафик (только ACME-проверка)." >&2
  echo "Сознательно оставить голый HTTP (только для теста в изолированной сети):" >&2
  echo "  ALLOW_HTTP=1 bash deploy_vps.sh" >&2
  exit 1
fi
for f in clan_hub.py clan_auth.py clan_ratelimit.py; do
  [ -f "$f" ] || { echo "missing $f next to this script" >&2; exit 1; }
done

echo "── swap (safety net on small-RAM VPS)"
if [ ! -f /swapfile ] && ! swapon --show | grep -q swap; then
  fallocate -l 512M /swapfile 2>/dev/null || dd if=/dev/zero of=/swapfile bs=1M count=512
  chmod 600 /swapfile
  mkswap /swapfile >/dev/null
  swapon /swapfile
  grep -q '/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

echo "── packages"
export DEBIAN_FRONTEND=noninteractive
apt-get update -q
apt-get install -y -q --no-install-recommends nginx certbot python3-certbot-nginx curl openssl

echo "── hub files"
id -u chesthub >/dev/null 2>&1 || useradd --system --home "$HUB_DIR" --shell /usr/sbin/nologin chesthub
mkdir -p "$HUB_DIR/data" "$HUB_DIR/backups"
install -m 644 clan_hub.py clan_auth.py clan_ratelimit.py "$HUB_DIR/"

# Migrate active gathers if a sessions.json was placed next to this script.
if [ -f sessions.json ]; then
  cp sessions.json "$HUB_DIR/data/sessions.json"
  echo "   migrated sessions.json ($(wc -c < sessions.json) bytes)"
fi

# CLAN_TOKEN is intentionally EMPTY. The hub then accepts every request
# (_check_token returns true when unset), which keeps the clients already
# distributed with the OLD baked-in token working — they send the header,
# the hub simply ignores it. Rate-limiting still protects session codes.
#
# REQUIRE_AUTH=1 is explicit even though it is the hub's default now: identity
# comes from Mojang, and this line is what an operator must consciously flip to 0
# for the one-window upgrade path (members still on the old mod). With 0 the hub
# logs at startup that identities are unverified and members are impersonable;
# host actions demand a verified session either way.
cat > "$HUB_DIR/env" <<EOF
HOST=127.0.0.1
PORT=$PORT
DATA_DIR=$HUB_DIR/data
CLAN_TOKEN=
REQUIRE_AUTH=1
SESSION_TTL_SEC=$((7*24*3600))
EOF
chmod 600 "$HUB_DIR/env"
chown -R chesthub:chesthub "$HUB_DIR"

echo "── systemd unit"
cat > /etc/systemd/system/chestmemory-hub.service <<EOF
[Unit]
Description=Chest Memory clan gather hub
After=network-online.target
Wants=network-online.target

[Service]
User=chesthub
Group=chesthub
EnvironmentFile=$HUB_DIR/env
WorkingDirectory=$HUB_DIR
ExecStart=/usr/bin/python3 $HUB_DIR/clan_hub.py
Restart=always
RestartSec=3
# The hub only needs its own directory; lock everything else down.
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=yes
ReadWritePaths=$HUB_DIR
PrivateTmp=yes

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable --now chestmemory-hub
sleep 2
systemctl --no-pager --lines=5 status chestmemory-hub || true

echo "── nginx site for $DOMAIN"
cat > /etc/nginx/sites-available/chestmemory-hub <<EOF
server {
    listen 80;
    listen [::]:80;
    server_name $DOMAIN;

    location / {
        proxy_pass http://127.0.0.1:$PORT;
        proxy_set_header Host \$host;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_read_timeout 30s;
    }
}
EOF
ln -sf /etc/nginx/sites-available/chestmemory-hub /etc/nginx/sites-enabled/chestmemory-hub
rm -f /etc/nginx/sites-enabled/default
nginx -t && systemctl reload nginx

echo "── TLS certificate"
# Requires the domain to already resolve to this VPS. Until a certificate exists the
# site is swapped for an ACME-only block below — a hub proxied over plain :80 serves
# the token and all session data in the clear, so "no cert" must mean "no traffic".
close_http() {
  cat > /etc/nginx/sites-available/chestmemory-hub <<EOF
server {
    listen 80;
    listen [::]:80;
    server_name $DOMAIN;

    # Only the Let's Encrypt challenge until a certificate is issued.
    location /.well-known/acme-challenge/ {
        root /var/www/html;
    }
    location / {
        return 503;
    }
}
EOF
  nginx -t && systemctl reload nginx
}

TLS_OK=0
if [ -n "$EMAIL" ]; then
  if certbot --nginx -d "$DOMAIN" --redirect -m "$EMAIL" --agree-tos -n; then
    TLS_OK=1
  else
    echo "!! certbot failed — DNS ещё не смотрит сюда? Переключите домен и запустите"
    echo "   скрипт ещё раз (он идемпотентен):"
    echo "   DOMAIN=$DOMAIN EMAIL=$EMAIL bash deploy_vps.sh"
  fi
fi
if [ "$TLS_OK" != "1" ]; then
  if [ "$ALLOW_HTTP" = "1" ]; then
    echo "!!!! ALLOW_HTTP=1 — хаб отдаёт трафик по ГОЛОМУ HTTP. Токен, сессии Mojang и"
    echo "!!!! все данные кланов идут открытым текстом. Только для изолированного теста."
  else
    close_http
    echo "Хаб закрыт до выпуска сертификата (на / отвечает 503, открыт только ACME)."
  fi
fi

echo
echo "════════════════════════════════════════════════════════"
echo " Готово. Локальная проверка хаба:"
curl -s -m 5 "http://127.0.0.1:$PORT/v1/health" && echo
echo
echo " Сессии (миграция):     $HUB_DIR/data/sessions.json"
echo " Логи:                  journalctl -u chestmemory-hub -f"
echo " Перезапуск:            systemctl restart chestmemory-hub"
echo " Токен НЕ используется (совместимость со старыми клиентами)."
if [ "$TLS_OK" = "1" ]; then
  echo " TLS: выпущен, весь трафик через https://$DOMAIN"
else
  echo
  echo " Осталось: направить домен $DOMAIN на этот сервер ($(curl -s -m5 ifconfig.me 2>/dev/null))."
  echo " На duckdns.org в поле IP укажите этот адрес, ИЛИ обновите через API."
  echo " DuckDNS-токен держите в файле, не в командной строке — команды видны в ps"
  echo " и остаются в истории shell. Один раз: вставьте токен в ~/.duckdns_token"
  echo " (редактором, не echo) и выполните chmod 600 ~/.duckdns_token. Затем:"
  echo "   printf 'url = \"https://www.duckdns.org/update?domains=${DOMAIN%%.duckdns.org}&token=%s&ip=\"' \"\$(cat ~/.duckdns_token)\" | curl -sS -K -"
  echo " Когда DNS переключён — запустите скрипт ещё раз (он идемпотентен, восстановит"
  echo " прокси-конфиг и выпустит сертификат; НЕ вызывайте certbot вручную по закрытому"
  echo " конфигу — он скопирует заглушку 503 в HTTPS-блок):"
  echo "   DOMAIN=$DOMAIN EMAIL=${EMAIL:-you@example.com} bash deploy_vps.sh"
fi
echo "════════════════════════════════════════════════════════"
