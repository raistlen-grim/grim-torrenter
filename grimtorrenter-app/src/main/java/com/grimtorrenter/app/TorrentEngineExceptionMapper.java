package com.grimtorrenter.app;

import com.grimtorrenter.engine.engine.TorrentEngineException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** E.g. a torrent with no usable HTTP(S) tracker - bad user input, not a server bug - 400, not the default 500. */
@Provider
public class TorrentEngineExceptionMapper implements ExceptionMapper<TorrentEngineException> {

    @Override
    public Response toResponse(TorrentEngineException exception) {
        return ErrorResponses.badRequest(exception.getMessage());
    }
}
