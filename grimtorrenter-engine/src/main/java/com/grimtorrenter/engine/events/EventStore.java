package com.grimtorrenter.engine.events;

import java.util.List;

/**
 * The single place library events (design_docs/0055) are recorded and read back from -
 * TorrentEngine and grimtorrenter-app's TorrentEventListener both record through this rather
 * than each keeping their own history, mirroring SettingsStore's own "one source of truth,
 * read through the interface" shape (design_docs/0041). Unlike SettingsStore, there is no
 * single current() value to keep in memory - this is an append-only feed, read back in full
 * (or filtered to one torrent) rather than swapped as a whole.
 */
public interface EventStore {

    void record(LibraryEvent event);

    /** Every currently retained event, newest first. */
    List<LibraryEvent> all();

    /** Every currently retained event for one torrent, newest first. */
    List<LibraryEvent> forTorrent(String infoHash);
}
