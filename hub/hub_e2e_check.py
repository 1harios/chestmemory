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
os.environ.update(DATA_DIR=DATA, PORT=str(PORT), REQUIRE_AUTH="1", CLAN_TOKEN="")
sys.path.insert(0, str(Path(__file__).resolve().parent))

import clan_auth  # noqa: E402
import clan_ratelimit  # noqa: E402
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


def call_full(method: str, path: str, token: str | None = None, body: dict | None = None):
    """(status, body, headers). Headers matter for one check: a quota must not send
    Retry-After, because that header is what made the mod report it as a rate limit."""
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
            return resp.status, json.loads(resp.read().decode() or "{}"), dict(resp.headers)
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode() or "{}"), dict(e.headers)


def call(method: str, path: str, token: str | None = None, body: dict | None = None):
    status, payload, _ = call_full(method, path, token, body)
    return status, payload


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

    print("\n== polling is not code-guessing (the 'slow down' complaint) ==")
    # The limiter is always live — it has no off switch — so clear the counters this run
    # has already spent before measuring against the real limits.
    clan_ratelimit.reset()

    st, poll_sess = call("POST", "/v1/sessions", "tok-host", {
        "name": "Poll", "schemaName": "Poll", "materials": {"minecraft:glass": 64},
    })
    pcode = poll_sess.get("code", "")
    call("POST", f"/v1/sessions/{pcode}/join", "tok-member")

    # A member's client polls every ~3s: 25 reads is well inside a normal minute in a
    # gather, and more than twice the lookup bucket that used to be charged for it.
    codes = [call("GET", f"/v1/sessions/{pcode}", "tok-member")[0] for _ in range(25)]
    limited = [c for c in codes if c == 429]
    check(
        "25 polls in a row are not rate limited",
        not limited,
        f"{len(limited)} of {len(codes)} got 429 — this is the bug being fixed",
    )

    # A stranger walking codes is still throttled at ten.
    guesses = [
        call("GET", f"/v1/sessions/CM-Z{i:03d}", "tok-member")[0] for i in range(14)
    ]
    check(
        "guessing unknown codes still hits the tight limit",
        429 in guesses,
        f"statuses: {sorted(set(guesses))} — the guessing surface must keep its limit",
    )
    clan_ratelimit.reset()

    print("\n== event history: the feed survives switching and relogging ==")
    st, hsess = call("POST", "/v1/sessions", "tok-host", {
        "name": "Hist", "schemaName": "Hist",
        "materials": {"minecraft:glass": 128, "minecraft:stone": 128},
    })
    hcode = hsess.get("code", "")
    hsecret = hsess.get("hostSecret")
    kinds = lambda snap: [e.get("kind") for e in (snap.get("events") or [])]
    check("creating a gather is itself an event", kinds(hsess) == ["create"], str(kinds(hsess)))

    call("POST", f"/v1/sessions/{hcode}/join", "tok-member")
    call("POST", f"/v1/sessions/{hcode}/claim", "tok-member", {"itemId": "minecraft:glass"})
    st, after = call("POST", f"/v1/sessions/{hcode}/deliver", "tok-member",
                     {"itemId": "minecraft:glass", "amount": 64})
    seq = kinds(after)
    check(
        "join, claim and deliver are all recorded, in order",
        seq == ["create", "join", "claim", "deliver"],
        str(seq),
    )
    delivered = [e for e in after["events"] if e["kind"] == "deliver"][0]
    check(
        "a delivery records who and how many",
        delivered["who"] == MEMBER_ID["name"] and delivered["n"] == 64,
        json.dumps(delivered),
    )

    # The whole point: a client that was not watching still gets the history.
    st, fresh = call("GET", f"/v1/sessions/{hcode}", "tok-host")
    check(
        "a client that saw none of it still receives the history",
        kinds(fresh) == seq,
        "this is what a relog or a switch back used to lose entirely",
    )

    st, rejoin = call("POST", f"/v1/sessions/{hcode}/join", "tok-member")
    check(
        "re-joining does not log a second arrival",
        kinds(rejoin).count("join") == 1,
        "join doubles as 'switch to', so this would bury the feed: " + str(kinds(rejoin)),
    )

    print("\n== releasing one claim instead of all of them ==")
    call("POST", f"/v1/sessions/{hcode}/claim", "tok-member", {"itemId": "minecraft:stone"})
    st, one = call("POST", f"/v1/sessions/{hcode}/release_claims", None,
                   {"itemId": "minecraft:glass", "hostSecret": hsecret})
    check(
        "the named claim is freed and the other is untouched",
        st == 200
        and one["materials"]["minecraft:glass"]["claimedBy"] is None
        and one["materials"]["minecraft:stone"]["claimedBy"] is not None,
        f"HTTP {st}",
    )
    check(
        "and it names who lost it, not who took it away",
        [e for e in one["events"] if e["kind"] == "release"][-1]["who"] == MEMBER_ID["name"],
    )
    st, unknown = call("POST", f"/v1/sessions/{hcode}/release_claims", None,
                       {"itemId": "minecraft:dirt", "hostSecret": hsecret})
    check("an unknown item is refused", st == 404, f"HTTP {st}")
    st, denied = call("POST", f"/v1/sessions/{hcode}/release_claims", "tok-member",
                      {"itemId": "minecraft:stone"})
    check("a member cannot free anyone's claim", st == 403, f"HTTP {st}")
    st, all_freed = call("POST", f"/v1/sessions/{hcode}/release_claims", None,
                         {"hostSecret": hsecret})
    check(
        "no item named still clears everything — the old behaviour",
        st == 200 and all_freed["materials"]["minecraft:stone"]["claimedBy"] is None
        and kinds(all_freed)[-1] == "release_all",
        f"HTTP {st} {kinds(all_freed)[-3:]}",
    )

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

    # Everything below mutates the session store and the caps, so it runs last.

    print("\n== finishing a material releases its claim ==")
    # The bug: nothing released the claim when a delivery completed a material, so the
    # members panel — which names a member's earliest claim — kept showing a collector on
    # the glass they had already finished, for the rest of the gather.
    _, made = call("POST", "/v1/sessions", "tok-host", {
        "name": "Release on done", "materials": {"minecraft:glass": 10, "minecraft:stone": 10},
    })
    rc = made.get("code", "")
    call("POST", f"/v1/sessions/{rc}/join", "tok-member", {})
    call("POST", f"/v1/sessions/{rc}/claim", "tok-member", {"itemId": "minecraft:glass"})
    st, part = call("POST", f"/v1/sessions/{rc}/deliver", "tok-member",
                    {"itemId": "minecraft:glass", "amount": 4})
    glass = part.get("materials", {}).get("minecraft:glass", {})
    check("a partial delivery keeps the claim", glass.get("claimedBy") is not None,
          f"HTTP {st} claimedBy={glass.get('claimedBy')}")
    st, full = call("POST", f"/v1/sessions/{rc}/deliver", "tok-member",
                    {"itemId": "minecraft:glass", "amount": 10})
    glass = full.get("materials", {}).get("minecraft:glass", {})
    check("the delivery that completes it releases the claim",
          glass.get("claimedBy") is None and glass.get("claimedName") is None,
          f"HTTP {st} claimedBy={glass.get('claimedBy')}")
    check("and the count is kept, not reset",
          glass.get("delivered") == 10, str(glass.get("delivered")))
    check("with a 'done' event naming who finished it",
          any(e.get("kind") == "done" and e.get("item") == "minecraft:glass"
              for e in (full.get("events") or [])),
          str([e.get("kind") for e in (full.get("events") or [])][-4:]))
    check("lastDeliveredBy survives the release, so the panel can still credit them",
          glass.get("lastDeliveredBy") == MEMBER_ID["name"], str(glass.get("lastDeliveredBy")))
    check("lastDeliveredAt is recorded for ordering",
          int(glass.get("lastDeliveredAt") or 0) > 0, str(glass.get("lastDeliveredAt")))
    st, other = call("POST", f"/v1/sessions/{rc}/claim", "tok-host",
                     {"itemId": "minecraft:glass"})
    check("a finished material can be claimed again without complaint", st == 200, f"HTTP {st}")

    print("\n== the open-gather cap is a quota, not a rate limit ==")
    # What this pins: the cap used to reply 429. The mod reads 429 as "the hub asks you to
    # slow down", so the host waited — and a quota does not drain with time. It has to be
    # 409, must not carry Retry-After, and has to name the codes holding the slots, because
    # taking a gather off your own list used to discard the secret needed to close it.
    saved_cap = clan_hub.MAX_SESSIONS_PER_HOST
    clan_hub.MAX_SESSIONS_PER_HOST = 2
    try:
        with clan_hub._lock:  # noqa: SLF001
            clan_hub._sessions.clear()  # noqa: SLF001
        held = []
        for i in range(2):
            _, made = call("POST", "/v1/sessions", "tok-host", {
                "name": f"Quota {i}", "materials": {"minecraft:stone": 64},
            })
            held.append(made.get("code", ""))
        st, refused, headers = call_full("POST", "/v1/sessions", "tok-host", {
            "name": "Over the cap", "materials": {"minecraft:stone": 64},
        })
        check("over the cap the hub answers 409, not 429", st == 409, f"HTTP {st}")
        check("with a reason the client can branch on",
              refused.get("reason") == "host_session_limit", str(refused.get("reason")))
        check("and no Retry-After, because waiting cannot help",
              headers.get("Retry-After") is None, str(headers.get("Retry-After")))
        check("the refusal names the codes holding the slots",
              sorted(refused.get("codes") or []) == sorted(held), str(refused.get("codes")))
        check("and reports open and max",
              refused.get("open") == 2 and refused.get("max") == 2,
              f"{refused.get('open')}/{refused.get('max')}")
    finally:
        clan_hub.MAX_SESSIONS_PER_HOST = saved_cap

    print("\n== a gather's lease depends on whether anything was handed in ==")
    # The old rule keyed off the roster and was wrong both ways: a host who steps away
    # leaves an empty roster on purpose, so a gather with progress sat on the short lease,
    # while a nameless test with the creator still listed got the long one.
    with clan_hub._lock:  # noqa: SLF001
        clan_hub._sessions.clear()  # noqa: SLF001
    _, fresh = call("POST", "/v1/sessions", "tok-host",
                    {"name": "Untouched", "materials": {"minecraft:stone": 64}})
    idle_code = fresh.get("code", "")
    _, started = call("POST", "/v1/sessions", "tok-host",
                      {"name": "Worked on", "materials": {"minecraft:stone": 64}})
    busy_code = started.get("code", "")
    st, _ = call("POST", f"/v1/sessions/{busy_code}/deliver", "tok-host",
                 {"itemId": "minecraft:stone", "amount": 1})
    check("a delivery lands", st == 200, f"HTTP {st}")
    with clan_hub._lock:  # noqa: SLF001
        check("a gather with no deliveries counts as unstarted",
              not clan_hub._has_progress(clan_hub._sessions[idle_code]))  # noqa: SLF001
        check("one delivered stack makes it started",
              clan_hub._has_progress(clan_hub._sessions[busy_code]))  # noqa: SLF001
        # Age both past the short lease, well inside the long one.
        stale = clan_hub._now() - (clan_hub.UNSTARTED_SESSION_TTL_SEC + 60) * 1000  # noqa: SLF001
        for c in (idle_code, busy_code):
            s = clan_hub._sessions[c]  # noqa: SLF001
            s["updatedAt"] = stale
            for m in s["members"]:
                m["lastSeen"] = stale
    clan_hub._purge_old(force=True)  # noqa: SLF001
    with clan_hub._lock:  # noqa: SLF001
        alive = set(clan_hub._sessions)  # noqa: SLF001
    check("an untouched gather expires on the short lease", idle_code not in alive)
    check("a gather with progress outlives it", busy_code in alive)
    check("the long lease is a month by default",
          clan_hub.SESSION_TTL_SEC >= 30 * 24 * 3600, str(clan_hub.SESSION_TTL_SEC))
finally:
    srv.shutdown()

print()
if failures:
    print(f"FAILED ({len(failures)}): " + "; ".join(failures))
    sys.exit(1)
print("all hub checks passed")
