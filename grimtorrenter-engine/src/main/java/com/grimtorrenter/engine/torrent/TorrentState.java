package com.grimtorrenter.engine.torrent;

/**
 * VERIFYING is entered by TorrentSession.restoreAsync() while it re-hashes
 * existing on-disk data after a process restart - see design_docs/0026.
 *
 * <p>STARTING is a start from STOPPED whose first tracker announce (or DHT fallback) is still in
 * flight; it becomes DOWNLOADING/SEEDING, or ERROR if nothing answered. It lets the engine start a
 * torrent in the background and still say truthfully what it is doing. A retry from the
 * retryable ERROR state stays in ERROR while it runs. See design_docs/0098.
 */
public enum TorrentState {
    DOWNLOADING, VERIFYING, SEEDING, STOPPED, ERROR, STARTING;

    /** DOWNLOADING or SEEDING: announced, with peer connections, choking and PEX running. Every
     * other state has no peers. */
    public boolean isRunning() {
        return this == DOWNLOADING || this == SEEDING;
    }
}
