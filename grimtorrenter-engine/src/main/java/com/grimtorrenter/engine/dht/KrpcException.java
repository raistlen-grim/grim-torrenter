package com.grimtorrenter.engine.dht;

public class KrpcException extends RuntimeException {

    public KrpcException(String message) {
        super(message);
    }

    public KrpcException(String message, Throwable cause) {
        super(message, cause);
    }
}
