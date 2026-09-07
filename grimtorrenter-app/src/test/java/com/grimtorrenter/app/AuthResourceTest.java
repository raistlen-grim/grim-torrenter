package com.grimtorrenter.app;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * One test, not several - AuthStore/SettingsStore/SessionTokenStore are @ApplicationScoped
 * singletons shared across every @QuarkusTest class in the same test run (not just within
 * this class), and JUnit doesn't guarantee method order across separate @Test methods (same
 * reasoning SettingsResourceTest's own combined-flow test already documents) - a single
 * ordered sequence is the only way to reliably exercise "no password yet" before "a password
 * exists" without relying on accidental ordering. Ends with authEnabled=false restored - every
 * other *ResourceTest in this module assumes a fully open API. See design_docs/0061.
 *
 * <p>Also applies TestAuthResource, which overwrites auth.json with "no password set" before
 * boot - without it, a second `mvn test` run without an intervening `mvn clean` would find
 * this test's own previous run's password still on disk under target/, breaking the very
 * first assertion below.
 */
@QuarkusTest
@QuarkusTestResource(TestSettingsResource.class)
@QuarkusTestResource(TestAuthResource.class)
class AuthResourceTest {

    private static final String INITIAL_PASSWORD = "correct horse battery staple";
    private static final String CHANGED_PASSWORD = "new password entirely";

    @Test
    void fullLifecycle() {
        given()
                .when().get("/api/auth/status")
                .then().statusCode(200)
                .body("authEnabled", equalTo(false))
                .body("passwordSet", equalTo(false));

        loginAttempt("whatever").then().statusCode(401);

        // Can't enable auth with no password to log in with.
        Map<String, Object> settingsWithAuthEnabled = currentSettingsAsMap();
        settingsWithAuthEnabled.put("authEnabled", true);
        given()
                .contentType(ContentType.JSON)
                .body(settingsWithAuthEnabled)
                .when().put("/api/settings")
                .then().statusCode(400);

        // Bootstrap: no current password required while none exists yet.
        given()
                .contentType(ContentType.JSON)
                .body(Map.of("newPassword", INITIAL_PASSWORD))
                .when().put("/api/auth/password")
                .then().statusCode(204);

        given()
                .when().get("/api/auth/status")
                .then().statusCode(200)
                .body("passwordSet", equalTo(true));

        // Setting a password doesn't itself turn enforcement on.
        given().when().get("/api/torrents").then().statusCode(200);

        String token = loginAttempt(INITIAL_PASSWORD)
                .then().statusCode(200)
                .extract().path("token");

        setAuthEnabled(true, null);

        try {
            given().when().get("/api/torrents").then().statusCode(401);
            given()
                    .header("Authorization", "Bearer " + token)
                    .when().get("/api/torrents")
                    .then().statusCode(200);

            // /status and /login stay reachable with no token at all.
            given().when().get("/api/auth/status").then().statusCode(200).body("authEnabled", equalTo(true));
            loginAttempt("not the password").then().statusCode(401);

            // PUT /api/auth/password isn't in AuthenticationFilter's allowlist, so once
            // authEnabled is true it needs the bearer token too, on top of (not instead of)
            // the currentPassword check below - defense in depth, see design_docs/0061.
            given()
                    .header("Authorization", "Bearer " + token)
                    .contentType(ContentType.JSON)
                    .body(Map.of("currentPassword", "wrong", "newPassword", CHANGED_PASSWORD))
                    .when().put("/api/auth/password")
                    .then().statusCode(401);

            given()
                    .header("Authorization", "Bearer " + token)
                    .contentType(ContentType.JSON)
                    .body(Map.of("currentPassword", INITIAL_PASSWORD, "newPassword", CHANGED_PASSWORD))
                    .when().put("/api/auth/password")
                    .then().statusCode(204);

            // The old password no longer works, but the already-issued token is untouched -
            // a password change doesn't revoke existing sessions (documented tradeoff, see
            // design_docs/0061).
            loginAttempt(INITIAL_PASSWORD).then().statusCode(401);
            given()
                    .header("Authorization", "Bearer " + token)
                    .when().get("/api/torrents")
                    .then().statusCode(200);

            loginAttempt(CHANGED_PASSWORD).then().statusCode(200);
        } finally {
            setAuthEnabled(false, token);
        }

        // Enforcement is really off again - no token needed any more.
        given().when().get("/api/torrents").then().statusCode(200);
    }

    private static Response loginAttempt(String password) {
        return given()
                .contentType(ContentType.JSON)
                .body(Map.of("password", password))
                .when().post("/api/auth/login");
    }

    /** Reads the current settings with the same bearerToken it's about to PUT with - the
     * read has to be authenticated too once authEnabled is already true (the disable call in
     * this test's own finally block), or the read itself 401s before the disabling PUT ever
     * gets sent, leaving the shared SettingsStore stuck enabled for every test class that
     * runs after this one. */
    private static void setAuthEnabled(boolean enabled, String bearerToken) {
        Map<String, Object> settings = currentSettingsAsMap(bearerToken);
        settings.put("authEnabled", enabled);
        var request = given().contentType(ContentType.JSON);
        if (bearerToken != null) {
            request = request.header("Authorization", "Bearer " + bearerToken);
        }
        request.body(settings)
                .when().put("/api/settings")
                .then().statusCode(200)
                .body("authEnabled", equalTo(enabled));
    }

    private static Map<String, Object> currentSettingsAsMap() {
        return currentSettingsAsMap(null);
    }

    private static Map<String, Object> currentSettingsAsMap(String bearerToken) {
        var request = given();
        if (bearerToken != null) {
            request = request.header("Authorization", "Bearer " + bearerToken);
        }
        Map<String, Object> map = request.when().get("/api/settings").then().statusCode(200)
                .extract().jsonPath().getMap("$");
        return new HashMap<>(map);
    }
}
