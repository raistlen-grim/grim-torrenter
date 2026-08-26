package com.grimtorrenter.engine.events;

/**
 * A closed, curated set of library-management-relevant occurrences - not a raw state-transition
 * dump. The frontend renders a fixed icon/label per type rather than an arbitrary free-form
 * string, matching TorrentState's own closed-enum precedent. See design_docs/0055.
 *
 * <p>Every type but SERVER_STARTED is torrent-scoped (LibraryEvent.infoHash/torrentName are
 * non-null). SERVER_STARTED is the first engine-wide event - LibraryEvent's own Javadoc
 * anticipated exactly this. Added 2026-08-26 so a timeline of events can be correlated against
 * process restarts (e.g. an auto-updater like Watchtower recreating the container) - see
 * design_docs/0055's own dated addendum.
 */
public enum EventType {
    ADDED,
    COMPLETED,
    ERROR,
    REMOVED,
    SEEDING_LIMIT_REACHED,
    SERVER_STARTED
}
