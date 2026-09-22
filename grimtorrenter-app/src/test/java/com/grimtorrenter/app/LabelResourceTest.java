package com.grimtorrenter.app;

import com.grimtorrenter.engine.engine.TorrentEngine;
import com.grimtorrenter.engine.label.Label;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

/** design_docs/0077. */
@QuarkusTest
@QuarkusTestResource(CleanDownloadsResource.class)
@QuarkusTestResource(TestSettingsResource.class)
class LabelResourceTest {

    @Inject
    TorrentEngine torrentEngine;

    /** One engine (and one label list) is shared across every test method in the class, same as
     * TorrentResourceTest - clear it before and after so a test never sees another's labels
     * (or leftovers from an earlier run that never got to clean up). */
    @BeforeEach
    @AfterEach
    void deleteAllLabels() {
        for (Label label : torrentEngine.labels().list()) {
            torrentEngine.deleteLabel(label.id());
        }
    }

    private static String createLabel(String name) {
        return given()
                .contentType(ContentType.JSON)
                .body("{\"name\": \"" + name + "\"}")
                .when().post("/api/labels")
                .then().statusCode(200)
                .extract().path("id");
    }

    @Test
    void listStartsEmpty() {
        given()
                .when().get("/api/labels")
                .then().statusCode(200).body("$", hasSize(0));
    }

    @Test
    void createReturnsTheLabelWithAnIdAndListShowsIt() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"name\": \"  Movies \"}")
                .when().post("/api/labels")
                .then().statusCode(200)
                .body("id", notNullValue())
                .body("name", equalTo("Movies"));

        given()
                .when().get("/api/labels")
                .then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].name", equalTo("Movies"));
    }

    @Test
    void createRejectsABlankOrTooLongNameWith400AndADuplicateWith409() {
        createLabel("Movies");

        given().contentType(ContentType.JSON).body("{\"name\": \"   \"}")
                .when().post("/api/labels").then().statusCode(400);
        given().contentType(ContentType.JSON).body("{\"name\": \"" + "x".repeat(33) + "\"}")
                .when().post("/api/labels").then().statusCode(400);
        given().contentType(ContentType.JSON).body("{}")
                .when().post("/api/labels").then().statusCode(400);
        given().contentType(ContentType.JSON).body("{\"name\": \"movies\"}")
                .when().post("/api/labels").then().statusCode(409);

        given().when().get("/api/labels").then().body("$", hasSize(1));
    }

    @Test
    void renameKeepsTheIdAndChangesTheName() {
        String id = createLabel("Movies");

        given()
                .contentType(ContentType.JSON)
                .body("{\"name\": \"Films\"}")
                .when().put("/api/labels/" + id)
                .then().statusCode(200)
                .body("id", equalTo(id))
                .body("name", equalTo("Films"));

        given()
                .when().get("/api/labels")
                .then().body("[0].id", equalTo(id)).body("[0].name", equalTo("Films"));
    }

    @Test
    void renameRejectsAnUnknownLabelWith404AndADuplicateNameWith409() {
        String movies = createLabel("Movies");
        createLabel("Music");

        given().contentType(ContentType.JSON).body("{\"name\": \"Whatever\"}")
                .when().put("/api/labels/no-such-id").then().statusCode(404);
        given().contentType(ContentType.JSON).body("{\"name\": \"music\"}")
                .when().put("/api/labels/" + movies).then().statusCode(409);
        given().contentType(ContentType.JSON).body("{\"name\": \"\"}")
                .when().put("/api/labels/" + movies).then().statusCode(400);
    }

    @Test
    void deleteRemovesTheLabelAndAnUnknownIdIs404() {
        String id = createLabel("Movies");

        given().when().delete("/api/labels/" + id).then().statusCode(204);

        given().when().get("/api/labels").then().body("$", hasSize(0));
        given().when().delete("/api/labels/" + id).then().statusCode(404);
    }
}
