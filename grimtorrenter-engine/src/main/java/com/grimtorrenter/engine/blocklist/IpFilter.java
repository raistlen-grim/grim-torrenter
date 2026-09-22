package com.grimtorrenter.engine.blocklist;

import java.net.InetAddress;

/**
 * "Should we refuse to exchange torrent data with this address?" - the one thing the connection
 * paths need to know about the blocklist, kept as a tiny interface so {@code TorrentSession} and
 * {@code PeerServer} don't depend on how the list is loaded. Must be safe to call from any thread
 * on a hot path: no locking, no allocation beyond the address's own bytes. See design_docs/0078.
 */
public interface IpFilter {

    /** Blocks nothing - the default until a real filter is set. */
    IpFilter NONE = address -> false;

    boolean isBlocked(InetAddress address);

    /** Called by an enforcement point each time it actually refuses an address, so the total
     * shows up in the status. A no-op for {@link #NONE}. */
    default void recordBlocked() {
    }
}
