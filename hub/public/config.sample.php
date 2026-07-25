<?php
/** Copy to config.php and set token. */
return [
    'token' => 'CHANGE_ME_SAME_AS_MOD',
    'ttl_sec' => 7 * 24 * 3600,
    // sessions.json lives one level up from public/
    'data_dir' => __DIR__ . '/../data',
];
