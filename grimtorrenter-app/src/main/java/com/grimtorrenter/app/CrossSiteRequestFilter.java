package com.grimtorrenter.app;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.util.Set;

/**
 * Refuses state-changing /api requests that a browser sent on behalf of some other site (CSRF).
 * With auth off - the default - nothing else stands between a web page the user happens to visit
 * and this API: a browser sends a cross-site POST with a text/plain, multipart or empty body
 * without asking first, and only hides the response. See design_docs/0093.
 *
 * <p>The rule: a non-safe request carrying an Origin header (every browser sends one on a
 * cross-origin POST/PUT/DELETE, and on same-origin fetches too) must also carry
 * {@value #REQUIRED_HEADER}. A page on another origin can't add a custom header without a CORS
 * preflight, which this app never approves, so only our own frontend (authInterceptor sets it on
 * every request) can satisfy it. No Origin header means a non-browser client - curl, a script -
 * which CSRF can't come from, so it's left alone.
 *
 * <p>Deliberately not an Origin-vs-Host comparison: a reverse proxy commonly rewrites Host, which
 * would break every request behind it. Runs ahead of AuthenticationFilter, so a forged request is
 * refused the same way whether or not auth is on.
 */
@Provider
@PreMatching
@Priority(Priorities.AUTHENTICATION - 100)
public class CrossSiteRequestFilter implements ContainerRequestFilter {

    static final String REQUIRED_HEADER = "X-Requested-With";
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (SAFE_METHODS.contains(requestContext.getMethod())) {
            return;
        }
        if (requestContext.getHeaderString("Origin") == null) {
            return;
        }
        String marker = requestContext.getHeaderString(REQUIRED_HEADER);
        if (marker == null || marker.isBlank()) {
            requestContext.abortWith(ErrorResponses.status(Response.Status.FORBIDDEN,
                    "Cross-site request refused - browser requests must come from the GrimTorrenter page"));
        }
    }
}
