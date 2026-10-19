package com.grimtorrenter.engine.stats;

/** The resolutions transfer history is kept at. Per torrent: SECOND and MINUTE. Engine-wide: all
 * three. See design_docs/0102. */
public enum HistoryResolution {
    SECOND, MINUTE, HOUR
}
