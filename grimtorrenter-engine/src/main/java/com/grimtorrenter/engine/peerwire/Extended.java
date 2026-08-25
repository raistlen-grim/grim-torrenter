package com.grimtorrenter.engine.peerwire;

import java.util.Arrays;
import java.util.Objects;

/**
 * BEP 10's extension protocol envelope (wire message id 20).
 * {@code extendedMessageId} 0 is reserved for the extended handshake
 * itself; any other value is whatever id was negotiated per-connection
 * via that handshake's "m" dictionary for a specific extension (e.g. BEP
 * 9's ut_metadata). {@code payload} is opaque here - decoding it (always
 * a bencoded dict, for some extensions followed by raw trailing bytes
 * that aren't part of that dict) is each extension's own concern, not
 * this generic envelope's. See design_docs/0028.
 */
public record Extended(int extendedMessageId, byte[] payload) implements PeerMessage {

    public Extended {
        payload = payload.clone();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Extended other)) {
            return false;
        }
        return extendedMessageId == other.extendedMessageId && Arrays.equals(payload, other.payload);
    }

    @Override
    public int hashCode() {
        return Objects.hash(extendedMessageId, Arrays.hashCode(payload));
    }
}
