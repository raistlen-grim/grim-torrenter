package com.grimtorrenter.app;

/** Shared by AuthResource (logout) and AuthenticationFilter - both need to pull the same
 * "Bearer &lt;token&gt;" token out of an Authorization header. */
final class BearerToken {

    /** The WWW-Authenticate challenge a 401 carries. */
    static final String SCHEME = "Bearer";

    private static final String PREFIX = SCHEME + " ";

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
