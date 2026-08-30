package com.grimtorrenter.app;

import com.grimtorrenter.engine.engine.TorrentEngine;

/** state serializes as the enum name ("RUNNING"/"DISABLED"/"FAILED") - matches DhtStatusView's
 * own "mirror the engine record, don't expose it directly over REST" shape. See design_docs/0059. */
public record ServiceStatusView(String name, String state) {
    public static ServiceStatusView from(TorrentEngine.ServiceStatus status) {
        return new ServiceStatusView(status.name(), status.state().name());
    }
}
