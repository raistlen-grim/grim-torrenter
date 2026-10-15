package com.grimtorrenter.app;

import com.grimtorrenter.app.HealthView.Check;
import com.grimtorrenter.app.HealthView.Group;
import com.grimtorrenter.app.HealthView.State;
import com.grimtorrenter.engine.blocklist.Blocklist;
import com.grimtorrenter.engine.engine.TorrentEngine;
import com.grimtorrenter.engine.tracker.TrackerStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The health report's individual rules, without booting the app - design_docs/0086. */
class HealthServiceTest {

    @Test
    void aWritableDirectoryIsOkAndAMissingOneIsCreated(@TempDir Path tempDir) {
        Path missing = tempDir.resolve("not-there-yet");

        Check check = HealthService.directoryCheck("downloads", missing);

        assertEquals(State.OK, check.state());
        assertTrue(Files.isDirectory(missing));
        assertTrue(check.message().endsWith("not-there-yet"));
    }

    @Test
    void aPathThatIsAFileNotADirectoryFails(@TempDir Path tempDir) throws Exception {
        Path file = Files.writeString(tempDir.resolve("a-file"), "x");

        assertEquals(State.FAILED, HealthService.directoryCheck("config", file).state());
    }

    @Test
    void lowFreeSpaceIsAWarningNotAFailure() {
        assertEquals(State.WARNING, HealthService.freeSpaceCheck(HealthService.LOW_FREE_SPACE_BYTES - 1).state());
        assertEquals(State.OK, HealthService.freeSpaceCheck(HealthService.LOW_FREE_SPACE_BYTES).state());
        assertEquals("5.0 GB free", HealthService.freeSpaceCheck(5L << 30).message());
    }

    @Test
    void noIncomingConnectionsYetIsInformationNotAFailure() {
        assertEquals(State.DISABLED, HealthService.incomingCheck(false, 0, 0, 0).state());
        assertEquals(State.INFO, HealthService.incomingCheck(true, 0, 0, 0).state());
        // Reached but not kept still proves the port is open.
        assertEquals(State.OK, HealthService.incomingCheck(true, 1, 0, 0).state());
        assertEquals("9 connected now. Since start: 53,812 received, 1,204 accepted",
                HealthService.incomingCheck(true, 53812, 1204, 9).message());
    }

    @Test
    void blocklistStates() {
        assertEquals(State.DISABLED, HealthService.blocklistCheck(blocklist(false, 0, null, false)).state());
        assertEquals(State.INFO, HealthService.blocklistCheck(blocklist(true, 0, null, true)).state());
        assertEquals(State.OK, HealthService.blocklistCheck(blocklist(true, 120, null, false)).state());
        // A failed refresh with an older list still enforced is degraded, not broken.
        assertEquals(State.WARNING, HealthService.blocklistCheck(blocklist(true, 120, "timed out", false)).state());
        assertEquals(State.FAILED, HealthService.blocklistCheck(blocklist(true, 0, "timed out", false)).state());
    }

    private static Blocklist.Status blocklist(boolean enabled, int ranges, String lastError, boolean loading) {
        return new Blocklist.Status(enabled, "source", ranges, 0, lastError, loading, 0);
    }

    @Test
    void authOffIsInformation() {
        assertEquals(State.INFO, HealthService.authCheck(false).state());
        assertEquals(State.OK, HealthService.authCheck(true).state());
    }

    @Test
    void engineServiceStatesMapOntoCheckStates() {
        assertEquals(State.OK, service(TorrentEngine.ServiceState.RUNNING).state());
        assertEquals(State.WARNING, service(TorrentEngine.ServiceState.DEGRADED).state());
        assertEquals(State.DISABLED, service(TorrentEngine.ServiceState.DISABLED).state());
        assertEquals(State.FAILED, service(TorrentEngine.ServiceState.FAILED).state());
        assertEquals("because of the proxy", HealthService.serviceCheck(new TorrentEngine.ServiceStatus(
                "dht", TorrentEngine.ServiceState.DISABLED, "because of the proxy")).message());
    }

    private static Check service(TorrentEngine.ServiceState state) {
        return HealthService.serviceCheck(new TorrentEngine.ServiceStatus("dht", state));
    }

    @Test
    void overallStatusIsTheWorstStatePresent() {
        assertEquals("OK", report(State.OK, State.INFO, State.DISABLED).status());
        assertEquals("WARNING", report(State.OK, State.WARNING).status());
        assertEquals("FAILED", report(State.WARNING, State.FAILED, State.OK).status());
    }

    private static HealthView report(State... states) {
        List<Check> checks = java.util.Arrays.stream(states).map(s -> new Check("c", s, "")).toList();
        return HealthView.of(List.of(new Group("g", checks)));
    }

    @Test
    void durationsAndSizesReadNaturally() {
        assertEquals("Less than a minute", HealthService.formatDuration(Duration.ofSeconds(20)));
        assertEquals("12m", HealthService.formatDuration(Duration.ofMinutes(12)));
        assertEquals("3h 5m", HealthService.formatDuration(Duration.ofMinutes(185)));
        assertEquals("2d 4h", HealthService.formatDuration(Duration.ofHours(52)));
        assertEquals("512 B", HealthService.formatBytes(512));
        assertEquals("1.5 MB", HealthService.formatBytes(3L << 19));
    }

    private static TrackerStatus tracker(String url, TrackerStatus.State state, String lastError) {
        return new TrackerStatus(url, 0, state, null, null, lastError, null, null, null);
    }

    /** design_docs/0089: every tracker failing across the running torrents is the pattern a
     * firewall or a VPN produces, and is the one case that warns. */
    @Test
    void trackersCheckWarnsOnlyWhenEveryTrackerInUseIsFailing() {
        TrackerStatus aFailing = tracker("udp://a.example:1337/announce", TrackerStatus.State.ERROR,
                "UDP tracker udp://a.example:1337/announce did not respond after 2 attempts");
        TrackerStatus bFailing = tracker("https://b.example/announce", TrackerStatus.State.ERROR,
                "Tracker request to https://b.example/announce failed: timed out connecting");
        TrackerStatus bWorking = tracker("https://b.example/announce", TrackerStatus.State.WORKING, null);

        Check allFailing = HealthService.trackersCheck(List.of(List.of(aFailing), List.of(bFailing)));
        assertEquals(State.WARNING, allFailing.state());
        assertTrue(allFailing.message().startsWith("None of the 2 trackers in use is answering."), allFailing.message());
        assertTrue(allFailing.message().contains("Latest: UDP tracker"), allFailing.message());

        // A tracker shared by two torrents counts once, and once working for either counts as working.
        Check oneWorking = HealthService.trackersCheck(List.of(List.of(aFailing, bFailing), List.of(bWorking)));
        assertEquals(State.OK, oneWorking.state());
        assertEquals("1 of 2 answering", oneWorking.message());
    }

    @Test
    void trackersCheckStaysInformationWhileItCannotTell() {
        assertEquals(State.DISABLED, HealthService.trackersCheck(List.of()).state());
        assertEquals(State.INFO, HealthService.trackersCheck(List.of(List.of(
                tracker("udp://a.example:1337/announce", TrackerStatus.State.ERROR, "x"),
                tracker("udp://b.example:1337/announce", TrackerStatus.State.UNKNOWN, null)))).state());
        // One dead tracker looks the same as a blocked network.
        assertEquals(State.INFO, HealthService.trackersCheck(List.of(List.of(
                tracker("udp://a.example:1337/announce", TrackerStatus.State.ERROR, "x")))).state());
    }
}
