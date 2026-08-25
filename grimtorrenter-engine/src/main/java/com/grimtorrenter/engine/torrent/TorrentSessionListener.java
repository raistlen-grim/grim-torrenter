package com.grimtorrenter.engine.torrent;

public interface TorrentSessionListener {

    void onStateChanged(TorrentSession session, TorrentState oldState, TorrentState newState);

    void onPieceCompleted(TorrentSession session, int pieceIndex);
}
