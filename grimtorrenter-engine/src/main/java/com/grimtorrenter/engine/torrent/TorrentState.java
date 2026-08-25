package com.grimtorrenter.engine.torrent;

/**
 * VERIFYING is entered by TorrentSession.restoreAsync() while it re-hashes
 * existing on-disk data after a process restart - see design_docs/0026.
 */
public enum TorrentState {
    DOWNLOADING, VERIFYING, SEEDING, STOPPED, ERROR
}
