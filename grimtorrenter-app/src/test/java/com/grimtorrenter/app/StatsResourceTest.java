package com.grimtorrenter.app;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.oneOf;

/** design_docs/0102 - shape only: the figures depend on whatever else the test run has done. */
@QuarkusTest
class StatsResourceTest {

    @Test
    void transferReportsRatesLimitsAndTotals() {
        given()
                .when().get("/api/stats/transfer")
                .then().statusCode(200)
                .body("downloadRateBytesPerSec", notNullValue())
                .body("uploadRateBytesPerSec", notNullValue())
                .body("downloadLimitSource", oneOf("NONE", "GLOBAL", "SCHEDULED"))
                .body("uploadLimitSource", oneOf("NONE", "GLOBAL", "SCHEDULED"))
                .body("downloadCapped", notNullValue())
                .body("lifetimeDownloadedBytes", notNullValue())
                .body("lifetimeUploadedBytes", notNullValue());
    }

    @Test
    void historyComesAtEachResolution() {
        given().when().get("/api/stats/history?resolution=second")
                .then().statusCode(200)
                .body("resolution", equalTo("second"))
                .body("intervalSeconds", equalTo(1))
                .body("download", hasSize(60))
                .body("upload", hasSize(60))
                .body("endEpochMillis", notNullValue());
        given().when().get("/api/stats/history?resolution=minute")
                .then().statusCode(200)
                .body("intervalSeconds", equalTo(60))
                .body("download", hasSize(120));
        given().when().get("/api/stats/history?resolution=hour")
                .then().statusCode(200)
                .body("intervalSeconds", equalTo(3600))
                .body("download", hasSize(168));
    }

    @Test
    void anUnknownResolutionIsA400() {
        given().when().get("/api/stats/history?resolution=day")
                .then().statusCode(400)
                .body("error", equalTo("resolution must be second, minute or hour"));
        given().when().get("/api/stats/history")
                .then().statusCode(400);
    }
}
