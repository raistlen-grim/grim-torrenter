package com.grimtorrenter.engine.torrent;

import com.grimtorrenter.engine.dht.DhtNode;
import com.grimtorrenter.engine.metainfo.TorrentFile;
import com.grimtorrenter.engine.metainfo.TorrentMetadata;
import com.grimtorrenter.engine.mse.EncryptionMode;
import com.grimtorrenter.engine.peer.PeerConnection;
import com.grimtorrenter.engine.peer.PeerConnectionListener;
import com.grimtorrenter.engine.peer.PeerSource;
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
import java.util.Collection;
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
     * just ut_pex for now, and not even that for a private torrent (BEP 27) - see
     * extensionsToAdvertise(). */
    private static final Map<String, Integer> PEX_EXTENSION_TO_ADVERTISE = Map.of(PEX_EXTENSION_NAME, PEX_EXTENSION_ID);
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
    /** Whether BEP 14 Local Service Discovery is genuinely running at the engine level right
     * now - a plain snapshot boolean, not a live-checked reference like dhtNode above, because
     * unlike DHT this session has no LSD-specific work of its own to do (no per-session
     * scheduled announce/lookup - LSD is entirely orchestrated by TorrentEngine's
     * maintenanceScheduler, see that class's own lsdService field Javadoc). Exists purely so
     * usesLsd() below can mirror usesDht()'s self-contained shape for TorrentView
     * (grimtorrenter-app) - TorrentEventListener deliberately has no TorrentEngine reference
     * (would create a circular CDI dependency with TorrentEngineProducer), so usesLsd can't be
     * computed from TorrentEngine at TorrentView-assembly time the way a naive "just ask the
     * engine" approach would. See design_docs/0062. */
    private final boolean lsdActive;
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
    /** Read once, at the point enterDownloading() schedules discoverPeersViaDht()'s periodic
     * task - not re-read mid-flight, same "fixed for this torrent's run, re-read on the next
     * start()" precedent a real tracker's own response.interval() already follows (a
     * ScheduledExecutorService's fixed-delay period can't be changed once scheduled without
     * cancelling and rebuilding it). Drives DHT peer discovery for any non-private torrent
     * DHT is eligible for, not just a genuinely trackerless one - see design_docs/0036's own
     * 2026-09-06 revision. Never null; callers that don't care get a fixed 300s default via
     * create()/restoreAsync()'s own lower-arity overloads. */
    private final Supplier<Long> dhtReannounceIntervalSeconds;

    /** This torrent's override of the global seeding-limit defaults - never null, defaults to
     * SeedingLimitOverride.INHERIT (both metrics follow the global default) via create()/
     * restoreAsync()'s own lower-arity overloads. Mutable at runtime (setSeedingLimitOverride())
     * since, unlike the other constructor-injected collaborators above, this is genuinely
     * per-torrent user data that can change after the session already exists. See
     * design_docs/0054. */
    private volatile SeedingLimitOverride seedingLimitOverride;
    /** Epoch millis this torrent first reached SEEDING, 0 if never. Was purely in-memory
     * (reset to 0 on every restart, per design_docs/0054's own callout - the seeding-limit
     * check's own byte counters already reset the same way, so this matched rather than being
     * a half-persisted exception to that) until design_docs/0064 started persisting it:
     * initialized from PersistedLifetimeStats at construction instead of always starting at 0,
     * which - as a direct consequence, not a separate fix - also closes a real restore-time
     * bug this field's own re-stamping guard (see checkForCompletion()) couldn't close on its
     * own: that guard only ever prevented re-stamping *within one process run*, so a restored
     * already-complete torrent's first post-restart completion check used to re-stamp this to
     * "now" every single restart. A restored session now starts with the real persisted value
     * already in place, so the same guard now correctly holds across restarts too. */
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
    /** This torrent's uploaded-byte total from every process run before this one, loaded once
     * at construction and never itself mutated afterward - lifetimeUploadedBytes() adds it to
     * bytesUploaded() (this run's own contribution), rather than bytesUploaded() itself being
     * made lifetime-cumulative, which would corrupt its two existing meanings: BEP 3's
     * "uploaded" announce field (session-scoped by protocol convention) and
     * design_docs/0054's seeding-ratio-limit check. See design_docs/0064. */
    private final long lifetimeUploadedBytesBaseline;
    /** Cumulative active (DOWNLOADING/VERIFYING/SEEDING) time from every process run before
     * this one, plus every completed active period so far *this* run - activeSince below
     * covers whatever active period is still ongoing. Folded in by setState() on every
     * transition out of an active state; timeActiveMillis() adds whatever's still ongoing.
     * See design_docs/0064. */
    private final AtomicLong activeMillisAccumulated;
    /** Null when not currently in an active state. Set by setState() on every transition into
     * DOWNLOADING/VERIFYING/SEEDING from a non-active state (left alone for a transition
     * between two active states, e.g. DOWNLOADING -&gt; SEEDING on completion - the clock keeps
     * running), cleared (after folding the elapsed time into activeMillisAccumulated) on every
     * transition into STOPPED/ERROR. See design_docs/0064. */
    private volatile Instant activeSince;
    /** Bytes received and discarded because the piece they belonged to failed its hash check
     * on live download (verifyPiece(), not verifyThenSettle()'s restore-time re-verification -
     * see wastedBytes()'s own Javadoc for why that distinction matters). Seeded from persisted
     * state at construction, like lifetimeUploadedBytesBaseline, but - unlike that field - this
     * one keeps accumulating directly (no existing "this session's own contribution" method to
     * add to), same idiom as accumulatedUploaded/accumulatedReceived. See design_docs/0066. */
    private final AtomicLong wastedBytes;
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
    /** How this session originally learned of each candidate address - first source wins
     * (putIfAbsent via recordKnownPeers()), never overwritten by a later rediscovery through a
     * different source. attemptConnect() looks this up to tag the resulting PeerConnection.
     * See design_docs/0066. */
    private final Map<PeerAddress, PeerSource> knownAddresses = new ConcurrentHashMap<>();
    /** Every address attemptConnect() has ever failed to reach, this session's whole
     * lifetime - excluded from fillConnections()'s own candidate selection alongside
     * currently-connected addresses, so a freed slot (a failed attempt, or a disconnect -
     * both now call fillConnections() again, see attemptConnect()/PeerListener.onDisconnected())
     * reaches a fresh candidate instead of re-attempting one already known dead. Deliberately
     * permanent, no retry-after-cooldown - confirmed with the user: matches this class's
     * existing no-retry-backoff simplicity (attemptConnect()'s own comment), and DHT/tracker/PEX
     * keep supplying fresh candidates continuously anyway, so there's little to gain from ever
     * retrying an address that's already failed once. Grows unboundedly over a very
     * long-running session in principle, same as knownAddresses itself already does (never
     * pruned) - accepted at this project's real-world swarm-size scale, not a new category of
     * growth this introduces. */
    private final Set<PeerAddress> failedAddresses = ConcurrentHashMap.newKeySet();
    /** Bounds concurrent connection attempts *plus* established connections to
     * MAX_CONNECTIONS, atomically - not a size-based check like fillConnections() used to
     * rely on alone. Necessary specifically because of the 2026-09-06 refill-on-failure
     * revision below: connections.size() only counts *established* connections, not attempts
     * still in flight, so several failures resolving within milliseconds of each other (a
     * "connection refused" is near-instant, unlike a 10-20s timeout) could each independently
     * see the same "N slots free" and each spawn up to N more attempts - an uncontrolled
     * cascade, not the small, bounded overshoot fillConnections()'s own older comment already
     * accepted. A real production `OutOfMemoryError` (reported 2026-09-06, right after this
     * revision shipped) is exactly that cascade: each PREFERRED-mode attempt does a real MSE
     * Diffie-Hellman handshake attempt first, so an unbounded burst of concurrent attempts is
     * a genuine memory/thread spike, not just cosmetic overshoot. One permit per attempt,
     * acquired before attemptConnect() ever starts and held for as long as that attempt is
     * either in flight or (on success) the resulting connection stays established - released
     * on failure (attemptConnect()'s own catch block) or on disconnect
     * (PeerListener.onDisconnected()). See design_docs/0017's own 2026-09-06 revision. */
    private final Semaphore connectionSlots = new Semaphore(MAX_CONNECTIONS);
    /** A second, narrower fix needed alongside connectionSlots (found the same day, once the
     * first fix was deployed): connectionSlots alone bounds the *total* number of concurrent
     * attempts, but does nothing to stop several concurrent fillConnections() calls from each
     * independently picking the *same* still-untried address before any of them has resolved -
     * observed in production as one address attempted over a dozen times within milliseconds,
     * every attempt wasted on a peer already known to be unreachable/refusing rather than
     * spreading across the real candidate pool. `.add()`'s own atomic "was this newly added"
     * return value is the claim: fillConnections() only spawns an attempt if it actually wins
     * the add for that address, so two concurrent calls can never both claim the same one.
     * Removed on success (attemptConnect(), now covered by the connections-based filter
     * instead) - but deliberately kept forever on failure (attemptConnect()'s catch block), not
     * removed once failedAddresses takes over as originally designed here: a real duplicate-
     * attempt race surfaced once this ran under genuinely concurrent load (a burst of
     * near-simultaneous fast failures - see design_docs/0017's own dated addendum) - releasing
     * the claim the instant after marking an address failed let a concurrent fillConnections()
     * call, whose own read of failedAddresses happened to be stale, re-claim and re-attempt an
     * address already known dead. Deliberately not the same set as failedAddresses despite now
     * overlapping with it for every failed address: a peer we connect to and later disconnect
     * from must remain eligible for reconnection (see failedAddresses's own Javadoc), which a
     * single shared "claimed forever" set would have broken for that case. See design_docs/0017's
     * own 2026-09-06 revision and its later dated addendum. */
    private final Set<PeerAddress> inFlightAddresses = ConcurrentHashMap.newKeySet();
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
    /** True only while the most recent tracker announce (start() or reannounce()) actually
     * failed - reflects current tracker health, not "ever failed this session," and not
     * whether DHT is actually running right now (discoverPeersViaDht() runs on its own
     * independent schedule regardless of this flag - see design_docs/0036's own 2026-09-06
     * revision). Only ever set true for a real (non-NoOp) TrackerClient; a genuinely
     * trackerless torrent's NoOpTrackerClient never fails, so this stays false there. See
     * design_docs/0039. */
    private volatile boolean dhtBackstopActive;

    private TorrentSession(TorrentMetadata metadata, TrackerClient trackerClient, TorrentStorage storage,
                            PieceManager pieceManager, PeerId ourPeerId, int ourListenPort,
                            TorrentSessionListener listener, DhtNode dhtNode, RateLimiters rateLimiters,
                            Semaphore pieceVerificationLimiter, Supplier<EncryptionMode> encryptionMode,
                            SeedingLimitOverride seedingLimitOverride, TorrentState initialState,
                            Instant addedAt, Supplier<Long> dhtReannounceIntervalSeconds, boolean lsdActive,
                            PersistedLifetimeStats persistedLifetimeStats) {
        this.metadata = metadata;
        this.trackerClient = trackerClient;
        this.storage = storage;
        this.pieceManager = pieceManager;
        this.ourPeerId = ourPeerId;
        this.ourListenPort = ourListenPort;
        this.listener = listener;
        this.dhtNode = dhtNode;
        this.lsdActive = lsdActive;
        this.rateLimiters = rateLimiters;
        this.pieceVerificationLimiter = pieceVerificationLimiter;
        this.encryptionMode = encryptionMode;
        this.seedingLimitOverride = seedingLimitOverride;
        this.state = initialState;
        this.addedAt = addedAt;
        this.dhtReannounceIntervalSeconds = dhtReannounceIntervalSeconds;
        this.lifetimeUploadedBytesBaseline = persistedLifetimeStats.uploadedBytesBaseline();
        this.activeMillisAccumulated = new AtomicLong(persistedLifetimeStats.activeMillisBaseline());
        this.completedAtEpochMillis = persistedLifetimeStats.completedAtEpochMillis();
        this.wastedBytes = new AtomicLong(persistedLifetimeStats.wastedBytesBaseline());
        // initialState can already be an active state (restoreAsync()'s VERIFYING) without
        // ever going through setState(), which is what normally starts the clock - see
        // design_docs/0064.
        if (isActiveState(initialState)) {
            this.activeSince = Instant.now();
        }
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

    /** Same as the sixteen-arg overload below but with lsdActive defaulted to false - for
     * every caller that predates LSD's addition (tests, mainly). See design_docs/0062. */
    public static TorrentSession create(TorrentMetadata metadata, TrackerClient trackerClient,
                                         Path downloadDirectory, PeerId ourPeerId, int ourListenPort,
                                         TorrentSessionListener listener, DhtNode dhtNode,
                                         RateLimiters rateLimiters, FileHandlePool fileHandlePool,
                                         Semaphore pieceVerificationLimiter,
                                         Supplier<EncryptionMode> encryptionMode,
                                         SeedingLimitOverride seedingLimitOverride,
                                         Instant addedAt,
                                         Supplier<Long> dhtReannounceIntervalSeconds) throws IOException {
        return create(metadata, trackerClient, downloadDirectory, ourPeerId, ourListenPort, listener, dhtNode,
                rateLimiters, fileHandlePool, pieceVerificationLimiter, encryptionMode, seedingLimitOverride,
                addedAt, dhtReannounceIntervalSeconds, false);
    }

    /** Same as the eighteen-arg overload below but with PersistedLifetimeStats.NONE - for
     * every caller that predates 0064 (tests, mainly). A genuinely new torrent has no lifetime
     * stats to load anyway, so this is also what TorrentEngine's own addTorrent() uses for a
     * brand-new torrent - only the reused-path/restoreAsync() cases pass real persisted
     * values. See design_docs/0064. */
    public static TorrentSession create(TorrentMetadata metadata, TrackerClient trackerClient,
                                         Path downloadDirectory, PeerId ourPeerId, int ourListenPort,
                                         TorrentSessionListener listener, DhtNode dhtNode,
                                         RateLimiters rateLimiters, FileHandlePool fileHandlePool,
                                         Semaphore pieceVerificationLimiter,
                                         Supplier<EncryptionMode> encryptionMode,
                                         SeedingLimitOverride seedingLimitOverride,
                                         Instant addedAt,
                                         Supplier<Long> dhtReannounceIntervalSeconds,
                                         boolean lsdActive) throws IOException {
        return create(metadata, trackerClient, downloadDirectory, ourPeerId, ourListenPort, listener, dhtNode,
                rateLimiters, fileHandlePool, pieceVerificationLimiter, encryptionMode, seedingLimitOverride,
                addedAt, dhtReannounceIntervalSeconds, lsdActive, PersistedLifetimeStats.NONE);
    }

    public static TorrentSession create(TorrentMetadata metadata, TrackerClient trackerClient,
                                         Path downloadDirectory, PeerId ourPeerId, int ourListenPort,
                                         TorrentSessionListener listener, DhtNode dhtNode,
                                         RateLimiters rateLimiters, FileHandlePool fileHandlePool,
                                         Semaphore pieceVerificationLimiter,
                                         Supplier<EncryptionMode> encryptionMode,
                                         SeedingLimitOverride seedingLimitOverride,
                                         Instant addedAt,
                                         Supplier<Long> dhtReannounceIntervalSeconds,
                                         boolean lsdActive,
                                         PersistedLifetimeStats persistedLifetimeStats) throws IOException {
        TorrentStorage storage = TorrentStorage.create(metadata, downloadDirectory, fileHandlePool);
        PieceManager pieceManager = new PieceManager(metadata);
        return new TorrentSession(metadata, trackerClient, storage, pieceManager, ourPeerId, ourListenPort,
                listener, dhtNode, rateLimiters, pieceVerificationLimiter, encryptionMode, seedingLimitOverride,
                TorrentState.STOPPED, addedAt, dhtReannounceIntervalSeconds, lsdActive, persistedLifetimeStats);
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

    /** Same as the seventeen-arg overload below but with lsdActive defaulted to false - for
     * every caller that predates LSD's addition (tests, mainly). See design_docs/0062. */
    public static TorrentSession restoreAsync(TorrentMetadata metadata, TrackerClient trackerClient,
                                               Path downloadDirectory, PeerId ourPeerId, int ourListenPort,
                                               TorrentSessionListener listener, DhtNode dhtNode,
                                               RateLimiters rateLimiters, FileHandlePool fileHandlePool,
                                               Semaphore pieceVerificationLimiter,
                                               Supplier<EncryptionMode> encryptionMode,
                                               SeedingLimitOverride seedingLimitOverride,
                                               Instant addedAt,
                                               Supplier<Long> dhtReannounceIntervalSeconds,
                                               boolean autoStart) throws IOException {
        return restoreAsync(metadata, trackerClient, downloadDirectory, ourPeerId, ourListenPort, listener,
                dhtNode, rateLimiters, fileHandlePool, pieceVerificationLimiter, encryptionMode,
                seedingLimitOverride, addedAt, dhtReannounceIntervalSeconds, false, autoStart);
    }

    /** Same as the nineteen-arg overload below but with PersistedLifetimeStats.NONE - for
     * every caller that predates 0064 (tests, mainly). See design_docs/0064. */
    public static TorrentSession restoreAsync(TorrentMetadata metadata, TrackerClient trackerClient,
                                               Path downloadDirectory, PeerId ourPeerId, int ourListenPort,
                                               TorrentSessionListener listener, DhtNode dhtNode,
                                               RateLimiters rateLimiters, FileHandlePool fileHandlePool,
                                               Semaphore pieceVerificationLimiter,
                                               Supplier<EncryptionMode> encryptionMode,
                                               SeedingLimitOverride seedingLimitOverride,
                                               Instant addedAt,
                                               Supplier<Long> dhtReannounceIntervalSeconds,
                                               boolean lsdActive,
                                               boolean autoStart) throws IOException {
        return restoreAsync(metadata, trackerClient, downloadDirectory, ourPeerId, ourListenPort, listener,
                dhtNode, rateLimiters, fileHandlePool, pieceVerificationLimiter, encryptionMode,
                seedingLimitOverride, addedAt, dhtReannounceIntervalSeconds, lsdActive, autoStart,
                PersistedLifetimeStats.NONE);
    }

    public static TorrentSession restoreAsync(TorrentMetadata metadata, TrackerClient trackerClient,
                                               Path downloadDirectory, PeerId ourPeerId, int ourListenPort,
                                               TorrentSessionListener listener, DhtNode dhtNode,
                                               RateLimiters rateLimiters, FileHandlePool fileHandlePool,
                                               Semaphore pieceVerificationLimiter,
                                               Supplier<EncryptionMode> encryptionMode,
                                               SeedingLimitOverride seedingLimitOverride,
                                               Instant addedAt,
                                               Supplier<Long> dhtReannounceIntervalSeconds,
                                               boolean lsdActive,
                                               boolean autoStart,
                                               PersistedLifetimeStats persistedLifetimeStats) throws IOException {
        TorrentStorage storage = TorrentStorage.create(metadata, downloadDirectory, fileHandlePool);
        PieceManager pieceManager = new PieceManager(metadata);
        TorrentSession session = new TorrentSession(metadata, trackerClient, storage, pieceManager,
                ourPeerId, ourListenPort, listener, dhtNode, rateLimiters, pieceVerificationLimiter,
                encryptionMode, seedingLimitOverride, TorrentState.VERIFYING, addedAt,
                dhtReannounceIntervalSeconds, lsdActive, persistedLifetimeStats);
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
     * createTrackerClient) is no longer special-cased here (see design_docs/0036's own
     * 2026-09-06 revision) - NoOpTrackerClient.announce() never throws and always succeeds
     * with zero peers and a deliberately huge interval, so it flows through the exact same
     * path as any other tracker's success response, harmlessly. DHT peer discovery - the
     * thing this branch used to exist to reach via startViaDht() - is now enterDownloading()'s
     * job for every non-private torrent regardless of tracker kind, via the periodic
     * discoverPeersViaDht() task below. */
    public synchronized void start() {
        if (state != TorrentState.STOPPED) {
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
        enterDownloading(response.peers(), Math.max(response.interval(), 30), PeerSource.TRACKER);
    }

    /** BEP 27: a private torrent's peer discovery must stay confined to whatever its
     * tracker(s) coordinate - every DHT touch point in this class (find_peers lookups,
     * implicitly announce_peer too, since PeerLookup.findPeers does both - see
     * design_docs/0028) checks this before ever using dhtNode, the same way each already
     * checks dhtNode != null. Does not affect onPeerAnnouncedDhtPort()'s ping - that only
     * strengthens this process's general DHT routing table and never queries or announces
     * anything about this specific torrent's info hash, so it carries no privacy leak. */
    private boolean dhtEligible() {
        return dhtNode != null && !metadata.isPrivate();
    }

    /** BEP 27: never advertise ut_pex support for a private torrent - not sending it in our
     * own extended handshake's "m" dict means a well-behaved peer never sends us one either.
     * See sendPexUpdates() for the other, equally necessary half of this: that method's own
     * decision to send a peer PEX data depends entirely on whether *they* advertised support
     * in *their* handshake, completely independent of what we advertise here, so this alone
     * isn't sufficient - both halves gate on metadata.isPrivate(). */
    private Map<String, Integer> extensionsToAdvertise() {
        return metadata.isPrivate() ? Map.of() : PEX_EXTENSION_TO_ADVERTISE;
    }

    /** Every tracker failed (MultiTrackerClient announced to all of them concurrently and
     * every one failed, see design_docs/0022's own 2026-09-06 revision) - rather than going
     * straight to ERROR, falls back to a DHT peer
     * lookup, the same motivation as multi-tracker fallback itself: a torrent's tracker(s)
     * being completely unreachable shouldn't strand the torrent when another peer-discovery
     * path is available. A genuinely trackerless torrent's NoOpTrackerClient never throws, so
     * this is only ever reached for a real TrackerClient that's actually failing. See
     * design_docs/0036.
     *
     * <p>An empty-but-successful DHT lookup still counts as success here (same as a tracker
     * responding with zero peers already does on the normal path) - it means DHT itself is
     * reachable, just that no peer happens to be known for this torrent right now; ERROR is
     * reserved for "no peer-discovery path worked at all," not "found nobody this time." */
    private void startViaDhtBackstop(RuntimeException trackerFailure) {
        if (!dhtEligible()) {
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
        enterDownloading(peers, DHT_BACKSTOP_REANNOUNCE_INTERVAL_SECONDS, PeerSource.DHT);
    }

    private void enterDownloading(List<PeerAddress> peers, long reannounceIntervalSeconds, PeerSource source) {
        recordKnownPeers(peers, source);
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
        // Zero initial delay, unlike every task above - a freshly-started torrent shouldn't
        // wait a full dhtReannounceIntervalSeconds (default 300s) for its first DHT lookup,
        // the same immediacy the old trackerless-only startViaDht() used to provide
        // synchronously, just non-blockingly here instead. See discoverPeersViaDht()'s own
        // Javadoc.
        scheduler.scheduleWithFixedDelay(this::discoverPeersViaDht,
                0, dhtReannounceIntervalSeconds.get(), TimeUnit.SECONDS);

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

    /** DOWNLOADING/VERIFYING/SEEDING count as "active" for timeActiveMillis() - anything the
     * torrent is actively doing, not narrowly "transferring bytes right now". STOPPED/ERROR
     * don't. See design_docs/0064. */
    private static boolean isActiveState(TorrentState state) {
        return state == TorrentState.DOWNLOADING || state == TorrentState.VERIFYING || state == TorrentState.SEEDING;
    }

    private void setState(TorrentState newState) {
        TorrentState old = state;
        state = newState;
        if (old != newState) {
            boolean wasActive = isActiveState(old);
            boolean nowActive = isActiveState(newState);
            if (nowActive && !wasActive) {
                activeSince = Instant.now();
            } else if (!nowActive && wasActive) {
                Instant since = activeSince;
                if (since != null) {
                    activeMillisAccumulated.addAndGet(Duration.between(since, Instant.now()).toMillis());
                }
                activeSince = null;
            }
            LOG.log(System.Logger.Level.INFO,
                    "Torrent " + metadata.infoHash() + ": " + old + " -> " + newState);
            listener.onStateChanged(this, old, newState);
        }
    }

    /** Package-private (not private) so tests can trigger exactly one reannounce cycle
     * directly, rather than waiting out the real scheduled interval (30s minimum) - same
     * rationale as TorrentEngine.selectTrackerTiers's own package-private-for-testing note.
     * A genuinely trackerless torrent's NoOpTrackerClient.announce() always succeeds
     * instantly with zero peers and a deliberately huge interval, so this cycle is harmless
     * (if largely pointless) for it - its real peer discovery is discoverPeersViaDht()'s job,
     * on its own independent schedule. See design_docs/0036's own 2026-09-06 revision.
     *
     * <p>dhtBackstopActive now purely reflects the tracker's own last-attempt health (true
     * on failure, false on success) - decoupled from whether a DHT lookup actually ran, since
     * discoverPeersViaDht() below runs on its own schedule regardless of tracker health. */
    void reannounce() {
        if (state != TorrentState.DOWNLOADING && state != TorrentState.SEEDING) {
            return;
        }
        try {
            TrackerResponse response = trackerClient.announce(new TrackerRequest(metadata.infoHash(), ourPeerId,
                    ourListenPort, bytesUploaded(), bytesDownloaded(), bytesRemaining(), null, NUM_WANT));
            dhtBackstopActive = false;
            recordKnownPeers(response.peers(), PeerSource.TRACKER);
            fillConnections();
        } catch (RuntimeException e) {
            // Transient tracker failure - existing connections keep working; retry next interval.
            // DHT peer discovery continues regardless, on its own schedule (discoverPeersViaDht()).
            LOG.log(System.Logger.Level.DEBUG, "Re-announce failed for " + metadata.infoHash(), e);
            dhtBackstopActive = true;
        }
    }

    /** BEP 5: independent, periodic DHT peer discovery - concurrent with, not a substitute
     * for, whatever a tracker itself already provides. Runs for any non-private torrent DHT
     * is eligible for (dhtEligible()), regardless of tracker health, on its own schedule (see
     * enterDownloading()) rather than being tied to reannounce()'s tracker-driven cadence -
     * this is the revision design_docs/0036 describes on 2026-09-06: DHT was previously only
     * ever consulted as a last-resort backstop once every tracker had failed outright
     * (startViaDhtBackstop()/the now-removed reannounceViaDhtBackstop()) or as the sole
     * mechanism for a genuinely trackerless torrent (the now-removed startViaDht()/
     * reannounceViaDht()) - both of those were real DHT usage, just gated far more narrowly
     * than a healthy tracker-bearing torrent needs to get any DHT-sourced peers at all.
     *
     * <p>Runs on its own virtual thread per tick, same reasoning as every other DHT call site
     * in this class: a multi-second lookup must never block the session's single scheduler
     * thread, shared with keepalive/choking/PEX/reannounce. addKnownPeers() is safe to call
     * regardless of what state the session is in by the time this completes. Package-private,
     * not private, so tests can trigger exactly one cycle directly rather than waiting out
     * the real scheduled interval - same rationale as reannounce()'s own package-private-for-
     * testing note. */
    void discoverPeersViaDht() {
        if (!dhtEligible()) {
            return;
        }
        Thread.ofVirtual().start(() -> {
            try {
                List<PeerAddress> peers = dhtNode.findPeers(metadata.infoHash(), ourListenPort, false, DHT_QUERY_TIMEOUT);
                // DEBUG, not silent - this was the missing half of the attemptConnect() logging
                // added while diagnosing the original peer-count gap: without this, "DHT ran but
                // found almost nothing" and "DHT hasn't run yet" were indistinguishable from the
                // outside.
                LOG.log(System.Logger.Level.DEBUG, "DHT peer discovery for " + metadata.infoHash()
                        + " found " + peers.size() + " peer(s)");
                addKnownPeers(peers, PeerSource.DHT);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.DEBUG, "DHT peer discovery failed for " + metadata.infoHash(), e);
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
     *
     * <p>BEP 27: never sends anything for a private torrent, regardless of what a connected
     * peer advertised in their own handshake - extensionsToAdvertise() only stops us
     * advertising ut_pex ourselves (so a well-behaved peer never sends us one), but a peer
     * choosing to advertise ut_pex support in *their* own handshake is entirely their own
     * decision, unaffected by ours; this is the check that actually stops us gossiping a
     * private swarm's membership regardless. */
    void sendPexUpdates() {
        if (metadata.isPrivate()) {
            return;
        }
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
     * message is dropped silently rather than disconnecting a peer over one bad message.
     *
     * <p>BEP 27: ignored entirely for a private torrent, belt-and-suspenders against a
     * peer sending one anyway despite us never advertising support (extensionsToAdvertise())
     * - private-torrent peer discovery must stay confined to the tracker, so an unsolicited
     * PEX "added" list is never allowed to feed knownAddresses here even if one arrives. */
    private void handleExtended(PeerConnection connection, Extended extended) {
        if (metadata.isPrivate() || extended.extendedMessageId() != PEX_EXTENSION_ID) {
            return;
        }
        try {
            PexMessage message = PexCodec.decode(extended.payload());
            addKnownPeers(message.added(), PeerSource.PEX);
        } catch (RuntimeException ignored) {
            // Malformed ut_pex message - drop it, same tolerance MetadataFetcher's own
            // ut_metadata decoding already applies to a peer sending us garbage.
        }
    }

    /**
     * connectionSlots (a Semaphore, not a size check) is what bounds *total* concurrency -
     * see its own field Javadoc for why a plain MAX_CONNECTIONS - connections.size() check
     * stopped being safe once attemptConnect()/onDisconnected() started calling this
     * reactively. inFlightAddresses.add()'s own atomic return value is what stops two
     * concurrent calls from both claiming the *same* candidate - see its own field Javadoc.
     * The .limit(MAX_CONNECTIONS) below is purely a cheap upper bound on how much of
     * knownAddresses this one call ever needs to scan; tryAcquire() is what actually stops
     * the loop once slots run out, every single call, regardless of how many other threads are
     * calling this concurrently.
     */
    private void fillConnections() {
        if (state != TorrentState.DOWNLOADING && state != TorrentState.SEEDING) {
            return;
        }
        List<PeerAddress> candidates = knownAddresses.keySet().stream()
                .filter(address -> !failedAddresses.contains(address))
                .filter(address -> !inFlightAddresses.contains(address))
                .filter(address -> connections.stream().noneMatch(c -> c.remoteAddress().equals(address)))
                .limit(MAX_CONNECTIONS)
                .toList();
        for (PeerAddress address : candidates) {
            if (!inFlightAddresses.add(address)) {
                // Lost the claim race to another concurrent fillConnections() call - it's
                // already being attempted, skip without touching a slot.
                continue;
            }
            if (!connectionSlots.tryAcquire()) {
                inFlightAddresses.remove(address);
                break;
            }
            Thread.ofVirtual().start(() -> attemptConnect(address));
        }
    }

    /** No retry backoff for a failed address within one fillConnections() batch - permanently
     * excluded instead (failedAddresses), not retried later this session at all. On failure,
     * releases this attempt's connectionSlots permit and calls fillConnections() again so a
     * fresh candidate fills the slot this one would have taken - previously, only four
     * external triggers (start()/reannounce()/discoverPeersViaDht()/addKnownPeers()) ever
     * refilled connections, so a burst of fast failures (the common case - most tracker/DHT-
     * supplied addresses are unreachable at any given moment) left the session under-connected
     * until the next one of those, up to dhtReannounceIntervalSeconds (default 300s) away. See
     * design_docs/0036's own 2026-09-06 revision - and connectionSlots's own Javadoc for why
     * the release-then-refill pair is safe against concurrent stampeding where a plain
     * connections.size() check was not. */
    private void attemptConnect(PeerAddress address) {
        try {
            PeerSource source = knownAddresses.getOrDefault(address, PeerSource.UNKNOWN);
            PeerConnection connection = PeerConnection.connect(address, metadata.infoHash(), ourPeerId,
                    new PeerListener(), extensionsToAdvertise(), rateLimiters, encryptionMode.get(), source);
            connections.add(connection);
            // Now covered by the connections-based filter in fillConnections() instead -
            // removing the inFlightAddresses claim just avoids that set growing forever with
            // entries that no other check ever needed again.
            inFlightAddresses.remove(address);
            onPeerConnected(connection);
            // This attempt's connectionSlots permit deliberately stays held - it now represents
            // the established connection itself, released only on disconnect (see
            // PeerListener.onDisconnected()).
        } catch (IOException | RuntimeException e) {
            // Most tracker-provided addresses are unreachable - this is the common case, not exceptional -
            // but silently swallowing every failure left no way to tell that apart from a systemic
            // connection-layer problem (e.g. every attempt failing) without this. DEBUG, not WARNING -
            // still the expected common case, just now observable when needed.
            LOG.log(System.Logger.Level.DEBUG, "Connection attempt to " + address + " for "
                    + metadata.infoHash() + " failed: " + e, e);
            failedAddresses.add(address);
            // Deliberately NOT inFlightAddresses.remove(address) here (unlike the success path
            // above) - see design_docs/0017's own dated addendum for the real duplicate-attempt
            // race this used to open: releasing the in-flight claim the instant after marking an
            // address failed let a concurrent fillConnections() call, whose own read of
            // failedAddresses happened to be stale (ran before this add() became visible to it),
            // see the address as "not failed, not in flight" and re-claim + re-attempt it. Since
            // failedAddresses already excludes this address from every future candidate snapshot
            // permanently, there's no correctness need to free its inFlightAddresses slot too -
            // leaving it claimed forever closes the race instead of just narrowing it, at the
            // cost of a redundant entry in a set that already grows unboundedly per session for
            // the same accepted reason failedAddresses itself does (see that field's own
            // Javadoc).
            connectionSlots.release();
            fillConnections();
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
        // connectionSlots, not a connections.size() check - inbound and outbound connections
        // share the same MAX_CONNECTIONS budget, and only the semaphore accounts for outbound
        // attempts still in flight (see connectionSlots's own Javadoc). A size check here
        // would let inbound and outbound connections each independently race past the real
        // cap, unaware of each other.
        if (!connectionSlots.tryAcquire()) {
            socket.close();
            return;
        }
        PeerConnection connection;
        try {
            connection = PeerConnection.accept(socket, in, out, remoteHandshake, ourPeerId,
                    new PeerListener(), extensionsToAdvertise(), rateLimiters);
        } catch (IOException | RuntimeException e) {
            connectionSlots.release();
            throw e;
        }
        connections.add(connection);
        onPeerConnected(connection);
    }

    /**
     * Seeds additional known peer addresses directly, bypassing tracker announce entirely.
     * Its only current external caller is TorrentEngine.onLsdPeerFound() (source LSD); source
     * is an explicit parameter rather than assumed, since this is a general "seed addresses
     * from outside" entry point, not an LSD-specific one - see design_docs/0066. Safe to call
     * regardless of current state; only actually attempts connections if the session is
     * already running.
     */
    public void addKnownPeers(List<PeerAddress> addresses, PeerSource source) {
        recordKnownPeers(addresses, source);
        fillConnections();
    }

    /** First source wins - putIfAbsent, not put, so a peer independently rediscovered later
     * through a different source keeps whichever one originally told us about it. See
     * design_docs/0066. */
    private void recordKnownPeers(Collection<PeerAddress> addresses, PeerSource source) {
        for (PeerAddress address : addresses) {
            knownAddresses.putIfAbsent(address, source);
        }
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
            if (!verified) {
                // Live download only, not verifyThenSettle()'s restore-time re-check - see
                // wastedBytes()'s own Javadoc. design_docs/0066.
                wastedBytes.addAndGet(bytes.length);
            }
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
                // Stamped *before* setState() flips the (volatile) state field, both still
                // inside this same synchronized block - not just "as soon as possible after."
                // state becomes visible as SEEDING to any other thread the instant setState()
                // runs, with no synchronization of its own required to observe that (a plain
                // volatile read, e.g. checkSeedingLimits() reading session.state() from a
                // completely different thread) - the Java Memory Model gives no guarantee that
                // such a reader also sees completedAtEpochMillis's write if it happened *after*
                // exiting this block, even by a few CPU instructions. Ordering it first, still
                // under the same lock, means the happens-before edge on the state write also
                // covers this one. Guarded, not unconditional - enterDownloading() calls
                // checkForCompletion() on every start(), including every resume and every
                // restart of an already-complete torrent (see its own comment), so without this
                // guard a routine pause/resume cycle would keep resetting the seed-time clock to
                // zero. See design_docs/0054/0064.
                if (completedAtEpochMillis == 0) {
                    completedAtEpochMillis = System.currentTimeMillis();
                }
                setState(TorrentState.SEEDING);
            }
        }
        if (!justCompleted) {
            return;
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

    /** 0 if this torrent has never completed - see this field's own Javadoc. See
     * design_docs/0054/0064. */
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

    /** What TorrentEngine loads from (or defaults for a brand-new torrent to) this torrent's
     * lifetime-stats marker, and hands to create()/restoreAsync() - see design_docs/0064/0065.
     * NONE is the all-zero baseline every genuinely new torrent starts from. */
    public record PersistedLifetimeStats(long uploadedBytesBaseline, long activeMillisBaseline,
                                          long completedAtEpochMillis, long wastedBytesBaseline) {
        public static final PersistedLifetimeStats NONE = new PersistedLifetimeStats(0, 0, 0, 0);
    }

    /** baseline + this run's own contribution (bytesUploaded()) - see
     * lifetimeUploadedBytesBaseline's own Javadoc for why this isn't bytesUploaded() itself
     * made lifetime-cumulative. See design_docs/0064. */
    public long lifetimeUploadedBytes() {
        return lifetimeUploadedBytesBaseline + bytesUploaded();
    }

    /** Cumulative time spent DOWNLOADING/VERIFYING/SEEDING, across every process run this
     * torrent has ever existed for - see activeMillisAccumulated/activeSince's own Javadoc.
     * See design_docs/0064. */
    public long timeActiveMillis() {
        Instant since = activeSince;
        long ongoing = since == null ? 0 : Duration.between(since, Instant.now()).toMillis();
        return activeMillisAccumulated.get() + ongoing;
    }

    /** Bytes received and discarded because the piece they belonged to failed a live-download
     * hash check - see this field's own Javadoc for why restore-time re-verification
     * (verifyThenSettle()) deliberately doesn't feed this. See design_docs/0066. */
    public long wastedBytes() {
        return wastedBytes.get();
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
            long uploadedBytes,
            boolean incoming,
            PeerSource source,
            /** Fraction (0-1) of the torrent's total pieces this peer has, per their
             * advertised bitfield/Have messages. See design_docs/0067. */
            double percentAvailable,
            /** Fraction (0-1) of the pieces *we still need* that this peer has - explains why
             * a peer isn't helping much (they may have plenty overall but little we lack). 0
             * when we need nothing (already complete/seeding) - moot once there's nothing left
             * to want. See design_docs/0067. */
            double relevance
    ) {
    }

    public List<PeerSnapshot> peers() {
        int totalPieces = pieceManager.pieceCount();
        int needed = 0;
        for (int i = 0; i < totalPieces; i++) {
            if (!pieceManager.isComplete(i)) {
                needed++;
            }
        }
        int stillNeeded = needed;
        return connections.stream()
                .map(c -> {
                    int has = 0;
                    int relevant = 0;
                    for (int i = 0; i < totalPieces; i++) {
                        if (c.peerHasPiece(i)) {
                            has++;
                            if (!pieceManager.isComplete(i)) {
                                relevant++;
                            }
                        }
                    }
                    double percentAvailable = totalPieces == 0 ? 0 : (double) has / totalPieces;
                    double relevance = stillNeeded == 0 ? 0 : (double) relevant / stillNeeded;
                    return new PeerSnapshot(c.remoteAddress(), c.remotePeerId(), c.amChoking(), c.amInterested(),
                            c.peerChoking(), c.peerInterested(), c.downloadedBytes(), c.uploadedBytes(),
                            c.incoming(), c.source(), percentAvailable, relevance);
                })
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
     * NoOpTrackerClient). A distinct concept from usesDht() below: this is about whether a
     * tracker exists at all, not whether DHT is actually contributing peers - a private
     * trackerless torrent (isTrackerless() true, usesDht() false) or a tracker-bearing
     * torrent with DHT globally disabled (isTrackerless() false, usesDht() false) both show
     * that the two can diverge. No longer what TorrentView.usesDht reads - see usesDht()
     * below, added by design_docs/0036's own 2026-09-06 revision. */
    public boolean isTrackerless() {
        return trackerClient instanceof NoOpTrackerClient;
    }

    /** True whenever DHT is actually eligible as a peer source for this torrent right now -
     * dhtEligible(), exposed publicly. Replaces isTrackerless() as what TorrentView.usesDht
     * reads (see design_docs/0031, design_docs/0036's own 2026-09-06 revision): DHT is now a
     * routine concurrent peer source for any non-private torrent with DHT configured, not
     * just a trackerless-only or last-resort mechanism, so "does this torrent have zero
     * trackers" is no longer the question the frontend's "DHT Enabled/Disabled" label
     * actually needs answered. */
    public boolean usesDht() {
        return dhtEligible();
    }

    /** BEP 27 gating, same reasoning as dhtEligible()/extensionsToAdvertise() above: a private
     * torrent's info hash must never be broadcast to the LAN via LSD either. lsdActive itself
     * is a construction-time snapshot of "was LSD running at the engine level when this session
     * was created" - see that field's own Javadoc for why this can't be a live dhtNode-style
     * reference. See design_docs/0062. */
    public boolean usesLsd() {
        return lsdActive && !metadata.isPrivate();
    }

    /** True only while the most recent tracker announce (start() or reannounce()) actually
     * failed - see design_docs/0036 for the backstop this originally tracked, design_docs/0039
     * for why this is exposed. Purely a tracker-health signal now (decoupled from whether a
     * DHT lookup happens to be running - see discoverPeersViaDht(), which runs on its own
     * schedule regardless). Always false for a trackerless torrent, whose NoOpTrackerClient
     * never fails. */
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
            // Releases this connection's own connectionSlots permit, held since attemptConnect()/
            // acceptIncomingConnection() first succeeded - then backfills the slot it just freed.
            // Deliberately not added to failedAddresses: this peer was reachable and connected
            // successfully once, so there's no reason to treat it as dead going forward (unlike
            // attemptConnect()'s own catch block, which never got that far). See
            // design_docs/0017's own 2026-09-06 revision.
            connectionSlots.release();
            fillConnections();
        }
    }
}
