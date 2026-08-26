package com.grimtorrenter.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grimtorrenter.engine.events.EventStore;
import com.grimtorrenter.engine.events.EventType;
import com.grimtorrenter.engine.events.LibraryEvent;
import com.grimtorrenter.engine.torrent.TorrentSession;
import com.grimtorrenter.engine.torrent.TorrentSessionListener;
import com.grimtorrenter.engine.torrent.TorrentState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;

/**
 * Pushes state transitions immediately (downloading -> seeding -> error,
 * etc.) - the moments worth reacting to right away. Piece-level progress
 * is deliberately NOT pushed here to avoid a large torrent's rapid piece
 * completions flooding the socket; TorrentSnapshotScheduler's periodic
 * tick covers that instead. See design_docs/0019 for the hybrid push
 * model this and TorrentSnapshotScheduler implement together.
 *
 * <p>Has no TorrentEngine reference by design - avoids a circular CDI
 * dependency with TorrentEngineProducer, which injects this listener to
 * construct the TorrentEngine in the first place.
 *
 * <p>Also the recording point for the two library events (design_docs/0055) that are outcomes
 * of a state transition rather than something TorrentEngine itself decided to do: reaching
 * ERROR, and completing a download (DOWNLOADING -> SEEDING - only that specific transition, not
 * every arrival at SEEDING, since restoring an already-complete torrent re-enters SEEDING from
 * VERIFYING without having "just completed" anything). An ordinary pause/resume (any other
 * transition, including SEEDING -> STOPPED - TorrentEngine's own seeding-limit check records
 * that case itself, with the actual reason, before it ever reaches this listener) is not an
 * event a user needs reviewing, so nothing is recorded for it.
 */
@ApplicationScoped
public class TorrentEventListener implements TorrentSessionListener {

    @Inject
    ObjectMapper objectMapper;

    @Inject
    EventStore eventStore;

    @Override
    public void onStateChanged(TorrentSession session, TorrentState oldState, TorrentState newState) {
        recordLibraryEventIfNotable(session, oldState, newState);
        broadcast(new TorrentEventMessage("state-changed", TorrentView.from(session)));
    }

    private void recordLibraryEventIfNotable(TorrentSession session, TorrentState oldState, TorrentState newState) {
        EventType type;
        if (newState == TorrentState.ERROR) {
            type = EventType.ERROR;
        } else if (oldState == TorrentState.DOWNLOADING && newState == TorrentState.SEEDING) {
            type = EventType.COMPLETED;
        } else {
            return;
        }
        String infoHash = session.metadata().infoHash().hex();
        eventStore.record(new LibraryEvent(Instant.now(), type, infoHash, session.metadata().name(), null));
    }

    @Override
    public void onPieceCompleted(TorrentSession session, int pieceIndex) {
        // Intentionally no-op - see class Javadoc.
    }

    private void broadcast(TorrentEventMessage message) {
        try {
            TorrentWebSocket.broadcast(objectMapper.writeValueAsString(message));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize torrent event", e);
        }
    }
}
