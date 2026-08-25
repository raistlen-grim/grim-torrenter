package com.grimtorrenter.app;

import com.grimtorrenter.engine.bencode.BencodeException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** A malformed .torrent upload is bad user input, not a server bug - 400, not the default 500. */
@Provider
public class BencodeExceptionMapper implements ExceptionMapper<BencodeException> {

    @Override
    public Response toResponse(BencodeException exception) {
        return ErrorResponses.badRequest(exception.getMessage());
    }
}
