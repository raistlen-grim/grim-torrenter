/** FETCHING_METADATA isn't a real backend TorrentState (TorrentSession never has that value) -
 * it's a pending magnet, rendered through the same Torrent shape via a sentinel string. See
 * design_docs/0070. */
/** STARTING: a start whose first announce is still in flight (design_docs/0098). */
export type TorrentState = 'DOWNLOADING' | 'VERIFYING' | 'SEEDING' | 'STOPPED' | 'ERROR' | 'STARTING' | 'FETCHING_METADATA';

/** Matches the backend's PieceState enum - see design_docs/0031's Piece map endpoint. */
export type PieceState = 'NEEDED' | 'IN_PROGRESS' | 'COMPLETE';

/** Matches the backend's PiecesView - see design_docs/0032's task 7. */
export interface PiecesResponse {
  pieces: PieceState[];
  pieceLength: number;
}

export interface Torrent {
  infoHash: string;
  name: string;
  state: TorrentState;
  progress: number;
  /** Verified-complete pieces only - moves in whole-piece jumps, drives progress/%. */
  bytesDownloaded: number;
  bytesUploaded: number;
  totalLength: number;
  connectedPeers: number;
  completedPieces: number;
  totalPieces: number;
  lastError: string | null;
  /** True whenever DHT is actually eligible as a peer source for this torrent right now -
   * DHT configured and the torrent isn't private (BEP 27), regardless of tracker presence or
   * health. See TorrentSession.usesDht()/design_docs/0036's own 2026-09-06 revision. */
  usesDht: boolean;
  trackerCount: number;
  /** True only while the most recent tracker announce actually failed - a separate,
   * tracker-health-only signal from usesDht, independent of whether DHT happens to be
   * eligible at all. See design_docs/0036/0039. */
  dhtBackstopActive: boolean;
  /** True whenever BEP 14 Local Service Discovery was running at the engine level when this
   * torrent's session was created and it isn't private (BEP 27) - same eligibility shape as
   * usesDht. See TorrentSession.usesLsd()/design_docs/0062. */
  usesLsd: boolean;
  /** ISO instant string, or null when unknown - a torrent directory added before this field
   * existed has no marker to read it back from and is never backfilled with a guess. See
   * design_docs/0032. */
  addedAt: string | null;
  /** This torrent's total uploaded bytes across every process run, not just this one - see
   * the backend's TorrentSession.lifetimeUploadedBytes()/design_docs/0064. */
  lifetimeUploadedBytes: number;
  /** Cumulative milliseconds spent DOWNLOADING/VERIFYING/SEEDING, across every process run.
   * See the backend's TorrentSession.timeActiveMillis()/design_docs/0064. */
  timeActiveMillis: number;
  /** Epoch millis this torrent first reached SEEDING, or 0 if it never has. See the backend's
   * TorrentSession.completedAtEpochMillis()/design_docs/0064. */
  completedAtEpochMillis: number;
  /** Bytes received and discarded to a failed piece hash check, lifetime. See the backend's
   * TorrentSession.wastedBytes()/design_docs/0066. */
  wastedBytes: number;
  /** Ids of this torrent's labels - ids, not names; resolve them through LabelService, so a
   * rename never changes a torrent. See design_docs/0077. */
  labelIds: string[];
  /** The current rates, measured by the server (the mean of its last 5 s) - 0 unless running.
   * See design_docs/0102. */
  downloadRateBytesPerSec: number;
  uploadRateBytesPerSec: number;
  /** Bytes/sec for each of up to the last 60 s, oldest first - feeds the Down cell's sparkline
   * tooltip (design_docs/0063). Empty when nothing arrived in that time. */
  downloadRateTrend: number[];
  /** Seconds to completion; null unless etaStatus is ESTIMATE. */
  etaSeconds: number | null;
  etaStatus: EtaStatus;
  /** Wanted pieces no connected peer has - set only when etaStatus is UNAVAILABLE. */
  unavailablePieces: number;
  /** The limit applying to this torrent right now (0 for none), whose it is, and whether a
   * limiter held this torrent back in the last few seconds. See design_docs/0102. */
  downloadLimitBytesPerSec: number;
  downloadLimitSource: LimitSource;
  downloadCapped: boolean;
  uploadLimitBytesPerSec: number;
  uploadLimitSource: LimitSource;
  uploadCapped: boolean;
}

/** Why a torrent's ETA is what it is - checked in this order on the server, the first that
 * applies wins. Only ESTIMATE carries etaSeconds. See design_docs/0102. */
export type EtaStatus = 'COMPLETE' | 'NOT_RUNNING' | 'NO_PEERS' | 'UNAVAILABLE' | 'CHOKED' | 'STALLED' | 'ESTIMATE';

/** Where a rate limit comes from: the global setting, the off-hours schedule, or the torrent's
 * own override. See design_docs/0102. */
export type LimitSource = 'NONE' | 'GLOBAL' | 'SCHEDULED' | 'TORRENT';

/** Matches the backend's TransferView - the engine-wide figures behind the header and footer,
 * pushed as the "transfer" WebSocket message on every snapshot tick. ratio is null until
 * something has been downloaded. See design_docs/0102. */
export interface TransferStats {
  downloadRateBytesPerSec: number;
  uploadRateBytesPerSec: number;
  downloadLimitBytesPerSec: number;
  downloadLimitSource: LimitSource;
  downloadCapped: boolean;
  uploadLimitBytesPerSec: number;
  uploadLimitSource: LimitSource;
  uploadCapped: boolean;
  lifetimeDownloadedBytes: number;
  lifetimeUploadedBytes: number;
  ratio: number | null;
}

/** A managed label: an immutable id (what torrents store) and a mutable display name. See
 * design_docs/0077. */
export interface Label {
  id: string;
  name: string;
}

/** alreadyExisted distinguishes "just added" from "you already had this" - both return
 * the same torrent, but the frontend needs to know which happened to give unambiguous
 * feedback rather than looking like the upload silently did nothing. See design_docs/0029. */
export interface AddTorrentResponse {
  torrent: Torrent;
  alreadyExisted: boolean;
}

/** Matches the backend's FileView. See design_docs/0031. */
export interface TorrentFile {
  pathSegments: string[];
  length: number;
  bytesDownloaded: number;
  priority: FilePriority;
}

/** SKIP is never requested; the rest are all wanted and differ only in fetch order. See
 * design_docs/0075. */
export type FilePriority = 'SKIP' | 'LOW' | 'MEDIUM' | 'HIGH';

/** Matches the backend's PeerView. No client-name decoding yet. See design_docs/0031. */
export interface Peer {
  address: string;
  port: number;
  peerId: string;
  amChoking: boolean;
  amInterested: boolean;
  peerChoking: boolean;
  peerInterested: boolean;
  downloadedBytes: number;
  uploadedBytes: number;
  /** True if this peer connected to us, false if we connected to them. See design_docs/0066. */
  incoming: boolean;
  /** How we learned of this peer's address before connecting - "UNKNOWN" for an incoming
   * connection. See design_docs/0066. */
  source: 'TRACKER' | 'DHT' | 'PEX' | 'LSD' | 'UNKNOWN';
  /** Fraction (0-1) of the torrent this peer has. See design_docs/0067. */
  percentAvailable: number;
  /** Fraction (0-1) of what *we* still need that this peer has - 0 once we need nothing. See
   * design_docs/0067. */
  relevance: number;
  /** Which transport this connection actually uses - orthogonal to source above. See
   * design_docs/0066's own addendum. */
  transportType: 'TCP' | 'UTP';
  /** At-a-glance health: ACTIVE (a block moved within the last 10s), WAITING (nothing moving
   * but we want something from them), IDLE. Computed by the backend. See design_docs/0076. */
  activity: 'ACTIVE' | 'WAITING' | 'IDLE';
  /** This connection's own rates, measured by the server like the torrent's. See
   * design_docs/0102. */
  downloadRateBytesPerSec: number;
  uploadRateBytesPerSec: number;
  downloadRateTrend: number[];
}

/** Matches the backend's SeedingLimitOverride record - one torrent's override of the global
 * seeding-limit defaults. Per field: < 0 means "use the global default," 0 means "explicitly
 * no limit for this torrent," > 0 is a custom value - same sentinel convention as the backend
 * record itself. See design_docs/0054. */
export interface SeedingLimitOverride {
  ratioLimit: number;
  timeLimitMinutes: number;
}

/** Matches the backend's TorrentLimitOverride record - one torrent's override of the global
 * bandwidth/connection-count defaults. Same sentinel convention as SeedingLimitOverride above
 * (< 0 inherit, 0 explicit no limit, > 0 custom), independent per field. Unlike
 * SeedingLimitOverride, a non-inherit bandwidth override replaces the global rate-limit cap for
 * this torrent rather than narrowing it, and maxConnectionsOverride only takes effect the next
 * time this torrent is constructed (a backend restart or a remove-and-re-add), not live. See
 * design_docs/0072. */
export interface TorrentLimitOverride {
  uploadBytesPerSecOverride: number;
  downloadBytesPerSecOverride: number;
  maxConnectionsOverride: number;
}

/** Matches the backend's TrackerStatus.State enum. See design_docs/0031. */
export type TrackerState = 'UNKNOWN' | 'WORKING' | 'ERROR';

/** Matches the backend's TrackerView. lastAnnouncedAt/nextAnnounceAt are ISO instant
 * strings (or null); seeders/leechers survive a subsequent ERROR rather than clearing -
 * see design_docs/0031's Trackers endpoint. */
export interface Tracker {
  url: string;
  tier: number;
  status: TrackerState;
  lastAnnouncedAt: string | null;
  nextAnnounceAt: string | null;
  lastError: string | null;
  seeders: number | null;
  leechers: number | null;
  /** Size of the peer list that announce's response actually returned (bounded by that
   * tracker's own num_want handling), not the swarm's total size - survives a subsequent
   * ERROR the same way seeders/leechers do. See design_docs/0067. */
  peers: number | null;
}
