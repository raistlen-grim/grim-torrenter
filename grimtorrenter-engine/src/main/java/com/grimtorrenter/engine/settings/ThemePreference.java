package com.grimtorrenter.engine.settings;

/**
 * The frontend's light/dark theme preference (design_docs/0032's "Manual theme switcher" section) - purely a display
 * preference with no engine/protocol relevance, unlike every other Settings field, but stored
 * here anyway (not a separate store) since that's what "Backend Settings API" was chosen to
 * mean: the same GET/PUT /api/settings round-trip and Settings record as everything else, so
 * it follows the user to any browser/device hitting this self-hosted instance. SYSTEM means
 * "follow the OS/browser preference" - the frontend resolves that live via
 * `prefers-color-scheme`, not this store; this only remembers which of the three the user
 * picked.
 */
public enum ThemePreference {
    SYSTEM,
    LIGHT,
    DARK
}
