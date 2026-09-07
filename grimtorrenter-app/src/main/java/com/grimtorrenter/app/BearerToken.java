package com.grimtorrenter.app;

/** Shared by AuthResource (logout) and AuthenticationFilter - both need to pull the same
 * "Bearer &lt;token&gt;" token out of an Authorization header. */
final class BearerToken {

    private static final String PREFIX = "Bearer ";

    private BearerToken() {
    }

    static String extract(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(PREFIX)) {
            return null;
        }
        String token = authorizationHeader.substring(PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
