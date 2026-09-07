package com.grimtorrenter.app;

/** passwordSet lets the frontend tell "no password configured yet" (show a "set password"
 * form, no current-password field) apart from "a password exists" (show "change password",
 * current-password field required) - see the Settings page's Security group. Exposing
 * whether a password exists, without ever exposing the password/hash itself, to an
 * unauthenticated caller is deliberate: AuthenticationFilter always allows this endpoint
 * through, the same as /api/auth/login, since a client with no token yet has to be able to
 * ask this before it can do anything else. See design_docs/0061. */
public record AuthStatusView(boolean authEnabled, boolean passwordSet) {
}
