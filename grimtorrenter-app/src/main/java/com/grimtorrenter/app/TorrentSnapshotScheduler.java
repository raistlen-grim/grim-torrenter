package com.grimtorrenter.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grimtorrenter.engine.engine.TorrentEngine;
import com.grimtorrenter.engine.settings.SettingsStore;
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

    @Inject
    SettingsStore settingsStore;

    @Inject
    SessionTokenStore sessionTokenStore;

    @Inject
    WebSocketBroadcaster broadcaster;

    /** SKIP (not the default PROCEED) so a slow tick - e.g. broadcast() blocked on a
     * connection that never responds - can't overlap with the next one and pile up
     * worker threads. Defense in depth: broadcast() itself is non-blocking now (see its
     * own Javadoc), so this shouldn't be reachable in practice, but costs nothing to
     * guard against regardless. See design_docs/0019.
     *
     * <p>Includes pending magnets (design_docs/0070) alongside resolved torrents - so a
     * *different* already-open client sees one another client just added (or one restored
     * after a process restart) via this same periodic push, not a separate channel.
     *
     * <p>First closes any socket whose token is no longer valid (isValid(), not validate(), so
     * an open tab doesn't keep its own token alive), then skips the rest when nobody is
     * listening - the usual case for a headless server, and building the snapshot walks every
     * torrent's pieces. See design_docs/0094. */
    @Scheduled(every = "2s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void broadcastSnapshot() {
        if (settingsStore.current().authEnabled()) {
            broadcaster.closeConnectionsWhoseTokenFails(sessionTokenStore::isValid, TorrentWebSocket.SESSION_ENDED);
        }
        if (!broadcaster.hasConnections()) {
            return;
        }
        List<TorrentView> views = Stream.concat(
                        torrentEngine.listTorrents().stream().map(TorrentView::from),
                        torrentEngine.listPendingMagnets().stream().map(TorrentView::fromPendingMagnet))
                .toList();
        try {
            String json = objectMapper.writeValueAsString(new TorrentEventMessage("snapshot", views));
            broadcaster.broadcast(json);
            // The label list rides the same tick (design_docs/0077) so another open browser
            // sees a create/rename/delete within ~2s without a mutation-time hook - tiny
            // payload, and torrents carry ids only, so a rename changes nothing in the
            // snapshot above.
            broadcaster.broadcast(objectMapper.writeValueAsString(
                    new TorrentEventMessage("labels", torrentEngine.labels().list())));
            // The engine-wide rates and ratio for the header and footer (design_docs/0102) - the
            // same object GET /api/stats/transfer returns.
            broadcaster.broadcast(objectMapper.writeValueAsString(new TorrentEventMessage("transfer",
                    TransferView.from(torrentEngine.globalTransfer(), torrentEngine.listTorrents()))));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize torrent snapshot", e);
        }
    }
}
