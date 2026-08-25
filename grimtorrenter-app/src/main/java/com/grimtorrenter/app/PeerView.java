package com.grimtorrenter.app;

import com.grimtorrenter.engine.torrent.TorrentSession;

public record PeerView(
        String address,
        int port,
        String peerId,
        boolean amChoking,
        boolean amInterested,
        boolean peerChoking,
        boolean peerInterested,
        long downloadedBytes,
        long uploadedBytes
) {
    public static PeerView from(TorrentSession.PeerSnapshot snapshot) {
        return new PeerView(
                snapshot.address().address().getHostAddress(),
                snapshot.address().port(),
                snapshot.peerId().hex(),
                snapshot.amChoking(),
                snapshot.amInterested(),
                snapshot.peerChoking(),
                snapshot.peerInterested(),
                snapshot.downloadedBytes(),
                snapshot.uploadedBytes());
    }
}
