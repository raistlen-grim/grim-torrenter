package com.grimtorrenter.engine.events;

/**
 * A closed, curated set of library-management-relevant occurrences - not a raw state-transition
 * dump. The frontend renders a fixed icon/label per type rather than an arbitrary free-form
 * string, matching TorrentState's own closed-enum precedent. See design_docs/0055.
 */
public enum EventType {
    ADDED,
    COMPLETED,
    ERROR,
    REMOVED,
    SEEDING_LIMIT_REACHED
}
