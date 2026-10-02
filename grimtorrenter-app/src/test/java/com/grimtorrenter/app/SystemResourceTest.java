package com.grimtorrenter.app;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItems;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class SystemResourceTest {

    @Test
    void diskUsageReportsPositiveFreeBytes() {
        // greaterThan(0) (an int) would build a Matcher<Integer> - freeBytes deserializes
        // as a Long, and Hamcrest's ordering comparison requires matching Comparable types,
        // failing the match even though 117893218304 is obviously greater than 0. 0L keeps
        // both sides Long.
        given()
                .when().get("/api/system/disk-usage")
                .then().statusCode(200)
                .body("freeBytes", greaterThan(0L));
    }

    @Test
    void resourceUsageReportsHeapAndProcessors() {
        // processCpuLoad isn't asserted beyond being present - it's legitimately -1.0 (the
        // JDK's own "unavailable" sentinel) on some platforms/sandboxes, so no numeric range
        // holds on every environment this test might run in.
        //
        // heapUsedBytes/heapMaxBytes can't use greaterThan(0L) the way freeBytes above does:
        // that trick assumes the JSON value always deserializes as Long, but REST-assured
        // picks Integer or Long per-response based on whether the actual number fits in an
        // int - heapUsedBytes routinely does (a fresh test JVM's used heap is well under
        // Integer.MAX_VALUE) while heapMaxBytes usually doesn't, so a fixed 0L/0 matcher
        // would only work for one of the two, unpredictably, depending on JVM heap state at
        // test time. Extracting as Number and comparing via longValue() sidesteps the
        // type-matching entirely.
        ValidatableResponse response = given()
                .when().get("/api/system/resource-usage")
                .then().statusCode(200)
                .body("availableProcessors", greaterThan(0));
        Number heapUsed = response.extract().path("heapUsedBytes");
        Number heapMax = response.extract().path("heapMaxBytes");
        assertTrue(heapUsed.longValue() > 0, "heapUsedBytes should be positive");
        assertTrue(heapMax.longValue() > 0, "heapMaxBytes should be positive");
    }

    /** design_docs/0086 - shape only: which states come back depends on the test profile. */
    @Test
    void healthReportsEveryGroupWithItsChecks() {
        given()
                .when().get("/api/system/health")
                .then().statusCode(200)
                .body("status", org.hamcrest.Matchers.oneOf("OK", "WARNING", "FAILED"))
                .body("groups.name", org.hamcrest.Matchers.contains(
                        "network", "storage", "connectivity", "protection", "build"))
                .body("groups.find { it.name == 'network' }.checks.name", hasItems("dht", "peerServer", "lsd"))
                .body("groups.find { it.name == 'storage' }.checks.name",
                        org.hamcrest.Matchers.contains("downloads", "config", "watch", "freeSpace"))
                .body("groups.find { it.name == 'storage' }.checks.find { it.name == 'downloads' }.state",
                        org.hamcrest.Matchers.equalTo("OK"))
                .body("groups.find { it.name == 'connectivity' }.checks.name",
                        org.hamcrest.Matchers.contains("incoming", "proxy"))
                .body("groups.find { it.name == 'protection' }.checks.name",
                        org.hamcrest.Matchers.contains("blocklist", "auth"))
                .body("groups.find { it.name == 'build' }.checks.name",
                        org.hamcrest.Matchers.contains("version", "uptime", "user"));
    }

    /** design_docs/0086 - the container health check's endpoint. */
    @Test
    void healthzReportsUpWhileTheDirectoriesAreUsable() {
        given()
                .when().get("/api/system/healthz")
                .then().statusCode(200)
                .body("status", org.hamcrest.Matchers.equalTo("UP"));
    }

    /** design_docs/0084. */
    @Test
    void versionReportsTheClientNameAndBuildVersion() {
        given()
                .when().get("/api/system/version")
                .then().statusCode(200)
                .body("name", org.hamcrest.Matchers.equalTo("GrimTorrenter"))
                .body("version", org.hamcrest.Matchers.equalTo(com.grimtorrenter.engine.ClientIdentity.version()));
    }

    /** Asserts names/shape only, not a specific state - whether each service reports RUNNING
     * or DISABLED depends on the test profile's own DHT/incoming-connections/LSD settings,
     * which this test shouldn't need to know about. See design_docs/0059/0062. */
    @Test
    void servicesReportsDhtAndPeerServerAndLsd() {
        given()
                .when().get("/api/system/services")
                .then().statusCode(200)
                .body("name", hasItems("dht", "peerServer", "lsd"));
    }
}
