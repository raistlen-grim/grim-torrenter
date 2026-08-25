export type TorrentState = 'DOWNLOADING' | 'VERIFYING' | 'SEEDING' | 'STOPPED' | 'ERROR';

/** Matches the backend's PieceState enum - see design_docs/0031's Piece map endpoint. */
export type PieceState = 'NEEDED' | 'IN_PROGRESS' | 'COMPLETE';

export interface Torrent {
  infoHash: string;
  name: string;
  state: TorrentState;
  progress: number;
  /** Verified-complete pieces only - moves in whole-piece jumps, drives progress/%.
   * Use bytesReceived for a rate calculation instead - see TorrentEventsService. */
  bytesDownloaded: number;
  /** Raw bytes received over the wire, including not-yet-verified data - continuously
   * moving, unlike bytesDownloaded. Only meaningful for a rate calculation, never for
   * progress. See the backend's TorrentSession.bytesReceived() / design_docs/0031. */
  bytesReceived: number;
  bytesUploaded: number;
  totalLength: number;
  connectedPeers: number;
  completedPieces: number;
  totalPieces: number;
  lastError: string | null;
  /** True for a trackerless torrent relying on DHT for peer discovery - see
   * TorrentSession.isTrackerless()/design_docs/0028. */
  usesDht: boolean;
  trackerCount: number;
  /** True only while the most recent tracker announce actually fell back to DHT rather
   * than succeeding via the tracker - distinct from usesDht, which only ever means "has
   * zero trackers at all." A tracker-bearing torrent (usesDht false) can still have this
   * true while its tracker is unreachable. See design_docs/0036/0039. */
  dhtBackstopActive: boolean;
}

/** downloadRateBytesPerSec/uploadRateBytesPerSec are computed client-side via RateTracker
 * (a smoothed rate over its primary window, not a raw two-sample delta) - not part of the
 * backend's TorrentView DTO. See design_docs/0020/0025. downloadRateWindows/
 * uploadRateWindows carry every tracked window (e.g. "5s"/"15s"/"60s") for a secondary
 * display - a short-window rate well below the long-window one suggests a recent stall. */
export interface TorrentWithRate extends Torrent {
  downloadRateBytesPerSec: number;
  uploadRateBytesPerSec: number;
  downloadRateWindows: Record<string, number>;
  uploadRateWindows: Record<string, number>;
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
}

/** Matches the backend's PeerView - the existing-field subset (no rate, no % piece
 * availability, no client-name decoding yet). See design_docs/0031. */
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
}

/** Matches the backend's SeedingLimitOverride record - one torrent's override of the global
 * seeding-limit defaults. Per field: < 0 means "use the global default," 0 means "explicitly
 * no limit for this torrent," > 0 is a custom value - same sentinel convention as the backend
 * record itself. See design_docs/0054. */
export interface SeedingLimitOverride {
  ratioLimit: number;
  timeLimitMinutes: number;
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
}
