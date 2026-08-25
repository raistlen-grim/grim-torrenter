package com.grimtorrenter.engine.tracker;

public class TrackerException extends RuntimeException {

    public TrackerException(String message) {
        super(message);
    }

    public TrackerException(String message, Throwable cause) {
        super(message, cause);
    }
}
