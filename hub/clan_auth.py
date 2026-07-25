"""
Mojang-backed player authentication for the clan hub.

Why this exists
---------------
The hub used to trust a single shared token plus whatever ``uuid`` the client put
in the request body. Nothing tied a request to a real Minecraft account, so any
member could impersonate any other: release someone else's claim, "deliver"
materials on their behalf, or close the session. The shared token also leaked
into a public git history, which made the hub readable by anyone who found it.

How it works
------------
Exactly the handshake Minecraft itself performs when joining an online-mode
server, so no new trust is introduced:

1. ``GET  /v1/auth/challenge``          -> hub returns a random nonce
2. client calls Mojang's ``joinServer`` with that nonce (its access token never
   leaves the game)
3. ``POST /v1/auth/verify {name, nonce}`` -> hub asks Mojang ``hasJoined``; on
   success it mints a short-lived session token bound to the verified UUID
4. every later request carries ``X-Clan-Session: <token>``

The shared clan token stays as an *invite* to the hub, not as an identity. Even
if it leaks again, an attacker still cannot act as a specific player.

Tokens live in memory only: a hub restart just means clients re-authenticate,
which costs one round trip.
"""

from __future__ import annotations

import json
import secrets
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Any, Optional

HAS_JOINED_URL = "https://sessionserver.mojang.com/session/minecraft/hasJoined"

#: How long a nonce stays usable. Long enough for a slow round trip to Mojang,
#: short enough that a captured nonce is worthless.
CHALLENGE_TTL_SEC = 60
#: Session lifetime. Refreshed on every use, so an active player never notices.
SESSION_TTL_SEC = 12 * 3600
#: Upper bounds so a hostile client cannot grow these maps without limit.
MAX_CHALLENGES = 512
MAX_SESSIONS = 512
#: Seconds to wait on Mojang before giving up.
MOJANG_TIMEOUT_SEC = 8

_lock = threading.RLock()
#: nonce -> issued_at (monotonic seconds)
_challenges: dict[str, float] = {}
#: token -> {"uuid": str, "name": str, "expires": float}
_sessions: dict[str, dict[str, Any]] = {}


def _now() -> float:
    return time.monotonic()


def _purge_locked() -> None:
    now = _now()
    for nonce, issued in list(_challenges.items()):
        if now - issued > CHALLENGE_TTL_SEC:
            _challenges.pop(nonce, None)
    for token, data in list(_sessions.items()):
        if data.get("expires", 0) < now:
            _sessions.pop(token, None)


def new_challenge() -> Optional[str]:
    """Issue a nonce for the joinServer handshake, or None when overloaded."""
    with _lock:
        _purge_locked()
        if len(_challenges) >= MAX_CHALLENGES:
            return None
        nonce = secrets.token_hex(16)
        _challenges[nonce] = _now()
        return nonce


def _consume_challenge(nonce: str) -> bool:
    """Single-use: a nonce is valid at most once."""
    with _lock:
        _purge_locked()
        issued = _challenges.pop(nonce, None)
    return issued is not None and (_now() - issued) <= CHALLENGE_TTL_SEC


def _ask_mojang(username: str, nonce: str) -> Optional[dict[str, Any]]:
    """Return the verified profile from Mojang, or None if it does not vouch."""
    query = urllib.parse.urlencode({"username": username, "serverId": nonce})
    url = f"{HAS_JOINED_URL}?{query}"
    try:
        with urllib.request.urlopen(url, timeout=MOJANG_TIMEOUT_SEC) as resp:
            # 204 (empty) means "this player did not join with that nonce".
            if resp.status != 200:
                return None
            raw = resp.read()
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        print("hasJoined failed: %s" % e)
        return None
    if not raw:
        return None
    try:
        data = json.loads(raw.decode("utf-8"))
    except Exception:
        return None
    if not isinstance(data, dict) or not data.get("id") or not data.get("name"):
        return None
    return data


def _dashed_uuid(raw: str) -> str:
    """Mojang returns UUIDs without dashes; the mod sends the dashed form."""
    s = raw.replace("-", "").lower()
    if len(s) != 32:
        return raw.lower()
    return f"{s[0:8]}-{s[8:12]}-{s[12:16]}-{s[16:20]}-{s[20:32]}"


def verify(username: str, nonce: str) -> Optional[dict[str, str]]:
    """
    Complete the handshake.

    Returns ``{"token", "uuid", "name"}`` on success, or None when the nonce is
    unknown/expired or Mojang does not confirm the player.
    """
    if not username or not nonce:
        return None
    if not _consume_challenge(nonce):
        return None
    profile = _ask_mojang(username, nonce)
    if profile is None:
        return None

    uuid = _dashed_uuid(str(profile["id"]))
    name = str(profile["name"])
    token = secrets.token_urlsafe(32)
    with _lock:
        _purge_locked()
        if len(_sessions) >= MAX_SESSIONS:
            # Drop the closest to expiry rather than refusing a legitimate login.
            oldest = min(_sessions, key=lambda t: _sessions[t].get("expires", 0))
            _sessions.pop(oldest, None)
        _sessions[token] = {
            "uuid": uuid,
            "name": name,
            "expires": _now() + SESSION_TTL_SEC,
        }
    return {"token": token, "uuid": uuid, "name": name}


def resolve(token: str) -> Optional[dict[str, str]]:
    """
    Identity behind a session token, or None when absent/expired.

    Sliding expiry: using the token refreshes it, so a player in a long build
    session is never logged out mid-gather.
    """
    if not token:
        return None
    with _lock:
        _purge_locked()
        data = _sessions.get(token)
        if data is None:
            return None
        data["expires"] = _now() + SESSION_TTL_SEC
        return {"uuid": data["uuid"], "name": data["name"]}


def stats() -> dict[str, int]:
    with _lock:
        _purge_locked()
        return {"challenges": len(_challenges), "sessions": len(_sessions)}
