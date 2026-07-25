#!/usr/bin/env python3
"""
Entry for ISPmanager site handler «Python» (nginx → PORT).

Put this file as the site start file (server.py). Workdir = site root
which should contain clan_hub.py, data/, .clan_token (or set CLAN_TOKEN).

Listens on 127.0.0.1:$PORT only — public HTTPS via ShopVDS nginx + free SSL.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

# Site root = directory of this file
ROOT = Path(__file__).resolve().parent
os.chdir(ROOT)
sys.path.insert(0, str(ROOT))

# Token / data
token_file = ROOT / ".clan_token"
if token_file.is_file() and not os.environ.get("CLAN_TOKEN"):
    os.environ["CLAN_TOKEN"] = token_file.read_text(encoding="utf-8").strip()

os.environ.setdefault("DATA_DIR", str(ROOT / "data"))
# ShopVDS assigns PORT; must bind 127.0.0.1 only
os.environ["HOST"] = "127.0.0.1"
if "PORT" not in os.environ:
    os.environ["PORT"] = "8000"

# Reuse main hub
import clan_hub  # noqa: E402

if __name__ == "__main__":
    clan_hub.main()
