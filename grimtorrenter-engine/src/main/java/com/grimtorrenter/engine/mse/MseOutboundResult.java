package com.grimtorrenter.engine.mse;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * Outcome of a successful outbound MSE negotiation (see design_docs/0052) - the stream pair
 * everything from here on (the ordinary plaintext-format BT handshake, then PeerWireCodec
 * traffic) should use. Already RC4-wrapped if that's what was negotiated; otherwise these are
 * the connection's raw socket streams, unwrapped.
 */
public record MseOutboundResult(InputStream in, OutputStream out) {
}
