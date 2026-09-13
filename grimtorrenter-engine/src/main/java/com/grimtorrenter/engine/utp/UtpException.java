package com.grimtorrenter.engine.utp;

/** A µTP operation failed - malformed wire data, a handshake that never completed, or a
 * connection-level I/O failure. Deliberately one flat exception type rather than a taxonomy of
 * causes, same reasoning as DhtException: every caller here treats these identically ("this
 * packet/connection didn't work"). See design_docs/0074. */
public class UtpException extends RuntimeException {

    public UtpException(String message) {
        super(message);
    }

    public UtpException(String message, Throwable cause) {
        super(message, cause);
    }
}
