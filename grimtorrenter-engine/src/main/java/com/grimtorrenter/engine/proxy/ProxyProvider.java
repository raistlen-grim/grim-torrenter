package com.grimtorrenter.engine.proxy;

import java.util.Optional;

/**
 * "Should this connection go through a proxy right now, and which?" - read live on every new
 * outbound connection so a change to the proxy settings applies to the very next one. Empty means
 * connect directly. See design_docs/0079.
 */
public interface ProxyProvider {

    /** Never uses a proxy - the default until a real provider is set. */
    ProxyProvider NONE = Optional::empty;

    Optional<ProxySettings> current();
}
