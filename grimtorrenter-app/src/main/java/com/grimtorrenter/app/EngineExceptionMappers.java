package com.grimtorrenter.app;

import com.grimtorrenter.engine.bencode.BencodeException;
import com.grimtorrenter.engine.engine.TorrentEngineException;
import com.grimtorrenter.engine.engine.TorrentEngineIoException;
import com.grimtorrenter.engine.magnet.MagnetLinkException;
import com.grimtorrenter.engine.metainfo.MetainfoException;
import io.quarkus.logging.Log;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * Maps the engine's exceptions to {"error": ...} responses (the shape ErrorResponses and
 * WebApplicationExceptionMapper also write). One class with one method per type, rather than
 * one near-identical ExceptionMapper class each. See design_docs/0094 and 0100.
 */
public class EngineExceptionMappers {

    /** A malformed .torrent upload is bad user input, not a server bug - 400, not the default
     * 500. */
    @ServerExceptionMapper
    public Response bencode(BencodeException exception) {
        return ErrorResponses.badRequest(exception.getMessage());
    }

    /** Same for a .torrent that bencode-decodes but isn't valid metainfo. */
    @ServerExceptionMapper
    public Response metainfo(MetainfoException exception) {
        return ErrorResponses.badRequest(exception.getMessage());
    }

    /** Same for a malformed magnet URI. */
    @ServerExceptionMapper
    public Response magnetLink(MagnetLinkException exception) {
        return ErrorResponses.badRequest(exception.getMessage());
    }

    /** A request the engine can't honour (e.g. a torrent with no usable tracker) is a 400. The
     * engine failing to read or write its own files (TorrentEngineIoException) is a 500, and is
     * logged with its cause, since it's the server that needs looking at. */
    @ServerExceptionMapper
    public Response torrentEngine(TorrentEngineException exception) {
        if (exception instanceof TorrentEngineIoException) {
            Log.error(exception.getMessage(), exception);
            return ErrorResponses.status(Response.Status.INTERNAL_SERVER_ERROR, exception.getMessage());
        }
        return ErrorResponses.badRequest(exception.getMessage());
    }
}
