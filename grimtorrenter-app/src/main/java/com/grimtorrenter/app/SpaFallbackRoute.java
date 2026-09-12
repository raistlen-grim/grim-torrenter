package com.grimtorrenter.app;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import java.io.IOException;
import java.io.InputStream;

/**
 * Serves index.html for any GET request nothing else already handled - specifically, a direct
 * navigation or page refresh on an Angular client-side route (e.g. /torrents/&lt;hash&gt;),
 * which Quarkus's static-resource handler 404s on today: it only knows about real files under
 * META-INF/resources and has no notion of the frontend's own client-side router.
 *
 * <p>Registered directly on the Vert.x Router (.last(), so it only ever fires for a request
 * nothing else - the static handler, or a real /api or /ws route - already claimed), not as a
 * JAX-RS resource: a JAX-RS catch-all would go through AuthenticationFilter's own
 * {@code @PreMatching} filter chain like every other JAX-RS request, so a page refresh would
 * 401 instead of 404 once auth is enabled (design_docs/0061) - this route runs entirely outside
 * that pipeline, matching how the static handler itself is already unauthenticated for the same
 * file-serving reason. /api and /ws paths are still explicitly excluded here anyway, so a
 * genuinely missing API route keeps 404ing rather than silently returning the app shell.
 *
 * <p>Reads index.html once, at Router-registration time, rather than per request. If it's
 * absent - the frontend build only ever copies it into META-INF/resources at Docker build time
 * (design_docs/0010), so a plain local {@code mvn test}/{@code quarkus:dev} run without that
 * step has no such file - the fallback route is simply never registered, a no-op rather than
 * failing application startup over a page nothing local ever asked to serve.
 */
@ApplicationScoped
public class SpaFallbackRoute {

    private static final System.Logger LOG = System.getLogger(SpaFallbackRoute.class.getName());

    void register(@Observes Router router) {
        byte[] indexHtml = readIndexHtml();
        if (indexHtml == null) {
            LOG.log(System.Logger.Level.DEBUG,
                    "No META-INF/resources/index.html on the classpath - SPA fallback route not registered");
            return;
        }
        router.route().method(HttpMethod.GET).last().handler(ctx -> {
            String path = ctx.normalizedPath();
            if (path.startsWith("/api") || path.startsWith("/ws")) {
                ctx.next();
                return;
            }
            ctx.response().putHeader("Content-Type", "text/html; charset=UTF-8").end(Buffer.buffer(indexHtml));
        });
    }

    /** Null when absent - see this class's own Javadoc for why that's a real, tolerated case
     * (a local dev/test run with no frontend build copied in yet), not an error. */
    private static byte[] readIndexHtml() {
        try (InputStream in = SpaFallbackRoute.class.getResourceAsStream("/META-INF/resources/index.html")) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not read META-INF/resources/index.html", e);
            return null;
        }
    }
}
