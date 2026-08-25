package com.grimtorrenter.app;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThan;

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
}
