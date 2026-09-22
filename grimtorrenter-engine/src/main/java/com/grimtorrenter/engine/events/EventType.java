package com.grimtorrenter.engine.events;

/**
 * A closed, curated set of library-management-relevant occurrences - not a raw state-transition
 * dump. The frontend renders a fixed icon/label per type rather than an arbitrary free-form
 * string, matching TorrentState's own closed-enum precedent. See design_docs/0055.
 *
 * <p>Every type but SERVER_STARTED, DHT_UNAVAILABLE, PEER_SERVER_UNAVAILABLE, and the tracker pair is
 * torrent-scoped (LibraryEvent.infoHash/torrentName are non-null). SERVER_STARTED is the
 * first engine-wide event - LibraryEvent's own Javadoc anticipated exactly this. Added
 * 2026-08-26 so a timeline of events can be correlated against process restarts (e.g. an
 * auto-updater like Watchtower recreating the container) - see design_docs/0055's own dated
 * addendum. DHT_UNAVAILABLE/PEER_SERVER_UNAVAILABLE (design_docs/0059) record a bind failure
 * at startup for their respective subsystem - each fires at most once per process lifetime,
 * since neither subsystem retries binding after construction.
 *
 * <p>MAGNET_ADD_FAILED (design_docs/0060) is a partial exception to the torrent-scoped rule
 * above: infoHash is always set (the magnet's own info hash), but torrentName is always null
 * even when the magnet carried a display name - unlike every other torrent-scoped type, this
 * infoHash was never actually added as a real torrent, so the Events page can't safely render
 * it as a link to one. Any display name is folded into the free-text message instead.
 *
 * <p>TRACKER_UNREACHABLE/TRACKER_RECOVERED (design_docs/0055's own addendum and its 2026-09-21
 * revision) are engine-wide (null infoHash/torrentName), one pair per tracker URL no matter how
 * many torrents use it, with the URL folded into the message. Recorded via
 * TrackedTrackerClient's TrackerStatusListener callback, collapsed across torrents by
 * TrackerReachability, and adapted into library events by TorrentEngine. Only wired up for a
 * torrent's own persistent tracker client (addTorrent()/restoreOne()), not the throwaway client
 * used to probe trackers during magnet metadata resolution.
 *
 * <p>LSD_UNAVAILABLE (design_docs/0062) is engine-wide like DHT_UNAVAILABLE/
 * PEER_SERVER_UNAVAILABLE above (null infoHash/torrentName) and follows the exact same
 * fires-at-most-once-per-process-lifetime shape - LsdService never retries binding after
 * construction either.
 *
 * <p>BLOCKLIST_UPDATED/BLOCKLIST_FAILED (design_docs/0078) are engine-wide (null infoHash/
 * torrentName): a list finished loading (the message says how many ranges and from where), or a
 * load failed (the message says why - the previous list stays in force). BLOCKLIST_FAILED is only
 * recorded when the reason differs from the last one, so a persistently unreachable URL doesn't
 * fill the log on every retry.
 */
public enum EventType {
    ADDED,
    COMPLETED,
    ERROR,
    REMOVED,
    SEEDING_LIMIT_REACHED,
    SERVER_STARTED,
    DHT_UNAVAILABLE,
    PEER_SERVER_UNAVAILABLE,
    MAGNET_ADD_FAILED,
    TRACKER_UNREACHABLE,
    TRACKER_RECOVERED,
    LSD_UNAVAILABLE,
    BLOCKLIST_UPDATED,
    BLOCKLIST_FAILED
}
