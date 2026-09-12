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
        long uploadedBytes,
        /** True if this connection was accepted (the peer connected to us) rather than one we
         * initiated. See design_docs/0066. */
        boolean incoming,
        /** How we learned of this peer's address before connecting - "UNKNOWN" for an incoming
         * connection or one from a caller that didn't record a source. See design_docs/0066. */
        String source,
        /** Fraction (0-1) of the torrent this peer has, and fraction (0-1) of what *we* still
         * need that they have - see TorrentSession.PeerSnapshot's own Javadoc. See
         * design_docs/0067. */
        double percentAvailable,
        double relevance
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
                snapshot.uploadedBytes(),
                snapshot.incoming(),
                snapshot.source().name(),
                snapshot.percentAvailable(),
                snapshot.relevance());
    }
}
