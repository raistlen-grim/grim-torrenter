package com.grimtorrenter.engine.settings;

/**
 * The single source of truth for live application settings. Every consumer, in either
 * grimtorrenter-engine or grimtorrenter-app, reads through current() rather than caching
 * its own copy or reading the backing storage directly - nobody holds a Settings instance
 * beyond the scope of one use, so nobody can act on a stale value. update() persists
 * durably before the in-memory value changes, so a crash immediately after a save can
 * never leave the in-memory view claiming a change that isn't actually on disk yet. See
 * design_docs/0041.
 */
public interface SettingsStore {

    Settings current();

    void update(Settings settings);
}
