package com.grimtorrenter.app;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThan;
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
}
