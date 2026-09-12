package com.grimtorrenter.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grimtorrenter.engine.engine.TorrentEngine;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.stream.Stream;

/** The periodic half of the hybrid WS push model - see TorrentEventListener's Javadoc. */
@ApplicationScoped
public class TorrentSnapshotScheduler {

    @Inject
    TorrentEngine torrentEngine;

    @Inject
    ObjectMapper objectMapper;

    /** SKIP (not the default PROCEED) so a slow tick - e.g. broadcast() blocked on a
     * connection that never responds - can't overlap with the next one and pile up
     * worker threads. Defense in depth: broadcast() itself is non-blocking now (see its
     * own Javadoc), so this shouldn't be reachable in practice, but costs nothing to
     * guard against regardless. See design_docs/0019.
     *
     * <p>Includes pending magnets (design_docs/0070) alongside resolved torrents - so a
     * *different* already-open client sees one another client just added (or one restored
     * after a process restart) via this same periodic push, not a separate channel. */
    @Scheduled(every = "2s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void broadcastSnapshot() {
        List<TorrentView> views = Stream.concat(
                        torrentEngine.listTorrents().stream().map(TorrentView::from),
                        torrentEngine.listPendingMagnets().stream().map(TorrentView::fromPendingMagnet))
                .toList();
        try {
            String json = objectMapper.writeValueAsString(new TorrentEventMessage("snapshot", views));
            TorrentWebSocket.broadcast(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize torrent snapshot", e);
        }
    }
}
