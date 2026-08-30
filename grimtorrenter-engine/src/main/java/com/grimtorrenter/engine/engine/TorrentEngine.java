package com.grimtorrenter.engine.engine;

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
import com.grimtorrenter.engine.magnet.MagnetLink;
import com.grimtorrenter.engine.metadata.MetadataFetcher;
import com.grimtorrenter.engine.metainfo.InfoHash;
import com.grimtorrenter.engine.metainfo.MetainfoParser;
import com.grimtorrenter.engine.metainfo.TorrentMetadata;
import com.grimtorrenter.engine.mse.EncryptionMode;
import com.grimtorrenter.engine.peer.IncomingConnectionHandler;
import com.grimtorrenter.engine.peer.PeerServer;
import com.grimtorrenter.engine.ratelimit.RateLimiters;
import com.grimtorrenter.engine.settings.InMemorySettingsStore;
import com.grimtorrenter.engine.settings.Settings;
import com.grimtorrenter.engine.settings.SettingsStore;
import com.grimtorrenter.engine.storage.FileHandlePool;
import com.grimtorrenter.engine.torrent.SeedingLimitOverride;
import com.grimtorrenter.engine.torrent.SeedingLimits;
import com.grimtorrenter.engine.torrent.TorrentSession;
import com.grimtorrenter.engine.torrent.TorrentSessionListener;
import com.grimtorrenter.engine.torrent.TorrentState;
import com.grimtorrenter.engine.tracker.HttpTrackerClient;
import com.grimtorrenter.engine.tracker.MultiTrackerClient;
import com.grimtorrenter.engine.tracker.NoOpTrackerClient;
import com.grimtorrenter.engine.tracker.PeerAddress;
import com.grimtorrenter.engine.tracker.PeerId;
import com.grimtorrenter.engine.tracker.TrackedTrackerClient;
import com.grimtorrenter.engine.tracker.TrackerClient;
import com.grimtorrenter.engine.tracker.TrackerEvent;
import com.grimtorrenter.engine.tracker.TrackerRequest;
import com.grimtorrenter.engine.tracker.TrackerResponse;
import com.grimtorrenter.engine.tracker.UdpTrackerClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * The facade grimtorrenter-app talks to: manages the set of active
 * TorrentSessions (add/remove/pause/resume). See design_docs/0018 for the
 * design calls made here (peer id/download dir ownership, tracker URL
 * selection, why "pause"/"resume" need no new TorrentSession logic).
 */
public final class TorrentEngine {

    private static final System.Logger LOG = System.getLogger(TorrentEngine.class.getName());

    /** Marks a download directory with the info hash it belongs to - lets a later
     * addTorrent call tell "this is the same torrent being re-added" apart from
     * "a different torrent happens to have the same name". See design_docs/0024. */
    private static final String INFO_HASH_MARKER_FILENAME = ".grimtorrenter-infohash";

    /** Holds the original .torrent file's bytes - restore() needs them again to
     * re-parse the metadata after a process restart. Presence of this file (together
     * with INFO_HASH_MARKER_FILENAME) is what marks a directory as restorable. See
     * design_docs/0026. */
    private static final String TORRENT_FILE_MARKER_FILENAME = ".grimtorrenter.torrent";

    /** The desired running/paused state as of the last add/pause/resume call, written
     * through immediately (not just on clean shutdown) so a crash doesn't lose it.
     * See design_docs/0026. */
    private static final String STATE_MARKER_FILENAME = ".grimtorrenter-state";
    private static final String STATE_RUNNING = "RUNNING";
    private static final String STATE_STOPPED = "STOPPED";

    /** Persisted per BEP 5's own recommendation - a stable node id means peers who've
     * bucketed us stay valid across restarts. See design_docs/0028. */
    private static final String DHT_NODE_ID_MARKER_FILENAME = ".grimtorrenter-dht-node-id";

    /** This torrent's SeedingLimitOverride, plain key=value lines (grimtorrenter-engine has
     * zero production dependencies, no JSON library available at this layer - same reasoning
     * as every other marker file here). Deliberately never deleted by removeTorrent(infoHash,
     * false) (keep files) - a torrent-directory-scoped preference like this stays with the
     * data, same as the data itself does. See design_docs/0054. */
    private static final String SEEDING_LIMIT_OVERRIDE_MARKER_FILENAME = ".grimtorrenter-seeding-limit-override";
    private static final long SEEDING_LIMIT_CHECK_INTERVAL_SECONDS = 30;

    /** When this torrent was added, ISO-8601 instant text - for the details panel's "Added"
     * fact (design_docs/0032). Absent on a directory added before this field existed; no
     * migration/backfill for those (see readAddedAtMarker()) - "unknown" is a real, permanent
     * state for them, not something to guess at. */
    private static final String ADDED_AT_MARKER_FILENAME = ".grimtorrenter-added-at";

    /** See design_docs/0056. */
    private static final String WATCH_ADDED_SUBDIRECTORY = "added";
    private static final String WATCH_FAILED_SUBDIRECTORY = "failed";
    private static final String TORRENT_FILE_EXTENSION = ".torrent";
    private static final String WATCH_FOLDER_SOURCE = "watch folder";
    private static final long WATCH_FOLDER_SCAN_INTERVAL_SECONDS = 30;

    private static final int MAGNET_NUM_WANT = 50;
    /** Tried sequentially, not concurrently - see design_docs/0028 for the accepted
     * worst-case-latency trade-off this implies. */
    private static final int MAX_METADATA_FETCH_PEER_ATTEMPTS = 8;
    private static final Duration DHT_QUERY_TIMEOUT = Duration.ofSeconds(5);

    private final Path baseDownloadDirectory;
    private final PeerId ourPeerId;
    private final int ourListenPort;
    private final TorrentSessionListener listener;
    private final Map<InfoHash, TorrentSession> sessions = new ConcurrentHashMap<>();
    private final Map<InfoHash, Path> directories = new ConcurrentHashMap<>();
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
    /** Shared across every TorrentSession/PeerConnection this engine creates - see
     * design_docs/0042. Never null; the lower-arity constructors default to an unlimited,
     * engine-private SettingsStore so every pre-existing caller/test is unaffected. */
    private final RateLimiters rateLimiters;
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
    /** Read live from settingsStore on every connection attempt/accept, not snapshotted
     * here - see EncryptionMode's own Javadoc for why. See design_docs/0052. */
    private final Supplier<EncryptionMode> encryptionMode;
    /** Kept as a field (not just a constructor-local capture) since checkSeedingLimits()
     * reads it fresh on every scheduled tick, not from a single lambda built once at
     * construction. See design_docs/0054. */
    private final SettingsStore settingsStore;
    /** Records library-management events (torrent added/completed/errored/removed, an
     * auto-pause from a reached seeding limit) - see design_docs/0055. Never null; the
     * lower-arity constructors default to a private, unpersisted InMemoryEventStore so every
     * pre-existing caller/test is unaffected. */
    private final EventStore eventStore;
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
    /** Ticks every SEEDING_LIMIT_CHECK_INTERVAL_SECONDS/WATCH_FOLDER_SCAN_INTERVAL_SECONDS,
     * running checkSeedingLimits() and scanWatchFolder() - two cheap, independent periodic
     * engine-maintenance concerns sharing one thread rather than each getting its own
     * (originally named seedingLimitScheduler, generalized when the watch folder needed a
     * second periodic task - design_docs/0056). checkSeedingLimits() checks every
     * currently-SEEDING session against its effective seeding limit and reuses pauseTorrent()
     * outright for the actual stop - see that method's own Javadoc for why reusing that
     * method, not calling TorrentSession.stop() directly, was the deliberate choice there.
     * Daemon threads, unlike TorrentSession's own per-session scheduler - this one runs
     * unconditionally for every engine instance (no opt-in flag, matching RateLimiters),
     * including the many short-lived test-constructed engines that never call shutdown(); a
     * non-daemon thread here would leak a live, never-reclaimed thread per such instance. See
     * design_docs/0054. */
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
     * Settings.watchFolderEnabled rather than by construction.
     */
    public TorrentEngine(Path baseDownloadDirectory, int ourListenPort, TorrentSessionListener listener,
                          boolean enableDht, boolean acceptIncomingConnections, SettingsStore settingsStore,
                          FileHandlePool fileHandlePool, int maxConcurrentPieceVerifications, EventStore eventStore,
                          Path watchDirectory) {
        this.baseDownloadDirectory = baseDownloadDirectory;
        this.ourListenPort = ourListenPort;
        this.listener = listener;
        this.ourPeerId = PeerId.generate();
        this.dhtNode = enableDht ? createDhtNode(baseDownloadDirectory, ourListenPort, eventStore) : null;
        this.dhtBindFailed = enableDht && this.dhtNode == null;
        this.encryptionMode = () -> settingsStore.current().encryptionMode();
        this.peerServer = acceptIncomingConnections ? createPeerServer(ourListenPort, eventStore) : null;
        this.peerServerBindFailed = acceptIncomingConnections && this.peerServer == null;
        this.rateLimiters = RateLimiters.from(settingsStore);
        this.fileHandlePool = fileHandlePool;
        this.pieceVerificationLimiter = new Semaphore(maxConcurrentPieceVerifications);
        this.settingsStore = settingsStore;
        this.eventStore = eventStore;
        this.watchDirectory = watchDirectory;
        this.maintenanceScheduler.scheduleWithFixedDelay(this::checkSeedingLimits,
                SEEDING_LIMIT_CHECK_INTERVAL_SECONDS, SEEDING_LIMIT_CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS);
        this.maintenanceScheduler.scheduleWithFixedDelay(this::scanWatchFolder,
                WATCH_FOLDER_SCAN_INTERVAL_SECONDS, WATCH_FOLDER_SCAN_INTERVAL_SECONDS, TimeUnit.SECONDS);
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

    /**
     * Auto-adds any stable .torrent file dropped into watchDirectory, moving it to added/ on
     * success or failed/ on failure - see design_docs/0056 for the full design. A no-op (does
     * not even touch the filesystem) unless Settings.watchFolderEnabled is true, checked fresh
     * on every tick, same live-toggle spirit as the rate limits.
     *
     * <p>Package-private, not private, purely so a test can call this directly rather than
     * waiting on the real WATCH_FOLDER_SCAN_INTERVAL_SECONDS-second scheduler tick - same
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
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(TORRENT_FILE_EXTENSION))
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
     * an event, the outcome of the subsequent housekeeping move is not. See design_docs/0056. */
    private void processWatchedFile(Path file, Path addedDir, Path failedDir) {
        String filename = file.getFileName().toString();
        boolean added;
        try {
            byte[] torrentFileBytes = Files.readAllBytes(file);
            addTorrent(torrentFileBytes, WATCH_FOLDER_SOURCE);
            added = true;
        } catch (IOException | RuntimeException e) {
            added = false;
            LOG.log(System.Logger.Level.WARNING, "Watch folder could not add " + filename, e);
            eventStore.record(new LibraryEvent(Instant.now(), EventType.ERROR, null, null,
                    "Watch folder: could not add " + filename + " (" + e.getMessage() + ")"));
        }
        try {
            moveWithCollisionSuffix(file, added ? addedDir : failedDir);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING,
                    "Could not move watch-folder file " + filename + " to " + (added ? addedDir : failedDir), e);
        }
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

    /** The one place that bridges PeerServer's generic, TorrentSession-unaware lookup
     * (see IncomingConnectionHandler's own Javadoc on why it stays that way) to this
     * engine's actual session map. */
    private Optional<IncomingConnectionHandler> findIncomingConnectionHandler(InfoHash infoHash) {
        return Optional.ofNullable(sessions.get(infoHash)).map(session -> session::acceptIncomingConnection);
    }

    /** DHT reuses ourListenPort for its UDP socket, same as the TCP peer-wire listen port
     * (confirmed with the user - matches what real clients do and what the peerwire Port
     * message already implies). Bootstrapping the routing table is real network I/O
     * against an unknown number of hosts, so it runs on its own background thread rather
     * than delaying this constructor. */
    private static DhtNode createDhtNode(Path baseDownloadDirectory, int ourListenPort, EventStore eventStore) {
        try {
            NodeId nodeId = loadOrGenerateDhtNodeId(baseDownloadDirectory);
            DhtNode node = new DhtNode(nodeId, ourListenPort);
            Thread.ofVirtual().start(node::bootstrap);
            return node;
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not start DHT node - continuing without it", e);
            eventStore.record(new LibraryEvent(Instant.now(), EventType.DHT_UNAVAILABLE, null, null, null));
            return null;
        }
    }

    /** Falls back to a freshly generated (and re-persisted) id if the marker is missing
     * or unreadable, rather than failing DHT startup over a corrupt marker file. */
    private static NodeId loadOrGenerateDhtNodeId(Path baseDownloadDirectory) {
        Path marker = baseDownloadDirectory.resolve(DHT_NODE_ID_MARKER_FILENAME);
        if (Files.exists(marker)) {
            try {
                return new NodeId(Files.readString(marker).strip());
            } catch (IOException | IllegalArgumentException e) {
                LOG.log(System.Logger.Level.WARNING, "Could not read persisted DHT node id, generating a new one", e);
            }
        }
        NodeId generated = NodeId.random();
        try {
            Files.createDirectories(baseDownloadDirectory);
            Files.writeString(marker, generated.hex());
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not persist DHT node id - it will regenerate next restart", e);
        }
        return generated;
    }

    /** alreadyExisted lets a caller (e.g. the REST layer) tell "just added" apart from
     * "you already had this" - both return the same session, but they mean different
     * things to whoever's asking. */
    public record AddTorrentResult(TorrentSession session, boolean alreadyExisted) {
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
     * status stays on the torrent itself, not here. See design_docs/0059. */
    public enum ServiceState {
        RUNNING, DISABLED, FAILED
    }

    /** name is a stable identifier ("dht"/"peerServer"), matched by name against a frontend
     * display map - same closed-set-mapped-by-key shape EventType's own frontend map already
     * uses. See design_docs/0059. */
    public record ServiceStatus(String name, ServiceState state) {
    }

    /** DHT and the peer server only bind once, at construction - no retry - so FAILED is
     * stable for the whole process lifetime; a caller doesn't need to poll this expecting a
     * RUNNING->FAILED or FAILED->RUNNING transition mid-process. See design_docs/0059. */
    public List<ServiceStatus> serviceStatuses() {
        return List.of(
                new ServiceStatus("dht", serviceState(dhtNode != null, dhtBindFailed)),
                new ServiceStatus("peerServer", serviceState(peerServer != null, peerServerBindFailed)));
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
     * "watch folder" (scanWatchFolder()) - folded into the resulting ADDED library event's
     * message ("Added via watch folder") so it's distinguishable from a direct upload, without
     * adding a new parameter to every existing caller. See design_docs/0055/0056. */
    AddTorrentResult addTorrent(byte[] torrentFileBytes, String source) throws IOException {
        TorrentMetadata metadata = MetainfoParser.parse(torrentFileBytes);
        TrackerClient trackerClient = createTrackerClient(metadata);
        InfoHash infoHash = metadata.infoHash();
        DirectoryResolution resolution = resolveDownloadDirectory(metadata);
        Path torrentDirectory = resolution.directory();

        AtomicReference<IOException> creationFailure = new AtomicReference<>();
        AtomicBoolean wasNewlyCreated = new AtomicBoolean(false);
        TorrentSession session = sessions.computeIfAbsent(infoHash, key -> {
            wasNewlyCreated.set(true);
            try {
                // A directory this same info hash already claimed - e.g. a torrent removed
                // with "keep files" and now being re-added - may already hold a real,
                // possibly-complete download on disk. restoreAsync() re-verifies it in the
                // background (same path a process restart already uses) instead of
                // create()'s always-fresh, everything-NEEDED PieceManager, which would
                // otherwise silently re-download data that's already correct. See
                // design_docs/0037.
                //
                // readSeedingLimitOverrideMarker() also covers that same reused-directory
                // case: removeTorrent(infoHash, false) (keep files) deliberately never deletes
                // this marker (design_docs/0054), so a torrent re-added after that picks its
                // previously-set override back up rather than silently reverting to the
                // global default; a genuinely new directory just gets INHERIT (no marker
                // exists yet).
                SeedingLimitOverride seedingLimitOverride = readSeedingLimitOverrideMarker(torrentDirectory);
                Instant addedAt = Instant.now();
                TorrentSession created = resolution.preExisting()
                        ? TorrentSession.restoreAsync(metadata, trackerClient, torrentDirectory,
                                ourPeerId, ourListenPort, listener, dhtNode, rateLimiters, fileHandlePool,
                                pieceVerificationLimiter, encryptionMode, seedingLimitOverride, addedAt, true)
                        : TorrentSession.create(metadata, trackerClient, torrentDirectory, ourPeerId,
                                ourListenPort, listener, dhtNode, rateLimiters, fileHandlePool,
                                pieceVerificationLimiter, encryptionMode, seedingLimitOverride, addedAt);
                if (!resolution.preExisting()) {
                    created.start();
                }
                writeTorrentFileMarker(torrentDirectory, torrentFileBytes);
                writeStateMarker(torrentDirectory, STATE_RUNNING);
                writeAddedAtMarker(torrentDirectory, addedAt);
                directories.put(infoHash, torrentDirectory);
                seedFromDhtIfTrackerless(created, trackerClient, infoHash);
                return created;
            } catch (IOException e) {
                creationFailure.set(e);
                return null;
            }
        });
        if (creationFailure.get() != null) {
            throw creationFailure.get();
        }
        if (wasNewlyCreated.get()) {
            String message = source != null ? "Added via " + source : null;
            eventStore.record(new LibraryEvent(
                    Instant.now(), EventType.ADDED, infoHash.hex(), metadata.name(), message));
        }
        return new AddTorrentResult(session, !wasNewlyCreated.get());
    }

    /**
     * A torrent with no trackers at all - a trackerless magnet resolved via DHT, or a
     * plain .torrent upload that genuinely lists none - has no other way to find peers.
     * Runs a background DHT peer lookup and feeds whatever it finds straight into the new
     * session. A no-op if DHT is unavailable this process, or the torrent does have a real
     * tracker (trackerClient is only ever a NoOpTrackerClient in the no-trackers case -
     * see createTrackerClient). See design_docs/0028.
     */
    private void seedFromDhtIfTrackerless(TorrentSession session, TrackerClient trackerClient, InfoHash infoHash) {
        if (dhtNode == null || !(trackerClient instanceof NoOpTrackerClient)) {
            return;
        }
        Thread.ofVirtual().start(() -> {
            try {
                List<PeerAddress> peers = dhtNode.findPeers(infoHash, ourListenPort, false, DHT_QUERY_TIMEOUT);
                session.addKnownPeers(peers);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.DEBUG, "DHT peer lookup failed for " + infoHash, e);
            }
        });
    }

    /**
     * Starts resolving a magnet link's metadata from peers (BEP 9) and, once verified,
     * hands off into the same addTorrent pipeline every other torrent uses. Peers to fetch
     * from come from whichever of the magnet's embedded trackers respond if it has any,
     * or (since design_docs/0028's DHT slice) a DHT get_peers lookup for a trackerless
     * one - only actually trackerless AND DHT unavailable fails synchronously here, since
     * that's the one case with no possible path to peers at all.
     *
     * <p>Everything past that check runs on a background virtual thread: there's real
     * network I/O against an unknown number of peers before this torrent is known well
     * enough to show anything for it. A total failure (no peers reachable, or none of the
     * peers tried had the metadata) is only logged, not surfaced to the UI - the same
     * accepted add-to-visible latency gap as a regular upload, not a new one.
     */
    public void addMagnet(MagnetLink magnet) {
        List<String> trackerUrls = magnet.trackers().stream().filter(TorrentEngine::isSupportedTrackerUrl).toList();
        if (!trackerUrls.isEmpty()) {
            Thread.ofVirtual().start(() -> fetchMagnetMetadataViaTrackerThenAdd(magnet, trackerUrls));
            return;
        }
        if (dhtNode == null) {
            throw new TorrentEngineException(
                    "Magnet link has no usable tracker, and DHT is unavailable - trackerless magnets need DHT");
        }
        Thread.ofVirtual().start(() -> fetchMagnetMetadataViaDhtThenAdd(magnet));
    }

    private void fetchMagnetMetadataViaTrackerThenAdd(MagnetLink magnet, List<String> trackerUrls) {
        TrackerClient trackerClient = createTrackerClient(List.of(trackerUrls));
        TrackerResponse response;
        try {
            response = trackerClient.announce(new TrackerRequest(magnet.infoHash(), ourPeerId, ourListenPort,
                    0, 0, Long.MAX_VALUE, TrackerEvent.STARTED, MAGNET_NUM_WANT));
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not announce for magnet " + magnet.infoHash(), e);
            return;
        }
        List<PeerAddress> candidates = response.peers().stream().limit(MAX_METADATA_FETCH_PEER_ATTEMPTS).toList();
        fetchMetadataFromCandidatesThenAdd(magnet, candidates, trackerUrls);
    }

    private void fetchMagnetMetadataViaDhtThenAdd(MagnetLink magnet) {
        List<PeerAddress> peers;
        try {
            peers = dhtNode.findPeers(magnet.infoHash(), ourListenPort, false, DHT_QUERY_TIMEOUT);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "DHT peer lookup failed for magnet " + magnet.infoHash(), e);
            return;
        }
        List<PeerAddress> candidates = peers.stream().limit(MAX_METADATA_FETCH_PEER_ATTEMPTS).toList();
        // A trackerless magnet has no announce-list to give the resulting torrent - it
        // relies on addTorrent's own automatic DHT peer-seeding (seedFromDhtIfTrackerless)
        // from here on, same as it relied on DHT to find this first batch of peers.
        fetchMetadataFromCandidatesThenAdd(magnet, candidates, List.of());
    }

    /** Shared by both magnet metadata-fetch paths above: tries each candidate
     * sequentially, stopping at the first success - see design_docs/0028 for the accepted
     * worst-case-latency trade-off this implies. trackerUrls becomes the resulting
     * torrent's announce-list (empty for the DHT path). */
    private void fetchMetadataFromCandidatesThenAdd(
            MagnetLink magnet, List<PeerAddress> candidates, List<String> trackerUrls) {
        for (PeerAddress address : candidates) {
            byte[] infoDictBytes;
            try {
                infoDictBytes = MetadataFetcher.fetch(address, magnet.infoHash(), ourPeerId);
            } catch (IOException | RuntimeException e) {
                LOG.log(System.Logger.Level.DEBUG,
                        "Metadata fetch from " + address + " failed for magnet " + magnet.infoHash(), e);
                continue;
            }
            try {
                addTorrent(synthesizeTorrentFileBytes(infoDictBytes, trackerUrls));
            } catch (IOException | RuntimeException e) {
                // The problem is on our side (storage, directory creation, ...), not this
                // peer's - trying another peer for the same doomed outcome wouldn't help.
                LOG.log(System.Logger.Level.WARNING,
                        "Fetched metadata for magnet " + magnet.infoHash() + " but could not add the torrent", e);
            }
            return;
        }
        LOG.log(System.Logger.Level.WARNING, "Could not fetch metadata for magnet " + magnet.infoHash()
                + " from any of " + candidates.size() + " peer(s) tried");
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
     * Scans baseDownloadDirectory for torrents added in a previous run of this
     * process (directories carrying both INFO_HASH_MARKER_FILENAME and
     * TORRENT_FILE_MARKER_FILENAME) and re-registers each one. See
     * design_docs/0026: every restored session is registered - and therefore
     * visible to callers - immediately in TorrentSession's VERIFYING state,
     * before its on-disk data has actually been re-hashed; the re-hash and any
     * resulting auto-start happen in the background. A directory that fails to
     * restore (corrupt .torrent bytes, no usable tracker, ...) is logged and
     * skipped rather than aborting the rest of the scan.
     */
    public void restore() {
        if (!Files.isDirectory(baseDownloadDirectory)) {
            return;
        }
        try (var entries = Files.list(baseDownloadDirectory)) {
            for (Path directory : entries.filter(Files::isDirectory).toList()) {
                restoreOne(directory);
            }
        } catch (IOException e) {
            throw new TorrentEngineException(
                    "Could not scan download directory " + baseDownloadDirectory + ": " + e.getMessage());
        }
    }

    private void restoreOne(Path directory) {
        Path torrentFileMarker = directory.resolve(TORRENT_FILE_MARKER_FILENAME);
        if (!Files.exists(torrentFileMarker)) {
            return;
        }
        try {
            byte[] torrentFileBytes = Files.readAllBytes(torrentFileMarker);
            TorrentMetadata metadata = MetainfoParser.parse(torrentFileBytes);
            TrackerClient trackerClient = createTrackerClient(metadata);
            boolean running = !STATE_STOPPED.equals(readStateMarker(directory));
            SeedingLimitOverride seedingLimitOverride = readSeedingLimitOverrideMarker(directory);
            Instant addedAt = readAddedAtMarker(directory);
            TorrentSession session = TorrentSession.restoreAsync(
                    metadata, trackerClient, directory, ourPeerId, ourListenPort, listener, dhtNode,
                    rateLimiters, fileHandlePool, pieceVerificationLimiter, encryptionMode, seedingLimitOverride,
                    addedAt, running);
            sessions.put(metadata.infoHash(), session);
            directories.put(metadata.infoHash(), directory);
            seedFromDhtIfTrackerless(session, trackerClient, metadata.infoHash());
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not restore torrent from " + directory, e);
        }
    }

    /** Stops and forgets the session, and deletes its persisted resume record (so it
     * doesn't reappear on the next restore()) - but never deletes already-downloaded
     * files. Use removeTorrent(infoHash, true) to delete those too. */
    public void removeTorrent(InfoHash infoHash) {
        removeTorrent(infoHash, false);
    }

    public void removeTorrent(InfoHash infoHash, boolean deleteData) {
        TorrentSession session = sessions.remove(infoHash);
        if (session != null) {
            session.close();
            eventStore.record(new LibraryEvent(
                    Instant.now(), EventType.REMOVED, infoHash.hex(), session.metadata().name(), null));
        }
        Path directory = directories.remove(infoHash);
        if (directory == null) {
            return;
        }
        if (deleteData) {
            deleteRecursively(directory);
        } else {
            deleteIfExists(directory.resolve(TORRENT_FILE_MARKER_FILENAME));
            deleteIfExists(directory.resolve(STATE_MARKER_FILENAME));
        }
    }

    /**
     * Stops the session but keeps it registered - its in-memory piece
     * state survives, so resumeTorrent picks up where it left off without
     * re-downloading anything, for as long as this TorrentEngine instance
     * (i.e. the running process) stays alive. See design_docs/0018.
     */
    public void pauseTorrent(InfoHash infoHash) {
        TorrentSession session = sessions.get(infoHash);
        if (session != null) {
            session.stop();
            writeStateMarker(directories.get(infoHash), STATE_STOPPED);
        }
    }

    public void resumeTorrent(InfoHash infoHash) {
        TorrentSession session = sessions.get(infoHash);
        if (session != null) {
            session.start();
            writeStateMarker(directories.get(infoHash), STATE_RUNNING);
        }
    }

    public Optional<TorrentSession> getTorrent(InfoHash infoHash) {
        return Optional.ofNullable(sessions.get(infoHash));
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
            writeSeedingLimitOverrideMarker(directories.get(infoHash), override);
            session.setSeedingLimitOverride(override);
        }
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

    /** Stops every managed session - for graceful process shutdown. Deliberately does not
     * touch any persisted state marker: whatever running/stopped state was last recorded
     * stays as the desired state for the next restore(), regardless of why the process
     * is exiting. */
    public void shutdown() {
        maintenanceScheduler.shutdownNow();
        for (TorrentSession session : sessions.values()) {
            session.close();
        }
        sessions.clear();
        if (peerServer != null) {
            peerServer.close();
        }
        if (dhtNode != null) {
            dhtNode.close();
        }
    }

    private static void writeTorrentFileMarker(Path directory, byte[] torrentFileBytes) throws IOException {
        Files.write(directory.resolve(TORRENT_FILE_MARKER_FILENAME), torrentFileBytes);
    }

    /** No-ops if directory is null - a session with no known directory (shouldn't normally
     * happen, sessions and directories are always populated together) has no marker to write. */
    private static void writeStateMarker(Path directory, String state) {
        if (directory == null) {
            return;
        }
        try {
            Files.writeString(directory.resolve(STATE_MARKER_FILENAME), state);
        } catch (IOException e) {
            throw new TorrentEngineException("Could not persist state to " + directory + ": " + e.getMessage());
        }
    }

    private static String readStateMarker(Path directory) throws IOException {
        Path stateMarker = directory.resolve(STATE_MARKER_FILENAME);
        return Files.exists(stateMarker) ? Files.readString(stateMarker).strip() : STATE_RUNNING;
    }

    /** No-ops if directory is null - see writeStateMarker's own comment for why that's
     * possible in principle. */
    private static void writeSeedingLimitOverrideMarker(Path directory, SeedingLimitOverride override) {
        if (directory == null) {
            return;
        }
        try {
            Files.writeString(directory.resolve(SEEDING_LIMIT_OVERRIDE_MARKER_FILENAME),
                    "ratioLimit=" + override.ratioLimit() + "\ntimeLimitMinutes=" + override.timeLimitMinutes() + "\n");
        } catch (IOException e) {
            throw new TorrentEngineException(
                    "Could not persist seeding limit override to " + directory + ": " + e.getMessage());
        }
    }

    /** No-ops if directory is null - see writeStateMarker's own comment for why that's
     * possible in principle. */
    private static void writeAddedAtMarker(Path directory, Instant addedAt) {
        if (directory == null) {
            return;
        }
        try {
            Files.writeString(directory.resolve(ADDED_AT_MARKER_FILENAME), addedAt.toString());
        } catch (IOException e) {
            throw new TorrentEngineException(
                    "Could not persist added-at timestamp to " + directory + ": " + e.getMessage());
        }
    }

    /** Null when the marker is absent (every directory added before this field existed) -
     * see ADDED_AT_MARKER_FILENAME's own comment on why that's left unknown rather than
     * backfilled. A corrupt/unparseable marker (hand-edited, truncated by a crash mid-write)
     * is treated the same way rather than failing the whole restore over one cosmetic fact. */
    private static Instant readAddedAtMarker(Path directory) {
        Path marker = directory.resolve(ADDED_AT_MARKER_FILENAME);
        if (!Files.exists(marker)) {
            return null;
        }
        try {
            return Instant.parse(Files.readString(marker).strip());
        } catch (IOException | DateTimeParseException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not read added-at marker in " + directory, e);
            return null;
        }
    }

    /** Absent marker (every pre-existing torrent directory, and any brand-new one) means
     * "inherit both metrics from the global default" - no migration needed. See
     * design_docs/0054. */
    private static SeedingLimitOverride readSeedingLimitOverrideMarker(Path directory) throws IOException {
        Path marker = directory.resolve(SEEDING_LIMIT_OVERRIDE_MARKER_FILENAME);
        if (!Files.exists(marker)) {
            return SeedingLimitOverride.INHERIT;
        }
        double ratioLimit = SeedingLimitOverride.INHERIT.ratioLimit();
        long timeLimitMinutes = SeedingLimitOverride.INHERIT.timeLimitMinutes();
        for (String line : Files.readAllLines(marker)) {
            String[] parts = line.split("=", 2);
            if (parts.length != 2) {
                continue;
            }
            switch (parts[0]) {
                case "ratioLimit" -> ratioLimit = Double.parseDouble(parts[1]);
                case "timeLimitMinutes" -> timeLimitMinutes = Long.parseLong(parts[1]);
                default -> {
                    // Unknown line - ignore rather than fail, forward-compatible with a
                    // future field this version doesn't know about yet.
                }
            }
        }
        return new SeedingLimitOverride(ratioLimit, timeLimitMinutes);
    }

    private static void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new TorrentEngineException("Could not delete " + path + ": " + e.getMessage());
        }
    }

    private static void deleteRecursively(Path directory) {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException e) {
            throw new TorrentEngineException("Could not delete " + directory + ": " + e.getMessage());
        }
    }

    /**
     * Prefers the torrent's own declared name for its download directory
     * (matching real clients, and human-browsable) rather than the info
     * hash - see design_docs/0024 for why this replaced the original
     * info-hash-only naming and how it avoids two different failure modes:
     * two different torrents sharing a declared name colliding, and the
     * same torrent being re-added (e.g. after a restart) being mistaken
     * for a collision with itself. Synchronized because two different
     * torrents with the same name being added concurrently could
     * otherwise both see the same name as free.
     */
    /** preExisting is true when candidate already belonged to this exact info hash before
     * this call (a directory reused, not freshly created) - see design_docs/0037 for why
     * addTorrent() needs to know this to decide create() vs restoreAsync(). */
    private record DirectoryResolution(Path directory, boolean preExisting) {
    }

    private enum ClaimResult { OCCUPIED, CREATED, REUSED }

    private DirectoryResolution resolveDownloadDirectory(TorrentMetadata metadata) {
        String safeName = sanitizeDirectoryName(metadata.name());
        synchronized (directoryResolutionLock) {
            Path candidate = baseDownloadDirectory.resolve(safeName);
            int suffix = 2;
            while (true) {
                ClaimResult result = claimDirectory(candidate, metadata.infoHash());
                if (result != ClaimResult.OCCUPIED) {
                    return new DirectoryResolution(candidate, result == ClaimResult.REUSED);
                }
                candidate = baseDownloadDirectory.resolve(safeName + "-" + suffix);
                suffix++;
            }
        }
    }

    /** CREATED - candidate didn't exist, freshly claimed for infoHash (nothing on disk to
     * verify). REUSED - already marked as belonging to this same info hash (may hold a
     * real download on disk - see design_docs/0037). OCCUPIED - claimed by something else,
     * so the caller tries another name. */
    private static ClaimResult claimDirectory(Path candidate, InfoHash infoHash) {
        Path marker = candidate.resolve(INFO_HASH_MARKER_FILENAME);
        try {
            if (Files.exists(marker)) {
                return Files.readString(marker).strip().equals(infoHash.hex()) ? ClaimResult.REUSED : ClaimResult.OCCUPIED;
            }
            if (Files.exists(candidate)) {
                // Exists but unmarked - could be a pre-fix info-hash-named directory, or
                // unrelated content. Treat as occupied rather than risk writing into it.
                return ClaimResult.OCCUPIED;
            }
            Files.createDirectories(candidate);
            Files.writeString(marker, infoHash.hex());
            return ClaimResult.CREATED;
        } catch (IOException e) {
            throw new TorrentEngineException("Could not prepare download directory " + candidate + ": " + e.getMessage());
        }
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

    private static TrackerClient createTrackerClient(TorrentMetadata metadata) {
        return createTrackerClient(selectTrackerTiers(metadata));
    }

    private static TrackerClient createTrackerClient(List<List<String>> tierUrls) {
        if (tierUrls.isEmpty()) {
            return new NoOpTrackerClient();
        }
        List<List<TrackerClient>> tiers = new ArrayList<>(tierUrls.size());
        for (int tier = 0; tier < tierUrls.size(); tier++) {
            int tierIndex = tier;
            tiers.add(tierUrls.get(tier).stream()
                    .<TrackerClient>map(url -> new TrackedTrackerClient(url, tierIndex, createSingleTrackerClient(url)))
                    .toList());
        }
        return new MultiTrackerClient(tiers);
    }

    private static TrackerClient createSingleTrackerClient(String url) {
        return url.startsWith("udp://") ? new UdpTrackerClient(url) : new HttpTrackerClient(url);
    }

    /**
     * Prefers "announce-list" (BEP 12) when present - a spec-compliant
     * torrent file already includes the classic "announce" field
     * redundantly as part of it, so using both would just mean announcing
     * to the same tracker twice. Falls back to "announce" alone only when
     * announce-list is absent entirely (legacy single-tracker torrents).
     * Each tier is filtered down to HTTP(S)/UDP URLs, and tiers that end
     * up with nothing left are dropped.
     *
     * <p>A torrent with genuinely zero trackers declared (no "announce",
     * empty/absent "announce-list" - the shape a DHT-resolved trackerless
     * magnet's synthesized torrent bytes always have, see
     * synthesizeTorrentFileBytes) returns an empty list rather than
     * throwing, once DHT-only torrents became supported (design_docs/0028)
     * - createTrackerClient turns that into a NoOpTrackerClient. Trackers
     * that ARE declared but all unsupported still throws: that's a
     * different, still-fatal situation (the torrent came with tracker
     * URLs, but none of them are ones this client can talk to at all),
     * and DHT's own peer-discovery role is limited to the no-trackers-at-all
     * case for now (see design_docs/0028's slice 6 scope note).
     *
     * <p>Package-private (not private) so it can be unit-tested directly
     * without needing a real tracker server. See design_docs/0022 for the
     * multi-tracker fallback this feeds, and design_docs/0023 for UDP
     * tracker support - both HTTP(S) and UDP URLs are accepted here now,
     * where an earlier version only accepted HTTP(S).
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

        if (usableTiers.isEmpty()) {
            throw new TorrentEngineException(
                    "No usable tracker found for this torrent - only HTTP(S) and UDP trackers are supported");
        }
        return usableTiers;
    }

    private static boolean isSupportedTrackerUrl(String url) {
        return url.startsWith("http://") || url.startsWith("https://") || url.startsWith("udp://");
    }
}
