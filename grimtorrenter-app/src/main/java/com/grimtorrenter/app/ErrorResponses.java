package com.grimtorrenter.app;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

final class ErrorResponses {

    private ErrorResponses() {
    }

    static Response badRequest(String message) {
        return status(Response.Status.BAD_REQUEST, message);
    }

    static Response status(Response.Status status, String message) {
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", String.valueOf(message)))
                .build();
    }
}
