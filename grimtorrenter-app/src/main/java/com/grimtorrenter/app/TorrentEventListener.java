package com.grimtorrenter.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grimtorrenter.engine.torrent.TorrentSession;
import com.grimtorrenter.engine.torrent.TorrentSessionListener;
import com.grimtorrenter.engine.torrent.TorrentState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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
 */
@ApplicationScoped
public class TorrentEventListener implements TorrentSessionListener {

    @Inject
    ObjectMapper objectMapper;

    @Override
    public void onStateChanged(TorrentSession session, TorrentState oldState, TorrentState newState) {
        broadcast(new TorrentEventMessage("state-changed", TorrentView.from(session)));
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
