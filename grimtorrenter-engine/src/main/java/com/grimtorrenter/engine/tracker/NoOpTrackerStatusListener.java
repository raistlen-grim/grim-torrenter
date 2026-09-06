package com.grimtorrenter.engine.tracker;

/** Default listener for TrackedTrackerClient's single-arg constructor - every existing call
 * site (tests, and TorrentEngine's throwaway magnet-metadata-fetch tracker client) that has no
 * use for TRACKER_UNREACHABLE/TRACKER_RECOVERED reporting keeps working unchanged. */
enum NoOpTrackerStatusListener implements TrackerStatusListener {
    INSTANCE;

    @Override
    public void onTrackerUnreachable(String url, String lastError) {
    }

    @Override
    public void onTrackerRecovered(String url) {
    }
}
