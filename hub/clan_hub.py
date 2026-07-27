#!/usr/bin/env python3
"""
Chest Memory — clan gather hub (persistent).

Sessions are saved to disk after every change and reloaded on start.
Survives process crash / VDS reboot (as long as the process is started again).

  DATA_DIR=./data PORT=18787 CLAN_TOKEN=secret python3 clan_hub.py

API: see README.md
"""

from __future__ import annotations

import json
import os
import random
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
#: When true, every mutating request must carry a verified X-Clan-Session.
#: Leave off for one upgrade window so members on the old mod keep working, then
#: turn it on — that is what actually closes the impersonation hole.
REQUIRE_AUTH = os.environ.get("REQUIRE_AUTH", "").strip().lower() in ("1", "true", "yes")
SESSION_TTL_SEC = int(os.environ.get("SESSION_TTL_SEC", str(7 * 24 * 3600)))
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


class _BodyTooLarge(Exception):
    """Raised by _read_json when Content-Length exceeds MAX_BODY_BYTES."""


_lock = threading.RLock()
_sessions: dict[str, dict[str, Any]] = {}


def _now() -> int:
    return int(time.time() * 1000)


def _gen_code() -> str:
    return "CM-" + "".join(random.choice(ALPHABET) for _ in range(4))


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


def _purge_old() -> None:
    cutoff = _now() - SESSION_TTL_SEC * 1000
    dead = [c for c, s in _sessions.items() if int(s.get("updatedAt", 0) or 0) < cutoff]
    if not dead:
        return
    for c in dead:
        _sessions.pop(c, None)
    _save_sessions()


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
            mat["claimedBy"] = None
            mat["claimedName"] = None
            if stale[holder] not in released:
                released.append(stale[holder])
    # Keep the member listed but visibly stale; the roster shows who is away.
    return released


def _touch(sess: dict[str, Any]) -> None:
    sess["updatedAt"] = _now()
    sess["revision"] = int(sess.get("revision", 0)) + 1


def _member_upsert(sess: dict[str, Any], name: str, uuid: str) -> None:
    members = sess.setdefault("members", [])
    for m in members:
        if str(m.get("uuid", "")).lower() == uuid.lower():
            m["name"] = name or m.get("name", "")
            m["lastSeen"] = _now()
            return
    members.append({"name": name or "?", "uuid": uuid, "lastSeen": _now()})


class Handler(BaseHTTPRequestHandler):
    server_version = "ChestMemoryClanHub/2.0"

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
        return got == CLAN_TOKEN

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
                "version": 2,
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
                        _save_sessions()
                # since-poll: nothing the client does not already have — answer a stub.
                if since > 0 and int(sess.get("revision", 0)) == since:
                    self._send(200, {
                        "code": sess.get("code"),
                        "revision": since,
                        "unchanged": True,
                        "now": _now(),
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
                self._close(code, body)
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
        name = str(body.get("name") or "Build")
        schema = str(body.get("schemaName") or name)
        materials: dict[str, Any] = {}
        for k, v in materials_in.items():
            try:
                need = int(v)
            except Exception:
                continue
            if need <= 0:
                continue
            materials[str(k)] = {
                "need": need,
                "delivered": 0,
                "claimedBy": None,
                "claimedName": None,
            }
        if not materials:
            self._send(400, {"error": "empty materials"})
            return

        with _lock:
            _purge_old()
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
                "stagingKeys": list(body.get("stagingKeys") or [])
                if isinstance(body.get("stagingKeys"), list)
                else [],
            }
            _sessions[code] = sess
            _save_sessions()
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
            _member_upsert(sess, name, uuid)
            _touch(sess)
            _save_sessions()
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
                    m["claimedBy"] = None
                    m["claimedName"] = None
            else:
                if cur and str(cur).lower() != uuid.lower():
                    self._send(
                        409,
                        {"error": "already claimed by " + str(m.get("claimedName") or cur)},
                    )
                    return
                m["claimedBy"] = uuid
                m["claimedName"] = name
            _member_upsert(sess, name, uuid)
            _touch(sess)
            _save_sessions()
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
            _save_sessions()
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
        clean: list[str] = []
        for k in keys_in:
            s = str(k).strip()
            if s and s not in clean:
                clean.append(s)
        with _lock:
            sess = _sessions.get(code)
            if not sess:
                self._send(404, {"error": "not found"})
                return
            if replace:
                sess["stagingKeys"] = clean
            else:
                cur = list(sess.get("stagingKeys") or [])
                for k in clean:
                    if k not in cur:
                        cur.append(k)
                sess["stagingKeys"] = cur
            _member_upsert(sess, name, uuid)
            _touch(sess)
            _save_sessions()
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
                    m["claimedBy"] = None
                    m["claimedName"] = None
            members = sess.get("members") or []
            sess["members"] = [
                m
                for m in members
                if str(m.get("uuid") or "").lower() != uuid.lower()
            ]
            _touch(sess)
            _save_sessions()
            self._send_session(sess)

    def _close(self, code: str, body: dict[str, Any]) -> None:
        uuid, actor_name = self._actor(body)
        with _lock:
            sess = _sessions.get(code)
            if not sess:
                self._send(404, {"error": "not found"})
                return
            # An absent uuid used to skip the host check entirely and fall through to
            # the delete below, letting anyone drop any session by simply omitting it.
            if not uuid:
                self._send(403, {"error": "only host can close"})
                return
            if str(sess.get("hostUuid") or "").lower() != uuid.lower():
                self._send(403, {"error": "only host can close"})
                return
            _sessions.pop(code, None)
            _save_sessions()
            self._send(200, {"ok": True, "code": code})


def main() -> None:
    _load_sessions()
    with _lock:
        _purge_old()
    httpd = ThreadingHTTPServer((HOST, PORT), Handler)
    print(
        "Chest Memory clan hub v2 persistent on http://%s:%s  token=%s  data=%s  sessions=%s"
        % (HOST, PORT, "yes" if CLAN_TOKEN else "no", SESSIONS_FILE, len(_sessions))
    )
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("stop — saving…")
        with _lock:
            _save_sessions()


if __name__ == "__main__":
    main()
