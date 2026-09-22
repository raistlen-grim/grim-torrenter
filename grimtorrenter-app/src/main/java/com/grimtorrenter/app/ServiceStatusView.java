package com.grimtorrenter.app;

import com.grimtorrenter.engine.engine.TorrentEngine;

/** state serializes as the enum name ("RUNNING"/"DEGRADED"/"DISABLED"/"FAILED") - matches
 * DhtStatusView's own "mirror the engine record, don't expose it directly over REST" shape.
 * See design_docs/0059 and its own DEGRADED-state addendum. reason is null except for a DISABLED
 * service that a proxy turned off (design_docs/0079). */
public record ServiceStatusView(String name, String state, String reason) {
    public static ServiceStatusView from(TorrentEngine.ServiceStatus status) {
        return new ServiceStatusView(status.name(), status.state().name(), status.reason());
    }
}
