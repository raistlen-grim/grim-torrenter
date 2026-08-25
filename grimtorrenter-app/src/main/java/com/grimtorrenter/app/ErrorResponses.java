package com.grimtorrenter.app;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

final class ErrorResponses {

    private ErrorResponses() {
    }

    static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", String.valueOf(message)))
                .build();
    }
}
