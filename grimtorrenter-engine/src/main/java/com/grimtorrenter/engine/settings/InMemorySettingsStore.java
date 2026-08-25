package com.grimtorrenter.engine.settings;

/**
 * A simple in-memory SettingsStore, no persistence - the default where no real (persisted)
 * store is wired in (e.g. TorrentEngine's lower-arity constructors, which exist purely so
 * every pre-existing caller/test is unaffected by settings-consuming features added after
 * they were written - see design_docs/0042), and directly useful in tests that want to
 * exercise live-settings-consuming code without a real backing file.
 */
public final class InMemorySettingsStore implements SettingsStore {

    private volatile Settings current;

    public InMemorySettingsStore() {
        this(Settings.defaults());
    }

    public InMemorySettingsStore(Settings initial) {
        this.current = initial;
    }

    @Override
    public Settings current() {
        return current;
    }

    @Override
    public void update(Settings settings) {
        current = settings;
    }
}
