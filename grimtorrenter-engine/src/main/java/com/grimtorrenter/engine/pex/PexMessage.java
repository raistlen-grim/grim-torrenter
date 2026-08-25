package com.grimtorrenter.engine.pex;

import com.grimtorrenter.engine.tracker.PeerAddress;

import java.util.List;

/**
 * BEP 11's ut_pex message, IPv4-only ("added"/"dropped" - no "added.f" peer flags,
 * no "added6"/"dropped6", matching every other compact-peer format already in this
 * codebase, which is IPv4-only throughout). added is who a peer has newly connected to
 * since its last PEX message to us; dropped is who it's no longer connected to. See
 * design_docs/0040 for why dropped is decoded but never acted on.
 */
public record PexMessage(List<PeerAddress> added, List<PeerAddress> dropped) {

    public PexMessage {
        added = List.copyOf(added);
        dropped = List.copyOf(dropped);
    }
}
