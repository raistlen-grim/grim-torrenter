package com.grimtorrenter.app;

import com.grimtorrenter.engine.torrent.TorrentSession;

import java.util.List;

/** Wraps the per-piece state list with pieceLength - the details panel's Pieces tab caption
 * (design_docs/0032's task 7) needs a per-piece size ("512 KB each"), which TorrentMetadata
 * already carries but nothing exposed before this. A wrapper record rather than widening
 * TorrentView (pieceLength is meaningless outside this one self-contained detail endpoint -
 * see design_docs/0031's "self-contained, on-demand" pattern every other detail endpoint
 * here already follows) or leaving the endpoint as a bare array (pieceLength has no natural
 * per-element home in a list of piece states). */
public record PiecesView(List<String> pieces, long pieceLength) {
    public static PiecesView from(TorrentSession session) {
        List<String> pieces = session.pieceStates().stream().map(Enum::name).toList();
        return new PiecesView(pieces, session.metadata().pieceLength());
    }
}
