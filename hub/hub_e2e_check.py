#!/usr/bin/env python3
"""
End-to-end check of the hub changes: claim timestamps, the heartbeat stub, and host-only
exclusion. Run from the repo root:

    python3 hub/hub_e2e_check.py

Drives a real clan_hub over real HTTP with REQUIRE_AUTH on, so the host check exercised
here is the same one production uses. Verified identities are injected straight into
clan_auth's session table — the one thing that cannot be reproduced locally is a Mojang
handshake. Not a unit test: it is the "does the wire actually behave" check, and it
prints its own report.
"""
from __future__ import annotations

import json
import os
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

PORT = 18799
DATA = tempfile.mkdtemp(prefix="hubcheck-")
os.environ.update(
    DATA_DIR=DATA, PORT=str(PORT), REQUIRE_AUTH="1", CLAN_TOKEN="",
    RATE_LIMIT="0", CLAN_RATE_LIMIT="0",
)
sys.path.insert(0, str(Path(__file__).resolve().parent))

import clan_auth  # noqa: E402
import clan_hub  # noqa: E402

HOST_ID = {"uuid": "11111111-1111-1111-1111-111111111111", "name": "HostPlayer"}
MEMBER_ID = {"uuid": "22222222-2222-2222-2222-222222222222", "name": "Digger"}
with clan_auth._lock:  # noqa: SLF001 — injecting what Mojang would have granted
    for token, who in (("tok-host", HOST_ID), ("tok-member", MEMBER_ID)):
        clan_auth._sessions[token] = {  # noqa: SLF001
            "uuid": who["uuid"], "name": who["name"],
            "expires": clan_auth._now() + 3600,  # noqa: SLF001
        }

BASE = f"http://127.0.0.1:{PORT}"
failures: list[str] = []


def check(label: str, ok: bool, detail: str = "") -> None:
    print(("  PASS  " if ok else "  FAIL  ") + label + (f" — {detail}" if detail else ""))
    if not ok:
        failures.append(label)


def call(method: str, path: str, token: str | None = None, body: dict | None = None):
    req = urllib.request.Request(
        BASE + path, method=method,
        data=None if body is None else json.dumps(body).encode(),
    )
    if body is not None:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("X-Clan-Session", token)
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status, json.loads(resp.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode() or "{}")


srv = clan_hub.ThreadingHTTPServer(("127.0.0.1", PORT), clan_hub.Handler)
threading.Thread(target=srv.serve_forever, daemon=True).start()
time.sleep(0.4)

try:
    print("\n== claim timestamps (bug 1) ==")
    st, sess = call("POST", "/v1/sessions", "tok-host", {
        "name": "Test", "schemaName": "Test",
        "materials": {"minecraft:glass": 128, "minecraft:stone": 640},
    })
    check("host creates a gather", st == 200, f"HTTP {st}")
    code = sess.get("code", "")
    mats = sess.get("materials", {})
    check(
        "new materials carry claimedAt and excluded",
        all("claimedAt" in m and "excluded" in m for m in mats.values()),
        str(list(mats.values())[:1]),
    )

    call("POST", f"/v1/sessions/{code}/join", "tok-member")
    st, s1 = call("POST", f"/v1/sessions/{code}/claim", "tok-member",
                  {"itemId": "minecraft:glass"})
    glass_at = s1["materials"]["minecraft:glass"]["claimedAt"]
    time.sleep(0.05)
    st, s2 = call("POST", f"/v1/sessions/{code}/claim", "tok-member",
                  {"itemId": "minecraft:stone"})
    stone_at = s2["materials"]["minecraft:stone"]["claimedAt"]
    check("first claim is stamped", glass_at > 0, f"claimedAt={glass_at}")
    check(
        "glass (clicked first) sorts before stone",
        glass_at < stone_at,
        f"glass={glass_at} stone={stone_at}",
    )

    st, s3 = call("POST", f"/v1/sessions/{code}/claim", "tok-member",
                  {"itemId": "minecraft:stone", "unclaim": True})
    check(
        "unclaim clears the timestamp too",
        s3["materials"]["minecraft:stone"]["claimedAt"] == 0
        and s3["materials"]["minecraft:stone"]["claimedBy"] is None,
    )

    print("\n== heartbeat stub (bug 2) ==")
    rev = s3["revision"]
    st, stub = call("GET", f"/v1/sessions/{code}?since={rev}", "tok-member")
    check("quiet poll answers the stub", stub.get("unchanged") is True, json.dumps(stub)[:90])
    check("stub carries the hub clock", int(stub.get("now", 0)) > 0)
    seen = stub.get("seen") or {}
    check(
        "stub carries every member's lastSeen",
        len(seen) == 2 and all(int(v) > 0 for v in seen.values()),
        json.dumps(seen),
    )
    before = int(seen.get(MEMBER_ID["uuid"], 0))
    time.sleep(1.1)
    _, stub2 = call("GET", f"/v1/sessions/{code}?since={rev}", "tok-member")
    after = int((stub2.get("seen") or {}).get(MEMBER_ID["uuid"], 0))
    check(
        "polling moves the poller's lastSeen forward",
        after > before,
        f"{before} -> {after}",
    )
    check("the stub did not bump the revision", int(stub2.get("revision", 0)) == rev)

    print("\n== host-only exclusion (bug 5) ==")
    st, denied = call("POST", f"/v1/sessions/{code}/exclude", "tok-member",
                      {"itemId": "minecraft:stone", "excluded": True})
    check("a member cannot exclude", st == 403, f"HTTP {st} {denied.get('error')}")
    st, anon = call("POST", f"/v1/sessions/{code}/exclude", None,
                    {"itemId": "minecraft:stone", "excluded": True})
    # 401 from the blanket REQUIRE_AUTH gate on mutations, 403 from the host check itself
    # once identity exists — either is a refusal, and which one fires depends on the
    # operator's REQUIRE_AUTH setting.
    check("an unverified caller cannot exclude", st in (401, 403), f"HTTP {st}")

    st, ex = call("POST", f"/v1/sessions/{code}/exclude", "tok-host",
                  {"itemId": "minecraft:glass", "excluded": True})
    check("the host can exclude", st == 200 and ex["materials"]["minecraft:glass"]["excluded"])
    check(
        "excluding releases the claim on it",
        ex["materials"]["minecraft:glass"]["claimedBy"] is None
        and ex["materials"]["minecraft:glass"]["claimedAt"] == 0,
    )
    check("excluding bumps the revision", int(ex["revision"]) > rev)
    check(
        "delivered history survives exclusion",
        "delivered" in ex["materials"]["minecraft:glass"],
    )

    st, blocked = call("POST", f"/v1/sessions/{code}/claim", "tok-member",
                       {"itemId": "minecraft:glass"})
    check("an excluded material cannot be claimed", st == 409,
          f"HTTP {st} {blocked.get('error')}")

    st, back = call("POST", f"/v1/sessions/{code}/exclude", "tok-host",
                    {"itemId": "minecraft:glass", "excluded": False})
    check("the host can put it back",
          st == 200 and not back["materials"]["minecraft:glass"]["excluded"])
    st, reclaimed = call("POST", f"/v1/sessions/{code}/claim", "tok-member",
                         {"itemId": "minecraft:glass"})
    check("and it can be claimed again", st == 200
          and reclaimed["materials"]["minecraft:glass"]["claimedBy"] is not None)

    st, bulk = call("POST", f"/v1/sessions/{code}/exclude", "tok-host",
                    {"items": {"minecraft:glass": True, "minecraft:stone": True}})
    check("bulk exclusion works", st == 200 and all(
        bulk["materials"][k]["excluded"] for k in ("minecraft:glass", "minecraft:stone")
    ))
    st, unknown = call("POST", f"/v1/sessions/{code}/exclude", "tok-host",
                       {"itemId": "minecraft:dirt", "excluded": True})
    check("an unknown item is refused", st == 404, f"HTTP {st}")
    st, empty = call("POST", f"/v1/sessions/{code}/exclude", "tok-host", {})
    check("a bodyless call is refused", st == 400, f"HTTP {st}")

    print("\n== host secret: host tools without Mojang (offline launchers) ==")
    st, made = call("POST", "/v1/sessions", "tok-host", {
        "name": "Secret", "schemaName": "Secret",
        "materials": {"minecraft:glass": 64, "minecraft:stone": 64},
    })
    scode = made.get("code", "")
    secret = made.get("hostSecret")
    check("create hands the creator a host secret", bool(secret), f"len={len(secret or '')}")

    st, snap = call("GET", f"/v1/sessions/{scode}", "tok-member")
    check(
        "the secret is NOT in later snapshots",
        "hostSecret" not in snap,
        "every member polls this — leaking it here would make it worthless",
    )
    call("POST", f"/v1/sessions/{scode}/join", "tok-member")
    st, snap2 = call("GET", f"/v1/sessions/{scode}", "tok-member")
    check("still absent after a join", "hostSecret" not in snap2)

    # The whole point: no verified identity at all, only the secret.
    st, bysecret = call("POST", f"/v1/sessions/{scode}/exclude", None,
                        {"itemId": "minecraft:stone", "excluded": True,
                         "hostSecret": secret})
    check(
        "an unverified caller WITH the secret may exclude",
        st == 200 and bysecret.get("materials", {}).get("minecraft:stone", {}).get("excluded") is True,
        f"HTTP {st} {bysecret.get('error', '')}",
    )
    st, renamed = call("POST", f"/v1/sessions/{scode}/update", None,
                       {"name": "Renamed by secret", "hostSecret": secret})
    check("...and rename", st == 200 and renamed.get("name") == "Renamed by secret", f"HTTP {st}")
    st, released = call("POST", f"/v1/sessions/{scode}/release_claims", None,
                        {"hostSecret": secret})
    check("...and reset claims", st == 200, f"HTTP {st}")

    st, wrong = call("POST", f"/v1/sessions/{scode}/exclude", None,
                     {"itemId": "minecraft:glass", "hostSecret": "not-the-secret"})
    # 401 when REQUIRE_AUTH is on (a wrong secret authenticates nothing, so the blanket
    # gate speaks first), 403 when it is off and the host check answers. Both are refusals.
    check("a wrong secret is refused", st in (401, 403), f"HTTP {st} {wrong.get('error')}")
    st, none = call("POST", f"/v1/sessions/{scode}/exclude", None,
                    {"itemId": "minecraft:glass"})
    check("no secret and no identity is refused", st in (401, 403), f"HTTP {st}")
    st, uuidonly = call("POST", f"/v1/sessions/{scode}/exclude", None,
                        {"itemId": "minecraft:glass", "uuid": HOST_ID["uuid"],
                         "name": HOST_ID["name"]})
    check(
        "the public host uuid alone still proves nothing",
        st in (401, 403),
        f"HTTP {st} — this is the hole 0b2b731 closed; it must stay closed",
    )
    st, member_secret = call("POST", f"/v1/sessions/{scode}/exclude", "tok-member",
                             {"itemId": "minecraft:glass"})
    check("a member still cannot exclude", st == 403, f"HTTP {st}")

    st, closed = call("POST", f"/v1/sessions/{scode}/close", None, {"hostSecret": secret})
    check("...and close the gather", st == 200, f"HTTP {st} {closed.get('error', '')}")

    print("\n== old sessions without the new fields ==")
    with clan_hub._lock:  # noqa: SLF001
        legacy = clan_hub._sessions[code]  # noqa: SLF001
        for mat in legacy["materials"].values():
            mat.pop("claimedAt", None)
            mat.pop("excluded", None)
    st, legacy_read = call("GET", f"/v1/sessions/{code}", "tok-member")
    check("a session stored before this change still reads", st == 200, f"HTTP {st}")
    st, legacy_claim = call("POST", f"/v1/sessions/{code}/claim", "tok-member",
                            {"itemId": "minecraft:stone"})
    check("and can still be claimed, gaining a timestamp",
          st == 200 and legacy_claim["materials"]["minecraft:stone"]["claimedAt"] > 0,
          f"HTTP {st}")
finally:
    srv.shutdown()

print()
if failures:
    print(f"FAILED ({len(failures)}): " + "; ".join(failures))
    sys.exit(1)
print("all hub checks passed")
