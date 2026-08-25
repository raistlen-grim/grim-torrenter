package com.grimtorrenter.app;

import com.grimtorrenter.engine.engine.TorrentEngine;

public record DhtStatusView(boolean enabled, int nodeCount) {
    public static DhtStatusView from(TorrentEngine.DhtStatus status) {
        return new DhtStatusView(status.enabled(), status.nodeCount());
    }
}
