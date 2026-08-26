package com.grimtorrenter.app;

import com.grimtorrenter.engine.bencode.BDictionary;
import com.grimtorrenter.engine.bencode.BInteger;
import com.grimtorrenter.engine.bencode.BString;
import com.grimtorrenter.engine.bencode.BencodeEncoder;
import com.grimtorrenter.engine.engine.TorrentEngine;
import com.grimtorrenter.engine.torrent.TorrentSession;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;

/** Round-trips through the real REST layer, filtered to one test's own infoHash throughout
 * (never asserting on the unfiltered /api/events list) - the underlying JsonLinesEventStore is
 * a single @QuarkusTest-shared singleton, so other test classes in the same run add/remove
 * their own torrents into the same on-disk event log. See design_docs/0055.
 *
 * <p>Asserts containment, not exact size/order: torrentBytes()'s announce URL is deliberately
 * unreachable (same as TorrentResourceTest's own helper, for the same "exercise upload
 * plumbing, not tracker communication" reason), which means start()'s synchronous initial
 * announce fails and the session immediately transitions to ERROR too - a real,
 * TorrentEventListener-recorded event alongside ADDED/REMOVED, not a bug in either this test's
 * setup or the feature. */
@QuarkusTest
@QuarkusTestResource(CleanDownloadsResource.class)
@QuarkusTestResource(TestSettingsResource.class)
class EventsResourceTest {

    @Inject
    TorrentEngine torrentEngine;

    /** Same leak-prevention rationale as TorrentResourceTest's own @AfterEach. */
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
    void addingATorrentRecordsAnAddedEventVisibleThroughTheFilteredEndpoint() {
        byte[] torrent = torrentBytes("events-added-test.bin", new byte[]{1, 2, 3});
        String infoHash =
                given().multiPart("file", "test.torrent", torrent)
                        .when().post("/api/torrents")
                        .then().statusCode(200)
                        .extract().path("torrent.infoHash");

        given()
                .queryParam("infoHash", infoHash)
                .when().get("/api/events")
                .then().statusCode(200)
                .body("type", hasItem("ADDED"))
                .body("infoHash", everyItem(equalTo(infoHash)));
    }

    @Test
    void removingATorrentRecordsARemovedEventVisibleThroughTheFilteredEndpoint() {
        byte[] torrent = torrentBytes("events-removed-test.bin", new byte[]{4, 5, 6});
        String infoHash =
                given().multiPart("file", "test.torrent", torrent)
                        .when().post("/api/torrents")
                        .then().statusCode(200)
                        .extract().path("torrent.infoHash");

        given()
                .when().delete("/api/torrents/" + infoHash)
                .then().statusCode(204);

        given()
                .queryParam("infoHash", infoHash)
                .when().get("/api/events")
                .then().statusCode(200)
                .body("type", hasItem("ADDED"))
                .body("type", hasItem("REMOVED"))
                .body("infoHash", everyItem(equalTo(infoHash)));
    }
}
