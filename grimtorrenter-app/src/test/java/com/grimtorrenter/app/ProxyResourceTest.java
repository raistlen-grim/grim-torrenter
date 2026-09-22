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

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** design_docs/0079. */
@QuarkusTest
@QuarkusTestResource(CleanDownloadsResource.class)
@QuarkusTestResource(TestSettingsResource.class)
class ProxyResourceTest {

    @Inject
    SettingsStore settingsStore;

    @Inject
    TorrentEngine torrentEngine;

    private Settings original;

    @BeforeEach
    void remember() {
        original = settingsStore.current();
        torrentEngine.proxyConfig().clearPassword();
    }

    @AfterEach
    void restore() {
        settingsStore.update(original);
        torrentEngine.proxyConfig().clearPassword();
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
    void statusStartsInactiveWithNoPasswordAndBlockingOnByDefault() {
        given()
                .when().get("/api/proxy")
                .then().statusCode(200)
                .body("active", equalTo(false))
                .body("hasPassword", equalTo(false))
                .body("blockUnsupported", equalTo(true))
                .body("restartRequired", equalTo(false));
    }

    @Test
    void enablingTheProxyWithNoHostIsRejectedWith400() {
        Map<String, Object> body = currentSettingsJson();
        body.put("proxyEnabled", true);
        body.put("proxyHost", "  ");
        body.put("proxyPort", 1080);

        putSettings(body, 400);
    }

    @Test
    void enablingTheProxyWithAnInvalidPortIsRejectedWith400() {
        Map<String, Object> body = currentSettingsJson();
        body.put("proxyEnabled", true);
        body.put("proxyHost", "proxy.example");
        body.put("proxyPort", 70000);
        putSettings(body, 400);

        body.put("proxyPort", 0);
        putSettings(body, 400);
    }

    @Test
    void aDisabledProxyMayKeepAPartialConfiguration() {
        Map<String, Object> body = currentSettingsJson();
        body.put("proxyEnabled", false);
        body.put("proxyHost", "");
        body.put("proxyPort", 0);

        putSettings(body, 200);
    }

    /** The password is write-only: setting it flips hasPassword, and it never shows up in the
     * status or in the settings the API echoes back. */
    @Test
    void thePasswordIsWriteOnlyAndNeverEchoedAnywhere() {
        given().contentType(ContentType.JSON).body("{\"password\": \"s3cret-value\"}")
                .when().put("/api/proxy/password")
                .then().statusCode(200)
                .body("hasPassword", equalTo(true))
                .body(not(containsString("s3cret-value")));

        given().when().get("/api/proxy").then().body(not(containsString("s3cret-value")));
        String settings = given().when().get("/api/settings").then().statusCode(200).extract().asString();
        assertFalse(settings.contains("s3cret-value"), "the password must not appear in /api/settings");

        given().when().delete("/api/proxy/password")
                .then().statusCode(200)
                .body("hasPassword", equalTo(false));
    }

    @Test
    void anEmptyPasswordClearsIt() {
        given().contentType(ContentType.JSON).body("{\"password\": \"x\"}")
                .when().put("/api/proxy/password").then().body("hasPassword", equalTo(true));

        given().contentType(ContentType.JSON).body("{\"password\": \"\"}")
                .when().put("/api/proxy/password").then().body("hasPassword", equalTo(false));
    }

    @Test
    void testingWithNoProxyConfiguredSaysSoInsteadOfFailing() {
        given()
                .when().post("/api/proxy/test")
                .then().statusCode(200)
                .body("reachable", equalTo(false))
                .body("message", containsString("No proxy is configured"));
    }

    /** Settings saved with a proxy that isn't there: the test reports it's unreachable (it doesn't
     * throw), and the status reflects the saved settings. */
    @Test
    void testingAnUnreachableProxyReportsItAsUnreachable() {
        Map<String, Object> body = currentSettingsJson();
        body.put("proxyEnabled", true);
        body.put("proxyHost", "127.0.0.1");
        body.put("proxyPort", 1);
        putSettings(body, 200);

        given().when().get("/api/proxy").then().body("active", equalTo(true)).body("port", equalTo(1));
        given()
                .when().post("/api/proxy/test")
                .then().statusCode(200)
                .body("reachable", equalTo(false));
    }
}
