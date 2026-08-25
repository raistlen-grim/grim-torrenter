package com.grimtorrenter.app;

import com.grimtorrenter.engine.engine.TorrentEngine;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

@ApplicationScoped
public class TorrentEngineLifecycle {

    @Inject
    TorrentEngine torrentEngine;

    /** Picks back up any torrents left registered by a previous run of this process -
     * see design_docs/0026. Each one is visible immediately in VERIFYING; the actual
     * re-hash and any resulting auto-start happen on their own background threads, so
     * this does not delay Quarkus startup on however much data there is to re-check. */
    void onStart(@Observes StartupEvent event) {
        torrentEngine.restore();
    }

    void onShutdown(@Observes ShutdownEvent event) {
        torrentEngine.shutdown();
    }
}
