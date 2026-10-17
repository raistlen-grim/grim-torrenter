package com.grimtorrenter.app;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

/**
 * A browser sends a cross-site POST with an empty body without a CORS preflight - pause is such a
 * request. Refused unless it carries X-Requested-With, which only our own page can add; a client
 * that sends no Origin at all (curl, scripts, these tests elsewhere) isn't a browser and is let
 * through. Pausing an unknown (but well-formed) info hash is a harmless 404 - reaching the
 * resource at all is what shows the filter let it through. See design_docs/0093.
 */
@QuarkusTest
@QuarkusTestResource(CleanDownloadsResource.class)
@QuarkusTestResource(TestSettingsResource.class)
class CrossSiteRequestFilterTest {

    private static final String PAUSE_PATH = "/api/torrents/" + "0".repeat(40) + "/pause";

    @Test
    void refusesACrossSiteBrowserRequestWithoutTheMarkerHeader() {
        given()
                .header("Origin", "https://evil.example")
                .when().post(PAUSE_PATH)
                .then().statusCode(403);
    }

    @Test
    void allowsABrowserRequestCarryingTheMarkerHeader() {
        given()
                .header("Origin", "http://localhost:8081")
                .header(CrossSiteRequestFilter.REQUIRED_HEADER, "GrimTorrenter")
                .when().post(PAUSE_PATH)
                .then().statusCode(404);
    }

    @Test
    void allowsANonBrowserClientWithNoOrigin() {
        given()
                .when().post(PAUSE_PATH)
                .then().statusCode(404);
    }

    @Test
    void leavesSafeMethodsAlone() {
        given()
                .header("Origin", "https://evil.example")
                .when().get("/api/torrents")
                .then().statusCode(200);
    }
}
