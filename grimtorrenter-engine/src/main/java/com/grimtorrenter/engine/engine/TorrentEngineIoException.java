package com.grimtorrenter.engine.engine;

import java.io.IOException;

/**
 * The engine couldn't read or write its own files - a persisted marker, a download directory, a
 * delete. A server-side failure, unlike its parent, which the engine throws for a request it
 * can't honour (no usable tracker, say); the REST layer maps this one to 500 and the parent to
 * 400. A subclass so every existing {@code catch (TorrentEngineException)} keeps working. See
 * design_docs/0094.
 */
public class TorrentEngineIoException extends TorrentEngineException {

    public TorrentEngineIoException(String message, IOException cause) {
        super(message);
        initCause(cause);
    }
}
