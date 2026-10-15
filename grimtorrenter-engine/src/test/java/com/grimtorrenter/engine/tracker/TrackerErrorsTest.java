package com.grimtorrenter.engine.tracker;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** design_docs/0089: a failed tracker request says why. */
class TrackerErrorsTest {

    @Test
    void namesTheCommonNetworkFailures() {
        assertEquals("timed out connecting", TrackerErrors.reason(new HttpConnectTimeoutException("HTTP connect timed out")));
        assertEquals("timed out waiting for a reply", TrackerErrors.reason(new HttpTimeoutException("request timed out")));
        assertEquals("timed out", TrackerErrors.reason(new SocketTimeoutException("Read timed out")));
        assertEquals("could not resolve the host name", TrackerErrors.reason(new UnknownHostException("tracker.example")));
        assertEquals("secure connection (TLS) failed during the handshake",
                TrackerErrors.reason(new SSLHandshakeException("Remote host terminated the handshake")));
        assertEquals("connection reset by the other side", TrackerErrors.reason(new IOException("Connection reset")));
        assertEquals("connection refused", TrackerErrors.reason(new ConnectException("Connection refused")));
        assertEquals("could not connect", TrackerErrors.reason(new ConnectException()));
    }

    @Test
    void looksThroughTheCauseChain() {
        IOException wrapped = new IOException(null, new ConnectException("Connection refused"));

        assertEquals("connection refused", TrackerErrors.reason(wrapped));
    }

    @Test
    void fallsBackToTheMessageWithAnyUrlQueryRemoved() {
        IOException odd = new IOException("Bad response from http://t.example/announce?info_hash=%12%34&peer_id=x");

        assertEquals("Bad response from http://t.example/announce", TrackerErrors.reason(odd));
        assertEquals("IOException", TrackerErrors.reason(new IOException()));
    }
}
