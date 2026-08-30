package com.grimtorrenter.engine.torrent;

import com.grimtorrenter.engine.dht.DhtNode;
import com.grimtorrenter.engine.metainfo.TorrentFile;
import com.grimtorrenter.engine.metainfo.TorrentMetadata;
import com.grimtorrenter.engine.mse.EncryptionMode;
import com.grimtorrenter.engine.peer.PeerConnection;
import com.grimtorrenter.engine.peer.PeerConnectionListener;
import com.grimtorrenter.engine.peerwire.Bitfield;
import com.grimtorrenter.engine.peerwire.Cancel;
import com.grimtorrenter.engine.peerwire.Choke;
import com.grimtorrenter.engine.peerwire.Extended;
import com.grimtorrenter.engine.peerwire.Handshake;
import com.grimtorrenter.engine.peerwire.Have;
import com.grimtorrenter.engine.peerwire.Interested;
import com.grimtorrenter.engine.peerwire.KeepAlive;
import com.grimtorrenter.engine.peerwire.NotInterested;
import com.grimtorrenter.engine.peerwire.PeerMessage;
import com.grimtorrenter.engine.peerwire.Piece;
import com.grimtorrenter.engine.peerwire.Port;
import com.grimtorrenter.engine.peerwire.Request;
import com.grimtorrenter.engine.peerwire.Unchoke;
import com.grimtorrenter.engine.pex.PexCodec;
import com.grimtorrenter.engine.pex.PexMessage;
import com.grimtorrenter.engine.piece.PieceManager;
import com.grimtorrenter.engine.piece.PieceState;
import com.grimtorrenter.engine.ratelimit.RateLimiters;
import com.grimtorrenter.engine.storage.FileHandlePool;
import com.grimtorrenter.engine.storage.TorrentStorage;
import com.grimtorrenter.engine.tracker.NoOpTrackerClient;
import com.grimtorrenter.engine.tracker.PeerAddress;
import com.grimtorrenter.engine.tracker.PeerId;
import com.grimtorrenter.engine.tracker.TrackerClient;
import com.grimtorrenter.engine.tracker.TrackerEvent;
import com.grimtorrenter.engine.tracker.TrackerRequest;
import com.grimtorrenter.engine.tracker.TrackerResponse;
import com.grimtorrenter.engine.tracker.TrackerStatus;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * The orchestrator: wires TrackerClient + PeerConnections + PieceManager +
 * TorrentStorage into a running download. See design_docs/0017 for the
 * scope calls made in this class (startup behavior, connection limits,
 * scheduling, known imperfections).
 */
public final class TorrentSession implements AutoCloseable {

    /** java.lang.System.Logger, not a logging framework dependency - grimtorrenter-engine
     * stays Quarkus-free per design_docs/0005; this is the JDK's own logging facade. */
    private static final System.Logger LOG = System.getLogger(TorrentSession.class.getName());

    private static final int NUM_WANT = 50;
    private static final int MAX_CONNECTIONS = 30;
    private static final int PIPELINE_DEPTH = 5;
    private static final long KEEPALIVE_INTERVAL_SECONDS = 60;
    private static final long CHOKING_INTERVAL_SECONDS = 10;
    private static final int MAX_UNCHOKED_PEERS = 4;
    /** BEP 11 Peer Exchange - the ut_pex extension name, and the local id we advertise for
     * it in our own extended handshake (the id a peer uses when sending ut_pex messages to
     * us; see PeerConnection's own remoteExtensionId/sendExtended split for the two-id
     * BEP 10 negotiation). Not more than once a minute per BEP 11's own recommendation. See
     * design_docs/0040. */
    private static final String PEX_EXTENSION_NAME = "ut_pex";
    private static final int PEX_EXTENSION_ID = 1;
    private static final long PEX_INTERVAL_SECONDS = 60;
    /** BEP 11's own recommended sanity bound on a single message's "added" list. */
    private static final int MAX_PEX_ADDED_PER_MESSAGE = 50;
    /** What every outbound/inbound connection advertises in its own extended handshake -
     * just ut_pex for now. */
    private static final Map<String, Integer> EXTENSIONS_TO_ADVERTISE = Map.of(PEX_EXTENSION_NAME, PEX_EXTENSION_ID);
    /** Generous over our own 16 KiB request size (PieceManager.BLOCK_SIZE) - guards against
     * a peer requesting an absurd block length rather than trusting untrusted input. */
    private static final int MAX_SERVABLE_BLOCK_LENGTH = 128 * 1024;
    private static final Duration DHT_PING_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DHT_QUERY_TIMEOUT = Duration.ofSeconds(5);
    /** Used only when start() falls back to DHT because every tracker failed - there's no
     * TrackerResponse to read a real interval from in that case. A real tracker's own
     * interval (once one responds again via a later reannounce) still overrides this the
     * next time start() succeeds normally. See design_docs/0036. */
    private static final long DHT_BACKSTOP_REANNOUNCE_INTERVAL_SECONDS = 1800;

    private final TorrentMetadata metadata;
    private final TrackerClient trackerClient;
    private final TorrentStorage storage;
    private final PieceManager pieceManager;
    private final PeerId ourPeerId;
    private final int ourListenPort;
    private final TorrentSessionListener listener;
    /** Nullable - DHT is an optional enhancement (see design_docs/0028); a peer's Port
     * message is simply never acted on when it's null (DHT unavailable this process). */
    private final DhtNode dhtNode;
    /** Shared across every connection this session makes - see design_docs/0042. Never
     * null; callers that don't care about rate limiting get RateLimiters.unlimited() via
     * create()/restoreAsync()'s own lower-arity overloads. */
    private final RateLimiters rateLimiters;
    /** Shared across every TorrentSession the owning TorrentEngine creates or restores -
     * bounds how many pieces can be mid-verification (a full-piece byte[] read plus a SHA-1
     * hash) across the whole engine at once, not just within this session. Never null;
     * callers that don't care get an effectively-unbounded Semaphore via create()/
     * restoreAsync()'s own lower-arity overloads. See design_docs/0048. */
    private final Semaphore pieceVerificationLimiter;
    /** Read live on every outbound connection attempt, not snapshotted here - see
     * EncryptionMode's own Javadoc for why. Never null; callers that don't care get
     * DISABLED via create()/restoreAsync()'s own lower-arity overloads. See
     * design_docs/0052. */
    private final Supplier<EncryptionMode> encryptionMode;
    /** Read once, at the point enterDownloading() schedules the periodic reannounce for a
     * genuinely trackerless torrent (startViaDht()) - not re-read mid-flight, same "fixed for
     * this torrent's run, re-read on the next start()" precedent a real tracker's own
     * response.interval() already follows (a ScheduledExecutorService's fixed-delay period
     * can't be changed once scheduled without cancelling and rebuilding it). Never null;
     * callers that don't care get a fixed 300s default via create()/restoreAsync()'s own
     * lower-arity overloads. See design_docs/0036's own addendum. */
    private final Supplier<Long> trackerlessReannounceIntervalSeconds;

    /** This torrent's override of the global seeding-limit defaults - never null, defaults to
     * SeedingLimitOverride.INHERIT (both metrics follow the global default) via create()/
     * restoreAsync()'s own lower-arity overloads. Mutable at runtime (setSeedingLimitOverride())
     * since, unlike the other constructor-injected collaborators above, this is genuinely
     * per-torrent user data that can change after the session already exists. See
     * design_docs/0054. */
    private volatile SeedingLimitOverride seedingLimitOverride;
    /** Epoch millis this session first reached SEEDING, 0 until then - purely in-memory, not
     * persisted (see design_docs/0054's own callout on why: the byte counters a seeding-limit
     * check is computed from already reset on every restart, so this does too, rather than
     * being a half-persisted exception to that). Guarded against being re-stamped by
     * checkForCompletion() running again on every subsequent start() of an already-complete
     * torrent - see that method's own comment. */
    private volatile long completedAtEpochMillis;
    /** True if verifyThenSettle() (the restore-only re-verification pass) found every piece
     * already present and valid on disk, before this session's first start() was ever called -
     * false for a create()d session (never pre-populated, always starts everything NEEDED) and
     * false for a restored session that genuinely had data still missing. Distinct from
     * completedAtEpochMillis == 0 (which only distinguishes "first completion in this
     * process" from "a later resume of a torrent this same process already saw complete"):
     * this additionally catches the cross-restart case, where completedAtEpochMillis is back
     * at 0 on a brand-new session object even though the torrent finished long before this
     * process even started. Read by TorrentEventListener (grimtorrenter-app) alongside
     * completedAtEpochMillis to decide whether a DOWNLOADING -&gt; SEEDING transition is a
     * genuinely new completion worth a library event, or just this restart/resume rediscovering
     * data that was already done - see design_docs/0055's own COMPLETED-event Javadoc and the
     * real duplicate-event bug this field was added to fix. */
    private volatile boolean wasCompleteOnRestore;
    /** When this torrent was first added, for the details panel's "Added" fact (see
     * design_docs/0032). Nullable, not Optional - same convention as lastError() - because a
     * directory restored from a process that predates this field has no marker to read it
     * back from (see TorrentEngine.readAddedAtMarker()); "unknown" is a real, permanent state
     * for those torrents, not something to backfill with a guess (e.g. the restore time,
     * which would be wrong) or default away. Immutable per session, unlike the
     * runtime/mutable fields above - set once at construction (create()/restoreAsync()) and
     * never reassigned. */
    private final Instant addedAt;

    private final Set<PeerConnection> connections = ConcurrentHashMap.newKeySet();
    private final Set<PeerAddress> knownAddresses = ConcurrentHashMap.newKeySet();
    /** The connected-peer-address snapshot as of the last PEX broadcast, for computing the
     * next cycle's added/dropped delta - only ever read/written from the scheduler's single
     * thread (sendPexUpdates(), same as reannounce()/sendKeepAlives()/updateChoking()), so
     * a plain field needs no synchronization of its own. See design_docs/0040. */
    private Set<PeerAddress> previousPexPeers = Set.of();
    private final AtomicLong accumulatedUploaded = new AtomicLong();
    private final AtomicLong accumulatedReceived = new AtomicLong();
    private final AtomicInteger chokingRotation = new AtomicInteger();

    private volatile TorrentState state = TorrentState.STOPPED;
    private volatile Throwable lastError;
    private volatile ScheduledExecutorService scheduler;
    /** True only while the most recent tracker-driven peer-discovery attempt (start() or
     * reannounce()) actually used the DHT backstop rather than the tracker - reflects
     * current reality, not "ever used this session." Only ever set for a real (non-NoOp)
     * TrackerClient; a genuinely trackerless torrent's own usesDht signal
     * (TorrentSession.isTrackerless()) already covers it, so this stays false there. See
     * design_docs/0039. */
    private volatile boolean dhtBackstopActive;

    private TorrentSession(TorrentMetadata metadata, TrackerClient trackerClient, TorrentStorage storage,
                            PieceManager pieceManager, PeerId ourPeerId, int ourListenPort,
                            TorrentSessionListener listener, DhtNode dhtNode, RateLimiters rateLimiters,
                            Semaphore pieceVerificationLimiter, Supplier<EncryptionMode> encryptionMode,
                            SeedingLimitOverride seedingLimitOverride, TorrentState initialState,
                            Instant addedAt, Supplier<Long> trackerlessReannounceIntervalSeconds) {
        this.metadata = metadata;
        this.trackerClient = trackerClient;
        this.storage = storage;
        this.pieceManager = pieceManager;
        this.ourPeerId = ourPeerId;
        this.ourListenPort = ourListenPort;
        this.listener = listener;
        this.dhtNode = dhtNode;
        this.rateLimiters = rateLimiters;
        this.pieceVerificationLimiter = pieceVerificationLimiter;
        this.encryptionMode = encryptionMode;
        this.seedingLimitOverride = seedingLimitOverride;
        this.state = initialState;
        this.addedAt = addedAt;
        this.trackerlessReannounceIntervalSeconds = trackerlessReannounceIntervalSeconds;
    }

    /** Same as the eight-arg overload below but with no rate limiting - for every caller
     * that predates rate limiting and doesn't need it (tests, mainly). See
     * design_docs/0042. */
    public static TorrentSession create(TorrentMetadata metadata, TrackerClient trackerClient,
                                         Path downloadDirectory, PeerId ourPeerId, int ourListenPort,
                                         TorrentSessionListener listener, DhtNode dhtNode) throws IOException {
        return create(metadata, trackerClient, downloadDirectory, ourPeerId, ourListenPort, listener, dhtNode,
                RateLimiters.unlimited());
    }

    /** Same as the nine-arg overload below but with an unbounded file-handle pool - see
     * FileHandlePool.unbounded(). */
    public static TorrentSession create(TorrentMetadata metadata, TrackerClient trackerClient,
                                         Path downloadDirectory, PeerId ourPeerId, int ourListenPort,
                                         TorrentSessionListener listener, DhtNode dhtNode,
                                         RateLimiters rateLimiters) throws IOException {
        return create(metadata, trackerClient, downloadDirectory, ourPeerId, ourListenPort, listener, dhtNode,
                rateLimiters, FileHandlePool.unbounded());
    }

    /** Same as the ten-arg overload below but with an effectively-unbounded piece-
     * verification limiter - see the Semaphore built in TorrentEngine's own lower-arity
     * constructor for why Integer.MAX_VALUE, not a dedicated factory method, is used for
     * "unbounded" here. */
    public static TorrentSession create(TorrentMetadata metadata, TrackerClient trackerClient,
                                         Path downloadDirectory, PeerId ourPeerId, int ourListenPort,
                                         TorrentSessionListener listener, DhtNode dhtNode,
                                         RateLimiters rateLimiters, FileHandlePool fileHandlePool) throws IOException {
        return create(metadata, trackerClient, downloadDirectory, ourPeerId, ourListenPort, listener, dhtNode,
                rateLimiters, fileHandlePool, new Semaphore(Integer.MAX_VALUE));
    }

    /** Same as the eleven-arg overload below but with encryption disabled - for every caller
     * that predates MSE and doesn't need it (tests, mainly). See design_docs/0052. */
    public static TorrentSession create(TorrentMetadata metadata, TrackerClient trackerClient,
                                         Path downloadDirectory, PeerId ourPeerId, int ourListenPort,
                                         TorrentSessionListener listener, DhtNode dhtNode,
                                         RateLimiters rateLimiters, FileHandlePool fileHandlePool,
                                         Semaphore pieceVerificationLimiter) throws IOException {
        return create(metadata, trackerClient, downloadDirectory, ourPeerId, ourListenPort, listener, dhtNode,
                rateLimiters, fileHandlePool, pieceVerificationLimiter, () -> EncryptionMode.DISABLED);
    }

    /** Same as the twelve-arg overload below but with no seeding-limit override - for every
     * caller that predates seeding limits and doesn't need one (tests, mainly; a brand-new
     * torrent has nothing to override yet anyway). See design_docs/0054. */
    public static TorrentSession create(TorrentMetadata metadata, TrackerClient trackerClient,
                                         Path downloadDirectory, PeerId ourPeerId, int ourListenPort,
                                         TorrentSessionListener listener, DhtNode dhtNode,
                                         RateLimiters rateLimiters, FileHandlePool fileHandlePool,
                                         Semaphore pieceVerificationLimiter,
                                         Supplier<EncryptionMode> encryptionMode) throws IOException {
        return create(metadata, trackerClient, downloadDirectory, ourPeerId, ourListenPort, listener, dhtNode,
                rateLimiters, fileHandlePool, pieceVerificationLimiter, encryptionMode, SeedingLimitOverride.INHERIT);
    }

    /** Same as the thirteen-arg overload below but stamps addedAt as now - every caller that
     * predates the details panel's "Added" fact (tests, mainly; a genuinely brand-new torrent
     * has no other addedAt to give it anyway). See design_docs/0032. */
    public static TorrentSession create(TorrentMetadata metadata, TrackerClient trackerClient,
                                         Path downloadDirectory, PeerId ourPeerId, int ourListenPort,
                                         TorrentSessionListener listener, DhtNode dhtNode,
                                         RateLimiters rateLimiters, FileHandlePool fileHandlePool,
                                         Semaphore pieceVerificationLimiter,
                                         Supplier<EncryptionMode> encryptionMode,
                                         SeedingLimitOverride seedingLimitOverride) throws IOException {
        return create(metadata, trackerClient, downloadDirectory, ourPeerId, ourListenPort, listener, dhtNode,
                rateLimiters, fileHandlePool, pieceVerificationLimiter, encryptionMode, seedingLimitOverride,
                Instant.now());
    }

    /** Same as the fifteen-arg overload below but with a fixed 300s trackerless-reannounce
     * interval - for every caller that predates design_docs/0036's own addendum (tests,
     * mainly). */
    public static TorrentSession create(TorrentMetadata metadata, TrackerClient trackerClient,
                                         Path downloadDirectory, PeerId ourPeerId, int ourListenPort,
                                         TorrentSessionListener listener, DhtNode dhtNode,
                                         RateLimiters rateLimiters, FileHandlePool fileHandlePool,
                                         Semaphore pieceVerificationLimiter,
                                         Supplier<EncryptionMode> encryptionMode,
                                         SeedingLimitOverride seedingLimitOverride,
                                         Instant addedAt) throws IOException {
        return create(metadata, trackerClient, downloadDirectory, ourPeerId, ourListenPort, listener, dhtNode,
                rateLimiters, fileHandlePool, pieceVerificationLimiter, encryptionMode, seedingLimitOverride,
                addedAt, () -> 300L);
    }

    public static TorrentSession create(TorrentMetadata metadata, TrackerClient trackerClient,
                                         Path downloadDirectory, PeerId ourPeerId, int ourListenPort,
                                         TorrentSessionListener listener, DhtNode dhtNode,
                                         RateLimiters rateLimiters, FileHandlePool fileHandlePool,
                                         Semaphore pieceVerificationLimiter,
                                         Supplier<EncryptionMode> encryptionMode,
                                         SeedingLimitOverride seedingLimitOverride,
                                         Instant addedAt,
                                         Supplier<Long> trackerlessReannounceIntervalSeconds) throws IOException {
        TorrentStorage storage = TorrentStorage.create(metadata, downloadDirectory, fileHandlePool);
        PieceManager pieceManager = new PieceManager(metadata);
        return new TorrentSession(metadata, trackerClient, storage, pieceManager, ourPeerId, ourListenPort,
                listener, dhtNode, rateLimiters, pieceVerificationLimiter, encryptionMode, seedingLimitOverride,
                TorrentState.STOPPED, addedAt, trackerlessReannounceIntervalSeconds);
    }

    /** Same as the nine-arg overload below but with no rate limiting - see create()'s own
     * pair of overloads for why this exists. See design_docs/0042. */
    public static TorrentSession restoreAsync(TorrentMetadata metadata, TrackerClient trackerClient,
                                               Path downloadDirectory, PeerId ourPeerId, int ourListenPort,
                                               TorrentSessionListener listener, DhtNode dhtNode,
                                               boolean autoStart) throws IOException {
        return restoreAsync(metadata, trackerClient, downloadDirectory, ourPeerId, ourListenPort, listener,
                dhtNode, RateLimiters.unlimited(), autoStart);
    }

    /** Same as the ten-arg overload below but with an unbounded file-handle pool - see
     * FileHandlePool.unbounded(). */
    public static TorrentSession restoreAsync(TorrentMetadata metadata, TrackerClient trackerClient,
                                               Path downloadDirectory, PeerId ourPeerId, int ourListenPort,
                                               TorrentSessionListener listener, DhtNode dhtNode,
                                               RateLimiters rateLimiters, boolean autoStart) throws IOException {
        return restoreAsync(metadata, trackerClient, downloadDirectory, ourPeerId, ourListenPort, listener,
                dhtNode, rateLimiters, FileHandlePool.unbounded(), autoStart);
    }

    /** Same as the eleven-arg overload below but with an effectively-unbounded piece-
     * verification limiter - see create()'s matching overload for why. */
    public static TorrentSession restoreAsync(TorrentMetadata metadata, TrackerClient trackerClient,
                                               Path downloadDirectory, PeerId ourPeerId, int ourListenPort,
                                               TorrentSessionListener listener, DhtNode dhtNode,
                                               RateLimiters rateLimiters, FileHandlePool fileHandlePool,
                                               boolean autoStart) throws IOException {
        return restoreAsync(metadata, trackerClient, downloadDirectory, ourPeerId, ourListenPort, listener,
                dhtNode, rateLimiters, fileHandlePool, new Semaphore(Integer.MAX_VALUE), autoStart);
    }

    /**
     * Like create(), but for a torrent that may already have data on disk from a
     * previous process's run (see design_docs/0026). Returns immediately in
     * VERIFYING state - the caller should register/show the session right away
     * rather than waiting - and re-hashes every piece against storage on a
     * background virtual thread, so already-downloaded pieces are seeded as
     * complete rather than being re-requested from peers. Once verification
     * finishes, settles into STOPPED and then, if autoStart is true, calls the
     * normal start() (which is what actually reaches DOWNLOADING/SEEDING).
     * TorrentStorage.create() pre-allocates every file to its full length
     * regardless of progress, so reading any piece here is always safe even for
     * a torrent that's 0% downloaded - it just fails verification.
     */
    /** Same as the twelve-arg overload below but with encryption disabled - for every caller
     * that predates MSE and doesn't need it (tests, mainly). See design_docs/0052. */
    public static TorrentSession restoreAsync(TorrentMetadata metadata, TrackerClient trackerClient,
                                               Path downloadDirectory, PeerId ourPeerId, int ourListenPort,
                                               TorrentSessionListener listener, DhtNode dhtNode,
                                               RateLimiters rateLimiters, FileHandlePool fileHandlePool,
                                               Semaphore pieceVerificationLimiter, boolean autoStart) throws IOException {
        return restoreAsync(metadata, trackerClient, downloadDirectory, ourPeerId, ourListenPort, listener,
                dhtNode, rateLimiters, fileHandlePool, pieceVerificationLimiter, () -> EncryptionMode.DISABLED,
                autoStart);
    }

    /** Same as the thirteen-arg overload below but with no seeding-limit override - for every
     * caller that predates seeding limits and doesn't need one (tests, mainly). See
     * design_docs/0054. */
    public static TorrentSession restoreAsync(TorrentMetadata metadata, TrackerClient trackerClient,
                                               Path downloadDirectory, PeerId ourPeerId, int ourListenPort,
                                               TorrentSessionListener listener, DhtNode dhtNode,
                                               RateLimiters rateLimiters, FileHandlePool fileHandlePool,
                                               Semaphore pieceVerificationLimiter,
                                               Supplier<EncryptionMode> encryptionMode,
                                               boolean autoStart) throws IOException {
        return restoreAsync(metadata, trackerClient, downloadDirectory, ourPeerId, ourListenPort, listener,
                dhtNode, rateLimiters, fileHandlePool, pieceVerificationLimiter, encryptionMode,
                SeedingLimitOverride.INHERIT, autoStart);
    }

    /** seedingLimitOverride is the value TorrentEngine already read back from this torrent's
     * own marker file (design_docs/0054) - restoring is exactly where a previously-set
     * per-torrent override needs to actually take effect again, not just a freshly-added
     * torrent's default. Same as the fourteen-arg overload below but stamps addedAt as now -
     * every caller that predates the details panel's "Added" fact (tests, mainly). */
    public static TorrentSession restoreAsync(TorrentMetadata metadata, TrackerClient trackerClient,
                                               Path downloadDirectory, PeerId ourPeerId, int ourListenPort,
                                               TorrentSessionListener listener, DhtNode dhtNode,
                                               RateLimiters rateLimiters, FileHandlePool fileHandlePool,
                                               Semaphore pieceVerificationLimiter,
                                               Supplier<EncryptionMode> encryptionMode,
                                               SeedingLimitOverride seedingLimitOverride,
                                               boolean autoStart) throws IOException {
        return restoreAsync(metadata, trackerClient, downloadDirectory, ourPeerId, ourListenPort, listener,
                dhtNode, rateLimiters, fileHandlePool, pieceVerificationLimiter, encryptionMode,
                seedingLimitOverride, Instant.now(), autoStart);
    }

    /** addedAt is nullable - TorrentEngine.restoreOne() passes whatever
     * readAddedAtMarker() found, which is null for a directory that predates this field (see
     * design_docs/0032); a genuinely new torrent (addTorrent()'s own use of this method, for
     * the reused-directory "removed with keep files, now re-added" case) always has a real
     * value. */
    /** Same as the sixteen-arg overload below but with a fixed 300s trackerless-reannounce
     * interval - for every caller that predates design_docs/0036's own addendum (tests,
     * mainly). */
    public static TorrentSession restoreAsync(TorrentMetadata metadata, TrackerClient trackerClient,
                                               Path downloadDirectory, PeerId ourPeerId, int ourListenPort,
                                               TorrentSessionListener listener, DhtNode dhtNode,
                                               RateLimiters rateLimiters, FileHandlePool fileHandlePool,
                                               Semaphore pieceVerificationLimiter,
                                               Supplier<EncryptionMode> encryptionMode,
                                               SeedingLimitOverride seedingLimitOverride,
                                               Instant addedAt,
                                               boolean autoStart) throws IOException {
        return restoreAsync(metadata, trackerClient, downloadDirectory, ourPeerId, ourListenPort, listener,
                dhtNode, rateLimiters, fileHandlePool, pieceVerificationLimiter, encryptionMode,
                seedingLimitOverride, addedAt, () -> 300L, autoStart);
    }

    public static TorrentSession restoreAsync(TorrentMetadata metadata, TrackerClient trackerClient,
                                               Path downloadDirectory, PeerId ourPeerId, int ourListenPort,
                                               TorrentSessionListener listener, DhtNode dhtNode,
                                               RateLimiters rateLimiters, FileHandlePool fileHandlePool,
                                               Semaphore pieceVerificationLimiter,
                                               Supplier<EncryptionMode> encryptionMode,
                                               SeedingLimitOverride seedingLimitOverride,
                                               Instant addedAt,
                                               Supplier<Long> trackerlessReannounceIntervalSeconds,
                                               boolean autoStart) throws IOException {
        TorrentStorage storage = TorrentStorage.create(metadata, downloadDirectory, fileHandlePool);
        PieceManager pieceManager = new PieceManager(metadata);
        TorrentSession session = new TorrentSession(metadata, trackerClient, storage, pieceManager,
                ourPeerId, ourListenPort, listener, dhtNode, rateLimiters, pieceVerificationLimiter,
                encryptionMode, seedingLimitOverride, TorrentState.VERIFYING, addedAt,
                trackerlessReannounceIntervalSeconds);
        Thread.ofVirtual().start(() -> session.verifyThenSettle(autoStart));
        return session;
    }

    /**
     * Runs off the constructing thread so restoreAsync() can return immediately.
     * Narrow, accepted race: if stop() is called while this is still running (state
     * flips away from VERIFYING), this abandons the rehash rather than finishing it -
     * any pieces not yet re-checked just get treated as NEEDED and re-downloaded
     * normally later, same as the updateChoking()/stop() interaction noted in
     * design_docs/0025. Not a correctness issue, just a missed optimization in a
     * rare window.
     *
     * <p>Each iteration acquires a pieceVerificationLimiter permit before the read+verify
     * pair and releases it right after - with many torrents restoring at once (each on its
     * own virtual thread, all calling this concurrently), this bounds how many multi-MB
     * byte[] buffers and SHA-1 hashes are in flight across the whole engine simultaneously,
     * rather than letting every restoring torrent's full piece set pile up in memory at
     * once. See design_docs/0048.
     */
    private void verifyThenSettle(boolean autoStart) {
        try {
            for (int i = 0; i < pieceManager.pieceCount(); i++) {
                if (state != TorrentState.VERIFYING) {
                    return;
                }
                pieceVerificationLimiter.acquireUninterruptibly();
                try {
                    byte[] bytes = storage.read(pieceManager.pieceOffset(i), pieceManager.pieceLength(i));
                    pieceManager.verify(i, bytes);
                } finally {
                    pieceVerificationLimiter.release();
                }
            }
        } catch (IOException e) {
            fail(e);
            return;
        }
        synchronized (this) {
            if (state != TorrentState.VERIFYING) {
                return;
            }
            // Recorded before setState()/start() ever run, regardless of autoStart - a
            // torrent restored but not yet auto-started can still be resumed manually later,
            // and checkForCompletion() needs this flag set correctly whenever that happens.
            wasCompleteOnRestore = pieceManager.isAllComplete();
            setState(TorrentState.STOPPED);
        }
        if (autoStart) {
            start();
        }
    }

    /** Every start is treated as fully fresh in itself - no verification of pre-existing disk
     * data happens here. Pre-existing completion state, when there is any, comes only from
     * restoreAsync()'s background recheck. See design_docs/0017 and design_docs/0026.
     *
     * <p>A genuinely trackerless torrent (trackerClient is a NoOpTrackerClient - see
     * createTrackerClient) skips the tracker announce entirely rather than calling it anyway:
     * NoOpTrackerClient.announce() never throws and reports a deliberately huge interval so
     * its own reannounce loop would otherwise never fire - going through startViaDht() instead
     * gets it the same periodic DHT re-query a tracker-bearing torrent already gets via
     * startViaDhtBackstop()/reannounceViaDhtBackstop() below. See design_docs/0036's own
     * addendum. */
    public synchronized void start() {
        if (state != TorrentState.STOPPED) {
            return;
        }
        if (trackerClient instanceof NoOpTrackerClient) {
            startViaDht();
            return;
        }
        TrackerResponse response;
        try {
            response = trackerClient.announce(new TrackerRequest(metadata.infoHash(), ourPeerId, ourListenPort,
                    bytesUploaded(), 0, bytesRemaining(), TrackerEvent.STARTED, NUM_WANT));
        } catch (RuntimeException e) {
            startViaDhtBackstop(e);
            return;
        }

        dhtBackstopActive = false;
        enterDownloading(response.peers(), Math.max(response.interval(), 30));
    }

    /** Genuinely trackerless (no tracker to have failed) - unlike startViaDhtBackstop() below,
     * dhtNode == null or a failed/empty lookup is never ERROR here, just "enter DOWNLOADING
     * with zero peers so far": there's no prior working state to consider "failed," the same
     * way a regular tracker responding with zero peers isn't ERROR either. Deliberately does
     * NOT set dhtBackstopActive - that flag's own Javadoc reserves it for a tracker-bearing
     * torrent whose tracker is currently down, a genuine degradation; a trackerless torrent
     * doing DHT lookups is its normal operating mode, already covered by the separate
     * usesDht()/isTrackerless() signal. See design_docs/0036's own addendum. */
    private void startViaDht() {
        List<PeerAddress> peers = List.of();
        if (dhtNode != null) {
            try {
                peers = dhtNode.findPeers(metadata.infoHash(), ourListenPort, false, DHT_QUERY_TIMEOUT);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.DEBUG, "DHT lookup failed for trackerless torrent "
                        + metadata.infoHash(), e);
            }
        }
        enterDownloading(peers, trackerlessReannounceIntervalSeconds.get());
    }

    /** Every tracker failed (MultiTrackerClient's own BEP 12 tier fallback exhausted, see
     * design_docs/0022) - rather than going straight to ERROR, falls back to a DHT peer
     * lookup, the same motivation as multi-tracker fallback itself: a torrent's tracker(s)
     * being completely unreachable shouldn't strand the torrent when another
     * peer-discovery path is available. Only ever reached for a real (non-NoOp)
     * TrackerClient - a genuinely trackerless torrent takes the startViaDht() path above
     * instead, straight from start(). See design_docs/0036.
     *
     * <p>An empty-but-successful DHT lookup still counts as success here (same as a tracker
     * responding with zero peers already does on the normal path) - it means DHT itself is
     * reachable, just that no peer happens to be known for this torrent right now; ERROR is
     * reserved for "no peer-discovery path worked at all," not "found nobody this time." */
    private void startViaDhtBackstop(RuntimeException trackerFailure) {
        if (dhtNode == null) {
            dhtBackstopActive = false;
            LOG.log(System.Logger.Level.WARNING, "Initial tracker announce failed for " + metadata.infoHash(), trackerFailure);
            lastError = trackerFailure;
            setState(TorrentState.ERROR);
            return;
        }
        List<PeerAddress> peers;
        try {
            peers = dhtNode.findPeers(metadata.infoHash(), ourListenPort, false, DHT_QUERY_TIMEOUT);
        } catch (RuntimeException dhtFailure) {
            dhtBackstopActive = false;
            LOG.log(System.Logger.Level.WARNING, "Initial tracker announce failed for " + metadata.infoHash()
                    + ", and DHT fallback also failed", trackerFailure);
            lastError = trackerFailure;
            setState(TorrentState.ERROR);
            return;
        }
        dhtBackstopActive = true;
        LOG.log(System.Logger.Level.INFO, "Initial tracker announce failed for " + metadata.infoHash()
                + " - falling back to DHT, found " + peers.size() + " peer(s)", trackerFailure);
        enterDownloading(peers, DHT_BACKSTOP_REANNOUNCE_INTERVAL_SECONDS);
    }

    private void enterDownloading(List<PeerAddress> peers, long reannounceIntervalSeconds) {
        knownAddresses.addAll(peers);
        setState(TorrentState.DOWNLOADING);

        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(
                this::reannounce, reannounceIntervalSeconds, reannounceIntervalSeconds, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(this::sendKeepAlives,
                KEEPALIVE_INTERVAL_SECONDS, KEEPALIVE_INTERVAL_SECONDS, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(this::updateChoking,
                CHOKING_INTERVAL_SECONDS, CHOKING_INTERVAL_SECONDS, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(this::sendPexUpdates,
                PEX_INTERVAL_SECONDS, PEX_INTERVAL_SECONDS, TimeUnit.SECONDS);

        fillConnections();
        // A restore()d torrent can already be fully complete before its first start() -
        // without this, it would sit in DOWNLOADING forever since nothing else here re-checks
        // completion (that normally only happens when a new piece is verified). See design_docs/0026.
        checkForCompletion();
    }

    /** Deliberately doesn't call closeStorage() - a paused torrent must still be able to
     * resume via start() and keep reading/writing normally. This mattered a lot more before
     * design_docs/0047: TorrentStorage used to hold its FileChannels open for its whole
     * lifetime and had no reopen path once closed, so closing storage here would have been
     * exactly the bug design_docs/0030 fixed. Now storage.close() only evicts idle entries
     * from the shared FileHandlePool - genuinely safe to call even on a pause, since a later
     * read()/write() would just reopen on demand - but there's still no reason to evict a
     * torrent's files the moment it's paused, since resumeTorrent() (or continued seeding)
     * will likely touch them again soon. Only close() actually evicts, for when the session
     * is being permanently discarded (removeTorrent, engine shutdown, AutoCloseable teardown
     * in tests) rather than merely paused. See design_docs/0030, design_docs/0047. */
    public synchronized void stop() {
        if (state == TorrentState.STOPPED) {
            return;
        }
        try {
            trackerClient.announce(new TrackerRequest(metadata.infoHash(), ourPeerId, ourListenPort,
                    bytesUploaded(), bytesDownloaded(), bytesRemaining(), TrackerEvent.STOPPED, 0));
        } catch (RuntimeException ignored) {
            // best-effort - we're shutting down locally regardless
        }
        setState(TorrentState.STOPPED);
        shutdownNetworking();
    }

    /** Stops (if not already) and releases storage - unlike a plain stop(), this session is
     * never coming back. See design_docs/0030. */
    @Override
    public void close() {
        stop();
        closeStorage();
    }

    /** ERROR is terminal - start() only ever resumes from STOPPED - so releasing storage
     * here too is correct, not just an optimization. Handles its own networking teardown
     * rather than going through stop()/close() because stop() would re-announce STOPPED and
     * overwrite the ERROR state this method just set. */
    private synchronized void fail(Throwable cause) {
        if (state == TorrentState.ERROR || state == TorrentState.STOPPED) {
            return;
        }
        LOG.log(System.Logger.Level.WARNING, "Torrent " + metadata.infoHash() + " failed", cause);
        lastError = cause;
        setState(TorrentState.ERROR);
        shutdownNetworking();
        closeStorage();
    }

    private void shutdownNetworking() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        for (PeerConnection connection : connections) {
            connection.close();
        }
        connections.clear();
    }

    /** TorrentStorage.close() no longer declares IOException (see design_docs/0047) - it
     * just evicts this torrent's files from the shared FileHandlePool, which is a
     * best-effort, non-throwing operation on its own. */
    private void closeStorage() {
        storage.close();
    }

    private void setState(TorrentState newState) {
        TorrentState old = state;
        state = newState;
        if (old != newState) {
            LOG.log(System.Logger.Level.INFO,
                    "Torrent " + metadata.infoHash() + ": " + old + " -> " + newState);
            listener.onStateChanged(this, old, newState);
        }
    }

    /** Package-private (not private) so tests can trigger exactly one reannounce cycle
     * directly, rather than waiting out the real scheduled interval (30s minimum, or
     * trackerlessReannounceIntervalSeconds for a trackerless torrent) - same rationale as
     * TorrentEngine.selectTrackerTiers's own package-private-for-testing note. Trackerless
     * torrents skip the no-op tracker announce entirely (it would find nothing - see start()'s
     * own comment) and go straight to a fresh DHT lookup instead. See design_docs/0036's own
     * addendum. */
    void reannounce() {
        if (state != TorrentState.DOWNLOADING && state != TorrentState.SEEDING) {
            return;
        }
        if (trackerClient instanceof NoOpTrackerClient) {
            reannounceViaDht();
            return;
        }
        try {
            TrackerResponse response = trackerClient.announce(new TrackerRequest(metadata.infoHash(), ourPeerId,
                    ourListenPort, bytesUploaded(), bytesDownloaded(), bytesRemaining(), null, NUM_WANT));
            dhtBackstopActive = false;
            knownAddresses.addAll(response.peers());
            fillConnections();
        } catch (RuntimeException e) {
            // Transient tracker failure - existing connections keep working; retry next interval.
            LOG.log(System.Logger.Level.DEBUG, "Re-announce failed for " + metadata.infoHash(), e);
            reannounceViaDhtBackstop();
        }
    }

    /** Genuinely trackerless counterpart to reannounceViaDhtBackstop() below - same
     * runs-on-its-own-virtual-thread reasoning, but never touches dhtBackstopActive (see
     * startViaDht()'s own comment for why). See design_docs/0036's own addendum. */
    private void reannounceViaDht() {
        if (dhtNode == null) {
            return;
        }
        Thread.ofVirtual().start(() -> {
            try {
                List<PeerAddress> peers = dhtNode.findPeers(metadata.infoHash(), ourListenPort, false, DHT_QUERY_TIMEOUT);
                addKnownPeers(peers);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.DEBUG, "DHT re-query failed for trackerless torrent "
                        + metadata.infoHash(), e);
            }
        });
    }

    /** Runs on its own virtual thread rather than blocking the session's single scheduler
     * thread (shared with the keepalive/choking timers) for the lookup's multi-second
     * duration. addKnownPeers() is safe to call regardless of what state the session is in by
     * the time this completes. See design_docs/0036. */
    private void reannounceViaDhtBackstop() {
        if (dhtNode == null) {
            return;
        }
        Thread.ofVirtual().start(() -> {
            try {
                List<PeerAddress> peers = dhtNode.findPeers(metadata.infoHash(), ourListenPort, false, DHT_QUERY_TIMEOUT);
                dhtBackstopActive = true;
                addKnownPeers(peers);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.DEBUG, "DHT re-announce fallback failed for " + metadata.infoHash(), e);
            }
        });
    }

    private void sendKeepAlives() {
        for (PeerConnection connection : connections) {
            connection.sendKeepAlive();
        }
    }

    /**
     * BEP 11 Peer Exchange: gossips which peers we're connected to, not our whole
     * knownAddresses candidate pool - PEX shares who's actually in the swarm and reachable,
     * not untested tracker/DHT candidates. The added/dropped delta is computed once per
     * session per cycle (not per connection - see design_docs/0040) against
     * previousPexPeers, then the same delta is sent to every currently-connected,
     * ut_pex-supporting peer, each with its own address filtered out of "added" (no point
     * telling a peer about itself). A peer that's connected but never sent an extended
     * handshake, or doesn't support ut_pex, is silently skipped (remoteExtensionId is
     * empty for it).
     *
     * <p>Package-private (not private) so tests can trigger exactly one PEX cycle
     * directly, rather than waiting out the real 60s scheduled interval - same rationale as
     * reannounce()'s own package-private-for-testing note.
     */
    void sendPexUpdates() {
        Set<PeerAddress> current = new HashSet<>();
        for (PeerConnection connection : connections) {
            current.add(connection.remoteAddress());
        }
        Set<PeerAddress> added = new HashSet<>(current);
        added.removeAll(previousPexPeers);
        Set<PeerAddress> dropped = new HashSet<>(previousPexPeers);
        dropped.removeAll(current);
        previousPexPeers = current;

        if (added.isEmpty() && dropped.isEmpty()) {
            return;
        }
        List<PeerAddress> addedCapped = added.stream().limit(MAX_PEX_ADDED_PER_MESSAGE).toList();
        List<PeerAddress> droppedList = List.copyOf(dropped);

        for (PeerConnection connection : connections) {
            connection.remoteExtensionId(PEX_EXTENSION_NAME).ifPresent(theirExtensionId -> {
                List<PeerAddress> addedForThisPeer =
                        addedCapped.stream().filter(a -> !a.equals(connection.remoteAddress())).toList();
                if (addedForThisPeer.isEmpty() && droppedList.isEmpty()) {
                    return;
                }
                connection.sendExtended(
                        theirExtensionId, PexCodec.encode(new PexMessage(addedForThisPeer, droppedList)));
            });
        }
    }

    /** Package-private, for tests only - a connected peer's own extended handshake
     * arrives asynchronously on that connection's read loop, independent of when
     * connectedPeerCount() first reflects the connection itself; a test that needs to
     * know remoteExtensionId is actually populated before triggering a PEX cycle (rather
     * than guessing with a sleep) polls this instead. See design_docs/0040. */
    boolean hasReceivedExtendedHandshakeFrom(PeerAddress address) {
        return connections.stream()
                .filter(c -> c.remoteAddress().equals(address))
                .anyMatch(c -> c.remoteExtensionId(PEX_EXTENSION_NAME).isPresent());
    }

    /** ut_pex is the only extension TorrentSession's own steady-state connections handle
     * (BEP 9's ut_metadata is only ever used by the separate one-shot MetadataFetcher
     * during magnet resolution, see design_docs/0028) - anything else, including our own
     * handshake (extendedMessageId 0, already consumed internally by PeerConnection before
     * this is even called), is ignored. "added" feeds straight into the existing
     * addKnownPeers() - same mechanism tracker/DHT-discovered peers already use; "dropped"
     * is decoded but deliberately never acted on (see design_docs/0040). A malformed
     * message is dropped silently rather than disconnecting a peer over one bad message. */
    private void handleExtended(PeerConnection connection, Extended extended) {
        if (extended.extendedMessageId() != PEX_EXTENSION_ID) {
            return;
        }
        try {
            PexMessage message = PexCodec.decode(extended.payload());
            addKnownPeers(message.added());
        } catch (RuntimeException ignored) {
            // Malformed ut_pex message - drop it, same tolerance MetadataFetcher's own
            // ut_metadata decoding already applies to a peer sending us garbage.
        }
    }

    /**
     * Not a hard limit under concurrent connect attempts (design_docs/0017)
     * - a small overshoot past MAX_CONNECTIONS is an acceptable imprecision
     * rather than something worth adding reservation bookkeeping for.
     */
    private void fillConnections() {
        if (state != TorrentState.DOWNLOADING && state != TorrentState.SEEDING) {
            return;
        }
        int slots = MAX_CONNECTIONS - connections.size();
        if (slots <= 0) {
            return;
        }
        List<PeerAddress> candidates = knownAddresses.stream()
                .filter(address -> connections.stream().noneMatch(c -> c.remoteAddress().equals(address)))
                .limit(slots)
                .toList();
        for (PeerAddress address : candidates) {
            Thread.ofVirtual().start(() -> attemptConnect(address));
        }
    }

    /** No retry backoff for a failed address - the tracker's re-announce cadence naturally rate-limits retries. */
    private void attemptConnect(PeerAddress address) {
        try {
            PeerConnection connection = PeerConnection.connect(address, metadata.infoHash(), ourPeerId,
                    new PeerListener(), EXTENSIONS_TO_ADVERTISE, rateLimiters, encryptionMode.get());
            connections.add(connection);
            onPeerConnected(connection);
        } catch (IOException | RuntimeException e) {
            // Most tracker-provided addresses are unreachable - this is the common case, not exceptional.
        }
    }

    private void onPeerConnected(PeerConnection connection) {
        // ourListenPort doubles as our DHT node's UDP port too (see design_docs/0028), so
        // this is the one port value every peer needs telling about regardless of DHT
        // even being enabled this process - a peer with DHT disabled just ignores it.
        connection.sendPort(ourListenPort);
        if (pieceManager.completedCount() > 0) {
            connection.sendBitfield(buildBitfield());
        }
        updateInterest(connection);
    }

    /** Adopts a connection PeerServer already accepted and routed here by info hash - the
     * inbound counterpart to attemptConnect()/fillConnections(). Public (unlike those)
     * since PeerServer calls it from a different package; safe to hand this directly as an
     * IncomingConnectionHandler method reference. Closes socket itself, rather than
     * throwing, when this session isn't in a state to want new connections (not currently
     * running) or is already at its connection cap - PeerServer's own job ends at routing,
     * not deciding whether a session wants what it's been offered. See design_docs/0038. */
    public void acceptIncomingConnection(Socket socket, InputStream in, OutputStream out, Handshake remoteHandshake)
            throws IOException {
        if (state != TorrentState.DOWNLOADING && state != TorrentState.SEEDING) {
            socket.close();
            return;
        }
        if (connections.size() >= MAX_CONNECTIONS) {
            socket.close();
            return;
        }
        PeerConnection connection = PeerConnection.accept(socket, in, out, remoteHandshake, ourPeerId,
                new PeerListener(), EXTENSIONS_TO_ADVERTISE, rateLimiters);
        connections.add(connection);
        onPeerConnected(connection);
    }

    /**
     * Seeds additional known peer addresses directly, bypassing tracker announce entirely
     * - for a trackerless torrent (a magnet resolved via DHT, see design_docs/0028), whose
     * only source of peers at add-time is whatever DHT lookup already found while
     * fetching its metadata. Safe to call regardless of current state; only actually
     * attempts connections if the session is already running.
     */
    public void addKnownPeers(List<PeerAddress> addresses) {
        knownAddresses.addAll(addresses);
        fillConnections();
    }

    private Bitfield buildBitfield() {
        byte[] bits = new byte[(pieceManager.pieceCount() + 7) / 8];
        for (int i = 0; i < pieceManager.pieceCount(); i++) {
            if (pieceManager.isComplete(i)) {
                bits[i / 8] |= (byte) (0x80 >> (i % 8));
            }
        }
        return new Bitfield(bits);
    }

    private void updateInterest(PeerConnection connection) {
        boolean weWantSomething = pieceManager.selectNextPiece(connection::peerHasPiece).isPresent();
        if (weWantSomething && !connection.amInterested()) {
            connection.sendInterested();
        } else if (!weWantSomething && connection.amInterested()) {
            connection.sendNotInterested();
        }
    }

    private void handleMessage(PeerConnection connection, PeerMessage message) {
        if (state != TorrentState.DOWNLOADING && state != TorrentState.SEEDING) {
            return;
        }
        switch (message) {
            case Unchoke ignored -> requestMore(connection);
            case Have ignored -> onAvailabilityChanged(connection);
            case Bitfield ignored -> onAvailabilityChanged(connection);
            case Piece piece -> onPieceBlockReceived(connection, piece);
            case Choke ignored -> {
            }
            // Re-evaluate immediately on interest change (in addition to the periodic tick)
            // so a peer isn't left waiting up to CHOKING_INTERVAL_SECONDS for a free slot.
            case Interested ignored -> updateChoking();
            case NotInterested ignored -> updateChoking();
            case Request request -> onBlockRequested(connection, request);
            // Requests are served synchronously and immediately (see onBlockRequested) -
            // there's never a queued/pending send to actually cancel.
            case Cancel ignored -> {
            }
            case Port port -> onPeerAnnouncedDhtPort(connection, port);
            case KeepAlive ignored -> {
            }
            // BEP 10's extension protocol has no torrent-level semantics of its own -
            // PeerConnection already handles the extended handshake internally (see
            // design_docs/0028). BEP 11's ut_pex is the one extension built on top of it
            // this class handles - see design_docs/0040.
            case Extended extended -> handleExtended(connection, extended);
        }
    }

    /**
     * A peer telling us its DHT node's UDP port isn't itself proof it's really a live one
     * there - verified with a direct ping (background, best-effort) before it can ever
     * reach our routing table, the same "only trust nodes directly heard from" rule
     * DhtNode's own query handling already follows for everything else. A no-op if DHT is
     * disabled for this process. See design_docs/0028.
     */
    private void onPeerAnnouncedDhtPort(PeerConnection connection, Port port) {
        if (dhtNode == null) {
            return;
        }
        InetSocketAddress address = new InetSocketAddress(connection.remoteAddress().address(), port.listenPort());
        Thread.ofVirtual().start(() -> {
            try {
                dhtNode.ping(address, DHT_PING_TIMEOUT);
            } catch (RuntimeException ignored) {
                // Not a reachable DHT node at that address/port - fine, just don't add it.
            }
        });
    }

    private void onAvailabilityChanged(PeerConnection connection) {
        updateInterest(connection);
        if (!connection.peerChoking() && connection.amInterested()) {
            requestMore(connection);
        }
    }

    /**
     * Does not coordinate in-flight requests across different peers - see
     * design_docs/0016's note on PieceManager - but must avoid re-asking
     * THIS SAME connection for a block it already has outstanding: since
     * PieceManager only knows "received," not "requested," repeatedly
     * selecting the same not-yet-received block would otherwise loop
     * forever whenever a peer's available piece has fewer un-requested
     * blocks than PIPELINE_DEPTH (trivially true for a single-block
     * piece). selectUnrequestedBlock cross-checks candidates against this
     * connection's own pending requests to prevent that.
     */
    private void requestMore(PeerConnection connection) {
        if (connection.peerChoking()) {
            return;
        }
        while (connection.pendingRequestCount() < PIPELINE_DEPTH) {
            OptionalInt pieceIndex = pieceManager.selectNextPiece(connection::peerHasPiece);
            if (pieceIndex.isEmpty()) {
                break;
            }
            OptionalInt blockIndex = selectUnrequestedBlock(pieceIndex.getAsInt(), connection);
            if (blockIndex.isEmpty()) {
                // Every missing block of the best available piece is already pending on this
                // connection - nothing new to ask for right now.
                break;
            }
            int begin = pieceManager.blockOffsetWithinPiece(pieceIndex.getAsInt(), blockIndex.getAsInt());
            int length = pieceManager.blockLength(pieceIndex.getAsInt(), blockIndex.getAsInt());
            connection.sendRequest(pieceIndex.getAsInt(), begin, length);
        }
    }

    private OptionalInt selectUnrequestedBlock(int pieceIndex, PeerConnection connection) {
        Set<Request> pending = connection.pendingRequestsSnapshot();
        int blockCount = pieceManager.blockCount(pieceIndex);
        for (int block = 0; block < blockCount; block++) {
            if (pieceManager.isBlockReceived(pieceIndex, block)) {
                continue;
            }
            int begin = pieceManager.blockOffsetWithinPiece(pieceIndex, block);
            int length = pieceManager.blockLength(pieceIndex, block);
            if (!pending.contains(new Request(pieceIndex, begin, length))) {
                return OptionalInt.of(block);
            }
        }
        return OptionalInt.empty();
    }

    /**
     * Deliberately simple - no upload-rate tracking or reciprocity (real
     * BEP 3 tit-for-tat), just a capped rotation across whoever's
     * currently interested. Confirmed with the user as a reasonable
     * trade-off; see design_docs/0025 and ChokingStrategy's own Javadoc.
     * Synchronized since this can be triggered concurrently from multiple
     * peers' read-loop threads (on Interested/NotInterested) as well as
     * the periodic scheduled tick.
     */
    private synchronized void updateChoking() {
        if (state != TorrentState.DOWNLOADING && state != TorrentState.SEEDING) {
            return;
        }
        List<PeerConnection> interested = connections.stream().filter(PeerConnection::peerInterested).toList();
        Set<PeerConnection> toUnchoke =
                ChokingStrategy.selectToUnchoke(interested, MAX_UNCHOKED_PEERS, chokingRotation.getAndIncrement());

        for (PeerConnection connection : connections) {
            boolean shouldUnchoke = toUnchoke.contains(connection);
            if (shouldUnchoke && connection.amChoking()) {
                connection.sendUnchoke();
            } else if (!shouldUnchoke && !connection.amChoking()) {
                connection.sendChoke();
            }
        }
    }

    private void onBlockRequested(PeerConnection connection, Request request) {
        if (connection.amChoking()) {
            return; // a compliant peer shouldn't request while choked - ignore rather than serve
        }
        if (request.length() <= 0 || request.length() > MAX_SERVABLE_BLOCK_LENGTH) {
            return;
        }
        if (!pieceManager.isComplete(request.index())) {
            return;
        }
        byte[] block;
        try {
            block = storage.read(pieceManager.pieceOffset(request.index()) + request.begin(), request.length());
        } catch (IOException e) {
            // A failed read serving one peer isn't fatal to the whole session the way a
            // failed write while downloading is (see onPieceBlockReceived) - log and move on.
            LOG.log(System.Logger.Level.WARNING, "Failed to read block to serve for torrent " + metadata.infoHash(), e);
            return;
        }
        connection.sendPiece(request.index(), request.begin(), block);
    }

    private void onPieceBlockReceived(PeerConnection connection, Piece piece) {
        try {
            storage.write(pieceManager.pieceOffset(piece.index()) + piece.begin(), piece.block());
        } catch (IOException e) {
            fail(e);
            return;
        }
        pieceManager.markBlockReceived(piece.index(), piece.begin());

        if (pieceManager.isPieceReadyToVerify(piece.index())) {
            verifyPiece(piece.index());
        }
        requestMore(connection);
    }

    /** Same pieceVerificationLimiter as verifyThenSettle() - bounds how many pieces are
     * mid-verification across the whole engine at once, not just this session's own
     * completions. See design_docs/0048. */
    private void verifyPiece(int pieceIndex) {
        boolean verified;
        pieceVerificationLimiter.acquireUninterruptibly();
        try {
            byte[] bytes = storage.read(pieceManager.pieceOffset(pieceIndex), pieceManager.pieceLength(pieceIndex));
            verified = pieceManager.verify(pieceIndex, bytes);
        } catch (IOException e) {
            fail(e);
            return;
        } finally {
            pieceVerificationLimiter.release();
        }
        if (verified) {
            listener.onPieceCompleted(this, pieceIndex);
            for (PeerConnection connection : connections) {
                connection.sendHave(pieceIndex);
            }
            checkForCompletion();
        }
    }

    private void checkForCompletion() {
        boolean justCompleted;
        synchronized (this) {
            justCompleted = state == TorrentState.DOWNLOADING && pieceManager.isAllComplete();
            if (justCompleted) {
                setState(TorrentState.SEEDING);
            }
        }
        if (!justCompleted) {
            return;
        }
        // Guarded, not unconditional - enterDownloading() calls checkForCompletion() on every
        // start(), including every resume and every restart of an already-complete torrent
        // (see its own comment), so without this guard a routine pause/resume cycle would
        // keep resetting the seed-time clock to zero. See design_docs/0054.
        if (completedAtEpochMillis == 0) {
            completedAtEpochMillis = System.currentTimeMillis();
        }
        for (PeerConnection connection : connections) {
            updateInterest(connection);
        }
        try {
            trackerClient.announce(new TrackerRequest(metadata.infoHash(), ourPeerId, ourListenPort,
                    bytesUploaded(), bytesDownloaded(), 0, TrackerEvent.COMPLETED, 0));
        } catch (RuntimeException ignored) {
            // best-effort - local completion state doesn't depend on the tracker being reachable
        }
    }

    public TorrentMetadata metadata() {
        return metadata;
    }

    public TorrentState state() {
        return state;
    }

    public Throwable lastError() {
        return lastError;
    }

    /** Null when unknown - see this field's own Javadoc. */
    public Instant addedAt() {
        return addedAt;
    }

    /** 0 until this session first reaches SEEDING - see this field's own Javadoc for why it's
     * purely in-memory. See design_docs/0054. */
    public long completedAtEpochMillis() {
        return completedAtEpochMillis;
    }

    /** See this field's own Javadoc. */
    public boolean wasCompleteOnRestore() {
        return wasCompleteOnRestore;
    }

    public SeedingLimitOverride seedingLimitOverride() {
        return seedingLimitOverride;
    }

    /** Called by TorrentEngine after it's already persisted the new value to this torrent's
     * marker file - this only updates the live, in-memory copy the engine's own periodic
     * seeding-limit check reads. See design_docs/0054. */
    public void setSeedingLimitOverride(SeedingLimitOverride seedingLimitOverride) {
        this.seedingLimitOverride = seedingLimitOverride;
    }

    public int connectedPeerCount() {
        return connections.size();
    }

    /** A read-only snapshot of one connected peer's state, for external consumers (the
     * REST layer) - not the live PeerConnection itself, which stays engine-internal (see
     * design_docs/0006/0031). */
    public record PeerSnapshot(
            PeerAddress address,
            PeerId peerId,
            boolean amChoking,
            boolean amInterested,
            boolean peerChoking,
            boolean peerInterested,
            long downloadedBytes,
            long uploadedBytes
    ) {
    }

    public List<PeerSnapshot> peers() {
        return connections.stream()
                .map(c -> new PeerSnapshot(c.remoteAddress(), c.remotePeerId(), c.amChoking(), c.amInterested(),
                        c.peerChoking(), c.peerInterested(), c.downloadedBytes(), c.uploadedBytes()))
                .toList();
    }

    /** Delegates straight to the wrapped TrackerClient - a plain TrackerClient reports
     * nothing (empty by default), NoOpTrackerClient (trackerless torrents) inherits that,
     * and MultiTrackerClient aggregates its TrackedTrackerClient-wrapped trackers' own
     * statuses. See design_docs/0031's Trackers endpoint. */
    public List<TrackerStatus> trackers() {
        return trackerClient.statuses();
    }

    /** A file's static shape (from TorrentMetadata.files()) plus how much of it is
     * currently downloaded, for external consumers - see design_docs/0031's Files
     * endpoint. bytesDownloaded is piece-granular (whole completed pieces only, same
     * verified-only basis as bytesDownloaded()/progress()), not block-granular - matches
     * pieceStates()'s own granularity, and a byte count that jumps in piece-sized steps
     * rather than continuously is fine for a per-file progress display. */
    public record FileProgress(List<String> pathSegments, long length, long bytesDownloaded) {
    }

    /** Files are laid out contiguously in the torrent's overall byte stream (standard
     * BitTorrent layout) with no alignment to piece boundaries, so a single piece can
     * span two files - each piece's contribution is split across every file it overlaps,
     * proportional to the overlap, rather than credited to just one of them. */
    public List<FileProgress> files() {
        List<TorrentFile> files = metadata.files();
        List<FileProgress> progress = new ArrayList<>(files.size());
        long fileStart = 0;
        for (TorrentFile file : files) {
            long fileEnd = fileStart + file.length();
            progress.add(new FileProgress(file.pathSegments(), file.length(), downloadedInRange(fileStart, fileEnd)));
            fileStart = fileEnd;
        }
        return progress;
    }

    private long downloadedInRange(long rangeStart, long rangeEnd) {
        long downloaded = 0;
        for (int i = 0; i < pieceManager.pieceCount(); i++) {
            if (!pieceManager.isComplete(i)) {
                continue;
            }
            long pieceStart = pieceManager.pieceOffset(i);
            long pieceEnd = pieceStart + pieceManager.pieceLength(i);
            long overlapStart = Math.max(pieceStart, rangeStart);
            long overlapEnd = Math.min(pieceEnd, rangeEnd);
            if (overlapStart < overlapEnd) {
                downloaded += overlapEnd - overlapStart;
            }
        }
        return downloaded;
    }

    /** One entry per piece, in index order - see design_docs/0031's Piece map endpoint. */
    public List<PieceState> pieceStates() {
        List<PieceState> states = new ArrayList<>(pieceManager.pieceCount());
        for (int i = 0; i < pieceManager.pieceCount(); i++) {
            states.add(pieceManager.stateOf(i));
        }
        return states;
    }

    /** True for a torrent with no tracker at all - a trackerless magnet resolved via DHT,
     * or a plain .torrent upload that genuinely listed none (see design_docs/0028's
     * NoOpTrackerClient). Exposed as TorrentView.usesDht - see design_docs/0031. */
    public boolean isTrackerless() {
        return trackerClient instanceof NoOpTrackerClient;
    }

    /** True only while the most recent tracker announce (start() or reannounce()) actually
     * fell back to DHT rather than succeeding via the tracker - see design_docs/0036 for
     * the backstop itself, design_docs/0039 for why this is exposed. Always false for a
     * trackerless torrent (isTrackerless() already covers that case). */
    public boolean isDhtBackstopActive() {
        return dhtBackstopActive;
    }

    public int completedPieceCount() {
        return pieceManager.completedCount();
    }

    public long bytesDownloaded() {
        long total = 0;
        for (int i = 0; i < pieceManager.pieceCount(); i++) {
            if (pieceManager.isComplete(i)) {
                total += pieceManager.pieceLength(i);
            }
        }
        return total;
    }

    /** Raw bytes received over the wire (every block, as soon as it arrives - see
     * PeerConnection), whether or not the piece it belongs to has passed verification
     * yet - unlike bytesDownloaded() (verified-complete pieces only, used for progress/%),
     * this exists purely for a rate calculation, where "how fast is data actually flowing
     * right now" matters more than "how much is proven correct so far". Never used for
     * progress: a piece that later fails verification has its block state reset
     * (PieceManager.verify), but this total isn't adjusted back down for that, so it
     * isn't a valid stand-in for verified progress. See design_docs/0031.
     *
     * <p>Mirrors bytesUploaded()'s accumulator-plus-live-connections pattern exactly (see
     * design_docs/0025) so a peer disconnecting doesn't cause this to drop. */
    public long bytesReceived() {
        long total = accumulatedReceived.get();
        for (PeerConnection connection : connections) {
            total += connection.downloadedBytes();
        }
        return total;
    }

    public long bytesRemaining() {
        return metadata.totalLength() - bytesDownloaded();
    }

    /** accumulatedUploaded alone only reflects peers that have already disconnected -
     * this adds still-connected peers' live counters so the reported figure doesn't
     * undercount while we're actively serving anyone. See design_docs/0025. Public so
     * the app layer can expose it (e.g. in TorrentView), not just the tracker requests. */
    public long bytesUploaded() {
        long total = accumulatedUploaded.get();
        for (PeerConnection connection : connections) {
            total += connection.uploadedBytes();
        }
        return total;
    }

    public double progress() {
        return metadata.totalLength() == 0 ? 1.0 : (double) bytesDownloaded() / metadata.totalLength();
    }

    private final class PeerListener implements PeerConnectionListener {
        @Override
        public void onMessage(PeerConnection connection, PeerMessage message) {
            handleMessage(connection, message);
        }

        @Override
        public void onDisconnected(PeerConnection connection, Throwable cause) {
            accumulatedUploaded.addAndGet(connection.uploadedBytes());
            accumulatedReceived.addAndGet(connection.downloadedBytes());
            connections.remove(connection);
        }
    }
}
