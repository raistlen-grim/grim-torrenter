package com.grimtorrenter.app;

import com.grimtorrenter.engine.engine.TorrentEngine;
import com.grimtorrenter.engine.settings.Settings;
import com.grimtorrenter.engine.settings.SettingsStore;
import com.grimtorrenter.engine.storage.FileHandlePool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Path;

@ApplicationScoped
public class TorrentEngineProducer {

    @ConfigProperty(name = "grimtorrenter.download-directory", defaultValue = "downloads")
    String downloadDirectory;

    @ConfigProperty(name = "grimtorrenter.listen-port", defaultValue = "6881")
    int listenPort;

    /** Bounds total open torrent-data file handles across every torrent this engine manages
     * at once (design_docs/0047) - deploy-time config, not a live Settings field, matching
     * download-directory/listen-port above: it's a resource/infra sizing knob, not a
     * user-facing behavioral preference. 256 leaves comfortable headroom under a typical
     * default OS ulimit (often 1024) for everything else competing for file descriptors -
     * peer connection sockets (up to 30 per torrent, design_docs/0007), the DHT/PeerServer
     * sockets, and the JVM's own baseline usage. Raise it (and the container/host ulimit
     * alongside it, e.g. Docker's --ulimit nofile) for a deployment that's happy to trade a
     * higher fd ceiling for less cache churn across many simultaneously-active torrents. */
    @ConfigProperty(name = "grimtorrenter.max-open-files", defaultValue = "256")
    int maxOpenFiles;

    /** Bounds how many pieces can be mid-verification (a full-piece read plus a SHA-1 hash)
     * across every torrent this engine manages at once (design_docs/0048) - deploy-time
     * config, same category as max-open-files above. 0 means "use the number of available
     * processors" (resolved below, not in this annotation's defaultValue - that has to be a
     * compile-time constant) - CPU-bound hashing work parallelized past the core count buys
     * no extra throughput, only more simultaneous full-piece buffers in memory, so the core
     * count is the natural default rather than an arbitrary fixed number. */
    @ConfigProperty(name = "grimtorrenter.max-concurrent-piece-verifications", defaultValue = "0")
    int maxConcurrentPieceVerifications;

    @Inject
    TorrentEventListener eventListener;

    @Inject
    SettingsStore settingsStore;

    @Inject
    JsonLinesEventStore eventStore;

    /**
     * TorrentEventListener has no TorrentEngine reference (see
     * TorrentSnapshotScheduler for the one that does) specifically so this
     * producer has no circular dependency to worry about. See design_docs/0019.
     *
     * <p>dhtEnabled/acceptIncomingConnections are read once here at startup - see
     * design_docs/0041 for why: DhtNode/PeerServer are each created once, right here, and
     * don't support being started or stopped later, so a change to either setting still
     * requires a restart to actually apply even though it saves live.
     *
     * <p>settingsStore itself is also passed straight into TorrentEngine (not just read
     * once) - unlike those two booleans, rate limiting is genuinely live: TorrentEngine's
     * own RateLimiters re-read settingsStore.current() on every use, so a limit change
     * takes effect on the very next block sent/received. See design_docs/0042.
     */
    @Produces
    @ApplicationScoped
    public TorrentEngine torrentEngine() {
        Settings settings = settingsStore.current();
        int verificationPermits = maxConcurrentPieceVerifications > 0
                ? maxConcurrentPieceVerifications
                : Runtime.getRuntime().availableProcessors();
        return new TorrentEngine(Path.of(downloadDirectory), listenPort, eventListener,
                settings.dhtEnabled(), settings.acceptIncomingConnections(), settingsStore,
                new FileHandlePool(maxOpenFiles), verificationPermits, eventStore);
    }
}
