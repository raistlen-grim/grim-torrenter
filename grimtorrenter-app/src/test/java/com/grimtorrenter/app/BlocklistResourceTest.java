package com.grimtorrenter.app;

import com.grimtorrenter.engine.engine.TorrentEngine;
import com.grimtorrenter.engine.settings.Settings;
import com.grimtorrenter.engine.settings.SettingsStore;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** design_docs/0078. */
@QuarkusTest
@QuarkusTestResource(CleanDownloadsResource.class)
@QuarkusTestResource(TestSettingsResource.class)
class BlocklistResourceTest {

    @Inject
    SettingsStore settingsStore;

    @Inject
    TorrentEngine torrentEngine;

    private Settings original;

    @BeforeEach
    void rememberSettings() {
        original = settingsStore.current();
    }

    /** The settings store (and the engine's blocklist) is shared across every test in the class
     * and beyond - put the original settings back and let the blocklist notice. */
    @AfterEach
    void restoreSettings() {
        settingsStore.update(original);
        torrentEngine.blocklist().refreshIfNeeded();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> currentSettingsJson() {
        return given().when().get("/api/settings").then().statusCode(200).extract().as(Map.class);
    }

    private static void putSettings(Map<String, Object> body, int expectedStatus) {
        given().contentType(ContentType.JSON).body(body)
                .when().put("/api/settings")
                .then().statusCode(expectedStatus);
    }

    @Test
    void statusStartsDisabledAndEmpty() {
        given()
                .when().get("/api/blocklist")
                .then().statusCode(200)
                .body("enabled", equalTo(false))
                .body("source", equalTo(""))
                .body("rangeCount", equalTo(0))
                .body("loading", equalTo(false))
                .body("blockedCount", notNullValue());
    }

    @Test
    void enablingWithNoSourceIsRejectedWith400() {
        Map<String, Object> body = currentSettingsJson();
        body.put("blocklistEnabled", true);
        body.put("blocklistSource", "   ");

        putSettings(body, 400);

        assertTrue(!settingsStore.current().blocklistEnabled(), "a rejected update must not have been stored");
    }

    @Test
    void aBlocklistUrlThatIsNotHttpIsRejectedWith400() {
        Map<String, Object> body = currentSettingsJson();
        body.put("blocklistEnabled", true);
        body.put("blocklistSource", "ftp://example.invalid/list.txt");

        putSettings(body, 400);
    }

    @Test
    void aDisabledBlocklistMayKeepAnEmptyOrUnusedSource() {
        Map<String, Object> body = currentSettingsJson();
        body.put("blocklistEnabled", false);
        body.put("blocklistSource", "");

        putSettings(body, 200);
    }

    @Test
    void enablingWithAFileSourceAndReloadingLoadsTheList() throws Exception {
        Path file = Files.createTempFile("blocklist-test", ".txt");
        try {
            Files.writeString(file, "Bad:1.2.3.0-1.2.3.255\n4.4.4.4\n");
            Map<String, Object> body = currentSettingsJson();
            body.put("blocklistEnabled", true);
            body.put("blocklistSource", file.toString());
            putSettings(body, 200);

            given().when().post("/api/blocklist/reload").then().statusCode(200);

            long deadline = System.currentTimeMillis() + 5000;
            int rangeCount = 0;
            while (System.currentTimeMillis() < deadline) {
                Map<String, Object> status = given().when().get("/api/blocklist").then().statusCode(200)
                        .extract().as(Map.class);
                rangeCount = (Integer) status.get("rangeCount");
                if (rangeCount == 2 && Boolean.FALSE.equals(status.get("loading"))) {
                    break;
                }
                Thread.sleep(25);
            }
            assertEquals(2, rangeCount);
            given().when().get("/api/blocklist").then()
                    .body("enabled", equalTo(true))
                    .body("source", equalTo(file.toString()))
                    .body("loadedAtEpochMillis", greaterThan(0L));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void reloadingWithNothingConfiguredReportsWhyInsteadOfFailing() {
        given()
                .when().post("/api/blocklist/reload")
                .then().statusCode(200)
                .body("lastError", notNullValue());
    }
}
