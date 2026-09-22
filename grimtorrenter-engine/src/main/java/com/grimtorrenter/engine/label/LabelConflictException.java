package com.grimtorrenter.engine.label;

/** A label name that's already taken (case-insensitively) - distinct from a merely invalid name
 * so a caller can report a conflict rather than a bad request. See design_docs/0077. */
public class LabelConflictException extends RuntimeException {

    public LabelConflictException(String message) {
        super(message);
    }
}
