package com.grimtorrenter.engine.dht;

/** A well-known bootstrap node's hostname and port - kept together, not a shared port applied
 * to every host, since real bootstrap nodes genuinely don't agree on one (dht.libtorrent.org
 * uses 25401, not the 6881 the others share). See design_docs/0028's own 2026-08-30 addendum
 * (the follow-up fix section). */
record BootstrapHost(String host, int port) {
}
