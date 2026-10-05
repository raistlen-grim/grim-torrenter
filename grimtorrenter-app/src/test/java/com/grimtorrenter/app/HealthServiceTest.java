package com.grimtorrenter.app;

import com.grimtorrenter.app.HealthView.Check;
import com.grimtorrenter.app.HealthView.Group;
import com.grimtorrenter.app.HealthView.State;
import com.grimtorrenter.engine.blocklist.Blocklist;
import com.grimtorrenter.engine.engine.TorrentEngine;
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
}
