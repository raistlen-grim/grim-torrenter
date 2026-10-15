package com.grimtorrenter.engine.tracker;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.PortUnreachableException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.Locale;

/**
 * Turns the exception behind a failed tracker request into a short reason a user can act on -
 * "timed out connecting", "connection reset", "could not resolve the host name" - so a tracker
 * error says more than "failed". Telling a dead tracker from a network that is blocking every
 * tracker depends on it. See design_docs/0089.
 */
final class TrackerErrors {

    private static final int MAX_MESSAGE_LENGTH = 120;

    private TrackerErrors() {
    }

    /** Walks the cause chain for the most specific thing it recognises; falls back to the first
     * message it finds (with any URL query cut off - an announce URL's query carries the info
     * hash and peer id), then to the exception's class name. */
    static String reason(Throwable error) {
        String firstMessage = null;
        for (Throwable t = error; t != null; t = t.getCause()) {
            String known = known(t);
            if (known != null) {
                return known;
            }
            if (firstMessage == null && t.getMessage() != null && !t.getMessage().isBlank()) {
                firstMessage = t.getMessage();
            }
        }
        if (firstMessage != null) {
            return sanitize(firstMessage);
        }
        return error == null ? "unknown error" : error.getClass().getSimpleName();
    }

    private static String known(Throwable t) {
        if (t instanceof HttpConnectTimeoutException) {
            return "timed out connecting";
        }
        if (t instanceof HttpTimeoutException) {
            return "timed out waiting for a reply";
        }
        if (t instanceof SocketTimeoutException) {
            return "timed out";
        }
        if (t instanceof UnknownHostException || t instanceof UnresolvedAddressException) {
            return "could not resolve the host name";
        }
        if (t instanceof NoRouteToHostException) {
            return "no route to the host";
        }
        if (t instanceof PortUnreachableException) {
            return "port unreachable";
        }
        if (t instanceof SSLHandshakeException) {
            return "secure connection (TLS) failed during the handshake";
        }
        if (t instanceof SSLException) {
            return "secure connection (TLS) failed";
        }
        String message = t.getMessage() == null ? "" : t.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("connection reset")) {
            return "connection reset by the other side";
        }
        if (message.contains("connection refused")) {
            return "connection refused";
        }
        if (message.contains("network is unreachable")) {
            return "network unreachable";
        }
        if (t instanceof ConnectException) {
            return "could not connect";
        }
        return null;
    }

    private static String sanitize(String message) {
        String cut = message.replaceAll("\\?\\S*", "").strip();
        return cut.length() > MAX_MESSAGE_LENGTH ? cut.substring(0, MAX_MESSAGE_LENGTH) + "..." : cut;
    }
}
