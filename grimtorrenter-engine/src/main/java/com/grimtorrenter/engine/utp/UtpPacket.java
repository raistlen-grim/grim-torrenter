package com.grimtorrenter.engine.utp;

import java.util.Arrays;
import java.util.Objects;

/**
 * One BEP 29 packet, decoded/pre-encode form. See design_docs/0074's "Wire format" section for
 * the exact 20-byte header layout and the connection-ID/timestamp-difference semantics.
 *
 * <p>connectionId/seqNr/ackNr are wire uint16 fields, held here as plain {@code int} (always in
 * range 0-65535 - UtpPacketCodec enforces this on both encode and decode) rather than Java's
 * signed {@code short}, so callers never have to think about sign extension. timestampMicros/
 * timestampDifferenceMicros/windowSize are wire uint32 fields, held as {@code long} for the same
 * reason - only the low 32 bits are ever meaningful.
 *
 * <p>No selective-ack extension field yet (design_docs/0074's own slice 5) - this side never
 * sends one (UtpPacketCodec.encode always writes extension = 0), and silently discards any
 * extension chain a peer sends us rather than acting on it.
 *
 * <p>equals()/hashCode() are overridden rather than left to the record's own generated versions
 * - a {@code byte[]} component otherwise compares by reference identity, not content, which
 * would make two packets decoded from identical wire bytes compare unequal (caught by
 * UtpPacketCodecTest's own round-trip assertions).
 */
public record UtpPacket(UtpPacketType type, int connectionId, long timestampMicros,
                         long timestampDifferenceMicros, long windowSize, int seqNr, int ackNr,
                         byte[] payload) {

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UtpPacket other)) {
            return false;
        }
        return type == other.type && connectionId == other.connectionId && timestampMicros == other.timestampMicros
                && timestampDifferenceMicros == other.timestampDifferenceMicros && windowSize == other.windowSize
                && seqNr == other.seqNr && ackNr == other.ackNr && Arrays.equals(payload, other.payload);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(type, connectionId, timestampMicros, timestampDifferenceMicros,
                windowSize, seqNr, ackNr);
        return 31 * result + Arrays.hashCode(payload);
    }
}
