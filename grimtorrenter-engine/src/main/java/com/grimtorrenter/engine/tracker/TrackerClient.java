package com.grimtorrenter.engine.tracker;

import java.util.List;

public interface TrackerClient {

    TrackerResponse announce(TrackerRequest request);

    /** Empty by default - only a status-tracking wrapper (TrackedTrackerClient) or an
     * aggregator over one (MultiTrackerClient) has anything to report. See
     * design_docs/0031's Trackers endpoint. */
    default List<TrackerStatus> statuses() {
        return List.of();
    }
}
