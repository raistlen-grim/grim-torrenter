package com.grimtorrenter.engine.torrent;

/**
 * A torrent's time to completion and why. seconds is set only for ESTIMATE; unavailablePieces only
 * for UNAVAILABLE (0 otherwise). See design_docs/0102.
 */
public record Eta(Long seconds, EtaStatus status, int unavailablePieces) {

    static Eta of(EtaStatus status) {
        return new Eta(null, status, 0);
    }
}
