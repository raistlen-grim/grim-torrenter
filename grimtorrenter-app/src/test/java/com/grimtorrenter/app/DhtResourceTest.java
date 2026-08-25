package com.grimtorrenter.app;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@QuarkusTestResource(TestSettingsResource.class)
class DhtResourceTest {

    /** DHT is disabled via the seeded test settings.json (see TestSettingsResource) so the
     * test suite stays hermetic - this just confirms the endpoint reports that accurately
     * rather than, say, defaulting to true. */
    @Test
    void statusReportsDisabledInTestProfile() {
        given()
                .when().get("/api/dht/status")
                .then().statusCode(200)
                .body("enabled", equalTo(false))
                .body("nodeCount", equalTo(0));
    }
}
