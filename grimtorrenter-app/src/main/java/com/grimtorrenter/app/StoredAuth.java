package com.grimtorrenter.app;

/** auth.json's on-disk shape - just the current PasswordHasher-encoded hash string, or null
 * if no password has ever been set. Deliberately its own file, never merged into Settings/
 * settings.json (which GET /api/settings echoes back verbatim) - see design_docs/0061. */
record StoredAuth(String passwordHash) {
}
