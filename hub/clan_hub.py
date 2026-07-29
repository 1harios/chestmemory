#!/usr/bin/env python3
"""
Chest Memory — clan gather hub (persistent).

Sessions are persisted to disk (coalesced, at most every SAVE_COALESCE_SEC after a
change; creates and closes write immediately) and reloaded on start.
Survives process crash / VDS reboot (as long as the process is started again).

  DATA_DIR=./data PORT=18787 CLAN_TOKEN=secret python3 clan_hub.py

API: see README.md
"""

from __future__ import annotations

import hmac
import json
import os
import secrets
import signal
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import unquote

import clan_auth
import clan_ratelimit

PORT = int(os.environ.get("PORT", "8787"))
HOST = os.environ.get("HOST", "0.0.0.0")
#: Optional. Rate limiting (clan_ratelimit) is what protects session codes now, and
#: identity comes from Mojang (clan_auth), so a shared secret is no longer needed —
#: leave it empty and members only ever type a code. Set it if you additionally want
#: the hub invisible to anyone without it.
CLAN_TOKEN = os.environ.get("CLAN_TOKEN", "").strip()
#: When true (the default), every mutating request must carry a verified
#: X-Clan-Session. Defaulting to off shipped the impersonation hole to every operator
#: who never read this file: anyone could deliver, claim and leave as anyone else.
#: REQUIRE_AUTH=0 is the escape hatch for one mod-upgrade window (members still on the
#: old mod keep working) — choosing it makes the hub shout at startup that identities
#: are unverified, and host-only actions demand a verified session regardless.
REQUIRE_AUTH = os.environ.get("REQUIRE_AUTH", "").strip().lower() not in ("0", "false", "no")
SESSION_TTL_SEC = int(os.environ.get("SESSION_TTL_SEC", str(7 * 24 * 3600)))
#: A gather nobody ever joined is usually a test, an aborted attempt — or create-spam.
#: Letting each one sit for the full 7 days lets a scripted client accumulate them at
#: zero cost to itself; a solo session the host stopped heartbeating dies in a day.
SOLO_SESSION_TTL_SEC = int(os.environ.get("SOLO_SESSION_TTL_SEC", str(24 * 3600)))
#: A member's claims are released after this long without a heartbeat.
#:
#: The client polls every ~3s while the game is running, and polling refreshes
#: lastSeen — so this measures "client is gone" (quit, crash, lost connection), not
#: "player is idle". Someone mining in the Nether for an hour keeps their claims:
#: they changed dimension, not connection. 180s is 60 missed polls in a row, which
#: no ordinary network hiccup explains.
CLAIM_TIMEOUT_SEC = int(os.environ.get("CLAIM_TIMEOUT_SEC", "180"))
DATA_DIR = Path(os.environ.get("DATA_DIR", str(Path(__file__).resolve().parent / "data")))
SESSIONS_FILE = DATA_DIR / "sessions.json"
ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

# Hard cap on a single request body. The store lives in memory and is rewritten in
# full on every mutation, so unbounded input is both a RAM and a disk-fill vector.
MAX_BODY_BYTES = 512 * 1024

# Growth caps. The body cap above bounds one request; nothing bounded how many
# requests accumulate. POST /v1/sessions in a loop filled RAM and disk on the small
# VPS deploy_vps.sh targets — each session lived 7 days and every mutation rewrote
# the whole store.
MAX_SESSIONS_TOTAL = 256
#: A busy clan runs a handful of parallel gathers; dozens from one host is a script.
MAX_SESSIONS_PER_HOST = 4
MAX_MEMBERS_PER_SESSION = 64
MAX_MATERIALS_PER_SESSION = 400
#: stagingKeys grow by append from every member, so without a cap one scripted
#: member inflates the session (and the file it is rewritten into) without limit.
MAX_STAGING_KEYS_PER_SESSION = 256
#: Kicks are moderation, not a permanent ban list — past this, oldest entries fall
#: off rather than letting kick/rejoin cycles grow the session forever.
MAX_KICKED_TRACKED = 256
#: Real names and item ids ("minecraft:weathered_cut_copper_stairs") stay well under
#: these; anything longer is padding aimed at the store size.
MAX_NAME_LEN = 48
MAX_ITEM_ID_LEN = 128
#: Scanning every session on every GET/POST serialized all traffic behind the purge;
#: once a minute catches the same expiries at a fraction of the work.
PURGE_INTERVAL_SEC = 60
#: Mutations mark the store dirty and a background thread persists it, so a burst of
#: staging pushes costs one disk rewrite instead of one per request. A crash loses at
#: most this many seconds — deliver totals are absolute, so the next push heals them.
SAVE_COALESCE_SEC = 2


class _BodyTooLarge(Exception):
    """Raised by _read_json when Content-Length exceeds MAX_BODY_BYTES."""


_lock = threading.RLock()
_sessions: dict[str, dict[str, Any]] = {}
#: Store changed since the last write — see SAVE_COALESCE_SEC. Guarded by _lock.
_dirty = False
_last_purge_mono = 0.0


def _now() -> int:
    return int(time.time() * 1000)


def _gen_code() -> str:
    # secrets, not random: Mersenne Twister output is reconstructable from enough
    # observed codes, and the code is the only thing gating entry to a session.
    return "CM-" + "".join(secrets.choice(ALPHABET) for _ in range(4))


def _normalize_code(raw: str) -> str:
    s = (raw or "").strip().upper().replace(" ", "-")
    if len(s) == 4 and all(c.isalnum() for c in s):
        s = "CM-" + s
    if s.startswith("CM") and not s.startswith("CM-") and len(s) >= 6:
        s = "CM-" + s[2:]
    return s


def _ok_code(code: str) -> bool:
    return bool(code) and code.startswith("CM-") and len(code) == 7


def _ensure_data_dir() -> None:
    DATA_DIR.mkdir(parents=True, exist_ok=True)


def _load_sessions() -> None:
    global _sessions
    _ensure_data_dir()
    if not SESSIONS_FILE.is_file():
        _sessions = {}
        return
    try:
        with SESSIONS_FILE.open("r", encoding="utf-8") as f:
            raw = json.load(f)
        if isinstance(raw, dict) and "sessions" in raw and isinstance(raw["sessions"], dict):
            _sessions = raw["sessions"]
        elif isinstance(raw, dict):
            # plain map code -> session
            _sessions = {k: v for k, v in raw.items() if isinstance(v, dict)}
        else:
            _sessions = {}
        print("loaded %s sessions from %s" % (len(_sessions), SESSIONS_FILE))
    except Exception as e:
        print("load sessions failed: %s — starting empty" % e)
        _sessions = {}


def _save_sessions() -> None:
    """Atomic write so a crash mid-save does not corrupt the file."""
    _ensure_data_dir()
    payload = {
        "version": 1,
        "savedAt": _now(),
        "sessions": _sessions,
    }
    tmp = SESSIONS_FILE.with_suffix(".json.tmp")
    try:
        with tmp.open("w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False, indent=0, separators=(",", ":"))
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, SESSIONS_FILE)
    except Exception as e:
        print("save sessions failed: %s" % e)
        try:
            if tmp.is_file():
                tmp.unlink()
        except Exception:
            pass


def _mark_dirty() -> None:
    """Queue a save for the flush thread instead of writing inline. Call under _lock."""
    global _dirty
    _dirty = True


def _save_now() -> None:
    """Write the store immediately and clear the queued save. Call under _lock."""
    global _dirty
    _save_sessions()
    _dirty = False


def _flush_loop() -> None:
    """Persist coalesced changes — the price of a crash is SAVE_COALESCE_SEC, not data."""
    while True:
        time.sleep(SAVE_COALESCE_SEC)
        with _lock:
            if _dirty:
                _save_now()


def _purge_old(force: bool = False) -> None:
    """Drop expired sessions, at most once per PURGE_INTERVAL_SEC unless forced.

    This used to run in full on every GET and POST: every request paid for a scan of
    all sessions while holding the lock, which is pure waste between expiries.
    """
    global _last_purge_mono
    if not force and time.monotonic() - _last_purge_mono < PURGE_INTERVAL_SEC:
        return
    _last_purge_mono = time.monotonic()
    now = _now()
    dead: list[str] = []
    for c, s in _sessions.items():
        last = int(s.get("updatedAt", 0) or 0)
        ttl = SESSION_TTL_SEC
        members = s.get("members") or []
        if len(members) <= 1:
            # Solo sessions die on the short TTL — but heartbeats only refresh
            # lastSeen, not updatedAt, so count them or an idle-but-online host
            # would lose their gather a day after the last actual change.
            ttl = min(ttl, SOLO_SESSION_TTL_SEC)
            for m in members:
                last = max(last, int(m.get("lastSeen", 0) or 0))
        if last < now - ttl * 1000:
            dead.append(c)
    if not dead:
        return
    for c in dead:
        _sessions.pop(c, None)
    _mark_dirty()


def _release_stale_claims(sess: dict[str, Any]) -> list[str]:
    """
    Drop claims held by members whose client stopped talking to us.

    Without this a player who alt-F4'd kept their material reserved for the whole
    7-day session lifetime, and nobody else could pick it up.

    :return: names of members whose claims were released, for logging
    """
    cutoff = _now() - CLAIM_TIMEOUT_SEC * 1000
    stale: dict[str, str] = {}
    for m in sess.get("members") or []:
        uuid = str(m.get("uuid") or "")
        if uuid and int(m.get("lastSeen", 0) or 0) < cutoff:
            stale[uuid.lower()] = str(m.get("name") or "?")
    if not stale:
        return []
    released: list[str] = []
    for mat in (sess.get("materials") or {}).values():
        holder = str(mat.get("claimedBy") or "").lower()
        if holder and holder in stale:
            _clear_claim(mat)
            if stale[holder] not in released:
                released.append(stale[holder])
    # Keep the member listed but visibly stale; the roster shows who is away.
    return released


def _clear_claim(mat: dict[str, Any]) -> None:
    """
    Release one material's claim, every field of it.

    A claim is three fields now, not two: claimedAt joined claimedBy/claimedName so the
    client can order a member's claims by when they were taken. Five call sites release
    claims (stale sweep, unclaim, leave, kick, release_claims) and every one of them has
    to drop all three, or a released material keeps a timestamp that outlives its claim.
    """
    mat["claimedBy"] = None
    mat["claimedName"] = None
    mat["claimedAt"] = 0


def _touch(sess: dict[str, Any]) -> None:
    sess["updatedAt"] = _now()
    sess["revision"] = int(sess.get("revision", 0)) + 1


def _member_upsert(sess: dict[str, Any], name: str, uuid: str) -> bool:
    """
    :return: False when the roster is full and this uuid is not on it — join treats
             that as an error, heartbeat-ish callers just shrug.
    """
    # A kicked member must not drift back in through the heartbeat: their client keeps
    # polling until it notices the kick. Only an explicit join lifts the flag.
    kicked = sess.get("kicked") or []
    if uuid and uuid.lower() in {str(k).lower() for k in kicked}:
        return True
    name = (name or "")[:MAX_NAME_LEN]
    members = sess.setdefault("members", [])
    for m in members:
        if str(m.get("uuid", "")).lower() == uuid.lower():
            m["name"] = name or m.get("name", "")
            m["lastSeen"] = _now()
            return True
    if len(members) >= MAX_MEMBERS_PER_SESSION:
        return False
    members.append({"name": name or "?", "uuid": uuid, "lastSeen": _now()})
    return True


def _clean_staging_keys(keys_in: Any) -> list[str]:
    """Deduped, length-clamped staging keys; callers cap the resulting list size."""
    clean: list[str] = []
    if isinstance(keys_in, list):
        for k in keys_in:
            s = str(k).strip()[:MAX_ITEM_ID_LEN]
            if s and s not in clean:
                clean.append(s)
    return clean


class Handler(BaseHTTPRequestHandler):
    server_version = "ChestMemoryClanHub/3.0"

    def log_message(self, fmt: str, *args: Any) -> None:
        print("[%s] %s" % (self.log_date_time_string(), fmt % args))

    def _client_addr(self) -> str:
        """Client address, honouring a reverse proxy's X-Forwarded-For."""
        fwd = self.headers.get("X-Forwarded-For", "")
        if fwd:
            return fwd.split(",")[0].strip()
        return self.client_address[0] if self.client_address else "?"

    def _rate_ok(self, kind: str) -> bool:
        """Enforce the per-address limit; sends 429 itself when exceeded."""
        retry = clan_ratelimit.check(kind, self._client_addr())
        if retry is None:
            return True
        self.send_response(429)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Retry-After", str(retry))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(b'{"error":"rate limited"}')
        return False

    def _identity(self) -> dict[str, str] | None:
        """
        Verified player behind this request, or None when unauthenticated.

        Reads X-Clan-Session, which is only handed out after Mojang confirmed the
        account (see clan_auth). Never trusts a uuid from the request body.
        """
        return clan_auth.resolve(self.headers.get("X-Clan-Session", ""))

    def _hint_identity(self) -> dict[str, str] | None:
        """
        Unverified identity hint from headers, honoured only while REQUIRE_AUTH is off.

        Offline-mode launchers can never complete the Mojang handshake, so without this a
        plain poll carried no identity at all — lastSeen never moved, and the hub released
        the player's claims after CLAIM_TIMEOUT_SEC while they were actively online.
        """
        if REQUIRE_AUTH:
            return None
        uuid = (self.headers.get("X-Clan-Uuid", "") or "").strip()
        if not uuid:
            return None
        name = (self.headers.get("X-Clan-Name", "") or "?").strip() or "?"
        return {"uuid": uuid, "name": name}

    def _actor(self, body: dict[str, Any]) -> tuple[str, str]:
        """
        (uuid, name) to act as.

        Prefers the verified session. Falls back to the body only while
        REQUIRE_AUTH is off, so an existing clan can upgrade the hub before every
        member has updated the mod. Turn REQUIRE_AUTH on once they have.
        """
        who = self._identity() or self._hint_identity()
        if who is not None:
            return who["uuid"], who["name"]
        return (
            str(body.get("uuid") or body.get("hostUuid") or ""),
            str(body.get("name") or body.get("hostName") or "?"),
        )

    def _send_session(self, sess: dict[str, Any]) -> None:
        """Session snapshot + the hub's clock, so clients can judge staleness without
        trusting their own wall clock to agree with ours."""
        payload = dict(sess)
        payload["now"] = _now()
        self._send(200, payload)

    def _check_token(self) -> bool:
        if not CLAN_TOKEN:
            return True
        got = self.headers.get("X-Clan-Token", "")
        # compare_digest, not ==: plain equality returns on the first wrong byte,
        # which lets a patient prober time their way through the token.
        return hmac.compare_digest(got.encode("utf-8", "replace"), CLAN_TOKEN.encode("utf-8"))

    def _send(self, code: int, obj: Any) -> None:
        data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, X-Clan-Token, X-Clan-Session")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.end_headers()
        self.wfile.write(data)

    def _read_json(self) -> dict[str, Any]:
        try:
            n = int(self.headers.get("Content-Length", "0") or "0")
        except ValueError:
            return {}
        # Refuse oversized bodies outright: the whole session store is held in memory and
        # rewritten to disk on every mutation, so an unbounded POST is an easy way to
        # exhaust RAM or fill the disk.
        if n > MAX_BODY_BYTES:
            raise _BodyTooLarge(n)
        raw = self.rfile.read(n) if n > 0 else b"{}"
        try:
            o = json.loads(raw.decode("utf-8") or "{}")
            return o if isinstance(o, dict) else {}
        except Exception:
            return {}

    def do_OPTIONS(self) -> None:  # noqa: N802
        self._send(204, {})

    def do_GET(self) -> None:  # noqa: N802
        if not self._check_token():
            self._send(401, {"error": "bad token"})
            return
        path = unquote(self.path.split("?", 1)[0])
        if path == "/v1/auth/challenge":
            if not self._rate_ok("auth"):
                return
            nonce = clan_auth.new_challenge()
            if nonce is None:
                self._send(503, {"error": "auth busy"})
                return
            self._send(200, {"nonce": nonce})
            return
        if path in ("/", "/v1/health"):
            with _lock:
                n = len(_sessions)
            self._send(200, {
                "ok": True,
                "sessions": n,
                "version": 3,
                "persistent": True,
                "dataFile": str(SESSIONS_FILE),
            })
            return
        if path.startswith("/v1/sessions/"):
            # Guessing codes happens here, so this is the tight bucket.
            if not self._rate_ok("lookup"):
                return
            query = self.path.split("?", 1)[1] if "?" in self.path else ""
            since = 0
            for part in query.split("&"):
                if part.startswith("since="):
                    try:
                        since = int(part[len("since="):])
                    except ValueError:
                        since = 0
            code = _normalize_code(path[len("/v1/sessions/") :].split("/")[0])
            with _lock:
                _purge_old()
                sess = _sessions.get(code)
                if not sess:
                    self._send(404, {"error": "not found"})
                    return
                # The poll IS the heartbeat. Verified identity first; the header hint
                # covers offline-mode clients while REQUIRE_AUTH is off — without it
                # their lastSeen never moved and their claims timed out mid-game.
                who = self._identity() or self._hint_identity()
                changed = False
                if who is not None:
                    _member_upsert(sess, who["name"], who["uuid"])
                    changed = True
                released = _release_stale_claims(sess)
                if released:
                    print(
                        "released claims of %s in %s (no heartbeat for %ss)"
                        % (", ".join(released), code, CLAIM_TIMEOUT_SEC)
                    )
                    _touch(sess)
                    changed = True
                if changed:
                    # Heartbeats land every ~3s per member; rewriting the whole store to
                    # disk for each would grind. lastSeen precision on restart is worth
                    # seconds, not fsyncs — persist it opportunistically with real changes.
                    if released:
                        _mark_dirty()
                # since-poll: nothing the client does not already have — answer a stub.
                if since > 0 and int(sess.get("revision", 0)) == since:
                    # The stub still carries the heartbeat. A poll refreshes every
                    # member's lastSeen but deliberately does not bump the revision —
                    # bumping it would defeat since-polling entirely — so a quiet gather
                    # answers this stub forever. Without the seen map below the client
                    # measured its away timer against a lastSeen frozen at the last real
                    # change and flipped everybody to "offline" three minutes later,
                    # while they were standing right there collecting.
                    self._send(200, {
                        "code": sess.get("code"),
                        "revision": since,
                        "unchanged": True,
                        "now": _now(),
                        "seen": {
                            str(m.get("uuid")): int(m.get("lastSeen", 0) or 0)
                            for m in (sess.get("members") or [])
                            if m.get("uuid")
                        },
                    })
                    return
                self._send_session(sess)
            return
        self._send(404, {"error": "not found"})

    def do_POST(self) -> None:  # noqa: N802
        if not self._check_token():
            self._send(401, {"error": "bad token"})
            return
        path = unquote(self.path.split("?", 1)[0])
        try:
            body = self._read_json()
        except _BodyTooLarge as e:
            self._send(413, {"error": f"body too large ({e.args[0]} bytes, max {MAX_BODY_BYTES})"})
            return

        if path == "/v1/auth/verify":
            if not self._rate_ok("auth"):
                return
            result = clan_auth.verify(str(body.get("name") or ""), str(body.get("nonce") or ""))
            if result is None:
                self._send(401, {"error": "auth failed"})
                return
            self._send(200, result)
            return

        # Joining by code is a guessing surface like the GET above; everything else
        # is a member acting inside a session they already have, so it gets the
        # generous bucket (polling is legitimate and frequent).
        if not self._rate_ok("lookup" if path.endswith("/join") else "action"):
            return

        # Mutating endpoints act on behalf of a player, so they need a verified one.
        if REQUIRE_AUTH and self._identity() is None:
            self._send(401, {"error": "auth required"})
            return

        if path == "/v1/sessions":
            self._create(body)
            return

        if path.startswith("/v1/sessions/"):
            rest = path[len("/v1/sessions/") :]
            parts = [p for p in rest.split("/") if p]
            if not parts:
                self._send(404, {"error": "not found"})
                return
            code = _normalize_code(parts[0])
            action = parts[1] if len(parts) > 1 else ""
            if action == "join":
                self._join(code, body)
            elif action == "claim":
                self._claim(code, body)
            elif action == "deliver":
                self._deliver(code, body)
            elif action == "staging":
                self._staging(code, body)
            elif action == "leave":
                self._leave(code, body)
            elif action == "close":
                self._close(code)
            elif action == "update":
                self._update(code, body)
            elif action == "kick":
                self._kick(code, body)
            elif action == "release_claims":
                self._release_claims(code, body)
            elif action == "exclude":
                self._exclude(code, body)
            else:
                self._send(404, {"error": "not found"})
            return

        self._send(404, {"error": "not found"})

    def _create(self, body: dict[str, Any]) -> None:
        materials_in = body.get("materials") or {}
        if not isinstance(materials_in, dict) or not materials_in:
            self._send(400, {"error": "materials required"})
            return
        # NOT _actor(body): in the create body "name" is the BUILD's name, so the generic
        # fallback registered the host in the roster as "Castle" instead of their nick.
        who = self._identity() or self._hint_identity()
        if who is not None:
            host_uuid, host_name = who["uuid"], who["name"]
        else:
            host_uuid = str(body.get("hostUuid") or body.get("uuid") or "")
            host_name = str(body.get("hostName") or "Host")
        if not host_uuid:
            self._send(400, {"error": "hostUuid required"})
            return
        host_name = host_name[:MAX_NAME_LEN]
        name = str(body.get("name") or "Build")[:MAX_NAME_LEN]
        schema = str(body.get("schemaName") or name)[:MAX_NAME_LEN]
        materials: dict[str, Any] = {}
        for k, v in materials_in.items():
            try:
                need = int(v)
            except Exception:
                continue
            if need <= 0:
                continue
            key = str(k)
            if len(key) > MAX_ITEM_ID_LEN:
                continue
            materials[key] = {
                "need": need,
                "delivered": 0,
                "claimedBy": None,
                "claimedName": None,
                "claimedAt": 0,
                "excluded": False,
            }
        if not materials:
            self._send(400, {"error": "empty materials"})
            return
        if len(materials) > MAX_MATERIALS_PER_SESSION:
            self._send(400, {"error": "too many materials (max %d)" % MAX_MATERIALS_PER_SESSION})
            return
        staging = _clean_staging_keys(body.get("stagingKeys"))
        if len(staging) > MAX_STAGING_KEYS_PER_SESSION:
            self._send(400, {"error": "too many staging keys (max %d)" % MAX_STAGING_KEYS_PER_SESSION})
            return

        with _lock:
            _purge_old()
            # Creation is the one call that grows the store without knowing a code, so
            # the growth caps live here: a global one for the hub's own survival and a
            # per-host one so a single scripted client cannot eat the global budget.
            if len(_sessions) >= MAX_SESSIONS_TOTAL:
                self._send(503, {"error": "session limit reached"})
                return
            mine = sum(
                1 for s in _sessions.values()
                if str(s.get("hostUuid") or "").lower() == host_uuid.lower()
            )
            if mine >= MAX_SESSIONS_PER_HOST:
                self._send(
                    429,
                    {"error": "too many open sessions for this host (max %d)" % MAX_SESSIONS_PER_HOST},
                )
                return
            for _ in range(40):
                code = _gen_code()
                if code not in _sessions:
                    break
            else:
                self._send(500, {"error": "code exhausted"})
                return
            sess = {
                "code": code,
                "name": name,
                "schemaName": schema,
                "hostName": host_name,
                "hostUuid": host_uuid,
                "createdAt": _now(),
                "updatedAt": _now(),
                "revision": 1,
                "members": [
                    {"name": host_name, "uuid": host_uuid, "lastSeen": _now()}
                ],
                "materials": materials,
                "stagingKeys": staging,
            }
            _sessions[code] = sess
            # Written immediately, not coalesced: the host reads the code off their
            # screen and shares it — it must survive a crash in the same breath.
            _save_now()
            self._send_session(sess)

    def _join(self, code: str, body: dict[str, Any]) -> None:
        if not _ok_code(code):
            self._send(400, {"error": "bad code"})
            return
        uuid, actor_name = self._actor(body)
        # Display name comes from the verified identity too, otherwise a member could
        # show up in the roster under someone else's name.
        name = actor_name if actor_name != "?" else str(body.get("name") or "?")
        if not uuid:
            self._send(400, {"error": "uuid required"})
            return
        with _lock:
            sess = _sessions.get(code)
            if not sess:
                self._send(404, {"error": "not found"})
                return
            kicked = sess.get("kicked") or []
            if kicked:
                sess["kicked"] = [
                    k for k in kicked if str(k).lower() != uuid.lower()
                ]
            if not _member_upsert(sess, name, uuid):
                self._send(409, {"error": "session full (max %d members)" % MAX_MEMBERS_PER_SESSION})
                return
            _touch(sess)
            _mark_dirty()
            self._send_session(sess)

    def _claim(self, code: str, body: dict[str, Any]) -> None:
        uuid, actor_name = self._actor(body)
        # Display name comes from the verified identity too, otherwise a member could
        # show up in the roster under someone else's name.
        name = actor_name if actor_name != "?" else str(body.get("name") or "?")
        item = str(body.get("itemId") or "")
        unclaim = bool(body.get("unclaim"))
        if not uuid or not item:
            self._send(400, {"error": "itemId/uuid required"})
            return
        with _lock:
            sess = _sessions.get(code)
            if not sess:
                self._send(404, {"error": "not found"})
                return
            mats = sess.get("materials") or {}
            m = mats.get(item)
            if not m:
                self._send(404, {"error": "unknown item"})
                return
            cur = m.get("claimedBy")
            if unclaim:
                if cur and str(cur).lower() == uuid.lower():
                    _clear_claim(m)
            else:
                if m.get("excluded"):
                    # The host struck this material off the gather; claiming it would
                    # put a member to work on something nobody is collecting.
                    self._send(409, {"error": "item excluded from this gather"})
                    return
                if cur and str(cur).lower() != uuid.lower():
                    self._send(
                        409,
                        {"error": "already claimed by " + str(m.get("claimedName") or cur)},
                    )
                    return
                m["claimedBy"] = uuid
                m["claimedName"] = name
                # When the claim was taken, in hub time. The client shows "who is
                # carrying what" from this: a member holding glass and stone is working
                # the one they clicked first, and every client agrees on which that was.
                m["claimedAt"] = _now()
            _member_upsert(sess, name, uuid)
            _touch(sess)
            _mark_dirty()
            self._send_session(sess)

    def _deliver(self, code: str, body: dict[str, Any]) -> None:
        uuid, actor_name = self._actor(body)
        # Display name comes from the verified identity too, otherwise a member could
        # show up in the roster under someone else's name.
        name = actor_name if actor_name != "?" else str(body.get("name") or "?")
        # Two shapes: single {"itemId", "amount"} and batch {"amounts": {item: n}}.
        # The batch is what the periodic warehouse push sends — one request instead of
        # one per material.
        amounts: dict[str, int] = {}
        raw_amounts = body.get("amounts")
        if isinstance(raw_amounts, dict):
            for k, v in raw_amounts.items():
                try:
                    n = int(v)
                except Exception:
                    continue
                if n > 0:
                    amounts[str(k)] = n
        else:
            item = str(body.get("itemId") or "")
            try:
                amount = int(body.get("amount") or 0)
            except Exception:
                amount = 0
            if item and amount > 0:
                amounts[item] = amount
        if not uuid or not amounts:
            self._send(400, {"error": "itemId/amount/uuid required"})
            return
        with _lock:
            sess = _sessions.get(code)
            if not sess:
                self._send(404, {"error": "not found"})
                return
            mats = sess.get("materials") or {}
            known = 0
            changed = False
            for item, amount in amounts.items():
                m = mats.get(item)
                if not m:
                    continue
                known += 1
                new_delivered = min(
                    int(m.get("need", 0)),
                    max(int(m.get("delivered", 0)), amount),
                )
                if new_delivered != int(m.get("delivered", 0)):
                    m["delivered"] = new_delivered
                    # Remember who actually raised the count — the client's activity
                    # feed used to guess the claim holder, which is often not the
                    # person who carried the items in.
                    m["lastDeliveredBy"] = name
                    m["lastDeliveredAt"] = _now()
                    changed = True
            if known == 0:
                self._send(404, {"error": "unknown item"})
                return
            _member_upsert(sess, name, uuid)
            if changed:
                _touch(sess)
            _mark_dirty()
            self._send_session(sess)

    def _staging(self, code: str, body: dict[str, Any]) -> None:
        uuid, actor_name = self._actor(body)
        # Display name comes from the verified identity too, otherwise a member could
        # show up in the roster under someone else's name.
        name = actor_name if actor_name != "?" else str(body.get("name") or "?")
        keys_in = body.get("stagingKeys") or []
        replace = bool(body.get("replace"))
        if not uuid:
            self._send(400, {"error": "uuid required"})
            return
        if not isinstance(keys_in, list):
            self._send(400, {"error": "stagingKeys must be list"})
            return
        clean = _clean_staging_keys(keys_in)
        with _lock:
            sess = _sessions.get(code)
            if not sess:
                self._send(404, {"error": "not found"})
                return
            if replace:
                merged = clean
            else:
                merged = list(sess.get("stagingKeys") or [])
                for k in clean:
                    if k not in merged:
                        merged.append(k)
            if len(merged) > MAX_STAGING_KEYS_PER_SESSION:
                self._send(400, {"error": "too many staging keys (max %d)" % MAX_STAGING_KEYS_PER_SESSION})
                return
            sess["stagingKeys"] = merged
            _member_upsert(sess, name, uuid)
            _touch(sess)
            _mark_dirty()
            self._send_session(sess)

    def _leave(self, code: str, body: dict[str, Any]) -> None:
        uuid, actor_name = self._actor(body)
        with _lock:
            sess = _sessions.get(code)
            if not sess:
                self._send(404, {"error": "not found"})
                return
            for m in (sess.get("materials") or {}).values():
                if str(m.get("claimedBy") or "").lower() == uuid.lower():
                    _clear_claim(m)
            members = sess.get("members") or []
            sess["members"] = [
                m
                for m in members
                if str(m.get("uuid") or "").lower() != uuid.lower()
            ]
            _touch(sess)
            _mark_dirty()
            self._send_session(sess)

    def _host_session(self, code: str, deny: str = "only host") -> dict[str, Any] | None:
        """Session for a host-only action, or None after an error reply.

        Call under _lock. The same absent-uuid hole _close had applies to every
        host action, so the check lives in one place.

        Only the verified identity counts here, even while REQUIRE_AUTH is off. The
        compat fallbacks (X-Clan-Uuid header, body uuid) are attacker-chosen, and the
        host's uuid is public in every session snapshot — honouring them meant anyone
        could rename the gather, empty the roster or close the session by pasting
        that uuid into a request.
        """
        who = self._identity()
        if who is None:
            self._send(403, {"error": "host actions require verified identity"})
            return None
        sess = _sessions.get(code)
        if not sess:
            self._send(404, {"error": "not found"})
            return None
        if str(sess.get("hostUuid") or "").lower() != who["uuid"].lower():
            self._send(403, {"error": deny})
            return None
        return sess

    def _update(self, code: str, body: dict[str, Any]) -> None:
        """Rename the gather (host only). The name every member sees on their panel."""
        raw = str(body.get("name") or "").strip()
        if not raw:
            self._send(400, {"error": "name required"})
            return
        name = raw[:MAX_NAME_LEN]
        with _lock:
            sess = self._host_session(code)
            if sess is None:
                return
            sess["name"] = name
            sess["schemaName"] = name
            _touch(sess)
            _mark_dirty()
            self._send_session(sess)

    def _kick(self, code: str, body: dict[str, Any]) -> None:
        """Remove a member (host only): claims released, roster row gone, and the
        heartbeat cannot re-add them — only a fresh join by code can."""
        target = str(body.get("target") or "").strip().lower()
        if not target:
            self._send(400, {"error": "target required"})
            return
        with _lock:
            sess = self._host_session(code)
            if sess is None:
                return
            if target == str(sess.get("hostUuid") or "").lower():
                self._send(400, {"error": "host cannot kick self"})
                return
            members = sess.get("members") or []
            kept = [
                m for m in members
                if str(m.get("uuid") or "").lower() != target
            ]
            if len(kept) == len(members):
                self._send(404, {"error": "no such member"})
                return
            sess["members"] = kept
            for m in (sess.get("materials") or {}).values():
                if str(m.get("claimedBy") or "").lower() == target:
                    _clear_claim(m)
            kicked = sess.setdefault("kicked", [])
            if target not in {str(k).lower() for k in kicked}:
                kicked.append(target)
                # Oldest entries fall off past the cap — see MAX_KICKED_TRACKED.
                del kicked[:-MAX_KICKED_TRACKED]
            _touch(sess)
            _mark_dirty()
            self._send_session(sess)

    def _release_claims(self, code: str, body: dict[str, Any]) -> None:
        """Clear every claim (host only) — the reset button for a stalled evening."""
        with _lock:
            sess = self._host_session(code)
            if sess is None:
                return
            for m in (sess.get("materials") or {}).values():
                if m.get("claimedBy"):
                    _clear_claim(m)
            _touch(sess)
            _mark_dirty()
            self._send_session(sess)

    def _exclude(self, code: str, body: dict[str, Any]) -> None:
        """
        Strike materials off the gather, or put them back (host only).

        The host opened the schematic, so the host is the one who knows the shell is
        already built and nobody should be hauling 40k stone for it. Excluded materials
        stay in the session — their delivered history is real and must not be rewritten —
        they are just marked, and every client greys them out and stops counting them
        toward progress.

        Body is either {"itemId": id, "excluded": bool} or, for a bulk edit,
        {"items": {id: bool, ...}}. Verified identity is required and must match
        hostUuid: _host_session enforces both, exactly as kick and release_claims do.
        """
        updates: dict[str, bool] = {}
        raw_items = body.get("items")
        if isinstance(raw_items, dict):
            for k, v in raw_items.items():
                key = str(k)[:MAX_ITEM_ID_LEN]
                if key:
                    updates[key] = bool(v)
        else:
            item = str(body.get("itemId") or "").strip()[:MAX_ITEM_ID_LEN]
            if not item:
                self._send(400, {"error": "itemId required"})
                return
            # Absent "excluded" means exclude: a bare {"itemId"} reads as "drop this one".
            updates[item] = bool(body.get("excluded", True))
        if not updates:
            self._send(400, {"error": "no items"})
            return
        if len(updates) > MAX_MATERIALS_PER_SESSION:
            self._send(400, {"error": "too many items"})
            return
        with _lock:
            sess = self._host_session(code, "only the gather host can exclude items")
            if sess is None:
                return
            mats = sess.get("materials") or {}
            unknown = [k for k in updates if k not in mats]
            if unknown:
                self._send(404, {"error": "unknown item " + unknown[0]})
                return
            changed = False
            for key, flag in updates.items():
                mat = mats[key]
                if bool(mat.get("excluded")) == flag:
                    continue
                mat["excluded"] = flag
                if flag:
                    # Whoever was on it is off it. Leaving the claim would show a member
                    # carrying a material the gather no longer wants.
                    _clear_claim(mat)
                changed = True
            if changed:
                _touch(sess)
                _mark_dirty()
            self._send_session(sess)

    def _close(self, code: str) -> None:
        with _lock:
            # An absent uuid used to skip the host check entirely and fall through to
            # the delete below, letting anyone drop any session by simply omitting it —
            # and later, a spoofed one could impersonate the host. _host_session now
            # owns both checks for every host action.
            sess = self._host_session(code, deny="only host can close")
            if sess is None:
                return
            _sessions.pop(code, None)
            # Written immediately: a coalesce-window crash must not resurrect a gather
            # the host just ended for everyone.
            _save_now()
            self._send(200, {"ok": True, "code": code})


def _sigterm(signum: int, frame: Any) -> None:
    # systemd stops with SIGTERM; without this, the last SAVE_COALESCE_SEC of
    # mutations died with the process on every `systemctl restart`.
    raise KeyboardInterrupt


def main() -> None:
    _load_sessions()
    with _lock:
        _purge_old(force=True)
    if not REQUIRE_AUTH:
        # flush: hub.log / journald get stdout through a pipe, which Python
        # block-buffers — unflushed, this warning surfaces hours late or never.
        print(
            "!!!! REQUIRE_AUTH is OFF — player identity is UNVERIFIED.\n"
            "!!!! Any request may claim any uuid: members can be impersonated (claims\n"
            "!!!! released, deliveries forged in their name). Host-only actions still\n"
            "!!!! demand a Mojang-verified session and will fail for offline-mode hosts.\n"
            "!!!! This mode exists for one mod-upgrade window — unset REQUIRE_AUTH=0\n"
            "!!!! as soon as every member runs the updated mod.",
            flush=True,
        )
    threading.Thread(target=_flush_loop, name="clanhub-save", daemon=True).start()
    signal.signal(signal.SIGTERM, _sigterm)
    httpd = ThreadingHTTPServer((HOST, PORT), Handler)
    print(
        "Chest Memory clan hub v3 persistent on http://%s:%s  token=%s  auth=%s  data=%s  sessions=%s"
        % (
            HOST,
            PORT,
            "yes" if CLAN_TOKEN else "no",
            "required" if REQUIRE_AUTH else "OFF",
            SESSIONS_FILE,
            len(_sessions),
        ),
        flush=True,
    )
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("stop — saving…")
        with _lock:
            _save_now()


if __name__ == "__main__":
    main()
