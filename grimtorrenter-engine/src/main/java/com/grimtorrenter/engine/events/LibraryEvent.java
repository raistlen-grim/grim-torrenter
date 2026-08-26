package com.grimtorrenter.engine.events;

import java.time.Instant;

/**
 * One entry in the library event feed (design_docs/0055) - a thing a user would want to review
 * to manage their library, not a raw state-transition dump. infoHash/torrentName are nullable:
 * every event recorded so far is torrent-scoped, but nothing rules out a future engine-wide
 * event (e.g. a DHT bootstrap failure) that isn't.
 */
public record LibraryEvent(Instant timestamp, EventType type, String infoHash, String torrentName, String message) {
}
