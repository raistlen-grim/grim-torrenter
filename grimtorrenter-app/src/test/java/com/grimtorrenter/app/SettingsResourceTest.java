package com.grimtorrenter.app;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@QuarkusTestResource(TestSettingsResource.class)
class SettingsResourceTest {

    /** One test, not two - GET-then-PUT-then-GET in a fixed sequence, since both methods
     * read/write the same singleton SettingsStore and JUnit doesn't guarantee method order
     * across separate @Test methods. Confirms GET reads the seeded
     * target/test-config/settings.json (see TestSettingsResource) rather than
     * Settings.defaults(), and that a PUT persists through the same store GET reads from
     * afterward - not just echoing the request body back. */
    @Test
    void currentReflectsSeededSettingsThenAnUpdate() {
        given()
                .when().get("/api/settings")
                .then().statusCode(200)
                .body("dhtEnabled", equalTo(false))
                .body("acceptIncomingConnections", equalTo(false))
                .body("uploadRateLimitBytesPerSec", equalTo(0))
                .body("downloadRateLimitBytesPerSec", equalTo(0));

        String body = """
                {
                  "dhtEnabled": false,
                  "acceptIncomingConnections": false,
                  "uploadRateLimitBytesPerSec": 65536,
                  "downloadRateLimitBytesPerSec": 131072
                }""";

        given()
                .contentType(ContentType.JSON)
                .body(body)
                .when().put("/api/settings")
                .then().statusCode(200)
                .body("uploadRateLimitBytesPerSec", equalTo(65536))
                .body("downloadRateLimitBytesPerSec", equalTo(131072));

        given()
                .when().get("/api/settings")
                .then().statusCode(200)
                .body("uploadRateLimitBytesPerSec", equalTo(65536))
                .body("downloadRateLimitBytesPerSec", equalTo(131072));
    }

    /** A schedule PUT round-trips through the same store as the base fields - see
     * design_docs/0046. */
    @Test
    void updatePersistsAnEnabledSchedule() {
        String body = """
                {
                  "dhtEnabled": false,
                  "acceptIncomingConnections": false,
                  "uploadRateLimitBytesPerSec": 0,
                  "downloadRateLimitBytesPerSec": 0,
                  "rateLimitScheduleEnabled": true,
                  "rateLimitScheduleStart": "23:00",
                  "rateLimitScheduleEnd": "07:00",
                  "scheduledUploadRateLimitBytesPerSec": 204800,
                  "scheduledDownloadRateLimitBytesPerSec": 409600
                }""";

        given()
                .contentType(ContentType.JSON)
                .body(body)
                .when().put("/api/settings")
                .then().statusCode(200)
                .body("rateLimitScheduleEnabled", equalTo(true))
                .body("rateLimitScheduleStart", equalTo("23:00"))
                .body("rateLimitScheduleEnd", equalTo("07:00"))
                .body("scheduledUploadRateLimitBytesPerSec", equalTo(204800))
                .body("scheduledDownloadRateLimitBytesPerSec", equalTo(409600));
    }

    /** Round-trips the seeding-limit fields (design_docs/0054) through GET/PUT - no special
     * validation needed for these (plain numbers, unlike the schedule's freeform time
     * strings), so this is just confirming Jackson serializes/deserializes them correctly
     * through the real REST layer, not re-testing SettingsResource's own validation logic. */
    @Test
    void updatePersistsSeedingLimits() {
        String body = """
                {
                  "dhtEnabled": false,
                  "acceptIncomingConnections": false,
                  "uploadRateLimitBytesPerSec": 0,
                  "downloadRateLimitBytesPerSec": 0,
                  "seedRatioLimitEnabled": true,
                  "seedRatioLimit": 2.5,
                  "seedTimeLimitEnabled": true,
                  "seedTimeLimitMinutes": 4320
                }""";

        given()
                .contentType(ContentType.JSON)
                .body(body)
                .when().put("/api/settings")
                .then().statusCode(200)
                .body("seedRatioLimitEnabled", equalTo(true))
                .body("seedRatioLimit", equalTo(2.5f))
                .body("seedTimeLimitEnabled", equalTo(true))
                .body("seedTimeLimitMinutes", equalTo(4320));

        given()
                .when().get("/api/settings")
                .then().statusCode(200)
                .body("seedRatioLimitEnabled", equalTo(true))
                .body("seedRatioLimit", equalTo(2.5f))
                .body("seedTimeLimitEnabled", equalTo(true))
                .body("seedTimeLimitMinutes", equalTo(4320));
    }

    /** SettingsResource is the boundary that has to reject a malformed schedule time - see
     * requireParsableTime()'s own rationale. Only checked while the schedule is enabled. */
    @Test
    void updateRejectsAnUnparsableScheduleTimeWhenScheduleIsEnabled() {
        String body = """
                {
                  "dhtEnabled": false,
                  "acceptIncomingConnections": false,
                  "uploadRateLimitBytesPerSec": 0,
                  "downloadRateLimitBytesPerSec": 0,
                  "rateLimitScheduleEnabled": true,
                  "rateLimitScheduleStart": "not-a-time",
                  "rateLimitScheduleEnd": "07:00",
                  "scheduledUploadRateLimitBytesPerSec": 0,
                  "scheduledDownloadRateLimitBytesPerSec": 0
                }""";

        given()
                .contentType(ContentType.JSON)
                .body(body)
                .when().put("/api/settings")
                .then().statusCode(400);
    }

    /** Settings' own compact constructor - not this resource - is what actually enforces "no
     * value that would defeat the event log's bounded-growth purpose" (design_docs/0055): 0 or
     * negative is silently normalized to the default of 30, the same mechanism (and the same
     * call site) that backfills a pre-0055 settings.json missing this field entirely, since a
     * primitive int can't otherwise tell "explicitly 0" apart from "absent." This confirms
     * that normalization is reachable through the real REST layer, not just Settings' own
     * constructor tests. */
    @Test
    void updateNormalizesAZeroEventLogRetentionToTheDefault() {
        String body = """
                {
                  "dhtEnabled": false,
                  "acceptIncomingConnections": false,
                  "uploadRateLimitBytesPerSec": 0,
                  "downloadRateLimitBytesPerSec": 0,
                  "eventLogRetentionDays": 0
                }""";

        given()
                .contentType(ContentType.JSON)
                .body(body)
                .when().put("/api/settings")
                .then().statusCode(200)
                .body("eventLogRetentionDays", equalTo(30));
    }
}
