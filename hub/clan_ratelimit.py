"""
Per-address rate limiting for the clan hub.

Why
---
Session codes are the only thing standing between an outsider and a clan's data,
and ``CM-XXXX`` is a 32-character alphabet over 4 positions — 1,048,576 codes.
Unthrottled, a single home connection walks that in tens of minutes and lands on
a live session, which exposes every warehouse chest coordinate the clan marked.

The shared token used to paper over this, at the cost of making every member
paste a secret by hand. Throttling fixes the actual problem: at 10 lookups per
minute the same search takes months, so the token can go away.

Design notes
------------
Two separate buckets, because the limits want to be very different:

* ``lookup`` — reading/joining a session by code. Tight, this is the one being
  guessed at.
* ``action`` — everything a member does inside a session they already joined
  (claim, deliver, poll). Loose, since polling is legitimate and frequent.

Sliding window over timestamps rather than a token bucket: with these small
volumes the memory cost is trivial and the behaviour is easier to reason about
("no more than N in the last 60s" needs no tuning of refill rates).
"""

from __future__ import annotations

import threading
import time
from typing import Optional

#: Session lookups (GET /v1/sessions/CODE, POST .../join) per address per window.
#: A real member looks up a handful of times; a guesser needs hundreds of thousands.
LOOKUP_LIMIT = 10
LOOKUP_WINDOW_SEC = 60

#: In-session actions per address per window. Polling is ~20/min per player and a
#: whole clan can sit behind one NAT address, so this needs real headroom: 900
#: covers 45 simultaneous players from a single IP. Guessing codes is throttled by
#: the lookup bucket instead, so being generous here costs nothing.
ACTION_LIMIT = 900
ACTION_WINDOW_SEC = 60

#: Failed auth attempts per address per window — stops nonce grinding.
AUTH_LIMIT = 20
AUTH_WINDOW_SEC = 60

#: Stop tracking addresses beyond this, so a spoofed-source flood cannot grow the
#: map without limit. Oldest entries are dropped first.
MAX_TRACKED = 4096

_lock = threading.RLock()
#: bucket -> {address -> [timestamps]}
_hits: dict[str, dict[str, list[float]]] = {
    "lookup": {},
    "action": {},
    "auth": {},
}

_LIMITS = {
    "lookup": (LOOKUP_LIMIT, LOOKUP_WINDOW_SEC),
    "action": (ACTION_LIMIT, ACTION_WINDOW_SEC),
    "auth": (AUTH_LIMIT, AUTH_WINDOW_SEC),
}


def _now() -> float:
    return time.monotonic()


def _prune(bucket: dict[str, list[float]], window: float, now: float) -> None:
    for addr in list(bucket.keys()):
        stamps = [t for t in bucket[addr] if now - t < window]
        if stamps:
            bucket[addr] = stamps
        else:
            del bucket[addr]


def check(kind: str, address: str) -> Optional[int]:
    """
    Record a hit and report whether it is over the limit.

    :return: None when allowed, otherwise seconds until the caller may retry
             (suitable for a Retry-After header).
    """
    limit, window = _LIMITS.get(kind, (ACTION_LIMIT, ACTION_WINDOW_SEC))
    addr = address or "?"
    now = _now()
    with _lock:
        bucket = _hits.setdefault(kind, {})
        _prune(bucket, window, now)
        if len(bucket) >= MAX_TRACKED and addr not in bucket:
            # Under a flood, protect the hub itself rather than tracking perfectly.
            oldest = min(bucket, key=lambda a: bucket[a][0] if bucket[a] else 0)
            bucket.pop(oldest, None)
        stamps = bucket.setdefault(addr, [])
        if len(stamps) >= limit:
            retry = int(window - (now - stamps[0])) + 1
            return max(1, retry)
        stamps.append(now)
        return None


def reset() -> None:
    """Clear all counters (tests)."""
    with _lock:
        for bucket in _hits.values():
            bucket.clear()


def stats() -> dict[str, int]:
    with _lock:
        return {k: len(v) for k, v in _hits.items()}
