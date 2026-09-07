package com.grimtorrenter.app;

/** No username field - see AuthResource's own Javadoc. */
public record LoginRequest(String password) {
}
