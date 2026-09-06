package com.grimtorrenter.engine.tracker;

/**
 * Notified by TrackedTrackerClient when a single tracker's reachability actually changes state -
 * not on every announce() call, and not on the first failure alone (see TrackedTrackerClient's
 * own consecutive-failure debouncing). Deliberately independent of EventStore/LibraryEvent
 * (grimtorrenter-app-side concepts) so grimtorrenter-engine stays free of that dependency;
 * TorrentEngine adapts these callbacks into library events. See design_docs/0055's own
 * TRACKER_UNREACHABLE/TRACKER_RECOVERED addendum.
 */
public interface TrackerStatusListener {

    void onTrackerUnreachable(String url, String lastError);

    void onTrackerRecovered(String url);
}
