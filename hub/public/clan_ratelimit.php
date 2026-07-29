<?php
/**
 * Per-address rate limiting — PHP counterpart of clan_ratelimit.py.
 *
 * Why: session codes are the only thing standing between an outsider and a clan's
 * data, and CM-XXXX is 1,048,576 codes — unthrottled, a single home connection walks
 * that in tens of minutes and lands on a live session. The README's "the shared
 * token can stay empty" argument leans entirely on this throttle existing, but it
 * existed only in the Python hub — the recommended permanent-HTTPS PHP deployment
 * was the one left wide open, auth nonce grinding included.
 *
 * Same buckets, numbers and windows as clan_ratelimit.py: guessing is throttled
 * hard, playing is not, failed logins cannot be ground.
 *
 * Storage: PHP shares nothing between requests, so the sliding windows live in APCu
 * when available and fall back to a JSON file next to sessions.json, updated under
 * an exclusive lock — the same read-modify-write discipline as the session store,
 * so it works on bare shared hosting too.
 */
declare(strict_types=1);

/** bucket => [limit, window seconds] — keep in lockstep with clan_ratelimit.py. */
const CLAN_RL_LIMITS = [
    // Reading/joining a session by code: tight, this is the one being guessed at.
    'lookup' => [10, 60],
    // In-session actions: polling is ~20/min per player and a whole clan can sit
    // behind one NAT address, so 900 covers 45 simultaneous players from one IP.
    'action' => [900, 60],
    // Auth attempts — stops nonce grinding.
    'auth' => [20, 60],
];
/** Stop tracking addresses beyond this, so a spoofed-source flood cannot grow the
 *  state file without limit. Oldest entries are dropped first. (APCu enforces its
 *  own memory cap and per-entry TTL, so this applies to the file fallback.) */
const CLAN_RL_MAX_TRACKED = 4096;

/** @var string Absolute path of the fallback state file. */
$GLOBALS['clan_rl_file'] = '';

function clan_rl_init(string $dataDir): void
{
    $GLOBALS['clan_rl_file'] = rtrim($dataDir, '/\\') . '/ratelimit.json';
}

/**
 * Record a hit and report whether it is over the limit.
 *
 * @return int|null null when allowed, otherwise seconds until the caller may retry
 *                  (suitable for a Retry-After header).
 */
function clan_rl_check(string $kind, string $address): ?int
{
    [$limit, $window] = CLAN_RL_LIMITS[$kind] ?? CLAN_RL_LIMITS['action'];
    $addr = $address !== '' ? $address : '?';
    $now = microtime(true);
    if (function_exists('apcu_enabled') && apcu_enabled()) {
        return clan_rl_check_apcu($kind, $addr, $limit, $window, $now);
    }
    return clan_rl_check_file($kind, $addr, $limit, $window, $now);
}

/** @param float[] $stamps */
function clan_rl_over(array $stamps, int $limit, int $window, float $now): ?int
{
    if (count($stamps) < $limit) {
        return null;
    }
    return max(1, (int)($window - ($now - (float)$stamps[0])) + 1);
}

function clan_rl_check_apcu(string $kind, string $addr, int $limit, int $window, float $now): ?int
{
    $key = 'cmrl:' . $kind . ':' . $addr;
    // fetch-modify-store races with a parallel request for the same address; the
    // worst case is one hit counted or missed, which a guessing loop cannot exploit
    // at any useful scale — not worth apcu_cas gymnastics.
    $stamps = apcu_fetch($key);
    if (!is_array($stamps)) {
        $stamps = [];
    }
    $stamps = array_values(array_filter(
        $stamps,
        static fn($t) => $now - (float)$t < $window
    ));
    $retry = clan_rl_over($stamps, $limit, $window, $now);
    if ($retry !== null) {
        return $retry;
    }
    $stamps[] = $now;
    apcu_store($key, $stamps, $window + 1);
    return null;
}

function clan_rl_check_file(string $kind, string $addr, int $limit, int $window, float $now): ?int
{
    $file = (string)$GLOBALS['clan_rl_file'];
    if ($file === '') {
        // Not initialised. Refusing all traffic over a wiring mistake would DoS the
        // hub with its own guard, so fail open — the token/auth layers still stand.
        return null;
    }
    $lock = fopen($file . '.lock', 'c');
    if ($lock === false) {
        return null;
    }
    flock($lock, LOCK_EX);
    try {
        $state = [];
        if (is_file($file)) {
            $decoded = json_decode((string)@file_get_contents($file), true);
            if (is_array($decoded)) {
                $state = $decoded;
            }
        }
        $bucket = is_array($state[$kind] ?? null) ? $state[$kind] : [];
        foreach ($bucket as $a => $stamps) {
            $stamps = array_values(array_filter(
                is_array($stamps) ? $stamps : [],
                static fn($t) => $now - (float)$t < $window
            ));
            if ($stamps === []) {
                unset($bucket[$a]);
            } else {
                $bucket[$a] = $stamps;
            }
        }
        if (count($bucket) >= CLAN_RL_MAX_TRACKED && !isset($bucket[$addr])) {
            // Under a flood, protect the hub itself rather than tracking perfectly.
            $oldestAddr = null;
            $oldestAt = PHP_FLOAT_MAX;
            foreach ($bucket as $a => $stamps) {
                $t = (float)($stamps[0] ?? 0);
                if ($t < $oldestAt) {
                    $oldestAt = $t;
                    $oldestAddr = $a;
                }
            }
            if ($oldestAddr !== null) {
                unset($bucket[$oldestAddr]);
            }
        }
        $stamps = is_array($bucket[$addr] ?? null) ? $bucket[$addr] : [];
        $retry = clan_rl_over($stamps, $limit, $window, $now);
        if ($retry === null) {
            $stamps[] = $now;
            $bucket[$addr] = $stamps;
        }
        $state[$kind] = $bucket;
        $json = json_encode($state);
        if ($json !== false) {
            $tmp = $file . '.tmp';
            if (@file_put_contents($tmp, $json) !== false) {
                @rename($tmp, $file);
            }
        }
        return $retry;
    } finally {
        flock($lock, LOCK_UN);
        fclose($lock);
    }
}
