# Настройка хаба в ISPmanager (ShopVDS) — как сказала поддержка

## 0. После разблокировки SSH

`.bashrc` уже очищен (без nvm и без автозапуска).

**Не** запускай `start_tunnel.sh` / `watchdog.sh` / `npx localtunnel` — они жрут лимит процессов.

---

## 1. Постоянный процесс (один экземпляр, авто-рестарт)

ISPmanager → **Постоянные процессы** → **Создать**:

| Поле | Значение |
|------|----------|
| **Имя** | `chestmemory-hub` |
| **Рабочая папка** | `chestmemory-hub` |
| **Команда** | `/bin/bash run_panel.sh` |

Либо как писала поддержка:

| Команда | `/usr/bin/python3 clan_hub.py` |
| Рабочая папка | `chestmemory-hub` |

Если команда без `run_panel.sh` — в панели **нет** env, токен может быть пустым.  
Надёжнее: **`/bin/bash run_panel.sh`** (читает `.clan_token` и пишет `hub.log`).

Статус должен стать **active**.

Проверка по SSH (после создания процесса):

```bash
cd ~/chestmemory-hub
TOKEN=$(cat .clan_token)
curl -sS -H "X-Clan-Token: $TOKEN" http://127.0.0.1:18787/v1/health
# → "persistent": true, "version": 2
ls -la data/sessions.json
```

---

## 2. Постоянный публичный URL (без localtunnel)

По [доке ShopVDS](https://shopvds.ru/help/site/nodejs-python):

1. **Сайты** → создать сайт (домен или тех. поддомен).
2. Скопировать файлы хаба в `www/ТВОЙ-ДОМЕН/`:
   - `clan_hub.py`, `server.py`, `data/`, `.clan_token`, при желании `public/` для PHP.
3. Сайт → **Изменить**:
   - Обработчик: **Python**
   - Стартовый файл: **`server.py`**
   - Режим: **порт**
4. В корне сайта: `python3 -m venv .venv` (если панель требует venv — см. доку).
5. Сохранить → `https://ТВОЙ-ДОМЕН` с SSL.

В моде Ё → Клан:

- **URL:** `https://ТВОЙ-ДОМЕН`
- **Токен:** содержимое `.clan_token`

---

## 3. Альтернатива: PHP (без venv)

Корень сайта = папка `public/`  
`config.php` с тем же token, `data_dir` → `../data`.

---

## 4. Что НЕ делать

- `nohup`, `watchdog.sh`, `start_tunnel.sh`, localtunnel, cloudflared  
- автозапуск в `~/.bashrc`  
- несколько копий хаба вручную  

Лимит HOST 2: **100 процессов**, **128 МБ RAM**. Один Python-хаб — нормально; Node-туннели — нет.

---

## Токен сейчас

Смотри файл: `chestmemory-hub/.clan_token`  
(не свети в публичных чатах)
