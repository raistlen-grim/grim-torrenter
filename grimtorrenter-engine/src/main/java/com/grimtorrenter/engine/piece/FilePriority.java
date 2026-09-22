package com.grimtorrenter.engine.piece;

/** Per-file download priority. SKIP means never requested; the rest are all wanted and differ
 * only in fetch order. See design_docs/0075. */
public enum FilePriority {
    SKIP(0),
    LOW(1),
    MEDIUM(2),
    HIGH(3);

    private final int tier;

    FilePriority(int tier) {
        this.tier = tier;
    }

    /** 0 for SKIP (unwanted), 1-3 for LOW/MEDIUM/HIGH - higher is fetched first. */
    public int tier() {
        return tier;
    }

    public boolean isWanted() {
        return this != SKIP;
    }
}
