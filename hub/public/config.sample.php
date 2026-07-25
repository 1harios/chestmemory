<?php
/** Copy to config.php and set token. */
return [
    'token' => 'CHANGE_ME_SAME_AS_MOD',
    // Require Mojang-verified players for every mutating request.
    // Leave false for one upgrade window so members on the old mod keep working,
    // then set true — that is what actually stops impersonation.
    'require_auth' => false,
    'ttl_sec' => 7 * 24 * 3600,
    // sessions.json lives one level up from public/
    'data_dir' => __DIR__ . '/../data',
];
