package com.grimtorrenter.engine.proxy;

/**
 * A SOCKS5 proxy to send traffic through (design_docs/0079). username/password are null when the
 * proxy needs no authentication; a username with a null password is treated as an empty one.
 */
public record ProxySettings(String host, int port, String username, String password) {

    public boolean hasCredentials() {
        return username != null && !username.isEmpty();
    }

    /** Never includes the password - this gets logged and put in error messages. */
    @Override
    public String toString() {
        return "ProxySettings[" + host + ":" + port + (hasCredentials() ? ", authenticated" : "") + "]";
    }
}
