package com.grimtorrenter.engine.events;

/**
 * A closed, curated set of library-management-relevant occurrences - not a raw state-transition
 * dump. The frontend renders a fixed icon/label per type rather than an arbitrary free-form
 * string, matching TorrentState's own closed-enum precedent. See design_docs/0055.
 *
 * <p>Every type but SERVER_STARTED, DHT_UNAVAILABLE, and PEER_SERVER_UNAVAILABLE is
 * torrent-scoped (LibraryEvent.infoHash/torrentName are non-null). SERVER_STARTED is the
 * first engine-wide event - LibraryEvent's own Javadoc anticipated exactly this. Added
 * 2026-08-26 so a timeline of events can be correlated against process restarts (e.g. an
 * auto-updater like Watchtower recreating the container) - see design_docs/0055's own dated
 * addendum. DHT_UNAVAILABLE/PEER_SERVER_UNAVAILABLE (design_docs/0059) record a bind failure
 * at startup for their respective subsystem - each fires at most once per process lifetime,
 * since neither subsystem retries binding after construction.
 */
public enum EventType {
    ADDED,
    COMPLETED,
    ERROR,
    REMOVED,
    SEEDING_LIMIT_REACHED,
    SERVER_STARTED,
    DHT_UNAVAILABLE,
    PEER_SERVER_UNAVAILABLE
}
