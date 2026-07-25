# Chest Memory — постоянный клан-хаб

## Что сохраняется

- Все сессии (`CM-XXXX`), claim’ы, progress, staging keys  
- Файл: `data/sessions.json` (атомарная запись после каждого изменения)  
- После **ребута VDS / падения процесса** данные **не пропадают** (нужно снова запустить watchdog)

## Вариант A — Python + watchdog (сейчас на VDS)

```bash
cd ~/chestmemory-hub   # или /var/www/h001628/data/chestmemory-hub
bash start.sh
cat .clan_token
cat lt.url             # временный URL (loca.lt) — может смениться
```

Watchdog каждые 20 с поднимает хаб (и туннель), если упал.

**После ребута сервера** один раз зайди по SSH и снова `bash start.sh`  
(или добавь эту команду в **Cron панели ISPmanager** → `@reboot`).

В моде:

- URL: из `lt.url` (или постоянный — вариант B)
- Токен: из `.clan_token`

## Вариант B — постоянный HTTPS (рекомендуется)

Без localtunnel, URL не прыгает:

1. В панели ISPmanager: **WWW → создать сайт** (бесплатный DuckDNS или свой домен).  
2. Document root → папка `chestmemory-hub/public`  
3. Скопировать `public/config.sample.php` → `public/config.php`, прописать тот же token.  
4. SSL (Let’s Encrypt) в панели.  
5. В моде URL: `https://твой-домен` (без порта), токен из config.

PHP пишет в тот же `data/sessions.json`.

## Файлы

| Файл | Назначение |
|------|------------|
| `clan_hub.py` | API + диск |
| `watchdog.sh` | авто-рестарт |
| `start.sh` | запуск |
| `public/index.php` | API для обычного сайта |
| `data/sessions.json` | **все сессии** |

## Проверка

```bash
TOKEN=$(cat .clan_token)
curl -sS -H "X-Clan-Token: $TOKEN" http://127.1.6.129:18787/v1/health
# → "persistent": true, "version": 2
```
