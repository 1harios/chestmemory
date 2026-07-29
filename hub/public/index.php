<?php
/**
 * Chest Memory clan hub — PHP (persistent, for normal web hosting / ISPmanager site).
 *
 * Put this folder as site document root (or point domain here).
 * Config: copy config.sample.php → config.php
 *
 * Same API as clan_hub.py — Minecraft mod works without changes.
 */
declare(strict_types=1);

header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Headers: Content-Type, X-Clan-Token, X-Clan-Session');
header('Access-Control-Allow-Methods: GET, POST, OPTIONS');

if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
    http_response_code(204);
    exit;
}

$configFile = __DIR__ . '/config.php';
$config = is_file($configFile) ? require $configFile : [
    'token' => getenv('CLAN_TOKEN') ?: '',
    'ttl_sec' => 7 * 24 * 3600,
    'data_dir' => __DIR__ . '/../data',
];

$dataDir = rtrim((string)$config['data_dir'], '/\\');
if (!is_dir($dataDir)) {
    mkdir($dataDir, 0750, true);
}
$sessionsFile = $dataDir . '/sessions.json';
require_once __DIR__ . '/clan_auth.php';
require_once __DIR__ . '/clan_ratelimit.php';
clan_auth_init($dataDir);
clan_rl_init($dataDir);
/**
 * Require a verified session for mutating requests. See clan_auth.php.
 * Defaulting to false shipped the impersonation hole to every operator who kept the
 * sample config; false is now the explicit escape hatch for one mod-upgrade window.
 */
$requireAuth = (bool)($config['require_auth'] ?? true);
$GLOBALS['clan_require_auth'] = $requireAuth;
if (!$requireAuth) {
    // Loud but not per-request: once an hour survives log rotation without turning
    // the error log into a scroll of the same line.
    $warnStamp = $dataDir . '/auth_off_warned.stamp';
    if (!is_file($warnStamp) || time() - (int)@filemtime($warnStamp) > 3600) {
        @touch($warnStamp);
        error_log(
            'chestmemory-hub: require_auth is OFF — player identity is UNVERIFIED and '
            . 'members can be impersonated. Host-only actions are unaffected: they still '
            . 'refuse a bare uuid, and the creator proves itself with the gather'
            . "'s hostSecret. Set a token if the hub faces the open internet."
        );
    }
}
$token = (string)($config['token'] ?? '');
$ttlMs = (int)($config['ttl_sec'] ?? 604800) * 1000;
/**
 * A member's claims are released after this long without a heartbeat.
 * The client polls every ~3s and polling refreshes lastSeen, so this means "client
 * is gone" (quit/crash), not "player is idle" — mining in the Nether for an hour
 * keeps your claims, because that changes dimension, not connection.
 */
$claimTimeoutMs = (int)($config['claim_timeout_sec'] ?? 180) * 1000;

function now_ms(): int
{
    return (int)round(microtime(true) * 1000);
}

function respond(int $code, $obj): void
{
    http_response_code($code);
    header('Content-Type: application/json; charset=utf-8');
    echo json_encode($obj, JSON_UNESCAPED_UNICODE);
    exit;
}

/** Session snapshot + the hub's clock, so clients can judge staleness without
 *  trusting their own wall clock to agree with ours (same shape as clan_hub.py). */
function respond_session(array $sess, bool $includeSecret = false): void
{
    // hostSecret is stripped unless this is the create response. Every member polls this
    // same snapshot, so leaving it in would hand the host's proof to the whole clan —
    // which is the one thing that makes the secret worth anything.
    if (!$includeSecret) {
        unset($sess['hostSecret']);
    }
    $sess['now'] = now_ms();
    respond(200, $sess);
}

/**
 * True when the request carries this gather's hostSecret.
 *
 * hash_equals, not ==: plain comparison returns on the first wrong byte, which lets a
 * patient prober time their way through the secret. Absent on either side is a miss —
 * a session stored before secrets existed must not be openable by sending none.
 * Mirrors _host_secret_ok in clan_hub.py.
 */
function host_secret_ok(array $sess, array $body): bool
{
    $want = (string)($sess['hostSecret'] ?? '');
    $got = (string)($body['hostSecret'] ?? '');
    if ($want === '' || $got === '') {
        return false;
    }
    return hash_equals($want, $got);
}

function client_addr(): string
{
    // REMOTE_ADDR, deliberately NOT X-Forwarded-For: the web server terminates the
    // client connection right here, so that header is whatever the client typed —
    // honouring it would hand a guessing loop a fresh rate bucket per request.
    return (string)($_SERVER['REMOTE_ADDR'] ?? '?');
}

/** Enforce the per-address limit; replies 429 itself when exceeded. */
function rate_ok(string $kind): void
{
    $retry = clan_rl_check($kind, client_addr());
    if ($retry !== null) {
        header('Retry-After: ' . $retry);
        respond(429, ['error' => 'rate limited']);
    }
}

function check_token(string $expected): void
{
    if ($expected === '') {
        return;
    }
    $got = $_SERVER['HTTP_X_CLAN_TOKEN'] ?? '';
    if (!hash_equals($expected, $got)) {
        respond(401, ['error' => 'bad token']);
    }
}

function load_sessions(string $file): array
{
    if (!is_file($file)) {
        return [];
    }
    $raw = file_get_contents($file);
    if ($raw === false || $raw === '') {
        return [];
    }
    $j = json_decode($raw, true);
    if (!is_array($j)) {
        return [];
    }
    if (isset($j['sessions']) && is_array($j['sessions'])) {
        return $j['sessions'];
    }
    return $j;
}

function save_sessions(string $file, array $sessions): void
{
    $payload = [
        'version' => 2,
        'savedAt' => now_ms(),
        'sessions' => $sessions,
    ];
    $tmp = $file . '.tmp';
    $json = json_encode($payload, JSON_UNESCAPED_UNICODE);
    if ($json === false) {
        return;
    }
    file_put_contents($tmp, $json, LOCK_EX);
    rename($tmp, $file);
}

function normalize_code(string $raw): string
{
    $s = strtoupper(trim(str_replace(' ', '-', $raw)));
    if (preg_match('/^[A-Z0-9]{4}$/', $s)) {
        $s = 'CM-' . $s;
    }
    if (str_starts_with($s, 'CM') && !str_starts_with($s, 'CM-') && strlen($s) >= 6) {
        $s = 'CM-' . substr($s, 2);
    }
    return $s;
}

function ok_code(string $code): bool
{
    return (bool)preg_match('/^CM-[A-Z0-9]{4}$/', $code);
}

function gen_code(array $sessions): string
{
    $alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
    for ($i = 0; $i < 40; $i++) {
        $code = 'CM-';
        for ($j = 0; $j < 4; $j++) {
            $code .= $alphabet[random_int(0, strlen($alphabet) - 1)];
        }
        if (!isset($sessions[$code])) {
            return $code;
        }
    }
    respond(500, ['error' => 'code exhausted']);
}

function purge(array &$sessions, int $ttlMs, string $dataDir): void
{
    // Time-gated: a full scan of every session used to run on each GET and POST,
    // paid by whoever happened to send the request. Once a minute catches the same
    // expiries. The stamp file survives the shared-nothing request model.
    $stamp = $dataDir . '/purge.stamp';
    if (is_file($stamp) && time() - (int)@filemtime($stamp) < PURGE_INTERVAL_SEC) {
        return;
    }
    @touch($stamp);
    $now = now_ms();
    foreach ($sessions as $c => $s) {
        $last = (int)($s['updatedAt'] ?? 0);
        $ttl = $ttlMs;
        $members = is_array($s['members'] ?? null) ? $s['members'] : [];
        if (count($members) <= 1) {
            // Solo sessions die on the short TTL — but heartbeats only refresh
            // lastSeen, not updatedAt, so count them or an idle-but-online host
            // would lose their gather a day after the last actual change.
            $ttl = min($ttl, SOLO_SESSION_TTL_SEC * 1000);
            foreach ($members as $m) {
                $last = max($last, (int)($m['lastSeen'] ?? 0));
            }
        }
        if ($last < $now - $ttl) {
            unset($sessions[$c]);
            // caller saves on its own mutations; a purge-only GET leaves the file
            // as-is and the entry simply re-purges until the next write.
        }
    }
}

/**
 * Cut a string to $max characters without tearing a UTF-8 sequence: a blind byte cut
 * can leave invalid UTF-8, json_encode() then fails and save_sessions() silently
 * skips the write — the response says "renamed", the disk says otherwise.
 */
function clamp_str(string $s, int $max): string
{
    if (function_exists('mb_substr')) {
        return mb_substr($s, 0, $max);
    }
    $cut = substr($s, 0, $max);
    while ($cut !== '' && !preg_match('//u', $cut)) {
        $cut = substr($cut, 0, -1);
    }
    return $cut;
}

/**
 * @return bool false when the roster is full and this uuid is not on it — join
 *              treats that as an error, heartbeat-ish callers just shrug.
 */
function member_upsert(array &$sess, string $name, string $uuid): bool
{
    // A kicked member must not drift back in through the heartbeat: their client
    // keeps polling until it notices the kick. Only an explicit join lifts the flag.
    $kicked = is_array($sess['kicked'] ?? null) ? $sess['kicked'] : [];
    foreach ($kicked as $k) {
        if ($uuid !== '' && strcasecmp((string)$k, $uuid) === 0) {
            return true;
        }
    }
    $name = clamp_str($name, MAX_NAME_LEN);
    $members = &$sess['members'];
    if (!is_array($members)) {
        $members = [];
    }
    foreach ($members as &$m) {
        if (strcasecmp((string)($m['uuid'] ?? ''), $uuid) === 0) {
            $m['name'] = $name !== '' ? $name : ($m['name'] ?? '?');
            $m['lastSeen'] = now_ms();
            return true;
        }
    }
    if (count($members) >= MAX_MEMBERS_PER_SESSION) {
        return false;
    }
    $members[] = ['name' => $name !== '' ? $name : '?', 'uuid' => $uuid, 'lastSeen' => now_ms()];
    return true;
}

/** Deduped, length-clamped staging keys; callers cap the resulting list size. */
function clean_staging_keys($keysIn): array
{
    $clean = [];
    if (is_array($keysIn)) {
        foreach ($keysIn as $k) {
            $s = clamp_str(trim((string)$k), MAX_ITEM_ID_LEN);
            if ($s !== '' && !in_array($s, $clean, true)) {
                $clean[] = $s;
            }
        }
    }
    return $clean;
}

/**
 * Release one material's claim, every field of it.
 *
 * A claim is three fields now, not two: claimedAt joined claimedBy/claimedName so the client
 * can order a member's claims by when they were taken. Five paths release claims (stale
 * sweep, unclaim, leave, kick, release_claims) and each has to drop all three, or a released
 * material keeps a timestamp that outlives its claim. Mirrors _clear_claim in clan_hub.py —
 * both write the same sessions.json, so they cannot disagree about the shape.
 */
function clear_claim(array &$mat): void
{
    $mat['claimedBy'] = null;
    $mat['claimedName'] = null;
    $mat['claimedAt'] = 0;
}

/**
 * Drop claims held by members whose client stopped talking to us. Without this an
 * alt-F4 left a material reserved for the whole session lifetime.
 *
 * @return string[] names whose claims were released
 */
function release_stale_claims(array &$sess, int $timeoutMs): array
{
    $cutoff = now_ms() - $timeoutMs;
    $stale = [];
    foreach ($sess['members'] ?? [] as $m) {
        $uuid = (string)($m['uuid'] ?? '');
        if ($uuid !== '' && (int)($m['lastSeen'] ?? 0) < $cutoff) {
            $stale[strtolower($uuid)] = (string)($m['name'] ?? '?');
        }
    }
    if ($stale === []) {
        return [];
    }
    $released = [];
    if (isset($sess['materials']) && is_array($sess['materials'])) {
        foreach ($sess['materials'] as &$mat) {
            $holder = strtolower((string)($mat['claimedBy'] ?? ''));
            if ($holder !== '' && isset($stale[$holder])) {
                clear_claim($mat);
                if (!in_array($stale[$holder], $released, true)) {
                    $released[] = $stale[$holder];
                }
            }
        }
        unset($mat);
    }
    return $released;
}

function touch_sess(array &$sess): void
{
    $sess['updatedAt'] = now_ms();
    $sess['revision'] = (int)($sess['revision'] ?? 0) + 1;
}

/** Hard cap on a request body: the whole store is read, modified and rewritten per
 *  request, so unbounded input is both a memory and a disk-fill vector. */
const MAX_BODY_BYTES = 512 * 1024;

/*
 * Growth caps — keep in lockstep with clan_hub.py. The body cap above bounds one
 * request; nothing bounded how many requests accumulate: POST /v1/sessions in a
 * loop filled the store (each session lives 7 days), and every request rereads and
 * rewrites the whole file.
 */
const MAX_SESSIONS_TOTAL = 256;
/** A busy clan runs a handful of parallel gathers; dozens from one host is a script. */
const MAX_SESSIONS_PER_HOST = 4;
const MAX_MEMBERS_PER_SESSION = 64;
const MAX_MATERIALS_PER_SESSION = 400;
/** stagingKeys grow by append from every member — uncapped, one scripted member
 *  inflates the session (and the file it is rewritten into) without limit. */
const MAX_STAGING_KEYS_PER_SESSION = 256;
/** Kicks are moderation, not a permanent ban list — past this, oldest entries fall off. */
const MAX_KICKED_TRACKED = 256;
/** Real names and item ids stay well under these; longer is padding aimed at the store. */
const MAX_NAME_LEN = 48;
const MAX_ITEM_ID_LEN = 128;
/** A gather nobody ever joined is a test, an aborted attempt — or create-spam. */
const SOLO_SESSION_TTL_SEC = 24 * 3600;
const PURGE_INTERVAL_SEC = 60;

function read_body(): array
{
    // Memoized: php://input cannot be relied on for a second read, and the host-secret
    // check in the auth gate needs the body before any route handler asks for it.
    static $cached = null;
    if ($cached !== null) {
        return $cached;
    }
    $declared = (int)($_SERVER['CONTENT_LENGTH'] ?? 0);
    if ($declared > MAX_BODY_BYTES) {
        respond(413, ['error' => 'body too large (max ' . MAX_BODY_BYTES . ' bytes)']);
    }
    $raw = file_get_contents('php://input', false, null, 0, MAX_BODY_BYTES + 1);
    if ($raw === false || $raw === '') {
        $cached = [];
        return $cached;
    }
    if (strlen($raw) > MAX_BODY_BYTES) {
        respond(413, ['error' => 'body too large (max ' . MAX_BODY_BYTES . ' bytes)']);
    }
    $j = json_decode($raw, true);
    $cached = is_array($j) ? $j : [];
    return $cached;
}

/** Path after host: /v1/... or /index.php/v1/... */
function api_path(): string
{
    $uri = parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH) ?: '/';
    $uri = rawurldecode($uri);
    if (preg_match('#/index\.php(.*)$#', $uri, $m)) {
        $uri = $m[1] !== '' ? $m[1] : '/';
    }
    // strip directory prefix if site is in subfolder
    if (preg_match('#(/v1/.*)$#', $uri, $m)) {
        return $m[1];
    }
    return $uri === '' ? '/' : $uri;
}

/**
 * True when whoever is asking is already on this session's roster.
 *
 * Only ever used to pick a rate-limit bucket, never to authorize: the header hint counts
 * here as much as a verified session, because a poll is a poll whoever sends it and
 * claiming membership of a gather you are on buys nothing but the loose bucket.
 */
function clan_is_member_of(string $code, array $sessions): bool
{
    $who = clan_identity() ?? clan_hint_identity();
    if ($who === null) {
        return false;
    }
    $uuid = strtolower((string)($who['uuid'] ?? ''));
    if ($uuid === '') {
        return false;
    }
    $members = $sessions[$code]['members'] ?? null;
    if (!is_array($members)) {
        return false;
    }
    foreach ($members as $m) {
        if (strtolower((string)($m['uuid'] ?? '')) === $uuid) {
            return true;
        }
    }
    return false;
}

/**
 * Verified player behind this request, or null when unauthenticated.
 * Reads X-Clan-Session, handed out only after Mojang confirmed the account.
 * Never trusts a uuid from the request body.
 */
function clan_identity(): ?array
{
    return clan_auth_resolve((string)($_SERVER['HTTP_X_CLAN_SESSION'] ?? ''));
}

/**
 * Unverified identity hint from headers, honoured only while require_auth is off.
 *
 * Offline-mode launchers can never complete the Mojang handshake, so without this a
 * plain poll carried no identity at all — lastSeen never moved, and the hub released
 * the player's claims after claim_timeout while they were actively online. (The
 * Python hub has honoured this hint for a while; this one silently didn't.)
 *
 * @return array{uuid: string, name: string}|null
 */
function clan_hint_identity(): ?array
{
    if (!empty($GLOBALS['clan_require_auth'])) {
        return null;
    }
    $uuid = trim((string)($_SERVER['HTTP_X_CLAN_UUID'] ?? ''));
    if ($uuid === '') {
        return null;
    }
    $name = trim((string)($_SERVER['HTTP_X_CLAN_NAME'] ?? '?'));
    return ['uuid' => $uuid, 'name' => $name !== '' ? $name : '?'];
}

/**
 * [uuid, name] to act as. Prefers the verified session; falls back to the header
 * hint and then to the body only while require_auth is off, so a clan can upgrade
 * the hub before every member has updated the mod.
 *
 * @return array{0: string, 1: string}
 */
function clan_actor(array $body): array
{
    $who = clan_identity() ?? clan_hint_identity();
    if ($who !== null) {
        return [$who['uuid'], $who['name']];
    }
    if (!empty($GLOBALS['clan_require_auth'])) {
        // Unreachable for routed POSTs (the auth gate replied 401 already), kept so
        // no future call site can wander back into trusting the body by accident.
        return ['', '?'];
    }
    return [
        (string)($body['uuid'] ?? $body['hostUuid'] ?? ''),
        (string)($body['name'] ?? $body['hostName'] ?? '?'),
    ];
}

/**
 * Verified identity for a host-only action; replies itself when that fails.
 *
 * Only the verified identity counts here, even while require_auth is off. The
 * compat fallbacks (X-Clan-Uuid header, body uuid) are attacker-chosen, and the
 * host's uuid is public in every session snapshot — honouring them meant anyone
 * could rename the gather, empty the roster or close the session by pasting that
 * uuid into a request.
 *
 * @return array{uuid: string, name: string}
 */
function require_verified_host(
    array $sess, string $deny = 'only host', array $body = []
): array {
    // The hostSecret proves the creator without Mojang, which is the only way an
    // offline-mode launcher can hold host tools. The uuid is still refused on its own:
    // it is public in every snapshot, so honouring it let anyone replay it.
    if (host_secret_ok($sess, $body)) {
        return ['uuid' => (string)($sess['hostUuid'] ?? ''), 'name' => (string)($sess['hostName'] ?? '?')];
    }
    $who = clan_identity();
    if ($who === null) {
        respond(403, ['error' => 'host actions require verified identity']);
    }
    if (strcasecmp((string)($sess['hostUuid'] ?? ''), (string)$who['uuid']) !== 0) {
        respond(403, ['error' => $deny]);
    }
    return $who;
}

check_token($token);
$path = api_path();
$method = $_SERVER['REQUEST_METHOD'] ?? 'GET';

/*
 * Rate limits sit before the store lock: a guessing loop must cost a counter lookup,
 * not a full parse of sessions.json. Same bucket split as clan_hub.py — lookups
 * (code guessing) tight, auth (nonce grinding) tight, in-session actions generous.
 * Health stays unthrottled, matching Python.
 */
if ($path === '/v1/auth/challenge' || $path === '/v1/auth/verify') {
    rate_ok('auth');
} elseif ($method === 'GET' && preg_match('#^/v1/sessions/([^/]+)/?$#', $path, $rm) === 1) {
    // Guessing codes happens here — but so does every member's poll, ~20 reads a minute
    // against a bucket that allows ten, shared by a whole clan behind one address. A caller
    // already on the roster is polling (loose bucket); anyone else is looking up a code they
    // do not hold (tight). A guesser is by definition not a member, and an unknown code has
    // no roster, so the guessing surface keeps exactly the limit it had.
    // Mirrors _is_member_of in clan_hub.py.
    //
    // This costs a parse of sessions.json before the limiter, which the note above wanted
    // to avoid. Python does not pay it — its store is resident in memory, so the same check
    // is a dict lookup — but PHP is shared-nothing and has to read to know anything. The
    // read is deliberately unlocked and only decides which counter to charge: a roster one
    // request out of date can at worst bill one poll to the wrong bucket, and the
    // authoritative read still happens under the lock below.
    rate_ok(
        clan_is_member_of(normalize_code($rm[1]), load_sessions($sessionsFile))
            ? 'action' : 'lookup'
    );
} elseif ($method === 'POST' && str_starts_with($path, '/v1/sessions')) {
    rate_ok(preg_match('#/join/?$#', $path) === 1 ? 'lookup' : 'action');
}

/*
 * Every request reads the whole store, mutates it and writes it back. Without a lock
 * held across that whole cycle two concurrent requests both read the old state and the
 * later write wins — so one player's claim silently disappears. save_sessions() locked
 * only its own temp file, which protected nothing.
 *
 * Writers take an exclusive lock for the entire request; readers take a shared one so
 * they never observe a half-applied state. respond() calls exit(), so the release is
 * registered as a shutdown function rather than written after each branch.
 */
/*
 * Session lookups now write too (heartbeat + stale-claim release), so they need the
 * exclusive lock as well — a shared lock would let two concurrent polls read the same
 * state and clobber each other's lastSeen update.
 */
$isWrite = ($_SERVER['REQUEST_METHOD'] ?? 'GET') === 'POST'
    || preg_match('#^/v1/sessions/[^/]+/?$#', api_path()) === 1;
$lockHandle = fopen($dataDir . '/sessions.lock', 'c');
if ($lockHandle !== false) {
    flock($lockHandle, $isWrite ? LOCK_EX : LOCK_SH);
    register_shutdown_function(static function () use ($lockHandle) {
        flock($lockHandle, LOCK_UN);
        fclose($lockHandle);
    });
}

$sessions = load_sessions($sessionsFile);
purge($sessions, $ttlMs, $dataDir);

if ($method === 'GET' && $path === '/v1/auth/challenge') {
    $nonce = clan_auth_new_challenge();
    if ($nonce === null) {
        respond(503, ['error' => 'auth busy']);
    }
    respond(200, ['nonce' => $nonce]);
}

if ($method === 'POST' && $path === '/v1/auth/verify') {
    $body = read_body();
    $result = clan_auth_verify((string)($body['name'] ?? ''), (string)($body['nonce'] ?? ''));
    if ($result === null) {
        respond(401, ['error' => 'auth failed']);
    }
    respond(200, $result);
}

if ($method === 'GET' && ($path === '/' || $path === '/v1/health')) {
    respond(200, [
        'ok' => true,
        'sessions' => count($sessions),
        'version' => 2,
        'persistent' => true,
        'engine' => 'php',
    ]);
}

if ($method === 'GET' && preg_match('#^/v1/sessions/([^/]+)/?$#', $path, $m)) {
    $code = normalize_code($m[1]);
    if (!isset($sessions[$code])) {
        respond(404, ['error' => 'not found']);
    }
    // The poll IS the heartbeat — it used to refresh nothing, so lastSeen only moved
    // on claim/deliver and a long mining trip looked like a disconnect. Verified
    // identity first; the header hint covers offline-mode clients while require_auth
    // is off — without it their lastSeen never moved and claims timed out mid-game.
    $who = clan_identity() ?? clan_hint_identity();
    $changed = false;
    if ($who !== null) {
        member_upsert($sessions[$code], $who['name'], $who['uuid']);
        $changed = true;
    }
    $released = release_stale_claims($sessions[$code], $claimTimeoutMs);
    if ($released !== []) {
        touch_sess($sessions[$code]);
        $changed = true;
    }
    if ($changed) {
        save_sessions($sessionsFile, $sessions);
    }
    respond_session($sessions[$code]);
}

// Mutating endpoints act on behalf of a player, so they need a verified one.
//
// One narrow exception: a host-only action carrying its own gather's hostSecret. Without
// it the secret would be unusable whenever require_auth is on, forcing an operator with
// offline-mode players to leave identities unverified — the worse setting — just to keep
// host tools. Limited to the five host actions, so claim and deliver stay identity-gated.
// Mirrors _host_secret_request in clan_hub.py.
function host_secret_request(string $path, array $body, array $sessions): bool
{
    if (!str_starts_with($path, '/v1/sessions/')) {
        return false;
    }
    $parts = array_values(array_filter(explode('/', substr($path, strlen('/v1/sessions/'))), 'strlen'));
    if (count($parts) < 2) {
        return false;
    }
    if (!in_array($parts[1], ['update', 'kick', 'release_claims', 'exclude', 'close'], true)) {
        return false;
    }
    // The code comes from the path, never the body: holding one gather's secret must not
    // open any other gather.
    $sess = $sessions[normalize_code($parts[0])] ?? null;
    return is_array($sess) && host_secret_ok($sess, $body);
}

if ($requireAuth && $method === 'POST' && str_starts_with($path, '/v1/sessions')) {
    if (clan_identity() === null
        && !host_secret_request($path, read_body(), $sessions)) {
        respond(401, ['error' => 'auth required']);
    }
}

if ($method === 'POST' && $path === '/v1/sessions') {
    $body = read_body();
    $materialsIn = $body['materials'] ?? null;
    if (!is_array($materialsIn) || $materialsIn === []) {
        respond(400, ['error' => 'materials required']);
    }
    // NOT clan_actor(): in the create body "name" is the BUILD's name, so the generic
    // fallback registered the host in the roster as "Castle" instead of their nick.
    $who = clan_identity() ?? clan_hint_identity();
    if ($who !== null) {
        $hostUuid = (string)$who['uuid'];
        $hostName = (string)$who['name'];
    } else {
        $hostUuid = (string)($body['hostUuid'] ?? $body['uuid'] ?? '');
        $hostName = (string)($body['hostName'] ?? 'Host');
    }
    if ($hostUuid === '') {
        respond(400, ['error' => 'hostUuid required']);
    }
    $hostName = clamp_str($hostName, MAX_NAME_LEN);
    $name = clamp_str((string)($body['name'] ?? 'Build'), MAX_NAME_LEN);
    $schema = clamp_str((string)($body['schemaName'] ?? $name), MAX_NAME_LEN);
    $materials = [];
    foreach ($materialsIn as $k => $v) {
        $need = (int)$v;
        if ($need > 0 && strlen((string)$k) <= MAX_ITEM_ID_LEN) {
            $materials[(string)$k] = [
                'need' => $need,
                'delivered' => 0,
                'claimedBy' => null,
                'claimedName' => null,
                'claimedAt' => 0,
                'excluded' => false,
            ];
        }
    }
    if ($materials === []) {
        respond(400, ['error' => 'empty materials']);
    }
    if (count($materials) > MAX_MATERIALS_PER_SESSION) {
        respond(400, ['error' => 'too many materials (max ' . MAX_MATERIALS_PER_SESSION . ')']);
    }
    $staging = clean_staging_keys($body['stagingKeys'] ?? null);
    if (count($staging) > MAX_STAGING_KEYS_PER_SESSION) {
        respond(400, ['error' => 'too many staging keys (max ' . MAX_STAGING_KEYS_PER_SESSION . ')']);
    }
    // Creation is the one call that grows the store without knowing a code, so the
    // growth caps live here: a global one for the hub's own survival and a per-host
    // one so a single scripted client cannot eat the global budget.
    if (count($sessions) >= MAX_SESSIONS_TOTAL) {
        respond(503, ['error' => 'session limit reached']);
    }
    $mine = 0;
    foreach ($sessions as $s) {
        if (strcasecmp((string)($s['hostUuid'] ?? ''), $hostUuid) === 0) {
            $mine++;
        }
    }
    if ($mine >= MAX_SESSIONS_PER_HOST) {
        respond(429, ['error' => 'too many open sessions for this host (max ' . MAX_SESSIONS_PER_HOST . ')']);
    }
    $code = gen_code($sessions);
    $now = now_ms();
    $sess = [
        'code' => $code,
        'name' => $name,
        'schemaName' => $schema,
        'hostName' => $hostName,
        'hostUuid' => $hostUuid,
        'createdAt' => $now,
        'updatedAt' => $now,
        'revision' => 1,
        'members' => [['name' => $hostName, 'uuid' => $hostUuid, 'lastSeen' => $now]],
        'materials' => $materials,
        'stagingKeys' => $staging,
        // Proof of being the creator that does not depend on Mojang — see
        // require_verified_host. Handed over once, here, and never in another snapshot.
        'hostSecret' => bin2hex(random_bytes(18)),
    ];
    $sessions[$code] = $sess;
    save_sessions($sessionsFile, $sessions);
    respond_session($sess, true);
}

if ($method === 'POST' && preg_match('#^/v1/sessions/([^/]+)/(join|claim|deliver|staging|leave|close|update|kick|release_claims|exclude)/?$#', $path, $m)) {
    $code = normalize_code($m[1]);
    $action = $m[2];
    $body = read_body();
    if (!isset($sessions[$code])) {
        respond(404, ['error' => 'not found']);
    }
    $sess = &$sessions[$code];

    if ($action === 'join') {
        [$uuid, $actorName] = clan_actor($body);
        $name = $actorName !== '?' ? $actorName : (string)($body['name'] ?? '?');
        if ($uuid === '') {
            respond(400, ['error' => 'uuid required']);
        }
        // A deliberate re-join lifts the kicked flag: kicks are moderation, not a ban.
        $kicked = is_array($sess['kicked'] ?? null) ? $sess['kicked'] : [];
        if ($kicked !== []) {
            $sess['kicked'] = array_values(array_filter(
                $kicked,
                static fn($k) => strcasecmp((string)$k, $uuid) !== 0
            ));
        }
        if (!member_upsert($sess, $name, $uuid)) {
            respond(409, ['error' => 'session full (max ' . MAX_MEMBERS_PER_SESSION . ' members)']);
        }
        touch_sess($sess);
        save_sessions($sessionsFile, $sessions);
        respond_session($sess);
    }

    if ($action === 'claim') {
        [$uuid, $actorName] = clan_actor($body);
        $name = $actorName !== '?' ? $actorName : (string)($body['name'] ?? '?');
        $item = (string)($body['itemId'] ?? '');
        $unclaim = !empty($body['unclaim']);
        if ($uuid === '' || $item === '') {
            respond(400, ['error' => 'itemId/uuid required']);
        }
        if (!isset($sess['materials'][$item])) {
            respond(404, ['error' => 'unknown item']);
        }
        $mat = &$sess['materials'][$item];
        $cur = (string)($mat['claimedBy'] ?? '');
        if ($unclaim) {
            if ($cur !== '' && strcasecmp($cur, $uuid) === 0) {
                clear_claim($mat);
            }
        } else {
            if (!empty($mat['excluded'])) {
                // The host struck this material off the gather; claiming it would put a
                // member to work on something nobody is collecting.
                respond(409, ['error' => 'item excluded from this gather']);
            }
            if ($cur !== '' && strcasecmp($cur, $uuid) !== 0) {
                respond(409, ['error' => 'already claimed by ' . ($mat['claimedName'] ?? $cur)]);
            }
            $mat['claimedBy'] = $uuid;
            $mat['claimedName'] = $name;
            // When the claim was taken, in hub time. The client shows "who is carrying what"
            // from this, so a member holding glass and stone is shown on the one they took
            // first — and every client agrees on which that was.
            $mat['claimedAt'] = now_ms();
        }
        member_upsert($sess, $name, $uuid);
        touch_sess($sess);
        save_sessions($sessionsFile, $sessions);
        respond_session($sess);
    }

    if ($action === 'deliver') {
        [$uuid, $actorName] = clan_actor($body);
        $name = $actorName !== '?' ? $actorName : (string)($body['name'] ?? '?');
        // Two shapes: single {"itemId","amount"} and batch {"amounts": {item: n}}.
        // The batch is what the periodic warehouse push sends — parsed as the single
        // shape it read amount=0, so every push bounced with 400 on this hub.
        $amounts = [];
        if (is_array($body['amounts'] ?? null)) {
            foreach ($body['amounts'] as $k => $v) {
                $n = (int)$v;
                if ($n > 0) {
                    $amounts[(string)$k] = $n;
                }
            }
        } else {
            $item = (string)($body['itemId'] ?? '');
            $amount = (int)($body['amount'] ?? 0);
            if ($item !== '' && $amount > 0) {
                $amounts[$item] = $amount;
            }
        }
        if ($uuid === '' || $amounts === []) {
            respond(400, ['error' => 'itemId/amount/uuid required']);
        }
        $known = 0;
        $changed = false;
        foreach ($amounts as $item => $amount) {
            if (!isset($sess['materials'][$item])) {
                continue;
            }
            $known++;
            $mat = &$sess['materials'][$item];
            $need = (int)($mat['need'] ?? 0);
            $del = (int)($mat['delivered'] ?? 0);
            $newDelivered = min($need, max($del, $amount));
            if ($newDelivered !== $del) {
                $mat['delivered'] = $newDelivered;
                // Remember who actually raised the count — the client's activity
                // feed used to guess the claim holder, which is often not the
                // person who carried the items in.
                $mat['lastDeliveredBy'] = $name;
                $mat['lastDeliveredAt'] = now_ms();
                $changed = true;
            }
            unset($mat);
        }
        if ($known === 0) {
            respond(404, ['error' => 'unknown item']);
        }
        member_upsert($sess, $name, $uuid);
        if ($changed) {
            touch_sess($sess);
        }
        save_sessions($sessionsFile, $sessions);
        respond_session($sess);
    }

    if ($action === 'staging') {
        [$uuid, $actorName] = clan_actor($body);
        $name = $actorName !== '?' ? $actorName : (string)($body['name'] ?? '?');
        $keysIn = $body['stagingKeys'] ?? [];
        $replace = !empty($body['replace']);
        if ($uuid === '') {
            respond(400, ['error' => 'uuid required']);
        }
        if (!is_array($keysIn)) {
            respond(400, ['error' => 'stagingKeys must be list']);
        }
        $clean = clean_staging_keys($keysIn);
        if ($replace) {
            $merged = $clean;
        } else {
            $merged = is_array($sess['stagingKeys'] ?? null) ? $sess['stagingKeys'] : [];
            foreach ($clean as $k) {
                if (!in_array($k, $merged, true)) {
                    $merged[] = $k;
                }
            }
        }
        if (count($merged) > MAX_STAGING_KEYS_PER_SESSION) {
            respond(400, ['error' => 'too many staging keys (max ' . MAX_STAGING_KEYS_PER_SESSION . ')']);
        }
        $sess['stagingKeys'] = $merged;
        member_upsert($sess, $name, $uuid);
        touch_sess($sess);
        save_sessions($sessionsFile, $sessions);
        respond_session($sess);
    }

    if ($action === 'leave') {
        [$uuid, $actorName] = clan_actor($body);
        foreach ($sess['materials'] as &$mat) {
            if (strcasecmp((string)($mat['claimedBy'] ?? ''), $uuid) === 0) {
                clear_claim($mat);
            }
        }
        unset($mat);
        $sess['members'] = array_values(array_filter(
            $sess['members'] ?? [],
            static fn($m) => strcasecmp((string)($m['uuid'] ?? ''), $uuid) !== 0
        ));
        touch_sess($sess);
        save_sessions($sessionsFile, $sessions);
        respond_session($sess);
    }

    if ($action === 'update') {
        // Rename the gather (host only). The name every member sees on their panel.
        $raw = trim((string)($body['name'] ?? ''));
        if ($raw === '') {
            respond(400, ['error' => 'name required']);
        }
        require_verified_host($sess, 'only host', $body);
        $name = clamp_str($raw, MAX_NAME_LEN);
        $sess['name'] = $name;
        $sess['schemaName'] = $name;
        touch_sess($sess);
        save_sessions($sessionsFile, $sessions);
        respond_session($sess);
    }

    if ($action === 'kick') {
        // Remove a member (host only): claims released, roster row gone, and the
        // heartbeat cannot re-add them — only a fresh join by code can.
        $target = strtolower(trim((string)($body['target'] ?? '')));
        if ($target === '') {
            respond(400, ['error' => 'target required']);
        }
        require_verified_host($sess, 'only host', $body);
        if ($target === strtolower((string)($sess['hostUuid'] ?? ''))) {
            respond(400, ['error' => 'host cannot kick self']);
        }
        $members = is_array($sess['members'] ?? null) ? $sess['members'] : [];
        $kept = array_values(array_filter(
            $members,
            static fn($m) => strtolower((string)($m['uuid'] ?? '')) !== $target
        ));
        if (count($kept) === count($members)) {
            respond(404, ['error' => 'no such member']);
        }
        $sess['members'] = $kept;
        if (is_array($sess['materials'] ?? null)) {
            foreach ($sess['materials'] as &$mat) {
                if (strtolower((string)($mat['claimedBy'] ?? '')) === $target) {
                    clear_claim($mat);
                }
            }
            unset($mat);
        }
        $kicked = is_array($sess['kicked'] ?? null) ? $sess['kicked'] : [];
        $already = false;
        foreach ($kicked as $k) {
            if (strtolower((string)$k) === $target) {
                $already = true;
                break;
            }
        }
        if (!$already) {
            $kicked[] = $target;
            // Oldest entries fall off past the cap — see MAX_KICKED_TRACKED.
            $kicked = array_slice($kicked, -MAX_KICKED_TRACKED);
        }
        $sess['kicked'] = $kicked;
        touch_sess($sess);
        save_sessions($sessionsFile, $sessions);
        respond_session($sess);
    }

    if ($action === 'release_claims') {
        // Clear every claim (host only) — the reset button for a stalled evening.
        require_verified_host($sess, 'only host', $body);
        if (is_array($sess['materials'] ?? null)) {
            foreach ($sess['materials'] as &$mat) {
                if (!empty($mat['claimedBy'])) {
                    clear_claim($mat);
                }
            }
            unset($mat);
        }
        touch_sess($sess);
        save_sessions($sessionsFile, $sessions);
        respond_session($sess);
    }

    if ($action === 'exclude') {
        // Strike materials off the gather, or put them back (host only).
        //
        // The host opened the schematic, so the host is the one who knows the shell is
        // already built and nobody should be hauling 40k stone for it. Excluded materials
        // stay in the session — their delivered history is real and must not be rewritten —
        // they are just marked, and every client greys them out and stops counting them
        // toward progress. Mirrors _exclude in clan_hub.py.
        require_verified_host($sess, 'only the gather host can exclude items', $body);
        $updates = [];
        if (isset($body['items']) && is_array($body['items'])) {
            foreach ($body['items'] as $k => $v) {
                $key = clamp_str(trim((string)$k), MAX_ITEM_ID_LEN);
                if ($key !== '') {
                    $updates[$key] = (bool)$v;
                }
            }
        } else {
            $item = clamp_str(trim((string)($body['itemId'] ?? '')), MAX_ITEM_ID_LEN);
            if ($item === '') {
                respond(400, ['error' => 'itemId required']);
            }
            // Absent "excluded" means exclude: a bare {"itemId"} reads as "drop this one".
            $updates[$item] = array_key_exists('excluded', $body) ? (bool)$body['excluded'] : true;
        }
        if ($updates === []) {
            respond(400, ['error' => 'no items']);
        }
        foreach (array_keys($updates) as $key) {
            if (!isset($sess['materials'][$key])) {
                respond(404, ['error' => 'unknown item ' . $key]);
            }
        }
        $changed = false;
        foreach ($updates as $key => $flag) {
            $mat = &$sess['materials'][$key];
            if ((bool)($mat['excluded'] ?? false) === $flag) {
                unset($mat);
                continue;
            }
            $mat['excluded'] = $flag;
            if ($flag) {
                // Whoever was on it is off it. Leaving the claim would show a member
                // carrying a material the gather no longer wants.
                clear_claim($mat);
            }
            $changed = true;
            unset($mat);
        }
        if ($changed) {
            touch_sess($sess);
            save_sessions($sessionsFile, $sessions);
        }
        respond_session($sess);
    }

    if ($action === 'close') {
        // An empty uuid used to short-circuit the host check and fall through to the
        // delete below, letting anyone drop any session by simply omitting the field —
        // and later, a spoofed one could impersonate the host. Only a Mojang-verified
        // host closes now, same rule as clan_hub.py.
        require_verified_host($sess, 'only host can close', $body);
        unset($sessions[$code]);
        save_sessions($sessionsFile, $sessions);
        respond(200, ['ok' => true, 'code' => $code]);
    }
}

respond(404, ['error' => 'not found']);
