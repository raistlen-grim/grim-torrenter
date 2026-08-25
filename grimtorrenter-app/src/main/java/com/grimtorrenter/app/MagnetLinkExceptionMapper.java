package com.grimtorrenter.app;

import com.grimtorrenter.engine.magnet.MagnetLinkException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** A malformed magnet URI is bad user input, not a server bug - 400, not the default 500. */
@Provider
public class MagnetLinkExceptionMapper implements ExceptionMapper<MagnetLinkException> {

    @Override
    public Response toResponse(MagnetLinkException exception) {
        return ErrorResponses.badRequest(exception.getMessage());
    }
}
