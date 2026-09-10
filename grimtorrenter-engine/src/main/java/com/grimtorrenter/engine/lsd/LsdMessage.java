package com.grimtorrenter.engine.lsd;

import com.grimtorrenter.engine.metainfo.InfoHash;

/**
 * BEP 14's {@code BT-SEARCH} announcement, decoded from (or destined for) the LSD multicast
 * group. port is the sender's own BitTorrent listen port (the peer to actually connect to -
 * LSD's own multicast port, 6771, is fixed and unrelated). cookie is an opaque per-instance
 * token used only for self-suppression - see LsdService's own Javadoc for why a cookie is used
 * instead of relying on multicast-loopback socket options.
 */
public record LsdMessage(InfoHash infoHash, int port, String cookie) {
}
