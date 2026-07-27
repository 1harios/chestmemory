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
# Bind all interfaces so the public reverse-proxy (site → 127.x:18787) reaches it,
# matching the previously working instance. ISPmanager isolates the account, so this
# is not exposed beyond the account's own address.
export HOST="${HOST:-0.0.0.0}"
export PORT="${PORT:-18787}"
export SESSION_TTL_SEC="${SESSION_TTL_SEC:-$((7*24*3600))}"

# Release the port from any previous instance before binding.
# The panel runs this script in the same namespace as the process it manages, so a
# stale hub holding the port (e.g. after a code update where the panel started a
# duplicate that failed with "Address already in use") is reachable here even though
# it is invisible to the user's separate shell namespace. Without this the updated
# code could never bind and the old build kept serving forever.
fuser -k "${PORT}/tcp" 2>/dev/null || true
pkill -f "$DIR/clan_hub.py" 2>/dev/null || true
# Give the kernel a moment to release the socket (TIME_WAIT / cleanup).
sleep 2

# Log to file (panel has no journal for user)
exec /usr/bin/python3 "$DIR/clan_hub.py" >>"$DIR/hub.log" 2>&1
