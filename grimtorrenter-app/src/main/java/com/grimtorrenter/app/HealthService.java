package com.grimtorrenter.app;

import com.grimtorrenter.app.HealthView.Check;
import com.grimtorrenter.app.HealthView.Group;
import com.grimtorrenter.app.HealthView.State;
import com.grimtorrenter.engine.ClientIdentity;
import com.grimtorrenter.engine.blocklist.Blocklist;
import com.grimtorrenter.engine.engine.TorrentEngine;
import com.grimtorrenter.engine.events.EventType;
import com.grimtorrenter.engine.events.LibraryEvent;
import com.grimtorrenter.engine.proxy.Socks5;
import com.grimtorrenter.engine.settings.Settings;
import com.grimtorrenter.engine.settings.SettingsStore;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Builds the health report behind the Health page and the container health check
 * (design_docs/0086): the engine's network services, whether the mounted directories are
 * usable, whether anything outside can reach us, the protective features, and which build this
 * is. Everything is evaluated on request from state that is cheap to read - the one check that
 * needs the network (is the proxy reachable) runs in the background and is cached, so a request
 * never waits on it.
 */
@ApplicationScoped
public class HealthService {

    /** Below this much free space on the downloads volume the check turns into a warning. */
    static final long LOW_FREE_SPACE_BYTES = 1L << 30;
    private static final Duration PROXY_PROBE_TTL = Duration.ofMinutes(5);

    @Inject
    TorrentEngine torrentEngine;

    @Inject
    SettingsStore settingsStore;

    @Inject
    JsonLinesEventStore eventStore;

    @ConfigProperty(name = "grimtorrenter.download-directory", defaultValue = "downloads")
    String downloadDirectory;

    @ConfigProperty(name = "grimtorrenter.config-directory", defaultValue = "config")
    String configDirectory;

    @ConfigProperty(name = "grimtorrenter.watch-directory", defaultValue = "watch")
    String watchDirectory;

    /** Storage checks currently failing - so each failure is written to the event log once when
     * it starts, not on every poll. */
    private final Set<String> failingStorageChecks = ConcurrentHashMap.newKeySet();

    private record ProxyProbe(String target, Socks5.TestResult result, Instant at) {
    }

    private volatile ProxyProbe proxyProbe;
    private final AtomicBoolean proxyProbeInFlight = new AtomicBoolean();

    /** Evaluated once at startup so an unusable directory is in the event log (and the server
     * log) from the first moment, without waiting for someone to open the page. */
    void onStart(@Observes StartupEvent event) {
        storageGroup();
    }

    public HealthView report() {
        return HealthView.of(List.of(
                networkGroup(), storageGroup(), connectivityGroup(), protectionGroup(), buildGroup()));
    }

    /** What the container health check asks: is the app up and able to do its job at all. Only
     * the two directories it cannot work without count - a network service that failed to bind
     * or a dead proxy leaves the app running and worth keeping. */
    public boolean isLive() {
        return directoryCheck("config", Path.of(configDirectory)).state() != State.FAILED
                && directoryCheck("downloads", Path.of(downloadDirectory)).state() != State.FAILED;
    }

    private Group networkGroup() {
        List<Check> checks = new ArrayList<>();
        for (TorrentEngine.ServiceStatus status : torrentEngine.serviceStatuses()) {
            checks.add(serviceCheck(status));
        }
        return new Group("network", checks);
    }

    static Check serviceCheck(TorrentEngine.ServiceStatus status) {
        return switch (status.state()) {
            case RUNNING -> new Check(status.name(), State.OK, "Running");
            case DEGRADED -> new Check(status.name(), State.WARNING, "Sparse routing table");
            case DISABLED -> new Check(status.name(), State.DISABLED,
                    status.reason() != null ? status.reason() : "Off");
            case FAILED -> new Check(status.name(), State.FAILED,
                    "Could not start - the port may be in use. See the server log.");
        };
    }

    private Group storageGroup() {
        Path downloads = Path.of(downloadDirectory);
        List<Check> checks = new ArrayList<>();
        checks.add(directoryCheck("downloads", downloads));
        checks.add(directoryCheck("config", Path.of(configDirectory)));
        checks.add(settingsStore.current().watchFolderEnabled()
                ? directoryCheck("watch", Path.of(watchDirectory))
                : new Check("watch", State.DISABLED, "Watch folder is off"));
        checks.add(freeSpaceCheck(downloads));
        recordNewStorageFailures(checks);
        return new Group("storage", checks);
    }

    /** Creates the directory if it is missing (as the engine itself would), then asks the one
     * question that matters: can this process write there. */
    static Check directoryCheck(String name, Path directory) {
        Path absolute = directory.toAbsolutePath().normalize();
        try {
            Files.createDirectories(absolute);
        } catch (IOException | SecurityException e) {
            return new Check(name, State.FAILED, absolute + " does not exist and could not be created. "
                    + "Create it on the host, owned by the user the app runs as.");
        }
        if (!Files.isDirectory(absolute)) {
            return new Check(name, State.FAILED, absolute + " is not a directory.");
        }
        if (!Files.isWritable(absolute)) {
            return new Check(name, State.FAILED, absolute + " is not writable by the user the app runs as. "
                    + "Change its ownership on the host, or set PUID/PGID to the user that owns it.");
        }
        return new Check(name, State.OK, absolute.toString());
    }

    private static Check freeSpaceCheck(Path downloads) {
        try {
            Files.createDirectories(downloads);
            return freeSpaceCheck(Files.getFileStore(downloads).getUsableSpace());
        } catch (IOException | SecurityException e) {
            return new Check("freeSpace", State.INFO, "Unknown");
        }
    }

    static Check freeSpaceCheck(long usableBytes) {
        String free = formatBytes(usableBytes) + " free";
        return usableBytes < LOW_FREE_SPACE_BYTES
                ? new Check("freeSpace", State.WARNING, "Only " + free + " for downloads")
                : new Check("freeSpace", State.OK, free);
    }

    private void recordNewStorageFailures(List<Check> checks) {
        for (Check check : checks) {
            if (check.state() != State.FAILED) {
                failingStorageChecks.remove(check.name());
            } else if (failingStorageChecks.add(check.name())) {
                eventStore.record(new LibraryEvent(
                        Instant.now(), EventType.STORAGE_UNWRITABLE, null, null, check.message()));
            }
        }
    }

    private Group connectivityGroup() {
        return new Group("connectivity", List.of(incomingCheck(), proxyCheck()));
    }

    private Check incomingCheck() {
        boolean listening = torrentEngine.serviceStatuses().stream()
                .anyMatch(s -> s.name().equals("peerServer") && s.state() == TorrentEngine.ServiceState.RUNNING);
        return incomingCheck(listening, torrentEngine.incomingConnectionsSeen(),
                torrentEngine.incomingConnectionsAccepted(), torrentEngine.incomingConnectionsActive());
    }

    /** Evidence, not a probe: nothing here can test the port from outside, but a peer having
     * connected to us proves it is open. None yet proves nothing - a quiet swarm looks the same
     * as a closed port - so that case is information, not a failure. Once there is evidence, the
     * message gives all three numbers: seen alone reads as healthy even when almost none of
     * those peers were kept. */
    static Check incomingCheck(boolean listening, long seen, long accepted, int active) {
        if (!listening) {
            return new Check("incoming", State.DISABLED, "Not accepting incoming connections");
        }
        if (seen == 0) {
            return new Check("incoming", State.INFO, "None received since start. If this stays at none "
                    + "while torrents are active, the listen port is probably not forwarded, or this "
                    + "machine is behind a VPN that doesn't forward ports. Downloads still work.");
        }
        return new Check("incoming", State.OK, String.format(Locale.ROOT,
                "%,d connected now. Since start: %,d received, %,d accepted", active, seen, accepted));
    }

    private Check proxyCheck() {
        TorrentEngine.ProxyStatus status = torrentEngine.proxyStatus();
        if (!status.active()) {
            proxyProbe = null;
            return new Check("proxy", State.DISABLED, "No proxy in use");
        }
        String target = status.host() + ":" + status.port();
        ProxyProbe probe = proxyProbe;
        boolean current = probe != null && probe.target().equals(target);
        if (!current || probe.at().plus(PROXY_PROBE_TTL).isBefore(Instant.now())) {
            startProxyProbe(target);
        }
        if (!current) {
            return new Check("proxy", State.INFO, "Checking " + target + "…");
        }
        Socks5.TestResult result = probe.result();
        if (!result.reachable()) {
            return new Check("proxy", State.FAILED, target + ": " + result.message());
        }
        return result.udpSupported()
                ? new Check("proxy", State.OK, target + " reachable")
                : new Check("proxy", State.WARNING, target + " reachable, but it does not relay UDP "
                        + "- UDP trackers will not work through it");
    }

    private void startProxyProbe(String target) {
        if (!proxyProbeInFlight.compareAndSet(false, true)) {
            return;
        }
        Thread.ofVirtual().name("health-proxy-probe").start(() -> {
            try {
                proxyProbe = new ProxyProbe(target, torrentEngine.testProxy(), Instant.now());
            } catch (RuntimeException e) {
                proxyProbe = new ProxyProbe(target,
                        new Socks5.TestResult(false, false, String.valueOf(e.getMessage())), Instant.now());
            } finally {
                proxyProbeInFlight.set(false);
            }
        });
    }

    private Group protectionGroup() {
        Settings settings = settingsStore.current();
        return new Group("protection", List.of(
                blocklistCheck(torrentEngine.blocklist().status()),
                authCheck(settings.authEnabled())));
    }

    static Check blocklistCheck(Blocklist.Status status) {
        if (!status.enabled()) {
            return new Check("blocklist", State.DISABLED, "Off");
        }
        String ranges = status.rangeCount() + (status.rangeCount() == 1 ? " range" : " ranges");
        if (status.lastError() != null) {
            return status.rangeCount() > 0
                    ? new Check("blocklist", State.WARNING,
                            "Using the last good list (" + ranges + ") - the refresh failed: " + status.lastError())
                    : new Check("blocklist", State.FAILED, "No list loaded: " + status.lastError());
        }
        if (status.rangeCount() == 0) {
            return new Check("blocklist", State.INFO, status.loading() ? "Loading…" : "No list loaded yet");
        }
        return new Check("blocklist", State.OK, ranges + " loaded");
    }

    /** Off is the default and fine on a private network - information, not a warning. */
    static Check authCheck(boolean authEnabled) {
        return authEnabled
                ? new Check("auth", State.OK, "A password is required")
                : new Check("auth", State.INFO, "No password required - anyone who can reach this page has full "
                        + "control. Fine on a private network; turn it on in Settings before exposing it.");
    }

    private Group buildGroup() {
        long uptimeMillis = ManagementFactory.getRuntimeMXBean().getUptime();
        return new Group("build", List.of(
                new Check("version", State.INFO, ClientIdentity.NAME + " " + ClientIdentity.version()),
                new Check("uptime", State.INFO, formatDuration(Duration.ofMillis(uptimeMillis))),
                new Check("user", State.INFO, runningAs())));
    }

    /** The uid/gid the process runs as - what PUID/PGID resolved to in the container. /proc is
     * Linux-only; elsewhere this falls back to the account name. */
    private static String runningAs() {
        try {
            Path self = Path.of("/proc/self");
            Object uid = Files.getAttribute(self, "unix:uid");
            Object gid = Files.getAttribute(self, "unix:gid");
            return "uid " + uid + ", gid " + gid + (Integer.valueOf(0).equals(uid) ? " (root)" : "");
        } catch (IOException | RuntimeException e) {
            return System.getProperty("user.name", "unknown");
        }
    }

    static String formatBytes(long bytes) {
        String[] units = {"B", "KB", "MB", "GB", "TB", "PB"};
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return unit == 0 ? bytes + " B" : String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    static String formatDuration(Duration duration) {
        long days = duration.toDays();
        long hours = duration.toHoursPart();
        long minutes = duration.toMinutesPart();
        if (days > 0) {
            return days + "d " + hours + "h";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return minutes > 0 ? minutes + "m" : "Less than a minute";
    }
}
