package com.grimtorrenter.app;

import com.grimtorrenter.engine.bencode.BDictionary;
import com.grimtorrenter.engine.bencode.BInteger;
import com.grimtorrenter.engine.bencode.BString;
import com.grimtorrenter.engine.bencode.BencodeEncoder;
import com.grimtorrenter.engine.engine.TorrentEngine;
import com.grimtorrenter.engine.torrent.TorrentSession;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@QuarkusTestResource(CleanDownloadsResource.class)
@QuarkusTestResource(TestSettingsResource.class)
class TorrentResourceTest {

    @Inject
    TorrentEngine torrentEngine;

    /** @QuarkusTest shares one TorrentEngine (and its `sessions` map) across every test
     * method in this class - a test that uploads a torrent and never removes it leaks
     * into whichever other test happens to run after it. JUnit5's default method order is
     * a deterministic hash of the method set, not declaration order, so this doesn't
     * reliably surface until the set of test methods changes (adding a new test can
     * reorder every other one) - fixing it here, generically, rather than chasing
     * individual leaks each time that happens. */
    @AfterEach
    void removeAllTorrents() {
        for (TorrentSession session : torrentEngine.listTorrents()) {
            torrentEngine.removeTorrent(session.metadata().infoHash(), true);
        }
    }

    private static byte[] sha1(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Announce URL is deliberately unreachable - this test exercises the REST/upload
     * plumbing, not tracker communication, which is already covered at the engine level. */
    private static byte[] torrentBytes(String name, byte[] content) {
        BDictionary info = new BDictionary(Map.of(
                BString.of("name"), BString.of(name),
                BString.of("piece length"), new BInteger(content.length),
                BString.of("pieces"), BString.of(sha1(content)),
                BString.of("length"), new BInteger(content.length)));
        BDictionary top = new BDictionary(Map.of(
                BString.of("announce"), BString.of("http://127.0.0.1:1/announce"),
                BString.of("info"), info));
        return BencodeEncoder.encode(top);
    }

    @Test
    void listStartsEmpty() {
        given()
                .when().get("/api/torrents")
                .then().statusCode(200).body("$", hasSize(0));
    }

    @Test
    void uploadGetAndDeleteRoundTrip() {
        byte[] torrent = torrentBytes("test-file.bin", new byte[]{1, 2, 3, 4, 5});

        String infoHash =
                given()
                        .multiPart("file", "test.torrent", torrent)
                        .when().post("/api/torrents")
                        .then().statusCode(200)
                        .body("torrent.name", equalTo("test-file.bin"))
                        .body("alreadyExisted", equalTo(false))
                        .body("torrent.usesDht", equalTo(false))
                        .body("torrent.trackerCount", equalTo(1))
                        .body("torrent.dhtBackstopActive", equalTo(false))
                        .extract().path("torrent.infoHash");

        given()
                .when().get("/api/torrents/" + infoHash)
                .then().statusCode(200)
                .body("infoHash", equalTo(infoHash))
                .body("usesDht", equalTo(false))
                .body("trackerCount", equalTo(1))
                .body("dhtBackstopActive", equalTo(false));

        given()
                .when().delete("/api/torrents/" + infoHash)
                .then().statusCode(204);

        given()
                .when().get("/api/torrents/" + infoHash)
                .then().statusCode(404);
    }

    @Test
    void uploadingTheSameTorrentTwiceReportsAlreadyExisted() {
        byte[] torrent = torrentBytes("duplicate-upload-test.bin", new byte[]{7, 8, 9});

        given()
                .multiPart("file", "test.torrent", torrent)
                .when().post("/api/torrents")
                .then().statusCode(200)
                .body("alreadyExisted", equalTo(false));

        given()
                .multiPart("file", "test.torrent", torrent)
                .when().post("/api/torrents")
                .then().statusCode(200)
                .body("alreadyExisted", equalTo(true));
    }

    @Test
    void deleteWithoutDeleteDataKeepsDownloadedFiles() {
        byte[] torrent = torrentBytes("keep-data-test.bin", new byte[]{1, 2, 3});

        String infoHash =
                given()
                        .multiPart("file", "test.torrent", torrent)
                        .when().post("/api/torrents")
                        .then().statusCode(200)
                        .extract().path("torrent.infoHash");

        Path directory = Path.of("target", "test-downloads", "keep-data-test.bin");
        assertTrue(Files.exists(directory));

        given()
                .when().delete("/api/torrents/" + infoHash)
                .then().statusCode(204);

        assertTrue(Files.exists(directory));
    }

    @Test
    void deleteWithDeleteDataQueryParamRemovesDownloadedFiles() {
        byte[] torrent = torrentBytes("delete-data-test.bin", new byte[]{4, 5, 6});

        String infoHash =
                given()
                        .multiPart("file", "test.torrent", torrent)
                        .when().post("/api/torrents")
                        .then().statusCode(200)
                        .extract().path("torrent.infoHash");

        Path directory = Path.of("target", "test-downloads", "delete-data-test.bin");
        assertTrue(Files.exists(directory));

        given()
                .queryParam("deleteData", true)
                .when().delete("/api/torrents/" + infoHash)
                .then().statusCode(204);

        assertFalse(Files.exists(directory));
    }

    @Test
    void addMagnetWithUnreachableButUsableTrackerIsAccepted() {
        // The actual peer-fetch chain is covered end-to-end at the engine level
        // (TorrentEngineMagnetTest) - this only exercises the REST plumbing, same
        // rationale as torrentBytes()'s deliberately-unreachable tracker above.
        String magnetUri = "magnet:?xt=urn:btih:" + "a".repeat(40)
                + "&tr=http%3A%2F%2F127.0.0.1%3A1%2Fannounce";

        given()
                .contentType("text/plain")
                .body(magnetUri)
                .when().post("/api/torrents/magnet")
                .then().statusCode(204);
    }

    @Test
    void addMagnetWithMalformedUriReturns400() {
        given()
                .contentType("text/plain")
                .body("not-a-magnet-uri")
                .when().post("/api/torrents/magnet")
                .then().statusCode(400);
    }

    @Test
    void addMagnetWithNoUsableTrackerReturns400() {
        String magnetUri = "magnet:?xt=urn:btih:" + "b".repeat(40);

        given()
                .contentType("text/plain")
                .body(magnetUri)
                .when().post("/api/torrents/magnet")
                .then().statusCode(400);
    }

    @Test
    void gettingUnknownTorrentReturns404() {
        given()
                .when().get("/api/torrents/" + "0".repeat(40))
                .then().statusCode(404);
    }

    /** See design_docs/0031 - the three self-contained per-torrent detail endpoints
     * added in the "cheap tier" pass. */
    @Test
    void getPiecesReturnsOneNeededEntryForAFreshSinglePieceUpload() {
        byte[] torrent = torrentBytes("pieces-test.bin", new byte[]{1, 2, 3});
        String infoHash = upload(torrent);

        given()
                .when().get("/api/torrents/" + infoHash + "/pieces")
                .then().statusCode(200)
                .body("pieces", hasSize(1))
                .body("pieces[0]", equalTo("NEEDED"))
                .body("pieceLength", greaterThan(0));
    }

    @Test
    void getFilesReturnsTheSingleFileForASingleFileTorrent() {
        byte[] torrent = torrentBytes("files-test.bin", new byte[]{1, 2, 3});
        String infoHash = upload(torrent);

        given()
                .when().get("/api/torrents/" + infoHash + "/files")
                .then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].pathSegments", equalTo(List.of("files-test.bin")))
                .body("[0].length", equalTo(3))
                .body("[0].bytesDownloaded", equalTo(0));
    }

    /** start()'s initial announce is synchronous (see design_docs/0017), so by the time
     * upload() returns, the (deliberately unreachable) tracker has already been attempted
     * and failed - no polling needed, unlike the async DHT/piece-download tests. See
     * design_docs/0031. */
    @Test
    void getTrackersReturnsErrorStatusForAnUnreachableTracker() {
        byte[] torrent = torrentBytes("trackers-test.bin", new byte[]{1, 2, 3});
        String infoHash = upload(torrent);

        given()
                .when().get("/api/torrents/" + infoHash + "/trackers")
                .then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].url", equalTo("http://127.0.0.1:1/announce"))
                .body("[0].tier", equalTo(0))
                .body("[0].status", equalTo("ERROR"))
                .body("[0].lastError", notNullValue());
    }

    @Test
    void getPeersReturnsEmptyListWhenNoneConnected() {
        byte[] torrent = torrentBytes("peers-test.bin", new byte[]{1, 2, 3});
        String infoHash = upload(torrent);

        given()
                .when().get("/api/torrents/" + infoHash + "/peers")
                .then().statusCode(200)
                .body("$", hasSize(0));
    }

    @Test
    void detailEndpointsReturn404ForUnknownTorrent() {
        String unknownHash = "0".repeat(40);
        given().when().get("/api/torrents/" + unknownHash + "/pieces").then().statusCode(404);
        given().when().get("/api/torrents/" + unknownHash + "/files").then().statusCode(404);
        given().when().get("/api/torrents/" + unknownHash + "/peers").then().statusCode(404);
        given().when().get("/api/torrents/" + unknownHash + "/trackers").then().statusCode(404);
        given().when().get("/api/torrents/" + unknownHash + "/seeding-limits").then().statusCode(404);
    }

    /** -1 for both fields is SeedingLimitOverride.INHERIT - a fresh upload has never had its
     * override touched. See design_docs/0054. */
    @Test
    void getSeedingLimitsReturnsInheritForAFreshUpload() {
        byte[] torrent = torrentBytes("seeding-limits-fresh.bin", new byte[]{1, 2, 3});
        String infoHash = upload(torrent);

        given()
                .when().get("/api/torrents/" + infoHash + "/seeding-limits")
                .then().statusCode(200)
                .body("ratioLimit", equalTo(-1f))
                .body("timeLimitMinutes", equalTo(-1));
    }

    @Test
    void putSeedingLimitsUpdatesTheOverrideAndGetReflectsIt() {
        byte[] torrent = torrentBytes("seeding-limits-update.bin", new byte[]{1, 2, 3});
        String infoHash = upload(torrent);

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"ratioLimit": 3.0, "timeLimitMinutes": 90}""")
                .when().put("/api/torrents/" + infoHash + "/seeding-limits")
                .then().statusCode(200)
                .body("ratioLimit", equalTo(3.0f))
                .body("timeLimitMinutes", equalTo(90));

        given()
                .when().get("/api/torrents/" + infoHash + "/seeding-limits")
                .then().statusCode(200)
                .body("ratioLimit", equalTo(3.0f))
                .body("timeLimitMinutes", equalTo(90));
    }

    private static String upload(byte[] torrent) {
        return given()
                .multiPart("file", "test.torrent", torrent)
                .when().post("/api/torrents")
                .then().statusCode(200)
                .extract().path("torrent.infoHash");
    }

    @Test
    void invalidInfoHashReturns400() {
        given()
                .when().get("/api/torrents/not-a-valid-hash")
                .then().statusCode(400);
    }
}
