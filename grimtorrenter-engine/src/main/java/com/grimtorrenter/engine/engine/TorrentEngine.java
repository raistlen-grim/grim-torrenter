package com.grimtorrenter.engine.engine;

import com.grimtorrenter.engine.AtomicFiles;
import com.grimtorrenter.engine.GuardedTask;
import com.grimtorrenter.engine.bencode.BDictionary;
import com.grimtorrenter.engine.bencode.BList;
import com.grimtorrenter.engine.bencode.BString;
import com.grimtorrenter.engine.bencode.BValue;
import com.grimtorrenter.engine.bencode.BencodeDecoder;
import com.grimtorrenter.engine.bencode.BencodeEncoder;
import com.grimtorrenter.engine.dht.DhtNode;
import com.grimtorrenter.engine.dht.NodeId;
import com.grimtorrenter.engine.events.EventStore;
import com.grimtorrenter.engine.events.EventType;
import com.grimtorrenter.engine.events.InMemoryEventStore;
import com.grimtorrenter.engine.events.LibraryEvent;
import com.grimtorrenter.engine.blocklist.Blocklist;
import com.grimtorrenter.engine.label.LabelRegistry;
import com.grimtorrenter.engine.lsd.LsdService;
import com.grimtorrenter.engine.magnet.MagnetLink;
import com.grimtorrenter.engine.metadata.MetadataFetcher;
import com.grimtorrenter.engine.metainfo.InfoHash;
import com.grimtorrenter.engine.metainfo.MetainfoParser;
import com.grimtorrenter.engine.metainfo.SingleFileTorrent;
import com.grimtorrenter.engine.metainfo.TorrentMetadata;
import com.grimtorrenter.engine.mse.EncryptionMode;
import com.grimtorrenter.engine.peer.IncomingConnectionHandler;
import com.grimtorrenter.engine.peer.PeerServer;
import com.grimtorrenter.engine.proxy.ProxyConfig;
import com.grimtorrenter.engine.proxy.Socks5;
import com.grimtorrenter.engine.peer.PeerSource;
import com.grimtorrenter.engine.peer.UtpIncomingConnectionHandler;
import com.grimtorrenter.engine.peer.UtpPeerAcceptor;
import com.grimtorrenter.engine.ratelimit.AppliedLimit;
import com.grimtorrenter.engine.ratelimit.RateLimiters;
import com.grimtorrenter.engine.stats.HistoryResolution;
import com.grimtorrenter.engine.stats.HistoryRing;
import com.grimtorrenter.engine.stats.TransferDelta;
import com.grimtorrenter.engine.stats.TransferHistory;
import com.grimtorrenter.engine.stats.TransferMeter;
import com.grimtorrenter.engine.stats.TransferRate;
import com.grimtorrenter.engine.settings.InMemorySettingsStore;
import com.grimtorrenter.engine.settings.Settings;
import com.grimtorrenter.engine.settings.SettingsStore;
import com.grimtorrenter.engine.storage.FileHandlePool;
import com.grimtorrenter.engine.torrent.SeedingLimitOverride;
import com.grimtorrenter.engine.torrent.SeedingLimits;
import com.grimtorrenter.engine.piece.FilePriorities;
import com.grimtorrenter.engine.piece.FilePriority;
import com.grimtorrenter.engine.torrent.TorrentLimitOverride;
import com.grimtorrenter.engine.torrent.TorrentLimits;
import com.grimtorrenter.engine.torrent.TorrentSession;
import com.grimtorrenter.engine.torrent.TorrentSessionListener;
import com.grimtorrenter.engine.torrent.TorrentState;
import com.grimtorrenter.engine.tracker.HttpTrackerClient;
import com.grimtorrenter.engine.tracker.MultiTrackerClient;
import com.grimtorrenter.engine.tracker.NoOpTrackerClient;
import com.grimtorrenter.engine.tracker.PeerAddress;
import com.grimtorrenter.engine.tracker.PeerId;
import com.grimtorrenter.engine.tracker.TrackedTrackerClient;
import com.grimtorrenter.engine.tracker.TrackerReachability;
import com.grimtorrenter.engine.tracker.TrackerClient;
import com.grimtorrenter.engine.tracker.TrackerEvent;
import com.grimtorrenter.engine.tracker.TrackerRequest;
import com.grimtorrenter.engine.tracker.TrackerStatusListener;
import com.grimtorrenter.engine.tracker.UdpTrackerClient;
import com.grimtorrenter.engine.utp.UtpSocket;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The facade grimtorrenter-app talks to: manages the set of active
 * TorrentSessions (add/remove/pause/resume). See design_docs/0018 for the
 * design calls made here (peer id/download dir ownership, tracker URL
 * selection, why "pause"/"resume" need no new TorrentSession logic).
 */
public final class TorrentEngine {

    private static final System.Logger LOG = System.getLogger(TorrentEngine.class.getName());

    /** Every per-torrent marker (this one plus state/seeding-limit-override/added-at/
     * download-path) lives in its own subdirectory of this name under configDirectory, keyed
     * by info hash - configDirectory.resolve(TORRENTS_CONFIG_SUBDIRECTORY).resolve(infoHash.hex())
     * (see configTorrentDirectory()). Nothing but real downloaded content lives in the download
     * directory any more - see design_docs/0065, which replaced the previous
     * colocated-with-downloads scheme (design_docs/0024/0026/0054) outright, no migration (this
     * app has no existing installs to preserve compatibility for). */
    private static final String TORRENTS_CONFIG_SUBDIRECTORY = "torrents";

    /** Holds the original .torrent file's bytes - restore() needs them again to
     * re-parse the metadata after a process restart. Presence of this file is what marks a
     * config/torrents/<infoHash> directory as restorable. See design_docs/0026/0065. */
    private static final String TORRENT_FILE_MARKER_FILENAME = ".grimtorrenter.torrent";

    /** Records where this torrent's actual content lives on disk - a file path for a
     * single-file torrent placed flat in the download root, a directory for a multi-file one
     * (or a single-file torrent whose bare name collided with something already there). The
     * sole source of truth for "where do this torrent's files live," and for
     * resolveDownloadDirectory() recognizing a torrent it already resolved a path for once
     * (e.g. re-added after a "remove but keep files" removal) versus a genuinely new one. See
     * design_docs/0065. */
    private static final String DOWNLOAD_PATH_MARKER_FILENAME = ".grimtorrenter-download-path";

    /** The desired running/paused state as of the last add/pause/resume call, written
     * through immediately (not just on clean shutdown) so a crash doesn't lose it.
     * See design_docs/0026. */
    private static final String STATE_MARKER_FILENAME = ".grimtorrenter-state";
    private static final String STATE_RUNNING = "RUNNING";
    private static final String STATE_STOPPED = "STOPPED";

    /** Persisted per BEP 5's own recommendation - a stable node id means peers who've
     * bucketed us stay valid across restarts. See design_docs/0028. */
    private static final String DHT_NODE_ID_MARKER_FILENAME = ".grimtorrenter-dht-node-id";

    /** One "ip,port" line per known DHT contact - plain text, same "no JSON library at this
     * layer" convention as every other marker file here. Loaded on startup as a warm-start
     * head start for bootstrap (each contact is re-verified with a real ping before being
     * trusted, same as any hardcoded bootstrap host - see DhtNode.bootstrap(List)), saved
     * periodically and on shutdown. See design_docs/0028's own 2026-08-30 addendum. */
    private static final String DHT_KNOWN_NODES_MARKER_FILENAME = ".grimtorrenter-dht-nodes";

    /** This torrent's SeedingLimitOverride, plain key=value lines (grimtorrenter-engine has
     * zero production dependencies, no JSON library available at this layer - same reasoning
     * as every other marker file here). Deliberately never deleted by removeTorrent(infoHash,
     * false) (keep files) - a torrent-config-scoped preference like this stays with the
     * torrent's record, same as the download-path marker does. See design_docs/0054/0065. */
    private static final String SEEDING_LIMIT_OVERRIDE_MARKER_FILENAME = ".grimtorrenter-seeding-limit-override";
    private static final long SEEDING_LIMIT_CHECK_INTERVAL_SECONDS = 30;

    /** This torrent's TorrentLimitOverride, same plain key=value shape as the seeding-limit
     * override marker just above, for the same reason. Deliberately never deleted by
     * removeTorrent(infoHash, false) (keep files) - same "torrent-config-scoped preference
     * stays with the record" reasoning. See design_docs/0072/0065. */
    private static final String TORRENT_LIMIT_OVERRIDE_MARKER_FILENAME = ".grimtorrenter-torrent-limit-override";
    /** Sparse "index=PRIORITY" lines for every file not at MEDIUM - absent file/line, an unknown
     * priority name, or an out-of-range index all mean MEDIUM. See design_docs/0075. */
    private static final String FILE_PRIORITIES_MARKER_FILENAME = ".grimtorrenter-file-priorities";
    /** One label id per line - see design_docs/0077. The engine-wide label list itself lives in
     * configDirectory (LabelRegistry.FILENAME). */
    private static final String LABEL_IDS_MARKER_FILENAME = ".grimtorrenter-label-ids";
    /** Per-torrent cap, so a marker can't grow without bound. See design_docs/0077. */
    public static final int MAX_LABELS_PER_TORRENT = 20;
    private static final long BLOCKLIST_CHECK_INTERVAL_SECONDS = 60;
    private static final int PROXY_TEST_TIMEOUT_MS = 10_000;

    /** Lifetime uploaded bytes, cumulative active time, and completed-on timestamp - the three
     * metrics design_docs/0054 explicitly deferred persisting ("neither ratio nor seed time
     * survives a process restart"), picked back up in design_docs/0064. Plain key=value lines,
     * same reasoning as every other marker file here. Unlike the other per-torrent markers,
     * rewritten periodically by a live session rather than only on a deliberate user action -
     * written atomically (temp file + ATOMIC_MOVE), same "written often enough that a torn
     * write is worth avoiding cheaply" reasoning DHT_KNOWN_NODES_MARKER_FILENAME already uses. */
    private static final String LIFETIME_STATS_MARKER_FILENAME = ".grimtorrenter-lifetime-stats";
    /** Same cadence as SEEDING_LIMIT_CHECK_INTERVAL_SECONDS - both walk every live session on
     * the shared maintenanceScheduler tick. See design_docs/0064. */
    private static final long LIFETIME_STATS_FLUSH_INTERVAL_SECONDS = 30;

    /** The engine-wide hourly transfer history, so "last night" survives a restart - one
     * "hour=index,downloadBytes,uploadBytes,coveredMillis" line per hour held. Saved on the
     * lifetime-stats flush and at shutdown; missing or unreadable means starting empty, never a
     * failed startup. See design_docs/0102. */
    private static final String TRANSFER_HISTORY_FILENAME = ".grimtorrenter-transfer-history";
    /** The transfer meters' bucket width. See design_docs/0102. */
    private static final long TRANSFER_SAMPLE_INTERVAL_MILLIS = 1_000;
    private static final int GLOBAL_MINUTES_KEPT = 120;
    private static final int GLOBAL_HOURS_KEPT = 168;

    /** When this torrent was added, ISO-8601 instant text - for the details panel's "Added"
     * fact (design_docs/0032). Absent on a directory added before this field existed; no
     * migration/backfill for those (see readAddedAtMarker()) - "unknown" is a real, permanent
     * state for them, not something to guess at. */
    private static final String ADDED_AT_MARKER_FILENAME = ".grimtorrenter-added-at";

    /** Marks a config/torrents/<infoHash> directory as "a magnet metadata fetch is (or was,
     * when the process last ran) in progress" rather than a resolved torrent - present exactly
     * while there's no TORRENT_FILE_MARKER_FILENAME yet. Plain key=value lines: `displayName`
     * (may be absent - not every magnet carries a dn= param) plus one `tracker` line per
     * announce URL. Written before the fetch itself starts and deleted the instant it concludes
     * (success, failure, or explicit removal) - see design_docs/0070. */
    private static final String MAGNET_PENDING_MARKER_FILENAME = ".grimtorrenter-magnet-pending";

    /** See design_docs/0056. */
    private static final String WATCH_ADDED_SUBDIRECTORY = "added";
    private static final String WATCH_FAILED_SUBDIRECTORY = "failed";
    private static final String TORRENT_FILE_EXTENSION = ".torrent";
    /** See design_docs/0056's own 2026-09-06 addendum - a dropped magnet-link file is just its
     * bare magnet: URI as the file's entire (trimmed) text content, nothing more structured. */
    private static final String MAGNET_FILE_EXTENSION = ".magnet";
    private static final String WATCH_FOLDER_SOURCE = "watch folder";

    /** See design_docs/0055's own MAGNET_RESOLVED addendum - addTorrent()'s existing source
     * mechanism (built for WATCH_FOLDER_SOURCE above) reused rather than a new EventType, same
     * "Added via X" message shape. */
    private static final String MAGNET_SOURCE = "magnet";

    private static final Duration DHT_QUERY_TIMEOUT = Duration.ofSeconds(5);
    /** How long to wait before re-announcing/re-querying when a round found literally nothing
     * new to try (every candidate that came back was already tried this attempt) - without
     * this, that specific case would spin-loop re-announcing as fast as the tracker/DHT can
     * answer, since (unlike a round that actually attempts connections) it costs no wall-clock
     * time of its own to pace things. A round that DOES have fresh candidates needs no
     * additional delay - racing them already costs roughly one candidate's connect-timeout
     * worth of time, a reasonable re-announce cadence on its own. See design_docs/0028's
     * addendum. */
    private static final Duration EMPTY_ROUND_RETRY_DELAY = Duration.ofSeconds(5);
    /** How long shutdown() waits, in total, for the sessions' background STOPPED announces. Kept
     * well under a container runtime's default 10s stop grace period. See design_docs/0081. */
    private static final Duration SHUTDOWN_ANNOUNCE_GRACE = Duration.ofSeconds(3);

    private final Path baseDownloadDirectory;
    /** Where all engine bookkeeping lives: engine-wide files (DHT node id and contacts, labels,
     * blocklist cache, proxy password) and every per-torrent marker (configTorrentDirectory(),
     * design_docs/0065). Defaults to baseDownloadDirectory for constructors that don't take it;
     * production (grimtorrenter-app's TorrentEngineProducer) passes grimtorrenter.config-directory,
     * keeping GrimTorrenter's own files out of the download directory a user browses. */
    private final Path configDirectory;
    private final LabelRegistry labelRegistry;
    private final Blocklist blocklist;
    /** The live SOCKS5 proxy configuration (design_docs/0079) - every outbound TCP connection, HTTP
     * and UDP tracker announce, and the blocklist download reads it fresh, so a change to the
     * proxy settings applies to the very next one. */
    private final ProxyConfig proxyConfig;
    /** Whether the proxy's "block anything that can't use it" switch was on when this engine
     * started. DHT, µTP, LSD and the inbound peer server are each created once at construction, so
     * turning that switch on (or a proxy on with it) only stops them from the next start - this
     * remembers what *this* run actually did, so proxyStatus() can say a restart is still needed. */
    private final boolean proxyBlocksUnsupportedAtStart;
    private final PeerId ourPeerId;
    private final int ourListenPort;
    private final TorrentSessionListener listener;
    private final Map<InfoHash, TorrentSession> sessions = new ConcurrentHashMap<>();
    /** Each torrent's actual content location - a file for a flat-placed single-file torrent,
     * a directory otherwise (see contentPathFor()). Used for removeTorrent()'s deletion target
     * only; per-torrent config/marker I/O uses configTorrentDirectory(infoHash) instead, which
     * needs no map since it's deterministic from the info hash alone. See design_docs/0065. */
    private final Map<InfoHash, Path> directories = new ConcurrentHashMap<>();
    /** Every magnet currently mid-metadata-fetch - populated the instant addMagnet() is called
     * (before the fetch itself starts), removed the instant it concludes (success, failure, or
     * explicit removal). Mirrored on disk by MAGNET_PENDING_MARKER_FILENAME so a restart
     * mid-fetch re-drives it instead of losing it. See design_docs/0070. */
    private final Map<InfoHash, PendingMagnet> pendingMagnets = new ConcurrentHashMap<>();
    private final Object directoryResolutionLock = new Object();
    /** Nullable - DHT is a peer-discovery enhancement, not a hard dependency (see
     * design_docs/0028); construction failure leaves this null and every DHT-dependent
     * code path below just skips DHT rather than failing the whole engine. */
    private final DhtNode dhtNode;
    /** True only when DHT was requested (enableDht) but createDhtNode() still returned null -
     * distinguishes "never asked for" (DISABLED) from "asked for, bind failed" (FAILED) for
     * serviceStatuses() below, since dhtNode alone can't tell those two apart. See
     * design_docs/0059. */
    private final boolean dhtBindFailed;
    /** Nullable - inbound connections are an enhancement over outbound-only operation, not
     * a hard dependency (see design_docs/0038); construction failure (e.g. the port is
     * already in use) leaves this null rather than failing the whole engine. */
    private final PeerServer peerServer;
    /** Same "requested but failed" distinction as dhtBindFailed above, for the peer server.
     * See design_docs/0059. */
    private final boolean peerServerBindFailed;
    /** Nullable - BEP 14 Local Service Discovery is a LAN-only peer-discovery enhancement, not
     * a hard dependency, same category as dhtNode/peerServer above; construction failure leaves
     * this null rather than failing the whole engine. See design_docs/0062. */
    private final LsdService lsdService;
    /** Same "requested but failed" distinction as dhtBindFailed/peerServerBindFailed above, for
     * LSD. See design_docs/0059/0062. */
    private final boolean lsdBindFailed;
    /** Read once, at construction time, to schedule announceViaLsd()'s tick on
     * maintenanceScheduler below - same "engine-wide scheduled task, a live change takes effect
     * on the engine's next construction/restart, not retroactively" shape as
     * dhtRefreshIntervalSeconds/watchFolderScanIntervalSeconds above. See design_docs/0062. */
    private final long lsdAnnounceIntervalSeconds;
    /** Shared across every TorrentSession/PeerConnection this engine creates - see
     * design_docs/0042. Never null; the lower-arity constructors default to an unlimited,
     * engine-private SettingsStore so every pre-existing caller/test is unaffected. */
    private final RateLimiters rateLimiters;

    /** Engine-wide transfer figures: the sum of every session's, each tick. Written only by
     * sampleTransfer() on maintenanceScheduler. See design_docs/0102. */
    private final TransferMeter globalDownload = new TransferMeter();
    private final TransferMeter globalUpload = new TransferMeter();
    private final HistoryRing globalMinutes = new HistoryRing(60_000, GLOBAL_MINUTES_KEPT);
    private final HistoryRing globalHours = new HistoryRing(3_600_000, GLOBAL_HOURS_KEPT);
    /** System.nanoTime() of the previous sampleTransfer(); 0 before the first. Tick thread only. */
    private long lastTransferSampleNanos;
    /** Bounds total open torrent-data file handles across every TorrentSession this engine
     * creates or restores - see design_docs/0047. Never null; the lower-arity constructors
     * default to an unbounded pool so every pre-existing caller/test is unaffected. */
    private final FileHandlePool fileHandlePool;
    /** Shared across every TorrentSession this engine creates or restores - bounds how many
     * pieces can be mid-verification across the whole engine at once (a real concern at
     * startup, when every restoring torrent re-hashes its pieces concurrently on its own
     * virtual thread). See design_docs/0048. Never null; the lower-arity constructors default
     * to an effectively-unbounded Semaphore so every pre-existing caller/test is unaffected. */
    private final Semaphore pieceVerificationLimiter;
    /** Bounds simultaneous in-flight magnet metadata-fetch connection attempts across every
     * magnet-add happening at once, engine-wide - not per-magnet, per-round. Sized from
     * settingsStore.current().magnetFetchConcurrencyLimit() at construction, then resized to
     * the live value at the start of each magnet-add attempt (see
     * fetchMagnetMetadataThenAdd()) - a Semaphore's initial permit count alone can't stay live the
     * way a Supplier-read value can, so this uses LiveResizableSemaphore instead of a plain one.
     * See design_docs/0028's addendum. */
    private final LiveResizableSemaphore metadataFetchLimiter;
    /** Read live from settingsStore on every connection attempt/accept, not snapshotted
     * here - see EncryptionMode's own Javadoc for why. See design_docs/0052. */
    private final Supplier<EncryptionMode> encryptionMode;
    /** Same live-read-per-use shape as encryptionMode above - passed straight through to every
     * TorrentSession this engine creates or restores, which reads it once per start() to
     * schedule its own periodic DHT peer-discovery task - for any non-private torrent DHT is
     * eligible for, not just a genuinely trackerless one. See design_docs/0036's own 2026-09-06
     * revision. */
    private final Supplier<Long> dhtReannounceIntervalSeconds;
    /** Same live-read-per-use shape as dhtReannounceIntervalSeconds above - design_docs/0074's
     * slice 4, passed straight through to every TorrentSession, which reads it fresh on every
     * outbound µTP-first connection attempt (only ever used when Settings.utpEnabled is true). */
    private final Supplier<Integer> utpConnectTimeoutSeconds;
    /** Read once, at construction time, to schedule refreshDhtRoutingTable()'s tick on
     * maintenanceScheduler below - unlike dhtReannounceIntervalSeconds above, this
     * drives an engine-wide scheduled task rather than a per-torrent one, so a live change
     * here takes effect on the engine's next construction/restart, not retroactively (same
     * "a ScheduledExecutorService's period can't change mid-flight" limitation, just at
     * engine scope). See design_docs/0028's own 2026-08-30 addendum. */
    private final long dhtRefreshIntervalSeconds;
    /** Same "read once at construction, engine-wide scheduled task, a live change takes effect
     * on the engine's next construction/restart, not retroactively" shape as
     * dhtRefreshIntervalSeconds just above, for the same reason. See design_docs/0056's own
     * 2026-09-06 addendum. */
    private final long watchFolderScanIntervalSeconds;
    /** Kept as a field (not just a constructor-local capture) since checkSeedingLimits()
     * reads it fresh on every scheduled tick, not from a single lambda built once at
     * construction. See design_docs/0054. */
    private final SettingsStore settingsStore;
    /** Records library-management events (torrent added/completed/errored/removed, an
     * auto-pause from a reached seeding limit) - see design_docs/0055. Never null; the
     * lower-arity constructors default to a private, unpersisted InMemoryEventStore so every
     * pre-existing caller/test is unaffected. */
    private final EventStore eventStore;
    private final TrackerReachability trackerReachability = new TrackerReachability();
    /** How many inbound peer connections (TCP or µTP) have got as far as naming a torrent since
     * this engine started - whether or not we had that torrent. Any at all is proof the listen
     * port is reachable from outside, which is what the health report uses it for
     * (design_docs/0086). */
    private final AtomicLong incomingConnectionsSeen = new AtomicLong();
    /** How many of those a torrent then kept - the rest named a torrent we don't have, one that
     * is stopped or at its connection limit, a blocked address, or closed straight after the
     * handshake. Seen-but-not-accepted growing fast is the signal worth a look. */
    private final AtomicLong incomingConnectionsAccepted = new AtomicLong();
    /** Deploy-time config, same category as baseDownloadDirectory - see design_docs/0056.
     * Never read (and never even created on disk) unless Settings.watchFolderEnabled is true,
     * checked fresh on every scanWatchFolder() tick. */
    private final Path watchDirectory;
    /** Tracks each watch-folder candidate file's (size, lastModifiedTime) from the previous
     * tick, so scanWatchFolder() only processes a file once it's stopped changing for a full
     * poll interval - see that method's own Javadoc. Rebuilt fresh every tick (not mutated) so
     * a file that was processed or that vanished is simply absent from the next tick's map,
     * with no separate cleanup needed. Never accessed concurrently - only ever read/written
     * from maintenanceScheduler's single thread (or a test calling scanWatchFolder() directly,
     * never concurrently with the real scheduler in the same test). See design_docs/0056. */
    private volatile Map<String, WatchCandidateSnapshot> watchFolderSnapshot = Map.of();
    /** Ticks every SEEDING_LIMIT_CHECK_INTERVAL_SECONDS/watchFolderScanIntervalSeconds/
     * dhtRefreshIntervalSeconds, running checkSeedingLimits(), scanWatchFolder(), and (when DHT
     * is enabled) refreshDhtRoutingTable() - independent periodic engine-maintenance concerns
     * sharing one thread rather than each getting its own (originally named
     * seedingLimitScheduler, generalized when the watch folder needed a second periodic task -
     * design_docs/0056; refreshDhtRoutingTable() added as a third in design_docs/0028's own
     * 2026-08-30 addendum - that one spawns its own virtual thread rather than running inline,
     * since unlike the other two it can take several real seconds, and mustn't delay them on
     * this shared thread). checkSeedingLimits() checks every currently-SEEDING session against
     * its effective seeding limit and reuses pauseTorrent() outright for the actual stop - see
     * that method's own Javadoc for why reusing that method, not calling TorrentSession.stop()
     * directly, was the deliberate choice there. Daemon threads, unlike TorrentSession's own
     * per-session scheduler - this one runs unconditionally for every engine instance (no
     * opt-in flag, matching RateLimiters), including the many short-lived test-constructed
     * engines that never call shutdown(); a non-daemon thread here would leak a live,
     * never-reclaimed thread per such instance. See design_docs/0054. */
    private final ScheduledExecutorService maintenanceScheduler =
            Executors.newSingleThreadScheduledExecutor(TorrentEngine::newDaemonThread);

    private static Thread newDaemonThread(Runnable task) {
        Thread thread = new Thread(task, "engine-maintenance");
        thread.setDaemon(true);
        return thread;
    }

    /** See watchFolderSnapshot's own Javadoc. */
    private record WatchCandidateSnapshot(long size, FileTime lastModifiedTime) {
    }

    /** DHT and inbound peer connections both disabled - equivalent to enableDht=false,
     * acceptIncomingConnections=false below. Kept as the original constructor signature so
     * every existing caller (production and test) is unaffected by either addition;
     * opting in to each is a deliberate, separate choice. */
    public TorrentEngine(Path baseDownloadDirectory, int ourListenPort, TorrentSessionListener listener) {
        this(baseDownloadDirectory, ourListenPort, listener, false);
    }

    /** Inbound peer connections disabled - equivalent to acceptIncomingConnections=false
     * below. Kept so existing enableDht-only callers (production and test) are unaffected
     * by this addition. */
    public TorrentEngine(
            Path baseDownloadDirectory, int ourListenPort, TorrentSessionListener listener, boolean enableDht) {
        this(baseDownloadDirectory, ourListenPort, listener, enableDht, false);
    }

    /**
     * enableDht binds a UDP socket on ourListenPort and starts a background bootstrap
     * against the real internet (BEP 5's well-known bootstrap nodes); acceptIncomingConnections
     * binds a TCP ServerSocket on the same port and starts accepting connections in the
     * background (see PeerServer/design_docs/0038). Both are real, non-hermetic socket
     * activity a test suite generally shouldn't trigger just by constructing an engine,
     * which is why both are opt-in rather than baked into the constructors above.
     * Production wiring (grimtorrenter-app's TorrentEngineProducer) passes true for both.
     * See design_docs/0028.
     *
     * <p>No live SettingsStore - equivalent to passing an unlimited, engine-private one
     * below. Kept so existing callers (production and test) are unaffected by rate
     * limiting's addition; production wiring uses the seven-arg overload instead. See
     * design_docs/0042.
     */
    public TorrentEngine(Path baseDownloadDirectory, int ourListenPort, TorrentSessionListener listener,
                          boolean enableDht, boolean acceptIncomingConnections) {
        this(baseDownloadDirectory, ourListenPort, listener, enableDht, acceptIncomingConnections,
                new InMemorySettingsStore());
    }

    /**
     * settingsStore drives this engine's shared upload/download RateLimiters (see
     * design_docs/0042) - genuinely live, unlike enableDht/acceptIncomingConnections above:
     * a rate limit change takes effect on the very next block sent/received, no restart
     * needed, since RateLimiter re-reads settingsStore.current() on every call rather than
     * snapshotting it here.
     */
    public TorrentEngine(Path baseDownloadDirectory, int ourListenPort, TorrentSessionListener listener,
                          boolean enableDht, boolean acceptIncomingConnections, SettingsStore settingsStore) {
        this(baseDownloadDirectory, ourListenPort, listener, enableDht, acceptIncomingConnections, settingsStore,
                FileHandlePool.unbounded());
    }

    /** Same as the eight-arg overload below but with an effectively-unbounded piece-
     * verification limiter - see design_docs/0048. Kept so existing fileHandlePool-only
     * callers (production and test) are unaffected by this addition. */
    public TorrentEngine(Path baseDownloadDirectory, int ourListenPort, TorrentSessionListener listener,
                          boolean enableDht, boolean acceptIncomingConnections, SettingsStore settingsStore,
                          FileHandlePool fileHandlePool) {
        this(baseDownloadDirectory, ourListenPort, listener, enableDht, acceptIncomingConnections, settingsStore,
                fileHandlePool, Integer.MAX_VALUE);
    }

    /** Same as the nine-arg overload below but with a private, unpersisted InMemoryEventStore -
     * see design_docs/0055. Kept so every pre-existing caller/test is unaffected by this
     * addition; production wiring (grimtorrenter-app's TorrentEngineProducer) passes a real
     * persisted store instead. */
    public TorrentEngine(Path baseDownloadDirectory, int ourListenPort, TorrentSessionListener listener,
                          boolean enableDht, boolean acceptIncomingConnections, SettingsStore settingsStore,
                          FileHandlePool fileHandlePool, int maxConcurrentPieceVerifications) {
        this(baseDownloadDirectory, ourListenPort, listener, enableDht, acceptIncomingConnections, settingsStore,
                fileHandlePool, maxConcurrentPieceVerifications, new InMemoryEventStore());
    }

    /** Same as the ten-arg overload below but with a placeholder "watch" watch directory -
     * harmless since scanWatchFolder() never touches the filesystem at all unless
     * Settings.watchFolderEnabled is true, and every pre-existing caller/test leaves that
     * false by default. Kept so every pre-existing caller/test is unaffected by this addition;
     * production wiring (grimtorrenter-app's TorrentEngineProducer) passes a real configured
     * directory instead. See design_docs/0056. */
    public TorrentEngine(Path baseDownloadDirectory, int ourListenPort, TorrentSessionListener listener,
                          boolean enableDht, boolean acceptIncomingConnections, SettingsStore settingsStore,
                          FileHandlePool fileHandlePool, int maxConcurrentPieceVerifications, EventStore eventStore) {
        this(baseDownloadDirectory, ourListenPort, listener, enableDht, acceptIncomingConnections, settingsStore,
                fileHandlePool, maxConcurrentPieceVerifications, eventStore, Path.of("watch"));
    }

    /** Same as the eleven-arg overload below but with configDirectory defaulted to
     * baseDownloadDirectory - preserves every pre-existing caller/test's exact current
     * behavior (the two DHT marker files stay wherever they already were) unless a caller
     * explicitly opts into a separate directory. Kept so every pre-existing caller/test is
     * unaffected by this addition; production wiring (grimtorrenter-app's
     * TorrentEngineProducer) passes grimtorrenter.config-directory instead. See
     * design_docs/0028's own 2026-08-30 addendum. */
    public TorrentEngine(Path baseDownloadDirectory, int ourListenPort, TorrentSessionListener listener,
                          boolean enableDht, boolean acceptIncomingConnections, SettingsStore settingsStore,
                          FileHandlePool fileHandlePool, int maxConcurrentPieceVerifications, EventStore eventStore,
                          Path watchDirectory) {
        this(baseDownloadDirectory, ourListenPort, listener, enableDht, acceptIncomingConnections, settingsStore,
                fileHandlePool, maxConcurrentPieceVerifications, eventStore, watchDirectory, baseDownloadDirectory);
    }

    /**
     * fileHandlePool bounds this engine's total open torrent-data file handles across every
     * TorrentSession it creates or restores - see design_docs/0047. maxConcurrentPieceVerifications
     * bounds how many pieces can be mid-verification across the engine at once - see
     * design_docs/0048. Both unbounded by default (the seven-arg overload above) so every
     * pre-existing caller/test is unaffected; production wiring (grimtorrenter-app's
     * TorrentEngineProducer) passes real bounded values, both sized from configurable
     * properties. eventStore records library-management events (design_docs/0055) - see this
     * class's own eventStore field Javadoc. watchDirectory is the auto-add watch folder
     * (design_docs/0056) - deploy-time config like baseDownloadDirectory, gated live by
     * Settings.watchFolderEnabled rather than by construction. configDirectory is where
     * engine-wide bookkeeping (currently just the two DHT marker files) lives - see this
     * class's own configDirectory field Javadoc.
     */
    public TorrentEngine(Path baseDownloadDirectory, int ourListenPort, TorrentSessionListener listener,
                          boolean enableDht, boolean acceptIncomingConnections, SettingsStore settingsStore,
                          FileHandlePool fileHandlePool, int maxConcurrentPieceVerifications, EventStore eventStore,
                          Path watchDirectory, Path configDirectory) {
        this(baseDownloadDirectory, ourListenPort, listener, enableDht, acceptIncomingConnections, settingsStore,
                fileHandlePool, maxConcurrentPieceVerifications, eventStore, watchDirectory, configDirectory, false);
    }

    /**
     * Same as the twelve-arg overload above, plus enableLsd (BEP 14 Local Service Discovery -
     * design_docs/0062). Deliberately defaults to false in every overload above rather than
     * threaded through like enableDht/acceptIncomingConnections were from the start: those are
     * original constructor params every one of this class's 50+ existing test call sites
     * already passes explicitly, but enableLsd is new - defaulting it to true in the
     * backward-compat overloads would silently start binding a real UDP multicast socket in
     * every one of those pre-existing tests. Production wiring (grimtorrenter-app's
     * TorrentEngineProducer) passes settings.lsdEnabled() through this overload explicitly. Same
     * "real, non-hermetic socket activity, opt-in only" reasoning enableDht/
     * acceptIncomingConnections's own Javadoc above already gives.
     */
    public TorrentEngine(Path baseDownloadDirectory, int ourListenPort, TorrentSessionListener listener,
                          boolean enableDht, boolean acceptIncomingConnections, SettingsStore settingsStore,
                          FileHandlePool fileHandlePool, int maxConcurrentPieceVerifications, EventStore eventStore,
                          Path watchDirectory, Path configDirectory, boolean enableLsd) {
        this(baseDownloadDirectory, ourListenPort, listener, enableDht, acceptIncomingConnections, settingsStore,
                fileHandlePool, maxConcurrentPieceVerifications, eventStore, watchDirectory, configDirectory,
                enableLsd, null);
    }

    /** Package-private, test-only: lets a test pin LSD's multicast interface selection (e.g. to
     * loopback) instead of auto-detecting the host's real network interfaces. A real physical
     * interface is exactly the kind of thing a local firewall or switch can silently swallow
     * multicast traffic on, even between two sockets on the same host - unlike loopback, which
     * the kernel always delivers on locally regardless of anything in between. Found the hard
     * way: TorrentEngineTest's first LSD-activation test used the auto-detecting path on both
     * ends and failed in a real build environment for exactly this reason. null means
     * "auto-detect," matching the public constructor above - production code never passes
     * non-null. See design_docs/0062's own addendum. */
    TorrentEngine(Path baseDownloadDirectory, int ourListenPort, TorrentSessionListener listener,
                  boolean enableDht, boolean acceptIncomingConnections, SettingsStore settingsStore,
                  FileHandlePool fileHandlePool, int maxConcurrentPieceVerifications, EventStore eventStore,
                  Path watchDirectory, Path configDirectory, boolean enableLsd,
                  List<NetworkInterface> lsdInterfacesForTesting) {
        this.baseDownloadDirectory = baseDownloadDirectory;
        this.configDirectory = configDirectory;
        this.labelRegistry = new LabelRegistry(configDirectory);
        // design_docs/0079: with a proxy active and "block anything that can't use it" on (the
        // default once a proxy is enabled), the things a SOCKS5 proxy can't carry are never
        // started at all - DHT, µTP, LSD (all UDP) and the inbound peer server. Decided once here,
        // like their own enable flags: restart-required.
        this.proxyConfig = new ProxyConfig(configDirectory, settingsStore);
        this.proxyBlocksUnsupportedAtStart = ProxyConfig.blocksUnsupported(settingsStore.current());
        boolean dhtRequested = enableDht && !proxyBlocksUnsupportedAtStart;
        boolean incomingRequested = acceptIncomingConnections && !proxyBlocksUnsupportedAtStart;
        boolean lsdRequested = enableLsd && !proxyBlocksUnsupportedAtStart;
        this.ourListenPort = ourListenPort;
        // Wrapped so a session's first activation can trigger an immediate LSD announce - see
        // announceOnLsdActivation()'s own Javadoc for why this needs to be the listener every
        // TorrentSession is actually constructed with, not a separate observer bolted on
        // afterward. Safe to build here even though lsdService/lsdBindFailed aren't assigned
        // yet below - the returned listener only ever reads them later, when a real
        // TorrentSession (which can't exist until this constructor has already returned)
        // invokes onStateChanged().
        this.listener = announceOnLsdActivation(listener);
        this.ourPeerId = PeerId.generate();
        // design_docs/0074's slice 3: real inbound µTP connections only ever get routed to a
        // session when both accepting incoming connections at all, and Settings.utpEnabled,
        // are on - otherwise every inbound ST_SYN completes its own uTP-level handshake then is
        // immediately closed (DhtNode's own default), the same observable "no inbound µTP" outcome
        // as before this slice existed. Read once here, restart-required like acceptIncomingConnections
        // itself.
        Consumer<UtpSocket> onUtpConnectionAccepted =
                (incomingRequested && settingsStore.current().utpEnabled())
                        ? new UtpPeerAcceptor(this::findIncomingUtpConnectionHandler)::accept
                        : UtpSocket::close;
        this.dhtNode = dhtRequested
                ? createDhtNode(configDirectory, ourListenPort, eventStore, onUtpConnectionAccepted)
                : null;
        this.dhtBindFailed = dhtRequested && this.dhtNode == null;
        this.encryptionMode = () -> settingsStore.current().encryptionMode();
        this.dhtReannounceIntervalSeconds =
                () -> (long) settingsStore.current().dhtReannounceIntervalSeconds();
        this.utpConnectTimeoutSeconds = () -> settingsStore.current().utpConnectTimeoutSeconds();
        this.dhtRefreshIntervalSeconds = settingsStore.current().dhtRefreshIntervalSeconds();
        this.watchFolderScanIntervalSeconds = settingsStore.current().watchFolderPollIntervalSeconds();
        this.peerServer = incomingRequested ? createPeerServer(ourListenPort, eventStore) : null;
        this.peerServerBindFailed = incomingRequested && this.peerServer == null;
        this.lsdAnnounceIntervalSeconds = settingsStore.current().lsdAnnounceIntervalSeconds();
        this.lsdService = lsdRequested ? createLsdService(ourListenPort, eventStore, lsdInterfacesForTesting) : null;
        this.lsdBindFailed = lsdRequested && this.lsdService == null;
        this.rateLimiters = RateLimiters.from(settingsStore);
        this.fileHandlePool = fileHandlePool;
        this.pieceVerificationLimiter = new Semaphore(maxConcurrentPieceVerifications);
        this.metadataFetchLimiter = new LiveResizableSemaphore(settingsStore.current().magnetFetchConcurrencyLimit());
        this.settingsStore = settingsStore;
        this.eventStore = eventStore;
        this.watchDirectory = watchDirectory;
        // The engine-wide IP blocklist (design_docs/0078): constructed here, after settingsStore/
        // eventStore are assigned, and handed to PeerServer now and to every TorrentSession as it
        // is created/restored. refreshIfNeeded() is cheap when nothing changed - it only ever
        // starts a reload on its own virtual thread - so a one-minute tick also picks up a
        // settings change (a new source, enabling it) without any change-notification plumbing.
        this.blocklist = new Blocklist(configDirectory, settingsStore, eventStore, this::applyBlocklistChange,
                this.proxyConfig);
        if (this.peerServer != null) {
            this.peerServer.setIpFilter(this.blocklist);
        }
        this.maintenanceScheduler.scheduleWithFixedDelay(GuardedTask.of("blocklist refresh", this.blocklist::refreshIfNeeded),
                1, BLOCKLIST_CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS);
        this.maintenanceScheduler.scheduleWithFixedDelay(GuardedTask.of("seeding-limit check", this::checkSeedingLimits),
                SEEDING_LIMIT_CHECK_INTERVAL_SECONDS, SEEDING_LIMIT_CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS);
        this.maintenanceScheduler.scheduleWithFixedDelay(GuardedTask.of("lifetime-stats flush", this::flushLifetimeStats),
                LIFETIME_STATS_FLUSH_INTERVAL_SECONDS, LIFETIME_STATS_FLUSH_INTERVAL_SECONDS, TimeUnit.SECONDS);
        this.globalHours.restore(readTransferHistory(configDirectory));
        this.maintenanceScheduler.scheduleWithFixedDelay(GuardedTask.of("transfer sampling", this::sampleTransfer),
                TRANSFER_SAMPLE_INTERVAL_MILLIS, TRANSFER_SAMPLE_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
        this.maintenanceScheduler.scheduleWithFixedDelay(GuardedTask.of("watch-folder scan", this::scanWatchFolder),
                this.watchFolderScanIntervalSeconds, this.watchFolderScanIntervalSeconds, TimeUnit.SECONDS);
        if (this.dhtNode != null) {
            this.maintenanceScheduler.scheduleWithFixedDelay(GuardedTask.of("DHT refresh", this::refreshDhtRoutingTable),
                    this.dhtRefreshIntervalSeconds, this.dhtRefreshIntervalSeconds, TimeUnit.SECONDS);
        }
        if (this.lsdService != null) {
            this.maintenanceScheduler.scheduleWithFixedDelay(GuardedTask.of("LSD announce", this::announceViaLsd),
                    this.lsdAnnounceIntervalSeconds, this.lsdAnnounceIntervalSeconds, TimeUnit.SECONDS);
        }
        // Exactly one TorrentEngine per running process in production (TorrentEngineProducer's
        // @ApplicationScoped bean, constructed once), so recording this here is equivalent to
        // "the app started" - lets a timeline of events be correlated against process restarts
        // (e.g. an auto-updater like Watchtower recreating the container). Engine-wide, not
        // torrent-scoped - infoHash/torrentName are both null. See design_docs/0055.
        eventStore.record(new LibraryEvent(Instant.now(), EventType.SERVER_STARTED, null, null, null));
    }

    /** Reuses pauseTorrent() outright for the actual stop, rather than calling
     * TorrentSession.stop() directly - an earlier draft did the latter and would have left
     * STATE_MARKER_FILENAME stale ("RUNNING") on disk, since only pauseTorrent() writes that
     * marker today; reusing it gets correct persistence for free instead of duplicating it.
     * O(active session count) per tick - bounded and cheap, no new unbounded growth. See
     * design_docs/0054 and design_docs/0051.
     *
     * <p>Package-private, not private, purely so TorrentEngineTest can call this directly
     * rather than waiting on the real SEEDING_LIMIT_CHECK_INTERVAL_SECONDS-second scheduler
     * tick - same spirit as FileHandlePool.openCount() being made public for test diagnostics
     * (design_docs/0049), just narrower visibility since only a same-package test needs it. */
    void checkSeedingLimits() {
        Settings settings = settingsStore.current();
        long now = System.currentTimeMillis();
        for (TorrentSession session : sessions.values()) {
            if (session.state() != TorrentState.SEEDING) {
                continue;
            }
            reachedSeedingLimitReason(session, settings, now).ifPresent(reason -> {
                InfoHash infoHash = session.metadata().infoHash();
                eventStore.record(new LibraryEvent(Instant.now(), EventType.SEEDING_LIMIT_REACHED,
                        infoHash.hex(), session.metadata().name(), reason));
                pauseTorrent(infoHash);
            });
        }
    }

    /** Every live session's current lifetime uploaded/active-time/completed-on, flushed to its
     * own marker - O(session count) per tick, same cost class as checkSeedingLimits() above.
     * Package-private for the same test-visibility reason as checkSeedingLimits(). Also called
     * directly (not just from this scheduled tick) from pauseTorrent(), removeTorrent()'s
     * keep-files path, and shutdown() - a deliberate stop/exit shouldn't have to wait on the
     * next tick to capture its final totals. See design_docs/0064. */
    void flushLifetimeStats() {
        for (TorrentSession session : sessions.values()) {
            flushLifetimeStats(session);
        }
        writeTransferHistory();
    }

    /** One transfer sampling tick: every session meters its connections, and the engine-wide
     * meters and history take the sum. Measures the real time since the previous tick, so a late
     * tick reads as the right average rather than a spike. A session removed between two ticks
     * takes its last second with it - not worth tracking removals for. Package-private so tests
     * can drive it. See design_docs/0102. */
    void sampleTransfer() {
        long nowNanos = System.nanoTime();
        long elapsedNanos = lastTransferSampleNanos == 0
                ? TimeUnit.MILLISECONDS.toNanos(TRANSFER_SAMPLE_INTERVAL_MILLIS)
                : nowNanos - lastTransferSampleNanos;
        lastTransferSampleNanos = nowNanos;
        long nowMillis = System.currentTimeMillis();
        long downloaded = 0;
        long uploaded = 0;
        for (TorrentSession session : sessions.values()) {
            TransferDelta delta = session.sampleTransfer(elapsedNanos, nowMillis);
            downloaded += delta.downloadBytes();
            uploaded += delta.uploadBytes();
        }
        globalDownload.record(downloaded, elapsedNanos, nowMillis);
        globalUpload.record(uploaded, elapsedNanos, nowMillis);
        long coveredMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
        globalMinutes.add(downloaded, uploaded, coveredMillis, nowMillis);
        globalHours.add(downloaded, uploaded, coveredMillis, nowMillis);
    }

    /** The engine-wide transfer picture: rates, the global limits and whether they are holding
     * traffic back. See design_docs/0102. */
    public record GlobalTransfer(TransferRate download, TransferRate upload,
                                 AppliedLimit downloadLimit, AppliedLimit uploadLimit,
                                 boolean downloadCapped, boolean uploadCapped) {
    }

    /** Capped here means the global limiter itself made something wait. A torrent with its own
     * limit doesn't draw from it, so it can be capped while this isn't. */
    public GlobalTransfer globalTransfer() {
        long nowMillis = System.currentTimeMillis();
        return new GlobalTransfer(globalDownload.current(), globalUpload.current(),
                rateLimiters.downloadLimit(), rateLimiters.uploadLimit(),
                rateLimiters.download().waitedWithin(TorrentSession.CAPPED_WINDOW_MILLIS, nowMillis),
                rateLimiters.upload().waitedWithin(TorrentSession.CAPPED_WINDOW_MILLIS, nowMillis));
    }

    public TransferHistory transferHistory(HistoryResolution resolution) {
        long nowMillis = System.currentTimeMillis();
        return switch (resolution) {
            case SECOND -> TransferHistory.ofSeconds(globalDownload.current(), globalUpload.current(), nowMillis);
            case MINUTE -> globalMinutes.snapshot(nowMillis);
            case HOUR -> globalHours.snapshot(nowMillis);
        };
    }

    /** Best-effort, like the lifetime-stats markers: a failed write loses one flush. */
    private void writeTransferHistory() {
        try {
            AtomicFiles.write(configDirectory.resolve(TRANSFER_HISTORY_FILENAME), formatTransferHistory(globalHours.intervals()));
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not persist transfer history to " + configDirectory, e);
        }
    }

    /** Empty when absent or unreadable - losing it costs only a graph. */
    private static List<HistoryRing.Interval> readTransferHistory(Path configDirectory) {
        return readMarker(configDirectory, TRANSFER_HISTORY_FILENAME, "transfer history", List.of(),
                marker -> parseTransferHistory(KeyValueFile.read(marker)));
    }

    static String formatTransferHistory(List<HistoryRing.Interval> intervals) {
        List<KeyValueFile.Entry> entries = new ArrayList<>();
        for (HistoryRing.Interval interval : intervals) {
            entries.add(KeyValueFile.entry("hour", interval.index() + "," + interval.downloadBytes() + ","
                    + interval.uploadBytes() + "," + interval.coveredMillis()));
        }
        return KeyValueFile.format(entries);
    }

    /** A malformed line throws, and readMarker() then falls back to empty for the whole file. */
    static List<HistoryRing.Interval> parseTransferHistory(List<KeyValueFile.Entry> entries) {
        List<HistoryRing.Interval> intervals = new ArrayList<>();
        for (KeyValueFile.Entry entry : entries) {
            if (entry.key().equals("hour")) {
                String[] fields = entry.value().split(",");
                if (fields.length != 4) {
                    throw new IllegalArgumentException("Malformed transfer history line: " + entry.value());
                }
                intervals.add(new HistoryRing.Interval(Long.parseLong(fields[0]), Long.parseLong(fields[1]),
                        Long.parseLong(fields[2]), Long.parseLong(fields[3])));
            }
        }
        return intervals;
    }

    private void flushLifetimeStats(TorrentSession session) {
        Path configTorrentDirectory = configTorrentDirectory(session.metadata().infoHash());
        var stats = new TorrentSession.PersistedLifetimeStats(session.lifetimeUploadedBytes(),
                session.timeActiveMillis(), session.completedAtEpochMillis(), session.wastedBytes());
        writeLifetimeStatsMarker(configTorrentDirectory, stats);
    }

    /** Empty when neither limit is reached. Ratio is checked before time, so a torrent that
     * happens to cross both in the same tick is reported for whichever is checked first - the
     * event only needs to explain one true reason, not enumerate every one that applied. */
    private static Optional<String> reachedSeedingLimitReason(TorrentSession session, Settings settings, long now) {
        SeedingLimitOverride override = session.seedingLimitOverride();

        OptionalDouble ratioLimit = SeedingLimits.effectiveRatioLimit(settings, override);
        if (ratioLimit.isPresent()) {
            long downloaded = session.bytesDownloaded();
            if (downloaded > 0 && (double) session.bytesUploaded() / downloaded >= ratioLimit.getAsDouble()) {
                return Optional.of("Reached seed ratio limit of " + ratioLimit.getAsDouble());
            }
        }

        OptionalLong timeLimit = SeedingLimits.effectiveTimeLimitMinutes(settings, override);
        if (timeLimit.isPresent() && session.completedAtEpochMillis() > 0) {
            long minutesSeeding = (now - session.completedAtEpochMillis()) / 60_000;
            if (minutesSeeding >= timeLimit.getAsLong()) {
                return Optional.of("Reached seed time limit of " + timeLimit.getAsLong() + " minute(s)");
            }
        }

        return Optional.empty();
    }

    /** Spawns a virtual thread to run one DhtNode.refreshRoutingTable() tick, then
     * saveDhtRoutingTable() - never runs either inline on maintenanceScheduler's own thread,
     * since a NodeLookup can take several seconds (bounded by its own MAX_ROUNDS/per-query
     * timeout) and this scheduler is shared with checkSeedingLimits()/scanWatchFolder() above;
     * blocking it would delay both of those on their own schedule. The save piggybacks on this
     * same tick/cadence rather than getting a separate timer - both are routing-table
     * maintenance, and saveDhtRoutingTable() itself is cheap regardless of whether this
     * particular tick's refresh found anything new. Only ever scheduled when dhtNode != null
     * (see the constructor). See design_docs/0028's own 2026-08-30 addendum.
     *
     * <p>Package-private, not private, purely so a test can call this directly rather than
     * waiting on the real dhtRefreshIntervalSeconds-second scheduler tick - same spirit as
     * checkSeedingLimits()/scanWatchFolder() above. */
    void refreshDhtRoutingTable() {
        Thread.ofVirtual().start(() -> {
            dhtNode.refreshRoutingTable();
            saveDhtRoutingTable();
        });
    }

    /**
     * Auto-adds any stable .torrent file dropped into watchDirectory, moving it to added/ on
     * success or failed/ on failure - see design_docs/0056 for the full design. A no-op (does
     * not even touch the filesystem) unless Settings.watchFolderEnabled is true, checked fresh
     * on every tick, same live-toggle spirit as the rate limits.
     *
     * <p>Package-private, not private, purely so a test can call this directly rather than
     * waiting on the real watchFolderScanIntervalSeconds-second scheduler tick - same
     * spirit as checkSeedingLimits() above.
     */
    void scanWatchFolder() {
        if (!settingsStore.current().watchFolderEnabled()) {
            return;
        }
        try {
            Files.createDirectories(watchDirectory);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not create watch directory " + watchDirectory, e);
            return;
        }

        Path addedDir = watchDirectory.resolve(WATCH_ADDED_SUBDIRECTORY);
        Path failedDir = watchDirectory.resolve(WATCH_FAILED_SUBDIRECTORY);
        int retentionDays = settingsStore.current().watchFolderRetentionDays();
        pruneWatchFolderOutcomeDirectory(addedDir, retentionDays);
        pruneWatchFolderOutcomeDirectory(failedDir, retentionDays);

        List<Path> candidates;
        try (var entries = Files.list(watchDirectory)) {
            candidates = entries
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String lowerName = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return lowerName.endsWith(TORRENT_FILE_EXTENSION) || lowerName.endsWith(MAGNET_FILE_EXTENSION);
                    })
                    .toList();
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not scan watch folder " + watchDirectory, e);
            return;
        }

        Map<String, WatchCandidateSnapshot> previousSnapshot = watchFolderSnapshot;
        Map<String, WatchCandidateSnapshot> nextSnapshot = new HashMap<>();
        for (Path candidate : candidates) {
            String filename = candidate.getFileName().toString();
            WatchCandidateSnapshot snapshot;
            try {
                snapshot = new WatchCandidateSnapshot(Files.size(candidate), Files.getLastModifiedTime(candidate));
            } catch (IOException e) {
                // Vanished or became unreadable between listing and stating - try again next
                // tick rather than failing the whole scan over one file.
                continue;
            }
            if (!snapshot.equals(previousSnapshot.get(filename))) {
                // New, or still changing (e.g. a slow copy still in progress) - wait for it to
                // stabilize across a full poll interval before ever reading its contents.
                nextSnapshot.put(filename, snapshot);
                continue;
            }
            processWatchedFile(candidate, addedDir, failedDir);
            // Not added to nextSnapshot - processed (moved out) or its move failed and it's
            // still sitting here, in which case it's treated as newly-seen again next tick,
            // requiring one more stable interval before being retried. See design_docs/0056's
            // Stability section for why that's an acceptable, narrow edge case rather than
            // something worth more machinery to fully close off.
        }
        watchFolderSnapshot = nextSnapshot;
    }

    /** Reads and adds the file, then moves it to addedDir or failedDir depending on the
     * outcome - the move is attempted regardless of which, but a move failure is only ever
     * logged (not re-recorded as a second event): the outcome of the *add* is the thing worth
     * an event, the outcome of the subsequent housekeeping move is not. Dispatches on extension
     * - a .torrent file is added synchronously (addTorrent() itself has already fully resolved
     * success/failure by the time it returns/throws), a .magnet file only ever accepted or
     * rejected synchronously (see addWatchedMagnetFile()'s own Javadoc for why "added" here
     * doesn't mean "resolved"). See design_docs/0056. */
    private void processWatchedFile(Path file, Path addedDir, Path failedDir) {
        String filename = file.getFileName().toString();
        boolean added;
        try {
            if (filename.toLowerCase(Locale.ROOT).endsWith(MAGNET_FILE_EXTENSION)) {
                addWatchedMagnetFile(file);
            } else {
                addTorrent(Files.readAllBytes(file), WATCH_FOLDER_SOURCE);
            }
            added = true;
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Watch folder could not add " + filename, e);
            eventStore.record(new LibraryEvent(Instant.now(), EventType.ERROR, null, null,
                    "Watch folder: could not add " + filename + " (" + e.getMessage() + ")"));
            added = false;
        }
        try {
            moveWithCollisionSuffix(file, added ? addedDir : failedDir);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING,
                    "Could not move watch-folder file " + filename + " to " + (added ? addedDir : failedDir), e);
        }
    }

    /** "Added" here means only that a metadata-fetch attempt was successfully kicked off
     * (addMagnet() returned without throwing) - not that the magnet has actually resolved into
     * a real torrent yet, since that happens on a background virtual thread with its own
     * bounded retry budget (see addMagnet()'s own Javadoc). Waiting here for the real outcome
     * would hold up this whole scan tick for up to Settings.magnetFetchTimeBudgetSeconds per
     * file, serializing every other watch-folder candidate behind it. A background failure
     * still surfaces - as a MAGNET_ADD_FAILED library event (message prefixed "Watch folder: ",
     * see recordMagnetAddFailed()) rather than by this file's on-disk location. See
     * design_docs/0060. */
    private void addWatchedMagnetFile(Path file) throws IOException {
        addMagnet(MagnetLink.parse(Files.readString(file).trim()), WATCH_FOLDER_SOURCE);
    }

    /** Guards against destinationDir having been deleted (by the user, or anything else)
     * since the last tick - recreated here, immediately before the one operation that
     * actually needs it to exist, rather than relying solely on an earlier check in the same
     * tick. Renames with a "-2", "-3", ... suffix on a name collision (the same filename
     * already moved here once before), reusing resolveDownloadDirectory()'s own collision
     * convention rather than inventing a second one. Explicitly stamps the moved file's
     * last-modified time to now - Files.move preserves the *original* file's timestamp by
     * default, which would otherwise make pruneWatchFolderOutcomeDirectory() judge a
     * long-since-downloaded .torrent file as immediately due for deletion the moment it's
     * dropped in, rather than counting from when it was actually resolved. See
     * design_docs/0056. */
    private static void moveWithCollisionSuffix(Path source, Path destinationDir) throws IOException {
        Files.createDirectories(destinationDir);
        String filename = source.getFileName().toString();
        Path destination = destinationDir.resolve(filename);
        int suffix = 2;
        while (Files.exists(destination)) {
            destination = destinationDir.resolve(insertFilenameSuffix(filename, suffix));
            suffix++;
        }
        Files.move(source, destination);
        Files.setLastModifiedTime(destination, FileTime.from(Instant.now()));
    }

    /** "foo.torrent" + 2 -> "foo-2.torrent" - inserted before the extension rather than
     * appended after it, so the result still opens/sorts as a recognizable .torrent file. */
    private static String insertFilenameSuffix(String filename, int suffix) {
        int dot = filename.lastIndexOf('.');
        return dot <= 0
                ? filename + "-" + suffix
                : filename.substring(0, dot) + "-" + suffix + filename.substring(dot);
    }

    /** A missing directory (nothing has ever succeeded/failed yet) is a normal, silent no-op -
     * not a warning-worthy problem, unlike an actual I/O failure while a directory does exist.
     * Uses each file's own last-modified time (see moveWithCollisionSuffix's own Javadoc for
     * why that's stamped to the move time, not left at the original file's time) rather than a
     * filename-embedded date - unlike JsonLinesEventStore's day-files, these are ordinary files
     * whose real mtime is already meaningful once stamped correctly. See design_docs/0056 and
     * design_docs/0051. */
    private static void pruneWatchFolderOutcomeDirectory(Path directory, int retentionDays) {
        if (!Files.isDirectory(directory)) {
            return;
        }
        LocalDate cutoff = LocalDate.now(ZoneId.systemDefault()).minusDays(retentionDays);
        try (var entries = Files.list(directory)) {
            for (Path entry : entries.filter(Files::isRegularFile).toList()) {
                try {
                    LocalDate entryDate = LocalDate.ofInstant(
                            Files.getLastModifiedTime(entry).toInstant(), ZoneId.systemDefault());
                    if (entryDate.isBefore(cutoff)) {
                        Files.deleteIfExists(entry);
                    }
                } catch (IOException e) {
                    LOG.log(System.Logger.Level.WARNING, "Could not prune " + entry, e);
                }
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not list " + directory + " for pruning", e);
        }
    }

    private PeerServer createPeerServer(int port, EventStore eventStore) {
        try {
            return new PeerServer(port, this::findIncomingConnectionHandler, encryptionMode, sessions::keySet);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not start peer server - continuing without inbound connections", e);
            eventStore.record(new LibraryEvent(Instant.now(), EventType.PEER_SERVER_UNAVAILABLE, null, null, null));
            return null;
        }
    }

    public long incomingConnectionsSeen() {
        return incomingConnectionsSeen.get();
    }

    public long incomingConnectionsAccepted() {
        return incomingConnectionsAccepted.get();
    }

    /** Inbound peer connections open right now, across every torrent. */
    public int incomingConnectionsActive() {
        int count = 0;
        for (TorrentSession session : sessions.values()) {
            count += session.incomingPeerCount();
        }
        return count;
    }

    /** The one place that bridges PeerServer's generic, TorrentSession-unaware lookup
     * (see IncomingConnectionHandler's own Javadoc on why it stays that way) to this
     * engine's actual session map. */
    private Optional<IncomingConnectionHandler> findIncomingConnectionHandler(InfoHash infoHash) {
        incomingConnectionsSeen.incrementAndGet();
        return Optional.ofNullable(sessions.get(infoHash)).map(session -> (socket, in, out, handshake) -> {
            if (session.acceptIncomingConnection(socket, in, out, handshake)) {
                incomingConnectionsAccepted.incrementAndGet();
            }
        });
    }

    /** The µTP counterpart to findIncomingConnectionHandler() above (design_docs/0074's slice
     * 3) - same bridging role, for UtpPeerAcceptor instead of PeerServer. */
    private Optional<UtpIncomingConnectionHandler> findIncomingUtpConnectionHandler(InfoHash infoHash) {
        incomingConnectionsSeen.incrementAndGet();
        return Optional.ofNullable(sessions.get(infoHash)).map(session -> (utpSocket, handshake) -> {
            if (session.acceptIncomingUtpConnection(utpSocket, handshake)) {
                incomingConnectionsAccepted.incrementAndGet();
            }
        });
    }

    private LsdService createLsdService(int torrentListenPort, EventStore eventStore,
                                         List<NetworkInterface> interfacesForTesting) {
        try {
            return interfacesForTesting != null
                    ? new LsdService(torrentListenPort, this::onLsdPeerFound, interfacesForTesting)
                    : new LsdService(torrentListenPort, this::onLsdPeerFound);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not start LSD - continuing without local peer discovery", e);
            eventStore.record(new LibraryEvent(Instant.now(), EventType.LSD_UNAVAILABLE, null, null, null));
            return null;
        }
    }

    /** Non-private, currently DOWNLOADING/SEEDING torrents only - BEP 27 privacy (a private
     * torrent's info hash must never be broadcast to the LAN, same reasoning as
     * TorrentSession.dhtEligible()'s DHT/PEX gating) and no point announcing a paused/errored/
     * still-verifying torrent nobody can connect to yet. Shared by both announceViaLsd() (the
     * outbound announce list) and, indirectly, onLsdPeerFound() below (which re-checks privacy
     * per-session rather than trusting this list, since an incoming announcement for a private
     * info hash could come from a misbehaving/malicious LAN peer, not just from us). */
    private Collection<InfoHash> activeInfoHashesForLsd() {
        return sessions.values().stream()
                .filter(session -> !session.metadata().isPrivate())
                .filter(session -> session.state().isRunning())
                .map(session -> session.metadata().infoHash())
                .toList();
    }

    /** Dispatched onto its own virtual thread, same "don't block the shared maintenanceScheduler
     * thread" reasoning as refreshDhtRoutingTable() - LsdService.announce() does real (if
     * normally fast) network I/O per info hash.
     *
     * <p>Package-private, not private, purely so a test can call this directly rather than
     * waiting on the real lsdAnnounceIntervalSeconds-second scheduler tick - same spirit as
     * checkSeedingLimits()/scanWatchFolder() above. This is the periodic safety-net sweep only -
     * see announceOnLsdActivation() below for why a freshly-activated torrent doesn't have to
     * wait for it. */
    void announceViaLsd() {
        Thread.ofVirtual().start(() -> lsdService.announce(activeInfoHashesForLsd()));
    }

    /** The one place that bridges LsdService's generic, TorrentSession-unaware peer-found
     * callback to this engine's actual session map - mirrors findIncomingConnectionHandler
     * above. Re-checks isPrivate() here rather than trusting that an unrecognized/private info
     * hash was already filtered out by the sender's own activeInfoHashesForLsd() - a
     * misbehaving or malicious LAN peer could announce any info hash it likes. */
    private void onLsdPeerFound(InfoHash infoHash, PeerAddress address) {
        Optional.ofNullable(sessions.get(infoHash))
                .filter(session -> !session.metadata().isPrivate())
                .ifPresent(session -> session.addKnownPeers(List.of(address), PeerSource.LSD));
    }

    /** Wraps the caller-supplied session listener so a torrent's *first* LSD announce doesn't
     * wait for announceViaLsd()'s next periodic tick - up to lsdAnnounceIntervalSeconds
     * (default 300s) away, since that task runs on a fixed engine-wide schedule anchored to
     * TorrentEngine's own construction time, not to any individual torrent's activation. This
     * is the exact same class of gap DHT already hit and fixed
     * ([[0036-dht-backstop-for-tracker-bearing-torrents]]'s own 2026-09-06 revision -
     * discoverPeersViaDht() got a zero-initial-delay schedule specifically so "a freshly-started
     * torrent shouldn't wait a full dhtReannounceIntervalSeconds for its first DHT lookup") and
     * the tracker side avoids structurally (TorrentSession.start()'s own synchronous first
     * announce, before any periodic reannounce() is even scheduled). LSD has no per-session
     * scheduled task of its own to give a zero-initial-delay to (see the lsdService field's own
     * Javadoc), so the fix here is the closest equivalent: hook the one signal that fires from
     * every path a session can become active - TorrentSessionListener.onStateChanged() - rather
     * than TorrentEngine's own addTorrent()/resumeTorrent() call sites alone. Those two miss a
     * real, common case: TorrentSession.verifyThenSettle()'s background-thread autoStart, used
     * by both addTorrent()'s reused-directory branch and restoreOne() - i.e. every torrent
     * restored at engine startup, the ordinary case on every container restart. See
     * design_docs/0062's own addendum. */
    private TorrentSessionListener announceOnLsdActivation(TorrentSessionListener delegate) {
        return new TorrentSessionListener() {
            @Override
            public void onStateChanged(TorrentSession session, TorrentState oldState, TorrentState newState) {
                delegate.onStateChanged(session, oldState, newState);
                if (lsdService != null && !oldState.isRunning() && newState.isRunning()
                        && !session.metadata().isPrivate()) {
                    InfoHash infoHash = session.metadata().infoHash();
                    Thread.ofVirtual().start(() -> lsdService.announce(List.of(infoHash)));
                }
            }

            @Override
            public void onPieceCompleted(TorrentSession session, int pieceIndex) {
                delegate.onPieceCompleted(session, pieceIndex);
            }
        };
    }

    /** DHT reuses ourListenPort for its UDP socket, same as the TCP peer-wire listen port
     * (confirmed with the user - matches what real clients do and what the peerwire Port
     * message already implies). Bootstrapping the routing table is real network I/O
     * against an unknown number of hosts, so it runs on its own background thread rather
     * than delaying this constructor. configDirectory, not baseDownloadDirectory - see this
     * class's own configDirectory field Javadoc (design_docs/0028's own 2026-08-30
     * addendum). */
    private static DhtNode createDhtNode(Path configDirectory, int ourListenPort, EventStore eventStore,
                                          Consumer<UtpSocket> onUtpConnectionAccepted) {
        try {
            NodeId nodeId = loadOrGenerateDhtNodeId(configDirectory);
            DhtNode node = new DhtNode(nodeId, ourListenPort, onUtpConnectionAccepted);
            List<InetSocketAddress> persistedContacts = loadPersistedDhtContacts(configDirectory);
            Thread.ofVirtual().start(() -> node.bootstrap(persistedContacts));
            return node;
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not start DHT node - continuing without it", e);
            eventStore.record(new LibraryEvent(Instant.now(), EventType.DHT_UNAVAILABLE, null, null, null));
            return null;
        }
    }

    /** Falls back to a freshly generated (and re-persisted) id if the marker is missing
     * or unreadable, rather than failing DHT startup over a corrupt marker file. */
    private static NodeId loadOrGenerateDhtNodeId(Path configDirectory) {
        Path marker = configDirectory.resolve(DHT_NODE_ID_MARKER_FILENAME);
        if (Files.exists(marker)) {
            try {
                return new NodeId(Files.readString(marker).strip());
            } catch (IOException | IllegalArgumentException e) {
                LOG.log(System.Logger.Level.WARNING, "Could not read persisted DHT node id, generating a new one", e);
            }
        }
        NodeId generated = NodeId.random();
        try {
            AtomicFiles.write(marker, generated.hex());
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not persist DHT node id - it will regenerate next restart", e);
        }
        return generated;
    }

    /** Empty (not fatal) if the marker is missing, or on any read/parse failure - a warm
     * start is a head start, never something DHT startup depends on. Each contact is
     * re-verified with a real ping before being trusted (DhtNode.bootstrap(List)'s own job),
     * so a stale or malformed line here costs at worst one skipped/failed ping, never bad
     * data reaching the routing table directly. See design_docs/0028's own 2026-08-30
     * addendum. */
    private static List<InetSocketAddress> loadPersistedDhtContacts(Path configDirectory) {
        Path marker = configDirectory.resolve(DHT_KNOWN_NODES_MARKER_FILENAME);
        if (!Files.exists(marker)) {
            return List.of();
        }
        try {
            List<InetSocketAddress> contacts = new ArrayList<>();
            for (String line : Files.readAllLines(marker)) {
                parseKnownContactLine(line).ifPresent(contacts::add);
            }
            return contacts;
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not read persisted DHT contacts", e);
            return List.of();
        }
    }

    private static Optional<InetSocketAddress> parseKnownContactLine(String line) {
        int comma = line.indexOf(',');
        if (comma < 0) {
            return Optional.empty();
        }
        try {
            InetAddress address = InetAddress.getByName(line.substring(0, comma));
            int port = Integer.parseInt(line.substring(comma + 1));
            return Optional.of(new InetSocketAddress(address, port));
        } catch (UnknownHostException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** Writes dhtNode's current known contacts out, atomically (write to a temp file, then
     * Files.move(..., ATOMIC_MOVE)) so a save racing a process exit can never leave a
     * torn/partial file behind. No-ops if DHT is disabled; write failures are logged and
     * otherwise ignored - the next successful save (or, worst case, the next restart's
     * ordinary bootstrap) simply catches up, same tolerance every other marker file here
     * already has. Package-private, not private, purely so a test can call this directly
     * rather than waiting on the real dhtRefreshIntervalSeconds-second scheduler tick - same
     * spirit as checkSeedingLimits()/scanWatchFolder() above. See design_docs/0028's own
     * 2026-08-30 addendum. */
    void saveDhtRoutingTable() {
        if (dhtNode == null) {
            return;
        }
        Path marker = configDirectory.resolve(DHT_KNOWN_NODES_MARKER_FILENAME);
        StringBuilder content = new StringBuilder();
        for (InetSocketAddress contact : dhtNode.knownContacts()) {
            content.append(contact.getAddress().getHostAddress()).append(',').append(contact.getPort()).append('\n');
        }
        try {
            AtomicFiles.write(marker, content.toString());
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not persist DHT routing table", e);
        }
    }

    /** alreadyExisted lets a caller (e.g. the REST layer) tell "just added" apart from
     * "you already had this" - both return the same session, but they mean different
     * things to whoever's asking. */
    public record AddTorrentResult(TorrentSession session, boolean alreadyExisted) {
    }

    /** A magnet whose metadata is still being fetched - see design_docs/0070. Not a
     * TorrentSession (there's no TorrentMetadata yet to build one from); exposed to external
     * consumers via TorrentView.fromPendingMagnet() so it renders through the exact same DTO
     * shape a resolved torrent does, with state "FETCHING_METADATA". */
    public record PendingMagnet(InfoHash infoHash, String displayName, List<String> trackers) {
    }

    /** Exactly one of existingSession/pending is non-null - existingSession when this info hash
     * already had a real, resolved torrent (whether from a previous magnet resolution or a
     * direct .torrent add), pending when a fresh or already-in-flight fetch is what the caller
     * gets back. alreadyExisted mirrors AddTorrentResult's own field: true for an existing
     * session or an already-in-flight fetch this call didn't just start, false only for a
     * genuinely new fetch just kicked off. See design_docs/0070. */
    public record AddMagnetResult(TorrentSession existingSession, PendingMagnet pending, boolean alreadyExisted) {
    }

    /** nodeCount is 0 whenever enabled is false - not null/absent, so a caller (e.g. the
     * REST layer) can render it directly without a null check. See design_docs/0028. */
    public record DhtStatus(boolean enabled, int nodeCount) {
    }

    /** A self-contained, on-demand read - global DHT state, not tied to any one torrent,
     * so unlike TorrentView this isn't part of the always-broadcast snapshot (see
     * design_docs/0028's addendum on why detail-level data like this gets its own endpoint
     * fetched only while something's actually looking at it). */
    public DhtStatus dhtStatus() {
        return dhtNode != null ? new DhtStatus(true, dhtNode.routingTable().size()) : new DhtStatus(false, 0);
    }

    /** Engine-wide singleton subsystems only (DHT, the inbound peer server) - per-torrent
     * status stays on the torrent itself, not here. DEGRADED is DHT-only (see
     * dhtServiceState()) - the peer server has no equivalent "running but not fully healthy"
     * notion, only bound-or-not. See design_docs/0059 and its own DEGRADED-state addendum. */
    public enum ServiceState {
        RUNNING, DEGRADED, DISABLED, FAILED
    }

    /** name is a stable identifier ("dht"/"peerServer"), matched by name against a frontend
     * display map - same closed-set-mapped-by-key shape EventType's own frontend map already
     * uses. See design_docs/0059. */
    public record ServiceStatus(String name, ServiceState state, String reason) {
        /** No reason - the common case. */
        public ServiceStatus(String name, ServiceState state) {
            this(name, state, null);
        }
    }

    /** The peer server only binds once, at construction - no retry - so its FAILED/DISABLED
     * state is stable for the whole process lifetime, same as before. DHT's state is no
     * longer as stable: alongside the same bind-time FAILED/DISABLED, a running DHT node can
     * live-transition RUNNING<->DEGRADED as its routing table's known-node count crosses
     * DhtNode.isDegraded()'s threshold - a caller polling this (e.g. the Services page) should
     * expect that one transition, unlike bind status. See design_docs/0059 and its own
     * DEGRADED-state addendum. */
    public List<ServiceStatus> serviceStatuses() {
        return List.of(
                withProxyReason("dht", dhtServiceState()),
                withProxyReason("peerServer", serviceState(peerServer != null, peerServerBindFailed)),
                withProxyReason("lsd", serviceState(lsdService != null, lsdBindFailed)));
    }

    /** A DISABLED service on a run that started with the proxy's block switch on carries the
     * reason, so the Services page can say why instead of just "disabled". design_docs/0079. */
    private ServiceStatus withProxyReason(String name, ServiceState state) {
        if (state == ServiceState.DISABLED && proxyBlocksUnsupportedAtStart) {
            return new ServiceStatus(name, state,
                    "Turned off because a proxy is active - a SOCKS5 proxy can't carry it");
        }
        return new ServiceStatus(name, state);
    }

    private ServiceState dhtServiceState() {
        if (dhtNode != null) {
            return dhtNode.isDegraded() ? ServiceState.DEGRADED : ServiceState.RUNNING;
        }
        return dhtBindFailed ? ServiceState.FAILED : ServiceState.DISABLED;
    }

    private static ServiceState serviceState(boolean running, boolean failed) {
        if (running) {
            return ServiceState.RUNNING;
        }
        return failed ? ServiceState.FAILED : ServiceState.DISABLED;
    }

    /**
     * Parses and registers a torrent, starting it immediately. Adding the
     * same torrent (by info hash) twice is idempotent - returns the
     * existing session rather than creating a second one.
     */
    public AddTorrentResult addTorrent(byte[] torrentFileBytes) throws IOException {
        return addTorrent(torrentFileBytes, null);
    }

    /** source is null for a direct upload (the public overload above) or a short label like
     * "watch folder" (scanWatchFolder()) or "magnet" (addFetchedTorrent(), once a magnet's
     * metadata resolves) - folded into the resulting ADDED library event's message ("Added via
     * watch folder"/"Added via magnet") so it's distinguishable from a direct upload, without
     * adding a new parameter to every existing caller. See design_docs/0055/0056. */
    AddTorrentResult addTorrent(byte[] torrentFileBytes, String source) throws IOException {
        TorrentMetadata metadata = MetainfoParser.parse(torrentFileBytes);
        TrackerClient trackerClient = createTrackerClient(metadata);
        InfoHash infoHash = metadata.infoHash();
        DirectoryResolution resolution = resolveDownloadDirectory(metadata);
        Path torrentDirectory = resolution.directory();
        Path configTorrentDirectory = configTorrentDirectory(infoHash);

        AtomicReference<IOException> creationFailure = new AtomicReference<>();
        AtomicBoolean wasNewlyCreated = new AtomicBoolean(false);
        TorrentSession session = sessions.computeIfAbsent(infoHash, key -> {
            wasNewlyCreated.set(true);
            try {
                // A resolved torrent supersedes any pending-magnet record for the same info
                // hash - harmless no-op for a direct (non-magnet-originated) add, which never
                // had one. See design_docs/0070.
                concludePendingMagnet(infoHash);
                Files.createDirectories(configTorrentDirectory);
                Instant addedAt = Instant.now();
                // Declared - markers written - before the session is even constructed, let
                // alone started: a crash (or a start that fails outright) between here and a
                // running session must still leave this torrent recognizable on the next
                // restore(). "Added but not running" is a real state, like ERROR after a
                // tracker failure. See design_docs/0069.
                writeTorrentFileMarker(configTorrentDirectory, torrentFileBytes);
                writeStateMarker(configTorrentDirectory, STATE_RUNNING);
                writeAddedAtMarker(configTorrentDirectory, addedAt);
                // A path this info hash already resolved once (a torrent removed with "keep
                // files", now re-added) may already hold real data: it is re-verified in the
                // background rather than downloaded again from scratch. See design_docs/0037.
                TorrentSession created = buildSession(metadata, trackerClient, torrentDirectory,
                        configTorrentDirectory, addedAt,
                        resolution.preExisting() ? SessionOrigin.READDED : SessionOrigin.NEW, true);
                // Started below, once out of computeIfAbsent: the first announce can take a
                // tracker timeout, and this lambda holds the map's bin lock. See design_docs/0092.
                return created;
            } catch (IOException e) {
                creationFailure.set(e);
                return null;
            }
        });
        if (creationFailure.get() != null) {
            throw creationFailure.get();
        }
        if (wasNewlyCreated.get() && !resolution.preExisting()) {
            // In the background: the first announce can take a tracker timeout per tier, and the
            // add request (an upload, a watch-folder pick-up) shouldn't wait for it. The session
            // is STARTING when this returns. See design_docs/0098.
            session.startInBackground();
        }
        if (wasNewlyCreated.get()) {
            String message = source != null ? "Added via " + source : null;
            eventStore.record(new LibraryEvent(
                    Instant.now(), EventType.ADDED, infoHash.hex(), metadata.name(), message));
        }
        return new AddTorrentResult(session, !wasNewlyCreated.get());
    }

    /** How a session comes into existence - see buildSession(). */
    private enum SessionOrigin {
        /** A fresh download into a new directory. */
        NEW,
        /** Added again over a directory this info hash already used: verified in the background,
         * and missing files are put back. */
        READDED,
        /** Brought back after a restart: verified in the background, and missing files are
         * reported rather than recreated (design_docs/0088). */
        RESTORED
    }

    /** Builds a session from its per-torrent markers (overrides, lifetime stats, file priorities,
     * labels) and wires it to the engine-wide collaborators. Shared by addTorrent() and
     * restoreOne(); the caller registers it in sessions. A re-added torrent picks its old
     * overrides and stats back up, because a "keep files" removal leaves those markers in place
     * (design_docs/0054/0064/0072/0075). autoStart applies only to READDED/RESTORED; a NEW
     * session is started by the caller. */
    private TorrentSession buildSession(TorrentMetadata metadata, TrackerClient trackerClient, Path torrentDirectory,
                                        Path configTorrentDirectory, Instant addedAt, SessionOrigin origin,
                                        boolean autoStart) throws IOException {
        SeedingLimitOverride seedingLimitOverride = readSeedingLimitOverrideMarker(configTorrentDirectory);
        TorrentSession.PersistedLifetimeStats lifetimeStats = readLifetimeStatsMarker(configTorrentDirectory);
        TorrentLimitOverride torrentLimitOverride = readTorrentLimitOverrideMarker(configTorrentDirectory);
        int maxConnections = TorrentLimits.effectiveMaxConnections(settingsStore.current(), torrentLimitOverride);
        FilePriorities filePriorities = readFilePrioritiesMarker(configTorrentDirectory, metadata.files().size());
        // The per-torrent limiter reads the session's live limits, but the session needs the
        // limiter to be built. No peer can call acquire() before the session exists, so the
        // reference is filled in straight after. See design_docs/0072.
        AtomicReference<TorrentSession> sessionRef = new AtomicReference<>();
        RateLimiters perTorrentRateLimiters =
                RateLimiters.forTorrent(rateLimiters, settingsStore, () -> sessionRef.get().torrentLimits());
        TorrentSession session = origin == SessionOrigin.NEW
                ? TorrentSession.create(metadata, trackerClient, torrentDirectory, ourPeerId,
                        ourListenPort, listener, dhtNode, perTorrentRateLimiters, fileHandlePool,
                        pieceVerificationLimiter, encryptionMode, seedingLimitOverride, addedAt,
                        dhtReannounceIntervalSeconds, lsdService != null, lifetimeStats,
                        torrentLimitOverride, maxConnections, effectiveUtpEnabled(),
                        utpConnectTimeoutSeconds, filePriorities)
                : TorrentSession.restoreAsync(metadata, trackerClient, torrentDirectory,
                        ourPeerId, ourListenPort, listener, dhtNode, perTorrentRateLimiters, fileHandlePool,
                        pieceVerificationLimiter, encryptionMode, seedingLimitOverride, addedAt,
                        dhtReannounceIntervalSeconds, lsdService != null, autoStart, lifetimeStats,
                        torrentLimitOverride, maxConnections, effectiveUtpEnabled(),
                        utpConnectTimeoutSeconds, filePriorities, origin == SessionOrigin.RESTORED);
        sessionRef.set(session);
        session.setLabelIds(readLabelIdsMarker(configTorrentDirectory));
        session.setIpFilter(blocklist);
        session.setProxyProvider(proxyConfig);
        directories.put(metadata.infoHash(), contentPathFor(metadata, torrentDirectory));
        return session;
    }

    /**
     * Registers a magnet as pending (see design_docs/0070) and starts resolving its metadata
     * from peers (BEP 9), which - once verified - hands off into the same addTorrent pipeline
     * every other torrent uses. Peers to fetch from come from whichever of the magnet's
     * embedded trackers respond if it has any, or (since design_docs/0028's DHT slice) a DHT
     * get_peers lookup for a trackerless one - only actually trackerless AND DHT unavailable
     * fails synchronously here, since that's the one case with no possible path to peers at
     * all, and nothing is persisted for an attempt that was never actually going to run.
     *
     * <p>Idempotent: an info hash that already has a real, resolved torrent (whether from a
     * previous magnet resolution or a direct .torrent add) returns that instead of starting a
     * second attempt; one already mid-fetch returns the existing pending entry rather than
     * restarting it.
     *
     * <p>Everything past the synchronous checks above runs on a background virtual thread:
     * there's real network I/O against an unknown number of peers before this torrent is known
     * well enough to show anything for it. A total failure (no peers reachable, or none of the
     * peers tried had the metadata) records a MAGNET_ADD_FAILED library event (see
     * recordMagnetAddFailed()) alongside the log line - design_docs/0060.
     */
    public AddMagnetResult addMagnet(MagnetLink magnet) {
        return addMagnet(magnet, null);
    }

    /** source is null for the public overload above (REST/UI-triggered) or WATCH_FOLDER_SOURCE
     * (scanWatchFolder(), once design_docs/0056's own 2026-09-06 addendum added magnet-file
     * support) - threaded through to whichever fetch path is taken so a resulting ADDED event
     * reads "Added via watch folder" (addFetchedTorrent()) and a resulting MAGNET_ADD_FAILED
     * event's message is prefixed "Watch folder: " (recordMagnetAddFailed()), the same
     * distinction addTorrent()'s own source parameter already makes for .torrent files. */
    AddMagnetResult addMagnet(MagnetLink magnet, String source) {
        InfoHash infoHash = magnet.infoHash();
        TorrentSession existingSession = sessions.get(infoHash);
        if (existingSession != null) {
            return new AddMagnetResult(existingSession, null, true);
        }
        PendingMagnet alreadyPending = pendingMagnets.get(infoHash);
        if (alreadyPending != null) {
            return new AddMagnetResult(null, alreadyPending, true);
        }
        List<String> trackerUrls = magnet.trackers().stream().filter(TorrentEngine::isSupportedTrackerUrl).toList();
        if (trackerUrls.isEmpty() && dhtNode == null) {
            recordMagnetAddFailed(magnet,
                    "No usable tracker, and DHT is unavailable - trackerless magnets need DHT", source);
            throw new TorrentEngineException(
                    "Magnet link has no usable tracker, and DHT is unavailable - trackerless magnets need DHT");
        }
        // putIfAbsent is the claim: of two concurrent adds of the same magnet (the UI and the
        // watch folder, say), exactly one starts a fetch. See design_docs/0092.
        PendingMagnet pending = new PendingMagnet(infoHash, magnet.displayName(), magnet.trackers());
        PendingMagnet raced = pendingMagnets.putIfAbsent(infoHash, pending);
        if (raced != null) {
            return new AddMagnetResult(null, raced, true);
        }
        // Declared before the fetch itself starts, same "persist before acting"
        // reasoning as design_docs/0069 - see design_docs/0070.
        try {
            writeMagnetPendingMarker(configTorrentDirectory(infoHash), pending);
        } catch (RuntimeException e) {
            pendingMagnets.remove(infoHash, pending);
            throw e;
        }
        startMagnetFetch(magnet, trackerUrls, source);
        return new AddMagnetResult(null, pending, false);
    }

    /** Shared by the live-add path above and restorePendingMagnet() below - the only
     * difference between a fresh add and a restart-resumed one is where trackerUrls/source
     * came from, not what happens next. */
    private void startMagnetFetch(MagnetLink magnet, List<String> trackerUrls, String source) {
        Thread.ofVirtual().start(() -> fetchMagnetMetadataThenAdd(magnet, trackerUrls, source));
    }

    /** design_docs/0060. message is folded into the event rather than just infoHash/type
     * alone, since MAGNET_ADD_FAILED has no torrentName (see EventType's own Javadoc) to
     * carry any human-readable context otherwise. */
    private void recordMagnetAddFailed(MagnetLink magnet, String message) {
        recordMagnetAddFailed(magnet, message, null);
    }

    /** source is folded in as a "Watch folder: " prefix rather than a generic "via " + source
     * tag (unlike addTorrent()'s ADDED message) - message here is already a full failure-reason
     * sentence ("No peer had the metadata..."), and this mirrors the exact prefix
     * processWatchedFile()'s own ERROR event already uses for the identical situation on
     * the .torrent side. Assumes source, when non-null, is always WATCH_FOLDER_SOURCE - the only
     * non-null value that exists today.
     *
     * <p>Also concludePendingMagnet()'s one call site for the failure path - safe to call even
     * for the synchronous "couldn't even try" case above, which never had a pending entry to
     * begin with (both the map removal and the marker deletion are no-ops on something that was
     * never there). See design_docs/0070. */
    private void recordMagnetAddFailed(MagnetLink magnet, String message, String source) {
        String finalMessage = source != null ? "Watch folder: " + message : message;
        eventStore.record(new LibraryEvent(
                Instant.now(), EventType.MAGNET_ADD_FAILED, magnet.infoHash().hex(), null, finalMessage));
        concludePendingMagnet(magnet.infoHash());
    }

    /** The one place a pending magnet's registry entry and marker both go away together -
     * called on every conclusion path (failure via recordMagnetAddFailed(), success via
     * addTorrent()'s own generic cleanup, explicit removal via removeTorrent()). See
     * design_docs/0070. */
    private void concludePendingMagnet(InfoHash infoHash) {
        pendingMagnets.remove(infoHash);
        deleteMagnetPendingMarker(configTorrentDirectory(infoHash));
    }

    /** Where one magnet fetch gets each round's candidate peers from. */
    private interface MagnetCandidateSource {
        /** This round's candidates (possibly empty). Throws MagnetFetchAbandoned to give up at
         * once with that message. */
        List<PeerAddress> nextRound();

        /** The MAGNET_ADD_FAILED message once the time budget runs out. */
        String exhaustedMessage(int triedCount);
    }

    /** A candidate source that can't go on - the fetch fails with this message. */
    private static final class MagnetFetchAbandoned extends RuntimeException {
        MagnetFetchAbandoned(String message) {
            super(message);
        }
    }

    /** Announces to the magnet's trackers each round. A failed announce (commonly a transient
     * DNS/network blip, e.g. EAI_AGAIN inside a container) counts as an empty round, not a
     * failure: DHT is asked instead if it's running, otherwise the empty-round delay paces a
     * retry, all within the same time budget. See design_docs/0028's addendum. */
    private MagnetCandidateSource trackerCandidates(MagnetLink magnet, List<String> trackerUrls, int numWant) {
        TrackerClient trackerClient = createTrackerClient(List.of(trackerUrls));
        AtomicBoolean everAnnounced = new AtomicBoolean();
        return new MagnetCandidateSource() {
            @Override
            public List<PeerAddress> nextRound() {
                try {
                    List<PeerAddress> peers = trackerClient.announce(new TrackerRequest(magnet.infoHash(), ourPeerId,
                            ourListenPort, 0, 0, Long.MAX_VALUE, TrackerEvent.STARTED, numWant)).peers();
                    everAnnounced.set(true);
                    return peers;
                } catch (RuntimeException e) {
                    // Message only: MultiTrackerClient already condenses the per-tracker failures
                    // into one line, and a stack trace per round would bury it.
                    LOG.log(System.Logger.Level.WARNING, "Could not announce for magnet " + magnet.infoHash()
                            + (dhtNode != null ? ", trying DHT" : ", will retry") + ": " + e.getMessage());
                    return dhtNode != null ? dhtPeersOrEmpty(magnet) : List.of();
                }
            }

            @Override
            public String exhaustedMessage(int triedCount) {
                return everAnnounced.get() || triedCount > 0
                        ? "No peer had the metadata (tried " + triedCount + ")"
                        : "Could not announce to any tracker";
            }
        };
    }

    /** A trackerless magnet: a DHT lookup each round. Unlike the tracker path, a failed lookup
     * ends the fetch - there is nothing else to fall back to. */
    private MagnetCandidateSource dhtCandidates(MagnetLink magnet) {
        return new MagnetCandidateSource() {
            @Override
            public List<PeerAddress> nextRound() {
                try {
                    return dhtNode.findPeers(magnet.infoHash(), ourListenPort, false, DHT_QUERY_TIMEOUT);
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.WARNING, "DHT peer lookup failed for magnet " + magnet.infoHash(), e);
                    throw new MagnetFetchAbandoned("DHT peer lookup failed");
                }
            }

            @Override
            public String exhaustedMessage(int triedCount) {
                return "No peer had the metadata (tried " + triedCount + ")";
            }
        };
    }

    /** Rounds of: get candidates, drop blocked and already-tried ones, race the rest for the
     * metadata. Ends when a peer delivers it, the time budget runs out, or the magnet is removed
     * (removeTorrent() has then already recorded its own event and cleaned up, so this just
     * stops). trackerUrls becomes the resulting torrent's announce-list; a trackerless magnet
     * gets none and relies on the session's periodic DHT discovery from then on. */
    private void fetchMagnetMetadataThenAdd(MagnetLink magnet, List<String> trackerUrls, String source) {
        Settings settings = settingsStore.current();
        metadataFetchLimiter.resizeTo(settings.magnetFetchConcurrencyLimit());
        int perRound = settings.magnetFetchCandidatesPerRound();
        MagnetCandidateSource candidates = trackerUrls.isEmpty()
                ? dhtCandidates(magnet)
                : trackerCandidates(magnet, trackerUrls, perRound);
        Instant deadline = Instant.now().plusSeconds(settings.magnetFetchTimeBudgetSeconds());
        Set<PeerAddress> alreadyTried = new HashSet<>();
        do {
            List<PeerAddress> fresh;
            try {
                fresh = candidates.nextRound().stream()
                        .filter(address -> !blocklist.isBlocked(address.address()))
                        .filter(address -> !alreadyTried.contains(address))
                        .limit(perRound)
                        .toList();
            } catch (MagnetFetchAbandoned e) {
                recordMagnetAddFailed(magnet, e.getMessage(), source);
                return;
            }
            alreadyTried.addAll(fresh);
            Optional<byte[]> infoDictBytes = raceOneRound(magnet, fresh);
            if (infoDictBytes.isPresent()) {
                addFetchedTorrentUnlessRemoved(magnet, infoDictBytes.get(), trackerUrls, source);
                return;
            }
            if (fresh.isEmpty() && !sleepUnlessDeadlinePassed(EMPTY_ROUND_RETRY_DELAY, deadline)) {
                break;
            }
        } while (pendingMagnets.containsKey(magnet.infoHash()) && Instant.now().isBefore(deadline));
        if (!pendingMagnets.containsKey(magnet.infoHash())) {
            return;
        }
        LOG.log(System.Logger.Level.WARNING, "Could not fetch metadata for magnet " + magnet.infoHash()
                + " from any of " + alreadyTried.size() + " peer(s) tried");
        recordMagnetAddFailed(magnet, candidates.exhaustedMessage(alreadyTried.size()), source);
    }

    private List<PeerAddress> dhtPeersOrEmpty(MagnetLink magnet) {
        try {
            return dhtNode.findPeers(magnet.infoHash(), ourListenPort, false, DHT_QUERY_TIMEOUT);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "DHT peer lookup failed for magnet " + magnet.infoHash(), e);
            return List.of();
        }
    }

    /** Sleeps up to delay, but never past deadline, and never at all if deadline has already
     * passed - avoids sleeping the full delay only to immediately fail the loop's own
     * Instant.now().isBefore(deadline) check anyway. Returns false (loop should stop now,
     * interrupted or nothing left of the budget) or true (fine to keep looping). */
    private static boolean sleepUnlessDeadlinePassed(Duration delay, Instant deadline) {
        Duration remaining = Duration.between(Instant.now(), deadline);
        if (remaining.isNegative() || remaining.isZero()) {
            return false;
        }
        try {
            Thread.sleep(delay.compareTo(remaining) < 0 ? delay : remaining);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return true;
    }

    /** One round of the retry loops above: races every candidate concurrently via
     * invokeAny(), stopping at the first success - see design_docs/0028's addendum for why
     * this replaced the original sequential, single-attempt design (revisited once real-world
     * evidence showed it wasn't trying enough peers, not a speculative change). Per-candidate
     * failure reasons are logged inside fetchOneCandidate() itself, not here - invokeAny()
     * only surfaces the *last* failure as the ExecutionException's cause, which would lose
     * every other candidate's own reason otherwise. */
    private Optional<byte[]> raceOneRound(MagnetLink magnet, List<PeerAddress> candidates) {
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        List<Callable<byte[]>> tasks = candidates.stream()
                .<Callable<byte[]>>map(address -> () -> fetchOneCandidate(magnet, address))
                .toList();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            return Optional.of(executor.invokeAny(tasks));
        } catch (ExecutionException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** metadataFetchLimiter bounds simultaneous in-flight attempts engine-wide, across every
     * magnet-add and every round happening at once - a candidate beyond the current limit
     * simply blocks here until a slot frees up; invokeAny()'s own cancellation of not-yet
     * -started tasks once a round's winner is found means a candidate still waiting on
     * acquire() when that happens is just cleanly interrupted, never having opened a socket. */
    private byte[] fetchOneCandidate(MagnetLink magnet, PeerAddress address) throws IOException {
        try {
            metadataFetchLimiter.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for a metadata-fetch slot", e);
        }
        try {
            return MetadataFetcher.fetch(address, magnet.infoHash(), ourPeerId, encryptionMode.get(), proxyConfig);
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG,
                    "Metadata fetch from " + address + " failed for magnet " + magnet.infoHash(), e);
            throw e;
        } finally {
            metadataFetchLimiter.release();
        }
    }

    /** Shared by both magnet metadata-fetch paths above once raceOneRound() finds a winner.
     * trackerUrls becomes the resulting torrent's announce-list (empty for the DHT path).
     * source is null for an ordinary REST/UI-triggered magnet add (resulting message "Added via
     * magnet", the MAGNET_SOURCE constant) or WATCH_FOLDER_SOURCE for a watch-folder-dropped
     * magnet file (resulting message "Added via watch folder" instead - the same label a
     * watch-folder-dropped .torrent file already gets). Package-private (not private) so tests
     * can drive this directly rather than through a real peer metadata fetch - same
     * test-visibility rationale as trackerStatusListenerFor above. */
    /** The fetch loops only check for a removal between rounds, and a round can take many
     * seconds - a magnet removed while its winning round was still running must not be added
     * once that round returns. See design_docs/0092. */
    private void addFetchedTorrentUnlessRemoved(MagnetLink magnet, byte[] infoDictBytes, List<String> trackerUrls,
                                                String source) {
        if (!pendingMagnets.containsKey(magnet.infoHash())) {
            LOG.log(System.Logger.Level.INFO, "Magnet " + magnet.infoHash()
                    + " was removed while its metadata was being fetched - not adding it");
            return;
        }
        addFetchedTorrent(magnet, infoDictBytes, trackerUrls, source);
    }

    void addFetchedTorrent(MagnetLink magnet, byte[] infoDictBytes, List<String> trackerUrls, String source) {
        try {
            addTorrent(synthesizeTorrentFileBytes(infoDictBytes, trackerUrls), source != null ? source : MAGNET_SOURCE);
        } catch (IOException | RuntimeException e) {
            // The problem is on our side (storage, directory creation, ...), not this
            // peer's - trying another peer for the same doomed outcome wouldn't help.
            LOG.log(System.Logger.Level.WARNING,
                    "Fetched metadata for magnet " + magnet.infoHash() + " but could not add the torrent", e);
            recordMagnetAddFailed(magnet, "Fetched metadata but could not add the torrent", source);
        }
    }

    /** Wraps a verified info-dict back into a full top-level torrent-file byte array (with
     * the magnet's own trackers as a single flat announce-list tier - a magnet's tr= params
     * have no BEP 12 tier structure to preserve) so addTorrent() can be reused completely
     * unchanged, resume-record persistence included. See design_docs/0028. */
    private static byte[] synthesizeTorrentFileBytes(byte[] infoDictBytes, List<String> trackerUrls) {
        BValue info = BencodeDecoder.decode(infoDictBytes);
        BList announceList = new BList(
                trackerUrls.stream().<BValue>map(url -> new BList(List.of(BString.of(url)))).toList());
        BDictionary top = new BDictionary(Map.of(
                BString.of("info"), info,
                BString.of("announce-list"), announceList));
        return BencodeEncoder.encode(top);
    }

    /**
     * Scans configDirectory's torrents subdirectory for torrents added in a previous run of
     * this process (config/torrents/<infoHash> directories carrying TORRENT_FILE_MARKER_FILENAME)
     * and re-registers each one. See design_docs/0026/0065: every restored session is
     * registered - and therefore visible to callers - immediately in TorrentSession's
     * VERIFYING state, before its on-disk data has actually been re-hashed; the re-hash and
     * any resulting auto-start happen in the background. The exception is a torrent whose
     * files are missing from disk: it is registered in ERROR, saying so, and nothing is
     * recreated or downloaded until the user asks (design_docs/0088). A directory that fails to restore
     * (corrupt .torrent bytes, no usable tracker, a missing download-path marker, ...) is
     * logged and skipped rather than aborting the rest of the scan.
     */
    public void restore() {
        Path torrentsConfigDirectory = configDirectory.resolve(TORRENTS_CONFIG_SUBDIRECTORY);
        if (!Files.isDirectory(torrentsConfigDirectory)) {
            return;
        }
        try (var entries = Files.list(torrentsConfigDirectory)) {
            for (Path directory : entries.filter(Files::isDirectory).toList()) {
                restoreOne(directory);
            }
        } catch (IOException e) {
            throw new TorrentEngineIoException(
                    "Could not scan config directory " + torrentsConfigDirectory + ": " + e.getMessage(), e);
        }
    }

    private void restoreOne(Path configTorrentDirectory) {
        Path torrentFileMarker = configTorrentDirectory.resolve(TORRENT_FILE_MARKER_FILENAME);
        if (!Files.exists(torrentFileMarker)) {
            restorePendingMagnet(configTorrentDirectory);
            return;
        }
        try {
            byte[] torrentFileBytes = Files.readAllBytes(torrentFileMarker);
            TorrentMetadata metadata = MetainfoParser.parse(torrentFileBytes);
            TrackerClient trackerClient = createTrackerClient(metadata);
            boolean running = !STATE_STOPPED.equals(readStateMarker(configTorrentDirectory));
            Instant addedAt = readAddedAtMarker(configTorrentDirectory);
            Path torrentDirectory = readDownloadPathMarker(configTorrentDirectory);
            if (torrentDirectory == null) {
                LOG.log(System.Logger.Level.WARNING,
                        "No download-path marker in " + configTorrentDirectory + " - skipping");
                return;
            }
            TorrentSession session = buildSession(metadata, trackerClient, torrentDirectory, configTorrentDirectory,
                    addedAt, SessionOrigin.RESTORED, running);
            sessions.put(metadata.infoHash(), session);
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not restore torrent from " + configTorrentDirectory, e);
        }
    }

    /** Re-drives a magnet metadata fetch interrupted by a restart, with a fresh time budget -
     * see design_docs/0070's own "one attempt, not persist-and-resume elapsed time" reasoning.
     * A no-op if configTorrentDirectory has no magnet-pending marker at all (the common case -
     * restoreOne() only calls this once it's already confirmed there's no torrent-file marker
     * either, so most callers here are genuinely stale/unrelated directories, not just every
     * non-torrent directory silently doing nothing). */
    private void restorePendingMagnet(Path configTorrentDirectory) {
        InfoHash infoHash;
        try {
            infoHash = new InfoHash(configTorrentDirectory.getFileName().toString());
        } catch (IllegalArgumentException e) {
            return;
        }
        PendingMagnet pending = readMagnetPendingMarker(infoHash, configTorrentDirectory);
        if (pending == null) {
            return;
        }
        MagnetLink magnet = new MagnetLink(infoHash, pending.displayName(), pending.trackers());
        List<String> trackerUrls = pending.trackers().stream().filter(TorrentEngine::isSupportedTrackerUrl).toList();
        if (trackerUrls.isEmpty() && dhtNode == null) {
            LOG.log(System.Logger.Level.WARNING, "Could not resume magnet fetch for " + infoHash
                    + " - no usable tracker and DHT unavailable");
            recordMagnetAddFailed(magnet, "No usable tracker, and DHT is unavailable - trackerless magnets need DHT",
                    null);
            return;
        }
        pendingMagnets.put(infoHash, pending);
        startMagnetFetch(magnet, trackerUrls, null);
    }

    /** Stops and forgets the session, and deletes its persisted resume record (so it
     * doesn't reappear on the next restore()) - but never deletes already-downloaded
     * files. Use removeTorrent(infoHash, true) to delete those too. Returns whether there was a
     * torrent or pending magnet to remove. */
    public boolean removeTorrent(InfoHash infoHash) {
        return removeTorrent(infoHash, false);
    }

    public boolean removeTorrent(InfoHash infoHash, boolean deleteData) {
        // Checked first, before ever touching sessions/directories - a pending magnet never has
        // either populated. deleteData is meaningless here (nothing was ever downloaded) and is
        // ignored. The in-flight fetch thread, if any, notices via its own periodic
        // pendingMagnets check (see fetchMagnetMetadataThenAdd()) rather than being
        // interrupted mid-network-call. See design_docs/0070.
        if (pendingMagnets.remove(infoHash) != null) {
            deleteMagnetPendingMarker(configTorrentDirectory(infoHash));
            eventStore.record(new LibraryEvent(Instant.now(), EventType.REMOVED, infoHash.hex(), null, null));
            return true;
        }
        TorrentSession session = sessions.remove(infoHash);
        trackerReachability.forget(infoHash.hex());
        if (session != null) {
            session.close();
            eventStore.record(new LibraryEvent(
                    Instant.now(), EventType.REMOVED, infoHash.hex(), session.metadata().name(), null));
        }
        Path contentPath = directories.remove(infoHash);
        Path configTorrentDirectory = configTorrentDirectory(infoHash);
        if (deleteData) {
            if (contentPath != null) {
                if (Files.isDirectory(contentPath)) {
                    deleteRecursively(contentPath);
                } else {
                    deleteIfExists(contentPath);
                }
            }
            if (Files.isDirectory(configTorrentDirectory)) {
                deleteRecursively(configTorrentDirectory);
            }
        } else {
            // Deletes only what excludes this torrent from restore()'s scan - the rest of its
            // config record (seeding-limit override, added-at, download-path, lifetime stats)
            // deliberately stays behind, same "torrent-scoped facts stay with the record"
            // precedent as the seeding-limit override always followed. See design_docs/0054/0065.
            if (session != null) {
                // Captures final totals before this record potentially sits untouched for a
                // long time (until, if ever, re-added) - see design_docs/0064.
                flushLifetimeStats(session);
            }
            deleteIfExists(configTorrentDirectory.resolve(TORRENT_FILE_MARKER_FILENAME));
            deleteIfExists(configTorrentDirectory.resolve(STATE_MARKER_FILENAME));
        }
        return session != null;
    }

    /**
     * Stops the session but keeps it registered - its in-memory piece
     * state survives, so resumeTorrent picks up where it left off without
     * re-downloading anything, for as long as this TorrentEngine instance
     * (i.e. the running process) stays alive. See design_docs/0018.
     *
     * <p>Returns whether the torrent exists (false for a pending magnet, which has nothing to
     * pause), so the REST layer can 404 like every other per-torrent endpoint.
     *
     * <p>Like every per-torrent setter, the marker is written first: if that fails, the call
     * throws with nothing changed, instead of leaving a paused torrent that a restart resumes.
     */
    public boolean pauseTorrent(InfoHash infoHash) {
        TorrentSession session = sessions.get(infoHash);
        if (session == null) {
            return false;
        }
        writeStateMarker(configTorrentDirectory(infoHash), STATE_STOPPED);
        session.stop();
        // A deliberate stop shouldn't wait on the next periodic tick to capture its final
        // lifetime totals. See design_docs/0064.
        flushLifetimeStats(session);
        return true;
    }

    /** Returns whether the torrent exists; marker first - see pauseTorrent(). */
    public boolean resumeTorrent(InfoHash infoHash) {
        TorrentSession session = sessions.get(infoHash);
        if (session == null) {
            return false;
        }
        writeStateMarker(configTorrentDirectory(infoHash), STATE_RUNNING);
        // Returns once the session is STARTING; the announce runs in the background, so a resume
        // never waits on a slow tracker. See design_docs/0098.
        session.startInBackground();
        return true;
    }

    /** Forced recheck - see TorrentSession.recheck(). The persisted running/stopped marker is
     * deliberately left alone: the torrent returns to whatever it was doing, and a restart
     * mid-recheck re-verifies anyway. False if there is no such session (including a pending
     * magnet, which has nothing on disk to check) or a verification pass is already running.
     * See design_docs/0082. */
    public boolean recheckTorrent(InfoHash infoHash) {
        TorrentSession session = sessions.get(infoHash);
        return session != null && session.recheck();
    }

    /** Forced reannounce - see TorrentSession.reannounceNow(). False if there is no such session,
     * it isn't running, or one is already in flight. See design_docs/0082. */
    public boolean reannounceTorrent(InfoHash infoHash) {
        TorrentSession session = sessions.get(infoHash);
        return session != null && session.reannounceNow();
    }

    public Optional<TorrentSession> getTorrent(InfoHash infoHash) {
        return Optional.ofNullable(sessions.get(infoHash));
    }

    /** See design_docs/0070 - a pending magnet is never also a real session for the same info
     * hash (addTorrent()'s own concludePendingMagnet() call guarantees the two are mutually
     * exclusive), so callers merging both lists never need to de-duplicate by info hash. */
    public Optional<PendingMagnet> getPendingMagnet(InfoHash infoHash) {
        return Optional.ofNullable(pendingMagnets.get(infoHash));
    }

    public Collection<PendingMagnet> listPendingMagnets() {
        return List.copyOf(pendingMagnets.values());
    }

    /** Persists the override to this torrent's own marker file before updating the live
     * session - same crash-safety ordering as SettingsStore.update() (design_docs/0041): a
     * crash between the two can never leave the in-memory session claiming an override that
     * isn't actually durable yet. A no-op if infoHash isn't a currently-registered session,
     * same as pauseTorrent()/resumeTorrent()'s own silent-no-op convention. See
     * design_docs/0054. */
    public void setSeedingLimitOverride(InfoHash infoHash, SeedingLimitOverride override) {
        TorrentSession session = sessions.get(infoHash);
        if (session != null) {
            writeSeedingLimitOverrideMarker(configTorrentDirectory(infoHash), override);
            session.setSeedingLimitOverride(override);
        }
    }

    /** Same crash-safety ordering and no-op convention as setSeedingLimitOverride above. Only
     * the bandwidth half of this takes effect on the already-running session immediately
     * (session.setTorrentLimits() updates the volatile field RateLimiters.forTorrent() reads
     * live) - the maxConnectionsOverride half has no effect until this torrent is next
     * constructed (a restart or remove-and-re-add), since connectionSlots can't be
     * live-resized. See design_docs/0072. */
    public void setTorrentLimits(InfoHash infoHash, TorrentLimitOverride override) {
        TorrentSession session = sessions.get(infoHash);
        if (session != null) {
            writeTorrentLimitOverrideMarker(configTorrentDirectory(infoHash), override);
            session.setTorrentLimits(override);
        }
    }

    /** Marker written first, then applied live - a failed write throws before the in-memory
     * change, same ordering as setTorrentLimits above. Throws IllegalArgumentException for a
     * wrong-length array or one with nothing wanted (the REST layer maps that to a 400); a
     * no-op for an unknown torrent, same convention as the two setters above. Returns whether
     * the torrent existed. See design_docs/0075. */
    public boolean setFilePriorities(InfoHash infoHash, FilePriorities priorities) {
        TorrentSession session = sessions.get(infoHash);
        if (session == null) {
            return false;
        }
        if (priorities.priorities().size() != session.metadata().files().size()) {
            throw new IllegalArgumentException("Expected " + session.metadata().files().size()
                    + " priorities, got " + priorities.priorities().size());
        }
        if (!priorities.anyWanted()) {
            throw new IllegalArgumentException("At least one file must be wanted");
        }
        writeFilePrioritiesMarker(configTorrentDirectory(infoHash), priorities);
        session.setFilePriorities(priorities);
        return true;
    }

    /** Asks every torrent sitting in the retryable ERROR state (a failed first announce with no
     * fallback - design_docs/0036's 2026-09-20 addendum) to retry right now instead of waiting out
     * its backoff. Called when the proxy settings or password change, since that is the usual
     * reason such a torrent failed and the usual thing that fixes it. Cheap and non-blocking: each
     * attempt runs on the session's own retry thread. */
    public void retryFailedStarts() {
        for (TorrentSession session : sessions.values()) {
            session.retryStartNow();
        }
    }

    public ProxyConfig proxyConfig() {
        return proxyConfig;
    }

    /** µTP is UDP, which a SOCKS5 proxy can't carry here - off for this whole run whenever the
     * proxy's block switch was on at startup, regardless of the utpEnabled setting. */
    private boolean effectiveUtpEnabled() {
        return settingsStore.current().utpEnabled() && !proxyBlocksUnsupportedAtStart;
    }

    /** What the Proxy settings group shows. active is the live setting; blocksUnsupported is
     * whether this run actually turned DHT/µTP/LSD/incoming off; restartRequired is true when the
     * live settings would block them but this run didn't (or the reverse) - until then those
     * still use the real address. See design_docs/0079. */
    public record ProxyStatus(boolean active, String host, int port, boolean hasPassword,
                              boolean blockUnsupported, boolean blockingNow, boolean restartRequired) {
    }

    public ProxyStatus proxyStatus() {
        Settings settings = settingsStore.current();
        boolean blockingConfigured = ProxyConfig.blocksUnsupported(settings);
        return new ProxyStatus(ProxyConfig.isActive(settings), settings.proxyHost(), settings.proxyPort(),
                proxyConfig.hasPassword(), settings.proxyBlockUnsupported(), proxyBlocksUnsupportedAtStart,
                blockingConfigured != proxyBlocksUnsupportedAtStart);
    }

    /** Checks the currently saved proxy (host/port/username plus the stored password) end to end -
     * reachable, credentials accepted, UDP relayed - without changing anything. Runs on the
     * caller's thread and blocks for up to a couple of connect timeouts. */
    public Socks5.TestResult testProxy() {
        return proxyConfig.current()
                .map(proxy -> Socks5.test(proxy, PROXY_TEST_TIMEOUT_MS))
                .orElseGet(() -> new Socks5.TestResult(false, false, "No proxy is configured"));
    }

    public Blocklist blocklist() {
        return blocklist;
    }

    /** Runs on the blocklist's own loading thread whenever the enforced list changes: every
     * session forgets known addresses and closes connections that are now blocked. O(sessions x
     * (known addresses + connections)) once per reload - bounded, and reloads are rare. See
     * design_docs/0078. */
    private void applyBlocklistChange() {
        for (TorrentSession session : sessions.values()) {
            session.applyIpFilterChange();
        }
    }

    /** The engine-wide managed label list (design_docs/0077). Mutate it only through
     * deleteLabel() below for a delete (which must also strip the id from every torrent);
     * create/rename are safe to call directly on the registry. */
    public LabelRegistry labels() {
        return labelRegistry;
    }

    /** Replaces a torrent's labels with ids (deduplicated, order kept). Throws
     * IllegalArgumentException for an unknown label id or more than MAX_LABELS_PER_TORRENT;
     * marker written before the in-memory change, same ordering as setTorrentLimits. Returns
     * whether the torrent existed. See design_docs/0077. */
    public boolean setTorrentLabels(InfoHash infoHash, List<String> ids) {
        TorrentSession session = sessions.get(infoHash);
        if (session == null) {
            return false;
        }
        List<String> distinct = ids.stream().distinct().toList();
        if (distinct.size() > MAX_LABELS_PER_TORRENT) {
            throw new IllegalArgumentException("At most " + MAX_LABELS_PER_TORRENT + " labels per torrent");
        }
        for (String id : distinct) {
            if (!labelRegistry.exists(id)) {
                throw new IllegalArgumentException("Unknown label: " + id);
            }
        }
        writeLabelIdsMarker(configTorrentDirectory(infoHash), distinct);
        session.setLabelIds(distinct);
        return true;
    }

    /** Deletes a label and strips its id from every loaded torrent (rewriting each marker).
     * Markers of torrents not currently loaded may keep the dead id; readLabelIdsMarker()
     * drops it on read. Returns whether the label existed. See design_docs/0077. */
    public boolean deleteLabel(String id) {
        if (!labelRegistry.delete(id)) {
            return false;
        }
        for (TorrentSession session : sessions.values()) {
            if (session.labelIds().contains(id)) {
                List<String> remaining = session.labelIds().stream().filter(other -> !other.equals(id)).toList();
                writeLabelIdsMarker(configTorrentDirectory(session.metadata().infoHash()), remaining);
                session.setLabelIds(remaining);
            }
        }
        return true;
    }

    public Collection<TorrentSession> listTorrents() {
        return List.copyOf(sessions.values());
    }

    public PeerId ourPeerId() {
        return ourPeerId;
    }

    /** Empty when inbound connections are disabled or the peer server failed to bind - see
     * design_docs/0038. Exists mainly for tests to discover the real bound port when
     * constructed with port 0 (ephemeral); production wiring already knows its own
     * configured port. */
    public OptionalInt peerServerPort() {
        return peerServer != null ? OptionalInt.of(peerServer.port()) : OptionalInt.empty();
    }

    /** Package-private purely for test access (e.g. seeding a real contact directly into the
     * routing table to test saveDhtRoutingTable()/loadPersistedDhtContacts() without waiting
     * on real bootstrap) - null whenever DHT is disabled, same as dhtStatus().enabled(). */
    DhtNode dhtNode() {
        return dhtNode;
    }

    /** Stops every managed session - for graceful process shutdown. Deliberately does not
     * touch any persisted state marker: whatever running/stopped state was last recorded
     * stays as the desired state for the next restore(), regardless of why the process
     * is exiting. */
    public void shutdown() {
        maintenanceScheduler.shutdownNow();
        List<TorrentSession> closed = List.copyOf(sessions.values());
        for (TorrentSession session : closed) {
            // A graceful exit shouldn't rely on the next periodic tick having already run -
            // see design_docs/0064.
            flushLifetimeStats(session);
            session.close();
        }
        sessions.clear();
        writeTransferHistory();
        awaitStoppedAnnounces(closed);
        if (peerServer != null) {
            peerServer.close();
        }
        if (dhtNode != null) {
            saveDhtRoutingTable();
            dhtNode.close();
        }
        if (lsdService != null) {
            lsdService.close();
        }
    }

    /** Each close() above only *starts* its STOPPED announce (design_docs/0081), so they all run
     * at once; this gives them one shared, bounded window to go out before the process exits and
     * takes the still-running ones down with it. Bounded so shutdown never again takes as long as
     * the slowest tracker times the number of torrents. */
    private static void awaitStoppedAnnounces(List<TorrentSession> closed) {
        long deadlineNanos = System.nanoTime() + SHUTDOWN_ANNOUNCE_GRACE.toNanos();
        try {
            for (TorrentSession session : closed) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    return;
                }
                session.awaitStoppedAnnounce(Duration.ofNanos(remainingNanos));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Writes one marker atomically (AtomicFiles). A failure surfaces as a
     * TorrentEngineIoException naming what couldn't be saved: every marker but the lifetime
     * stats records something a user just asked for, so failing loudly is right. */
    private static void writeMarker(Path directory, String filename, String what, String content) {
        try {
            AtomicFiles.write(directory.resolve(filename), content);
        } catch (IOException e) {
            throw new TorrentEngineIoException("Could not persist " + what + " to " + directory + ": " + e.getMessage(), e);
        }
    }

    @FunctionalInterface
    private interface MarkerParser<T> {
        T parse(Path marker) throws IOException;
    }

    /** Reads one marker. fallback when it's absent (a new torrent, or one from before that
     * marker existed), and fallback, logged, when it can't be read or parsed: a hand-edited or
     * corrupt marker must never drop a torrent from restore. Never throws. See
     * design_docs/0092. */
    private static <T> T readMarker(Path directory, String filename, String what, T fallback, MarkerParser<T> parser) {
        Path marker = directory.resolve(filename);
        if (!Files.exists(marker)) {
            return fallback;
        }
        try {
            return parser.parse(marker);
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not read " + what + " in " + directory + " - using the default", e);
            return fallback;
        }
    }

    private static void writeTorrentFileMarker(Path directory, byte[] torrentFileBytes) throws IOException {
        AtomicFiles.write(directory.resolve(TORRENT_FILE_MARKER_FILENAME), torrentFileBytes);
    }

    private static void writeStateMarker(Path directory, String state) {
        writeMarker(directory, STATE_MARKER_FILENAME, "state", state);
    }

    /** RUNNING when absent or unreadable - the same default a torrent has before it was ever
     * paused. */
    private static String readStateMarker(Path directory) {
        return readMarker(directory, STATE_MARKER_FILENAME, "state marker", STATE_RUNNING,
                marker -> Files.readString(marker).strip());
    }

    private static void writeSeedingLimitOverrideMarker(Path directory, SeedingLimitOverride override) {
        writeMarker(directory, SEEDING_LIMIT_OVERRIDE_MARKER_FILENAME, "seeding limit override",
                KeyValueFile.format(List.of(
                        KeyValueFile.entry("ratioLimit", override.ratioLimit()),
                        KeyValueFile.entry("timeLimitMinutes", override.timeLimitMinutes()))));
    }

    /** INHERIT (both metrics from the global default) when absent or unreadable. See
     * design_docs/0054. */
    private static SeedingLimitOverride readSeedingLimitOverrideMarker(Path directory) {
        return readMarker(directory, SEEDING_LIMIT_OVERRIDE_MARKER_FILENAME, "seeding-limit override",
                SeedingLimitOverride.INHERIT, marker -> {
                    double ratioLimit = SeedingLimitOverride.INHERIT.ratioLimit();
                    long timeLimitMinutes = SeedingLimitOverride.INHERIT.timeLimitMinutes();
                    for (KeyValueFile.Entry entry : KeyValueFile.read(marker)) {
                        switch (entry.key()) {
                            case "ratioLimit" -> ratioLimit = Double.parseDouble(entry.value());
                            case "timeLimitMinutes" -> timeLimitMinutes = Long.parseLong(entry.value());
                            default -> {
                            }
                        }
                    }
                    return new SeedingLimitOverride(ratioLimit, timeLimitMinutes);
                });
    }

    private static void writeTorrentLimitOverrideMarker(Path directory, TorrentLimitOverride override) {
        writeMarker(directory, TORRENT_LIMIT_OVERRIDE_MARKER_FILENAME, "torrent limit override",
                KeyValueFile.format(List.of(
                        KeyValueFile.entry("uploadBytesPerSecOverride", override.uploadBytesPerSecOverride()),
                        KeyValueFile.entry("downloadBytesPerSecOverride", override.downloadBytesPerSecOverride()),
                        KeyValueFile.entry("maxConnectionsOverride", override.maxConnectionsOverride()))));
    }

    /** INHERIT when absent or unreadable. See design_docs/0072. */
    private static TorrentLimitOverride readTorrentLimitOverrideMarker(Path directory) {
        return readMarker(directory, TORRENT_LIMIT_OVERRIDE_MARKER_FILENAME, "torrent limit override",
                TorrentLimitOverride.INHERIT, marker -> {
                    long upload = TorrentLimitOverride.INHERIT.uploadBytesPerSecOverride();
                    long download = TorrentLimitOverride.INHERIT.downloadBytesPerSecOverride();
                    int maxConnections = TorrentLimitOverride.INHERIT.maxConnectionsOverride();
                    for (KeyValueFile.Entry entry : KeyValueFile.read(marker)) {
                        switch (entry.key()) {
                            case "uploadBytesPerSecOverride" -> upload = Long.parseLong(entry.value());
                            case "downloadBytesPerSecOverride" -> download = Long.parseLong(entry.value());
                            case "maxConnectionsOverride" -> maxConnections = Integer.parseInt(entry.value());
                            default -> {
                            }
                        }
                    }
                    return new TorrentLimitOverride(upload, download, maxConnections);
                });
    }

    private static void writeAddedAtMarker(Path directory, Instant addedAt) {
        writeMarker(directory, ADDED_AT_MARKER_FILENAME, "added-at timestamp", addedAt.toString());
    }

    /** Null when absent or unreadable - see ADDED_AT_MARKER_FILENAME on why an unknown date
     * stays unknown rather than being backfilled. */
    private static Instant readAddedAtMarker(Path directory) {
        return readMarker(directory, ADDED_AT_MARKER_FILENAME, "added-at marker", null,
                marker -> Instant.parse(Files.readString(marker).strip()));
    }

    private static void writeDownloadPathMarker(Path configTorrentDirectory, Path downloadPath) {
        writeMarker(configTorrentDirectory, DOWNLOAD_PATH_MARKER_FILENAME, "download path", downloadPath.toString());
    }

    /** Null when absent or unreadable: resolveDownloadDirectory() then treats the torrent as
     * new, and restoreOne() skips it. */
    private static Path readDownloadPathMarker(Path configTorrentDirectory) {
        return readMarker(configTorrentDirectory, DOWNLOAD_PATH_MARKER_FILENAME, "download-path marker", null,
                marker -> Path.of(Files.readString(marker).strip()));
    }

    private static void writeMagnetPendingMarker(Path configTorrentDirectory, PendingMagnet pending) {
        List<KeyValueFile.Entry> entries = new ArrayList<>();
        if (pending.displayName() != null) {
            entries.add(KeyValueFile.entry("displayName", pending.displayName()));
        }
        for (String tracker : pending.trackers()) {
            entries.add(KeyValueFile.entry("tracker", tracker));
        }
        writeMarker(configTorrentDirectory, MAGNET_PENDING_MARKER_FILENAME, "magnet-pending marker",
                KeyValueFile.format(entries));
    }

    private static void deleteMagnetPendingMarker(Path configTorrentDirectory) {
        deleteIfExists(configTorrentDirectory.resolve(MAGNET_PENDING_MARKER_FILENAME));
    }

    /** Null when absent (never a pending magnet, or one that has concluded) or unreadable:
     * restore() then doesn't re-drive a fetch it can't reconstruct. */
    private static PendingMagnet readMagnetPendingMarker(InfoHash infoHash, Path configTorrentDirectory) {
        return readMarker(configTorrentDirectory, MAGNET_PENDING_MARKER_FILENAME, "magnet-pending marker", null,
                marker -> {
                    String displayName = null;
                    List<String> trackers = new ArrayList<>();
                    for (KeyValueFile.Entry entry : KeyValueFile.read(marker)) {
                        switch (entry.key()) {
                            case "displayName" -> displayName = entry.value();
                            case "tracker" -> trackers.add(entry.value());
                            default -> {
                            }
                        }
                    }
                    return new PendingMagnet(infoHash, displayName, trackers);
                });
    }

    /** Best-effort, unlike the other writers: rewritten every flush tick by a live session, so a
     * failed write only loses this flush (the next tick, or the flush on stop/shutdown, catches
     * up), and the pause or shutdown that triggered it mustn't fail over it. See
     * design_docs/0064. */
    private static void writeLifetimeStatsMarker(Path configTorrentDirectory, TorrentSession.PersistedLifetimeStats stats) {
        try {
            AtomicFiles.write(configTorrentDirectory.resolve(LIFETIME_STATS_MARKER_FILENAME), KeyValueFile.format(List.of(
                    KeyValueFile.entry("lifetimeUploadedBytes", stats.uploadedBytesBaseline()),
                    KeyValueFile.entry("activeMillis", stats.activeMillisBaseline()),
                    KeyValueFile.entry("completedAtEpochMillis", stats.completedAtEpochMillis()),
                    KeyValueFile.entry("wastedBytes", stats.wastedBytesBaseline()))));
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not persist lifetime stats to " + configTorrentDirectory, e);
        }
    }

    /** PersistedLifetimeStats.NONE (all zero) when absent or unreadable. */
    private static TorrentSession.PersistedLifetimeStats readLifetimeStatsMarker(Path configTorrentDirectory) {
        return readMarker(configTorrentDirectory, LIFETIME_STATS_MARKER_FILENAME, "lifetime stats",
                TorrentSession.PersistedLifetimeStats.NONE, marker -> {
                    long uploadedBytes = 0;
                    long activeMillis = 0;
                    long completedAtEpochMillis = 0;
                    long wastedBytes = 0;
                    for (KeyValueFile.Entry entry : KeyValueFile.read(marker)) {
                        switch (entry.key()) {
                            case "lifetimeUploadedBytes" -> uploadedBytes = Long.parseLong(entry.value());
                            case "activeMillis" -> activeMillis = Long.parseLong(entry.value());
                            case "completedAtEpochMillis" -> completedAtEpochMillis = Long.parseLong(entry.value());
                            case "wastedBytes" -> wastedBytes = Long.parseLong(entry.value());
                            default -> {
                            }
                        }
                    }
                    return new TorrentSession.PersistedLifetimeStats(uploadedBytes, activeMillis,
                            completedAtEpochMillis, wastedBytes);
                });
    }

    /** Only the non-MEDIUM files are written; an absent marker means every file at MEDIUM. */
    private static void writeFilePrioritiesMarker(Path directory, FilePriorities priorities) {
        List<KeyValueFile.Entry> entries = new ArrayList<>();
        for (int i = 0; i < priorities.priorities().size(); i++) {
            FilePriority priority = priorities.get(i);
            if (priority != FilePriority.MEDIUM) {
                entries.add(KeyValueFile.entry(String.valueOf(i), priority.name()));
            }
        }
        writeMarker(directory, FILE_PRIORITIES_MARKER_FILENAME, "file priorities", KeyValueFile.format(entries));
    }

    /** Tolerant line by line: an entry it can't make sense of (bad index, unknown priority)
     * leaves that file at MEDIUM. Falls back to all-MEDIUM if the result would leave nothing
     * wanted (the invariant setFilePriorities() enforces), since a torrent with every file
     * skipped would "complete" instantly. See design_docs/0075. */
    private static FilePriorities readFilePrioritiesMarker(Path directory, int fileCount) {
        FilePriorities allMedium = FilePriorities.allMedium(fileCount);
        FilePriorities read = readMarker(directory, FILE_PRIORITIES_MARKER_FILENAME, "file priorities", allMedium,
                marker -> {
                    FilePriority[] priorities = new FilePriority[fileCount];
                    Arrays.fill(priorities, FilePriority.MEDIUM);
                    for (KeyValueFile.Entry entry : KeyValueFile.read(marker)) {
                        try {
                            int index = Integer.parseInt(entry.key().trim());
                            if (index >= 0 && index < fileCount) {
                                priorities[index] = FilePriority.valueOf(entry.value().trim());
                            }
                        } catch (IllegalArgumentException ignored) {
                            // that file stays at MEDIUM
                        }
                    }
                    return new FilePriorities(Arrays.asList(priorities));
                });
        return read.anyWanted() ? read : allMedium;
    }

    private static void writeLabelIdsMarker(Path directory, List<String> ids) {
        writeMarker(directory, LABEL_IDS_MARKER_FILENAME, "labels", ids.isEmpty() ? "" : String.join("\n", ids) + "\n");
    }

    /** Ids no longer in the registry (deleted while this torrent wasn't loaded) are dropped, a
     * missing/unreadable marker is no labels, and the result is capped at
     * MAX_LABELS_PER_TORRENT - so a stale or hand-edited marker can never surface a dead label
     * or grow without bound. */
    private List<String> readLabelIdsMarker(Path directory) {
        return readMarker(directory, LABEL_IDS_MARKER_FILENAME, "labels", List.of(),
                marker -> Files.readAllLines(marker).stream()
                        .map(String::strip)
                        .filter(id -> !id.isEmpty() && labelRegistry.exists(id))
                        .distinct()
                        .limit(MAX_LABELS_PER_TORRENT)
                        .toList());
    }

    /** Every torrent's config directory - configDirectory/torrents/<infoHash-hex> - holding
     * every per-torrent marker (torrent bytes, state, seeding-limit override, added-at,
     * download-path). Deterministic from the info hash alone, so (unlike the download-side
     * content path) no map is needed to look it back up. See design_docs/0065. */
    private Path configTorrentDirectory(InfoHash infoHash) {
        return configDirectory.resolve(TORRENTS_CONFIG_SUBDIRECTORY).resolve(infoHash.hex());
    }

    /** The actual on-disk location of a torrent's content, given the path already resolved
     * for TorrentSession/TorrentStorage (see DirectoryResolution). Identical to that path for
     * a multi-file torrent (or a single-file one whose name collided with something already
     * there); for a flat-placed single-file torrent, sessionDirectory is the *shared* download
     * root, so the actual content is one level down, at the file TorrentStorage itself
     * resolves (baseDirectory.resolve(single.name())) - see design_docs/0065. */
    private static Path contentPathFor(TorrentMetadata metadata, Path sessionDirectory) {
        return metadata instanceof SingleFileTorrent single ? sessionDirectory.resolve(single.name()) : sessionDirectory;
    }

    private static void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new TorrentEngineIoException("Could not delete " + path + ": " + e.getMessage(), e);
        }
    }

    private static void deleteRecursively(Path directory) {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException e) {
            throw new TorrentEngineIoException("Could not delete " + directory + ": " + e.getMessage(), e);
        }
    }

    /** preExisting is true when this info hash already had a download path recorded from a
     * previous resolveDownloadDirectory() call (reused, not freshly claimed) - addTorrent()
     * then verifies what's there instead of starting fresh. See design_docs/0037. */
    private record DirectoryResolution(Path directory, boolean preExisting) {
    }

    /**
     * Names the download location after the torrent's declared name (as other clients do, and
     * readable when browsing) rather than its info hash - see design_docs/0024; a single-file
     * torrent resolves to the file itself, with no wrapper folder (design_docs/0065). Guards
     * two cases: two different torrents with the same name (the second gets a "-2" suffix), and
     * the same torrent added again, which the download-path marker recognises as a reuse rather
     * than a collision. Holds directoryResolutionLock so two concurrent adds can't both claim
     * the same free candidate.
     */
    private DirectoryResolution resolveDownloadDirectory(TorrentMetadata metadata) {
        InfoHash infoHash = metadata.infoHash();
        Path configTorrentDirectory = configTorrentDirectory(infoHash);
        // The whole check-claim-record sequence holds the lock: two concurrent adds of the same new
        // torrent must not each claim a directory, with the marker naming one and the session the
        // other. Not a hot path - only ever an add. See design_docs/0092.
        synchronized (directoryResolutionLock) {
            Path alreadyResolved = readDownloadPathMarker(configTorrentDirectory);
            if (alreadyResolved != null) {
                return new DirectoryResolution(alreadyResolved, true);
            }
            Path resolved = claimDownloadPath(metadata);
            try {
                Files.createDirectories(configTorrentDirectory);
            } catch (IOException e) {
                throw new TorrentEngineIoException("Could not create config directory " + configTorrentDirectory + ": "
                        + e.getMessage(), e);
            }
            writeDownloadPathMarker(configTorrentDirectory, resolved);
            return new DirectoryResolution(resolved, false);
        }
    }

    /** Caller holds directoryResolutionLock. */
    private Path claimDownloadPath(TorrentMetadata metadata) {
        if (metadata instanceof SingleFileTorrent single && !Files.exists(baseDownloadDirectory.resolve(single.name()))) {
            // Common case: no name collision - claim the flat file path directly (matching
            // other clients' placement), by creating an empty placeholder while still
            // holding the lock, same "claim by creating something on disk" idiom the
            // directory branch below uses. TorrentStorage.create()'s own
            // baseDirectory.resolve(single.name()) reopens and preallocates it.
            try {
                Files.createDirectories(baseDownloadDirectory);
                Files.createFile(baseDownloadDirectory.resolve(single.name()));
            } catch (IOException e) {
                throw new TorrentEngineIoException(
                        "Could not claim download file " + single.name() + ": " + e.getMessage(), e);
            }
            return baseDownloadDirectory;
        }
        // Multi-file torrents, or a single-file torrent whose bare name collided with
        // something already there, each get their own disambiguated subdirectory -
        // same suffix scheme as before.
        String safeName = sanitizeDirectoryName(metadata.name());
        Path candidate = baseDownloadDirectory.resolve(safeName);
        int suffix = 2;
        while (Files.exists(candidate)) {
            candidate = baseDownloadDirectory.resolve(safeName + "-" + suffix);
            suffix++;
        }
        try {
            Files.createDirectories(candidate);
        } catch (IOException e) {
            throw new TorrentEngineIoException(
                    "Could not prepare download directory " + candidate + ": " + e.getMessage(), e);
        }
        return candidate;
    }

    /** Replaces filesystem-unsafe characters (path separators on any OS, plus Windows'
     * reserved set, since downloads may later be accessed from a mounted/shared volume).
     * Package-private for direct unit testing, same rationale as selectTrackerTiers. */
    static String sanitizeDirectoryName(String name) {
        String sanitized = name.replaceAll("[/\\\\:*?\"<>|\\x00]", "_").strip();
        if (sanitized.isEmpty() || sanitized.equals(".") || sanitized.equals("..")) {
            return "torrent";
        }
        return sanitized;
    }

    /** Wires up TRACKER_UNREACHABLE/TRACKER_RECOVERED reporting (design_docs/0055's own
     * addendum; engine-wide per tracker URL, not per torrent, since 2026-09-21) - only for a torrent's own persistent tracker client (addTorrent()/
     * restoreOne()). The listener-less overload below is for the throwaway tracker client used
     * to probe trackers during magnet metadata resolution, which isn't a tracked torrent yet
     * and shouldn't generate library events off its own retry churn. */
    private TrackerClient createTrackerClient(TorrentMetadata metadata) {
        return createTrackerClient(selectTrackerTiers(metadata), trackerStatusListenerFor(metadata));
    }

    /** Package-private (not private) so tests can invoke a tracker-status transition directly,
     * without needing to drive a real TorrentSession through two full reannounce cycles against
     * a fake tracker server - same test-visibility rationale as selectTrackerTiers/
     * sanitizeDirectoryName above. */
    TrackerStatusListener trackerStatusListenerFor(TorrentMetadata metadata) {
        String infoHash = metadata.infoHash().hex();
        return new TrackerStatusListener() {
            @Override
            public void onTrackerUnreachable(String url, String lastError) {
                if (trackerReachability.markUnreachable(url, infoHash)) {
                    String message = "Tracker unreachable: " + url
                            + (lastError != null ? " (" + lastError + ")" : "");
                    eventStore.record(new LibraryEvent(Instant.now(), EventType.TRACKER_UNREACHABLE, null, null, message));
                }
            }

            @Override
            public void onTrackerRecovered(String url) {
                if (trackerReachability.markRecovered(url)) {
                    eventStore.record(new LibraryEvent(
                            Instant.now(), EventType.TRACKER_RECOVERED, null, null, "Tracker recovered: " + url));
                }
            }
        };
    }

    private TrackerClient createTrackerClient(List<List<String>> tierUrls) {
        return createTrackerClient(tierUrls, null);
    }

    private TrackerClient createTrackerClient(List<List<String>> tierUrls, TrackerStatusListener listener) {
        if (tierUrls.isEmpty()) {
            return new NoOpTrackerClient();
        }
        List<List<TrackerClient>> tiers = new ArrayList<>(tierUrls.size());
        for (int tier = 0; tier < tierUrls.size(); tier++) {
            int tierIndex = tier;
            tiers.add(tierUrls.get(tier).stream()
                    .<TrackerClient>map(url -> listener == null
                            ? new TrackedTrackerClient(url, tierIndex, createSingleTrackerClient(url))
                            : new TrackedTrackerClient(url, tierIndex, createSingleTrackerClient(url), listener))
                    .toList());
        }
        return new MultiTrackerClient(tiers);
    }

    /** Both tracker kinds are handed the live proxy config, so they tunnel through the SOCKS5
     * proxy whenever one is active - HTTP(S) via a CONNECT tunnel, UDP via the proxy's UDP relay.
     * See design_docs/0079. */
    private TrackerClient createSingleTrackerClient(String url) {
        return url.startsWith("udp://")
                ? new UdpTrackerClient(url, proxyConfig)
                : new HttpTrackerClient(url, proxyConfig);
    }

    /**
     * Prefers "announce-list" (BEP 12) when present - a spec-compliant torrent file repeats the
     * classic "announce" inside it, so using both would announce to the same tracker twice. Falls
     * back to "announce" alone only when announce-list is absent (legacy single-tracker
     * torrents). Each tier is filtered down to HTTP(S)/UDP URLs, and tiers left empty are
     * dropped. See design_docs/0022 and design_docs/0023.
     *
     * <p>An empty result means the torrent finds peers through DHT alone; createTrackerClient()
     * turns it into a NoOpTrackerClient (design_docs/0028). That covers a torrent with no
     * trackers at all (a trackerless magnet's synthesized bytes) and, since design_docs/0099, one
     * whose trackers are all of kinds this client can't use (wss://, say). The exception is a
     * private torrent: BEP 27 forbids it DHT, so with no usable tracker it could never find a
     * peer, and adding it fails instead.
     *
     * <p>Package-private so it can be unit-tested without a tracker server.
     */
    static List<List<String>> selectTrackerTiers(TorrentMetadata metadata) {
        List<List<String>> tierUrls = !metadata.announceList().isEmpty()
                ? metadata.announceList()
                : metadata.announce() != null ? List.of(List.of(metadata.announce())) : List.of();

        if (tierUrls.isEmpty()) {
            return List.of();
        }

        List<List<String>> usableTiers = tierUrls.stream()
                .map(tier -> tier.stream().filter(TorrentEngine::isSupportedTrackerUrl).toList())
                .filter(tier -> !tier.isEmpty())
                .toList();

        if (usableTiers.isEmpty() && metadata.isPrivate()) {
            throw new TorrentEngineException("No usable tracker found for this private torrent - only HTTP(S) and UDP"
                    + " trackers are supported, and a private torrent can't use DHT instead");
        }
        if (usableTiers.isEmpty()) {
            LOG.log(System.Logger.Level.INFO, "No supported tracker for " + metadata.infoHash()
                    + " (only HTTP(S) and UDP are) - using DHT alone");
        }
        return usableTiers;
    }

    private static boolean isSupportedTrackerUrl(String url) {
        return url.startsWith("http://") || url.startsWith("https://") || url.startsWith("udp://");
    }
}
