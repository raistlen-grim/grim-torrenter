package com.grimtorrenter.engine.dht;

/** A DHT operation (query, or handling a response) failed - timeout, a KRPC error reply,
 * or an unexpected/malformed response shape. Deliberately one flat exception type rather
 * than a taxonomy of causes: callers doing a lookup or a bucket refresh treat all of these
 * identically ("this node didn't work, move on"). */
public class DhtException extends RuntimeException {

    public DhtException(String message) {
        super(message);
    }

    public DhtException(String message, Throwable cause) {
        super(message, cause);
    }
}
