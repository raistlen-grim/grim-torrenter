package com.grimtorrenter.app;

import io.quarkus.logging.Log;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

/**
 * Gives every {@code throw new BadRequestException(msg)} / {@code NotFoundException} /
 * {@code WebApplicationException(msg, status)} the same {@code {"error": msg}} body that
 * ErrorResponses and the other exception mappers write - otherwise the message only reaches the
 * server log and a client sees a bare status. Status and headers (e.g. a 401's
 * WWW-Authenticate) are kept; a response that already carries a body is passed through
 * untouched. 5xx responses are logged with their cause. See design_docs/0094.
 */
@Provider
public class WebApplicationExceptionMapper implements ExceptionMapper<WebApplicationException> {

    @Override
    public Response toResponse(WebApplicationException exception) {
        Response response = exception.getResponse();
        if (response.hasEntity()) {
            return response;
        }
        if (response.getStatus() >= 500) {
            Log.error(exception.getMessage(), exception);
        }
        return Response.fromResponse(response)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", String.valueOf(exception.getMessage())))
                .build();
    }
}
