<?php
/**
 * Mojang-backed player authentication — PHP counterpart of clan_auth.py.
 *
 * Same handshake as the Python hub:
 *   GET  /v1/auth/challenge   -> nonce
 *   client calls Mojang joinServer with it (access token stays in the game)
 *   POST /v1/auth/verify      -> hub asks hasJoined, mints a session token
 *   later requests send X-Clan-Session
 *
 * The difference from the Python hub is storage: PHP starts fresh on every
 * request, so nonces and tokens live in a small JSON file next to sessions.json
 * instead of in memory. It is written under an exclusive lock — the same
 * read-modify-write hazard as the session store.
 */
declare(strict_types=1);

const CLAN_AUTH_CHALLENGE_TTL = 60;
const CLAN_AUTH_SESSION_TTL = 12 * 3600;
const CLAN_AUTH_MAX_CHALLENGES = 512;
const CLAN_AUTH_MAX_SESSIONS = 512;
const CLAN_AUTH_MOJANG_TIMEOUT = 8;
const CLAN_AUTH_HAS_JOINED = 'https://sessionserver.mojang.com/session/minecraft/hasJoined';

/** @var string Absolute path of the auth state file. */
$GLOBALS['clan_auth_file'] = '';

function clan_auth_init(string $dataDir): void
{
    $GLOBALS['clan_auth_file'] = rtrim($dataDir, '/\\') . '/auth.json';
}

/**
 * Read-modify-write the auth state under an exclusive lock.
 *
 * @param callable(array): array $mutator receives state, returns new state
 * @return mixed whatever the mutator stored in $out
 */
function clan_auth_with_state(callable $mutator)
{
    $file = (string)$GLOBALS['clan_auth_file'];
    if ($file === '') {
        return null;
    }
    $lockPath = $file . '.lock';
    $lock = fopen($lockPath, 'c');
    if ($lock === false) {
        return null;
    }
    flock($lock, LOCK_EX);
    try {
        $state = ['challenges' => [], 'sessions' => []];
        if (is_file($file)) {
            $raw = file_get_contents($file);
            if ($raw !== false && $raw !== '') {
                $decoded = json_decode($raw, true);
                if (is_array($decoded)) {
                    $state['challenges'] = is_array($decoded['challenges'] ?? null) ? $decoded['challenges'] : [];
                    $state['sessions'] = is_array($decoded['sessions'] ?? null) ? $decoded['sessions'] : [];
                }
            }
        }
        $state = clan_auth_purge($state);
        $out = null;
        $state = $mutator($state, $out);
        $json = json_encode($state, JSON_UNESCAPED_UNICODE);
        if ($json !== false) {
            $tmp = $file . '.tmp';
            file_put_contents($tmp, $json);
            rename($tmp, $file);
        }
        return $out;
    } finally {
        flock($lock, LOCK_UN);
        fclose($lock);
    }
}

function clan_auth_purge(array $state): array
{
    $now = time();
    foreach ($state['challenges'] as $nonce => $issued) {
        if ($now - (int)$issued > CLAN_AUTH_CHALLENGE_TTL) {
            unset($state['challenges'][$nonce]);
        }
    }
    foreach ($state['sessions'] as $token => $data) {
        if ((int)($data['expires'] ?? 0) < $now) {
            unset($state['sessions'][$token]);
        }
    }
    return $state;
}

/** Issue a single-use nonce, or null when overloaded. */
function clan_auth_new_challenge(): ?string
{
    return clan_auth_with_state(static function (array $state, &$out): array {
        if (count($state['challenges']) >= CLAN_AUTH_MAX_CHALLENGES) {
            $out = null;
            return $state;
        }
        $nonce = bin2hex(random_bytes(16));
        $state['challenges'][$nonce] = time();
        $out = $nonce;
        return $state;
    });
}

/** Ask Mojang whether this player really joined with this nonce. */
function clan_auth_ask_mojang(string $username, string $nonce): ?array
{
    $url = CLAN_AUTH_HAS_JOINED . '?' . http_build_query([
        'username' => $username,
        'serverId' => $nonce,
    ]);
    $context = stream_context_create([
        'http' => ['timeout' => CLAN_AUTH_MOJANG_TIMEOUT, 'ignore_errors' => true],
    ]);
    $raw = @file_get_contents($url, false, $context);
    // 204 (empty body) means "no, that player did not join with that nonce".
    if ($raw === false || $raw === '') {
        return null;
    }
    $data = json_decode($raw, true);
    if (!is_array($data) || empty($data['id']) || empty($data['name'])) {
        return null;
    }
    return $data;
}

/** Mojang returns UUIDs without dashes; the mod sends the dashed form. */
function clan_auth_dashed_uuid(string $raw): string
{
    $s = strtolower(str_replace('-', '', $raw));
    if (strlen($s) !== 32) {
        return strtolower($raw);
    }
    return substr($s, 0, 8) . '-' . substr($s, 8, 4) . '-' . substr($s, 12, 4)
        . '-' . substr($s, 16, 4) . '-' . substr($s, 20, 12);
}

/**
 * Complete the handshake.
 *
 * @return array{token: string, uuid: string, name: string}|null
 */
function clan_auth_verify(string $username, string $nonce): ?array
{
    if ($username === '' || $nonce === '') {
        return null;
    }
    // Consume the nonce first: it must be usable at most once, even if the Mojang
    // call below fails.
    $known = clan_auth_with_state(static function (array $state, &$out) use ($nonce): array {
        $issued = $state['challenges'][$nonce] ?? null;
        unset($state['challenges'][$nonce]);
        $out = $issued !== null && (time() - (int)$issued) <= CLAN_AUTH_CHALLENGE_TTL;
        return $state;
    });
    if ($known !== true) {
        return null;
    }

    $profile = clan_auth_ask_mojang($username, $nonce);
    if ($profile === null) {
        return null;
    }
    $uuid = clan_auth_dashed_uuid((string)$profile['id']);
    $name = (string)$profile['name'];

    return clan_auth_with_state(static function (array $state, &$out) use ($uuid, $name): array {
        if (count($state['sessions']) >= CLAN_AUTH_MAX_SESSIONS) {
            // Drop the closest to expiry rather than refusing a legitimate login.
            $oldest = null;
            $oldestExp = PHP_INT_MAX;
            foreach ($state['sessions'] as $t => $d) {
                $exp = (int)($d['expires'] ?? 0);
                if ($exp < $oldestExp) {
                    $oldestExp = $exp;
                    $oldest = $t;
                }
            }
            if ($oldest !== null) {
                unset($state['sessions'][$oldest]);
            }
        }
        $token = rtrim(strtr(base64_encode(random_bytes(24)), '+/', '-_'), '=');
        $state['sessions'][$token] = [
            'uuid' => $uuid,
            'name' => $name,
            'expires' => time() + CLAN_AUTH_SESSION_TTL,
        ];
        $out = ['token' => $token, 'uuid' => $uuid, 'name' => $name];
        return $state;
    });
}

/**
 * Identity behind a session token, or null when absent/expired.
 * Sliding expiry, so an active player is never logged out mid-gather.
 *
 * @return array{uuid: string, name: string}|null
 */
function clan_auth_resolve(string $token): ?array
{
    if ($token === '') {
        return null;
    }
    return clan_auth_with_state(static function (array $state, &$out) use ($token): array {
        $data = $state['sessions'][$token] ?? null;
        if (!is_array($data)) {
            $out = null;
            return $state;
        }
        $state['sessions'][$token]['expires'] = time() + CLAN_AUTH_SESSION_TTL;
        $out = ['uuid' => (string)$data['uuid'], 'name' => (string)$data['name']];
        return $state;
    });
}
