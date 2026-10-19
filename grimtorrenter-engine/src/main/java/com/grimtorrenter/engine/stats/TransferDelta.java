package com.grimtorrenter.engine.stats;

/** Bytes moved in each direction since the previous sampling tick. See design_docs/0102. */
public record TransferDelta(long downloadBytes, long uploadBytes) {

    public static final TransferDelta NONE = new TransferDelta(0, 0);
}
