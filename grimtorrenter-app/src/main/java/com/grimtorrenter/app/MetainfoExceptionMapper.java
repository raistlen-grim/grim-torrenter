package com.grimtorrenter.app;

import com.grimtorrenter.engine.metainfo.MetainfoException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** A malformed .torrent upload is bad user input, not a server bug - 400, not the default 500. */
@Provider
public class MetainfoExceptionMapper implements ExceptionMapper<MetainfoException> {

    @Override
    public Response toResponse(MetainfoException exception) {
        return ErrorResponses.badRequest(exception.getMessage());
    }
}
