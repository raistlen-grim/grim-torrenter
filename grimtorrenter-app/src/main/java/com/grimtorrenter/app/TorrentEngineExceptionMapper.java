package com.grimtorrenter.app;

import com.grimtorrenter.engine.engine.TorrentEngineException;
import com.grimtorrenter.engine.engine.TorrentEngineIoException;
import io.quarkus.logging.Log;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** A request the engine can't honour (e.g. a torrent with no usable tracker) is a 400. The engine
 * failing to read or write its own files (TorrentEngineIoException) is a 500, and is logged with
 * its cause, since it's the server that needs looking at. See design_docs/0094. */
@Provider
public class TorrentEngineExceptionMapper implements ExceptionMapper<TorrentEngineException> {

    @Override
    public Response toResponse(TorrentEngineException exception) {
        if (exception instanceof TorrentEngineIoException) {
            Log.error(exception.getMessage(), exception);
            return ErrorResponses.status(Response.Status.INTERNAL_SERVER_ERROR, exception.getMessage());
        }
        return ErrorResponses.badRequest(exception.getMessage());
    }
}
