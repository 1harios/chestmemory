<?php
/** Copy to config.php and set token. */
return [
    'token' => 'CHANGE_ME_SAME_AS_MOD',
    // Require Mojang-verified players for every mutating request. Defaulting this to
    // false shipped the impersonation hole to every operator who kept the sample:
    // anyone could claim, deliver and leave as anyone else.
    // Setting false is the escape hatch for ONE mod-upgrade window (members still on
    // the old mod keep working); the hub then logs loudly that identities are
    // unverified, and host-only actions (rename/kick/close) demand a verified
    // session regardless. Set it back to true as soon as everyone updated.
    'require_auth' => true,
    'ttl_sec' => 7 * 24 * 3600,
    // sessions.json lives one level up from public/
    'data_dir' => __DIR__ . '/../data',
];
