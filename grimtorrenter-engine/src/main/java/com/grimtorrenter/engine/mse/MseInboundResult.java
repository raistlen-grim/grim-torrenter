package com.grimtorrenter.engine.mse;

import com.grimtorrenter.engine.metainfo.InfoHash;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * Outcome of a successful inbound MSE negotiation (see design_docs/0052). Unlike the outbound
 * case, the incoming connection doesn't say which torrent it's for in the clear - infoHash is
 * the one recovered by matching the peer's SKEY hash against every currently-active torrent
 * this engine knows about. in/out are ready for the ordinary plaintext-format BT handshake and
 * PeerWireCodec traffic, already RC4-wrapped if that's what was negotiated.
 */
public record MseInboundResult(InfoHash infoHash, InputStream in, OutputStream out) {
}
