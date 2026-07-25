#!/bin/bash
# Launcher for ISPmanager «Постоянные процессы».
# Workdir: chestmemory-hub
# Command:  /bin/bash run_panel.sh
#   or:     /usr/bin/python3 clan_hub.py  (then set env in panel if possible)
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR"

export DATA_DIR="${DATA_DIR:-$DIR/data}"
mkdir -p "$DATA_DIR"

if [ -f "$DIR/.clan_token" ]; then
  export CLAN_TOKEN="$(tr -d '\r\n' <"$DIR/.clan_token")"
fi
# Permanent process: only local listen (public via Site/Python or not at all)
export HOST="${HOST:-127.0.0.1}"
export PORT="${PORT:-18787}"
export SESSION_TTL_SEC="${SESSION_TTL_SEC:-$((7*24*3600))}"

# Log to file (panel has no journal for user)
exec /usr/bin/python3 "$DIR/clan_hub.py" >>"$DIR/hub.log" 2>&1
