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
header('Access-Control-Allow-Headers: Content-Type, X-Clan-Token');
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
$token = (string)($config['token'] ?? '');
$ttlMs = (int)($config['ttl_sec'] ?? 604800) * 1000;

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

function purge(array &$sessions, int $ttlMs): void
{
    $cutoff = now_ms() - $ttlMs;
    $changed = false;
    foreach ($sessions as $c => $s) {
        $u = (int)($s['updatedAt'] ?? 0);
        if ($u < $cutoff) {
            unset($sessions[$c]);
            $changed = true;
        }
    }
    if ($changed) {
        // caller saves
    }
}

function member_upsert(array &$sess, string $name, string $uuid): void
{
    $members = &$sess['members'];
    if (!is_array($members)) {
        $members = [];
    }
    foreach ($members as &$m) {
        if (strcasecmp((string)($m['uuid'] ?? ''), $uuid) === 0) {
            $m['name'] = $name !== '' ? $name : ($m['name'] ?? '?');
            $m['lastSeen'] = now_ms();
            return;
        }
    }
    $members[] = ['name' => $name !== '' ? $name : '?', 'uuid' => $uuid, 'lastSeen' => now_ms()];
}

function touch_sess(array &$sess): void
{
    $sess['updatedAt'] = now_ms();
    $sess['revision'] = (int)($sess['revision'] ?? 0) + 1;
}

/** Hard cap on a request body: the whole store is read, modified and rewritten per
 *  request, so unbounded input is both a memory and a disk-fill vector. */
const MAX_BODY_BYTES = 512 * 1024;

function read_body(): array
{
    $declared = (int)($_SERVER['CONTENT_LENGTH'] ?? 0);
    if ($declared > MAX_BODY_BYTES) {
        respond(413, ['error' => 'body too large (max ' . MAX_BODY_BYTES . ' bytes)']);
    }
    $raw = file_get_contents('php://input', false, null, 0, MAX_BODY_BYTES + 1);
    if ($raw === false || $raw === '') {
        return [];
    }
    if (strlen($raw) > MAX_BODY_BYTES) {
        respond(413, ['error' => 'body too large (max ' . MAX_BODY_BYTES . ' bytes)']);
    }
    $j = json_decode($raw, true);
    return is_array($j) ? $j : [];
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

check_token($token);
$path = api_path();
$method = $_SERVER['REQUEST_METHOD'] ?? 'GET';

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
$isWrite = ($_SERVER['REQUEST_METHOD'] ?? 'GET') === 'POST';
$lockHandle = fopen($dataDir . '/sessions.lock', 'c');
if ($lockHandle !== false) {
    flock($lockHandle, $isWrite ? LOCK_EX : LOCK_SH);
    register_shutdown_function(static function () use ($lockHandle) {
        flock($lockHandle, LOCK_UN);
        fclose($lockHandle);
    });
}

$sessions = load_sessions($sessionsFile);
purge($sessions, $ttlMs);

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
    respond(200, $sessions[$code]);
}

if ($method === 'POST' && $path === '/v1/sessions') {
    $body = read_body();
    $materialsIn = $body['materials'] ?? null;
    if (!is_array($materialsIn) || $materialsIn === []) {
        respond(400, ['error' => 'materials required']);
    }
    $hostName = (string)($body['hostName'] ?? $body['name'] ?? 'Host');
    $hostUuid = (string)($body['hostUuid'] ?? $body['uuid'] ?? '');
    if ($hostUuid === '') {
        respond(400, ['error' => 'hostUuid required']);
    }
    $name = (string)($body['name'] ?? 'Build');
    $schema = (string)($body['schemaName'] ?? $name);
    $materials = [];
    foreach ($materialsIn as $k => $v) {
        $need = (int)$v;
        if ($need > 0) {
            $materials[(string)$k] = [
                'need' => $need,
                'delivered' => 0,
                'claimedBy' => null,
                'claimedName' => null,
            ];
        }
    }
    if ($materials === []) {
        respond(400, ['error' => 'empty materials']);
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
        'stagingKeys' => [],
    ];
    $sessions[$code] = $sess;
    save_sessions($sessionsFile, $sessions);
    respond(200, $sess);
}

if ($method === 'POST' && preg_match('#^/v1/sessions/([^/]+)/(join|claim|deliver|staging|leave|close)/?$#', $path, $m)) {
    $code = normalize_code($m[1]);
    $action = $m[2];
    $body = read_body();
    if (!isset($sessions[$code]) && $action !== 'close') {
        // close may 404 too
    }
    if (!isset($sessions[$code])) {
        respond(404, ['error' => 'not found']);
    }
    $sess = &$sessions[$code];

    if ($action === 'join') {
        $uuid = (string)($body['uuid'] ?? '');
        $name = (string)($body['name'] ?? '?');
        if ($uuid === '') {
            respond(400, ['error' => 'uuid required']);
        }
        member_upsert($sess, $name, $uuid);
        touch_sess($sess);
        save_sessions($sessionsFile, $sessions);
        respond(200, $sess);
    }

    if ($action === 'claim') {
        $uuid = (string)($body['uuid'] ?? '');
        $name = (string)($body['name'] ?? '?');
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
                $mat['claimedBy'] = null;
                $mat['claimedName'] = null;
            }
        } else {
            if ($cur !== '' && strcasecmp($cur, $uuid) !== 0) {
                respond(409, ['error' => 'already claimed by ' . ($mat['claimedName'] ?? $cur)]);
            }
            $mat['claimedBy'] = $uuid;
            $mat['claimedName'] = $name;
        }
        member_upsert($sess, $name, $uuid);
        touch_sess($sess);
        save_sessions($sessionsFile, $sessions);
        respond(200, $sess);
    }

    if ($action === 'deliver') {
        $uuid = (string)($body['uuid'] ?? '');
        $name = (string)($body['name'] ?? '?');
        $item = (string)($body['itemId'] ?? '');
        $amount = (int)($body['amount'] ?? 0);
        if ($uuid === '' || $item === '' || $amount <= 0) {
            respond(400, ['error' => 'itemId/amount/uuid required']);
        }
        if (!isset($sess['materials'][$item])) {
            respond(404, ['error' => 'unknown item']);
        }
        $mat = &$sess['materials'][$item];
        $need = (int)($mat['need'] ?? 0);
        $del = (int)($mat['delivered'] ?? 0);
        $mat['delivered'] = min($need, max($del, $amount));
        member_upsert($sess, $name, $uuid);
        touch_sess($sess);
        save_sessions($sessionsFile, $sessions);
        respond(200, $sess);
    }

    if ($action === 'staging') {
        $uuid = (string)($body['uuid'] ?? '');
        $name = (string)($body['name'] ?? '?');
        $keysIn = $body['stagingKeys'] ?? [];
        $replace = !empty($body['replace']);
        if ($uuid === '') {
            respond(400, ['error' => 'uuid required']);
        }
        if (!is_array($keysIn)) {
            respond(400, ['error' => 'stagingKeys must be list']);
        }
        $clean = [];
        foreach ($keysIn as $k) {
            $s = trim((string)$k);
            if ($s !== '' && !in_array($s, $clean, true)) {
                $clean[] = $s;
            }
        }
        if ($replace) {
            $sess['stagingKeys'] = $clean;
        } else {
            $cur = is_array($sess['stagingKeys'] ?? null) ? $sess['stagingKeys'] : [];
            foreach ($clean as $k) {
                if (!in_array($k, $cur, true)) {
                    $cur[] = $k;
                }
            }
            $sess['stagingKeys'] = $cur;
        }
        member_upsert($sess, $name, $uuid);
        touch_sess($sess);
        save_sessions($sessionsFile, $sessions);
        respond(200, $sess);
    }

    if ($action === 'leave') {
        $uuid = (string)($body['uuid'] ?? '');
        foreach ($sess['materials'] as &$mat) {
            if (strcasecmp((string)($mat['claimedBy'] ?? ''), $uuid) === 0) {
                $mat['claimedBy'] = null;
                $mat['claimedName'] = null;
            }
        }
        unset($mat);
        $sess['members'] = array_values(array_filter(
            $sess['members'] ?? [],
            static fn($m) => strcasecmp((string)($m['uuid'] ?? ''), $uuid) !== 0
        ));
        touch_sess($sess);
        save_sessions($sessionsFile, $sessions);
        respond(200, $sess);
    }

    if ($action === 'close') {
        $uuid = (string)($body['uuid'] ?? '');
        // An empty uuid used to short-circuit the host check and fall through to the
        // delete below, letting anyone drop any session by simply omitting the field.
        if ($uuid === '' || strcasecmp((string)($sess['hostUuid'] ?? ''), $uuid) !== 0) {
            respond(403, ['error' => 'only host can close']);
        }
        unset($sessions[$code]);
        save_sessions($sessionsFile, $sessions);
        respond(200, ['ok' => true, 'code' => $code]);
    }
}

respond(404, ['error' => 'not found']);
