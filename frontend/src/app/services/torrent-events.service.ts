import { Injectable, computed, inject, signal } from '@angular/core';

import { LibraryEvent } from '../models/events.model';
import { Torrent, TorrentWithRate } from '../models/torrent.model';
import { RateTracker, RateWindows } from '../shared/rate-tracker';
import { TorrentService } from './torrent.service';

/** type is "state-changed" (payload: a single Torrent), "snapshot" (payload: Torrent[]), or
 * "event" (payload: a single LibraryEvent, design_docs/0055) - matches the backend's
 * TorrentEventMessage envelope. */
interface TorrentEventMessage {
  type: 'state-changed' | 'snapshot' | 'event';
  payload: Torrent | Torrent[] | LibraryEvent;
}

/** Caps how many live-pushed library events this tab keeps in memory - a long-running tab
 * shouldn't accumulate unbounded history client-side just because the backend's own retention
 * window (design_docs/0055) is measured in days, not events. The EventsPage merges this with
 * its own REST-loaded page of history, so trimming here only affects how far back a page left
 * open the whole time can scroll without a reload - the backend's own log is unaffected. */
const MAX_BUFFERED_LIBRARY_EVENTS = 500;

interface Rates {
  downloadRateBytesPerSec: number;
  uploadRateBytesPerSec: number;
  downloadRateWindows: Record<string, number>;
  uploadRateWindows: Record<string, number>;
}

const RECONNECT_DELAY_MS = 3000;
const ZERO_RATES: Rates = {
  downloadRateBytesPerSec: 0,
  uploadRateBytesPerSec: 0,
  downloadRateWindows: {},
  uploadRateWindows: {},
};

/** Shared across every RateTracker this app creates so a tooltip's window labels mean the
 * same thing regardless of which one (session or, later, per-peer) produced them. */
export const RATE_WINDOWS: RateWindows = { '5s': 5_000, '15s': 15_000, '60s': 60_000 };
export const PRIMARY_RATE_WINDOW = '15s';

/**
 * Single source of truth for torrent state on the client. Seeds itself via
 * an initial REST list() call, then stays current via the backend's hybrid
 * push model (periodic snapshot + immediate state-changed events) - see
 * design_docs/0019. Reconnects on socket close since a missed connection
 * self-heals on the next periodic snapshot regardless.
 *
 * <p>Also computes download/upload rates client-side via RateTracker (a windowed
 * average, not a raw two-sample delta - see shared/rate-tracker.ts) from successive
 * bytesDownloaded/bytesUploaded readings - the backend DTO has no rate fields, and this
 * needs no backend changes since the snapshot cadence alone is enough to derive them.
 * See design_docs/0020/0025.
 */
@Injectable({ providedIn: 'root' })
export class TorrentEventsService {
  private readonly torrentService = inject(TorrentService);

  private readonly torrentsByHash = signal(new Map<string, Torrent>());
  private readonly ratesByHash = signal(new Map<string, Rates>());
  private readonly downloadRateTracker = new RateTracker(RATE_WINDOWS, PRIMARY_RATE_WINDOW);
  private readonly uploadRateTracker = new RateTracker(RATE_WINDOWS, PRIMARY_RATE_WINDOW);

  /** Library events pushed since this service connected (app start, not page mount) - newest
   * first. EventsPage merges this with its own REST-loaded page of older history rather than
   * this service owning that merge, since it has no reason to know about the REST endpoint
   * otherwise. See design_docs/0055. */
  private readonly recentLibraryEvents = signal<LibraryEvent[]>([]);
  readonly libraryEvents = this.recentLibraryEvents.asReadonly();

  readonly torrents = computed<TorrentWithRate[]>(() => {
    const rates = this.ratesByHash();
    return Array.from(this.torrentsByHash().values()).map((t) => ({
      ...t,
      ...(rates.get(t.infoHash) ?? ZERO_RATES),
    }));
  });

  private socket?: WebSocket;

  connect(): void {
    if (this.socket) {
      return;
    }
    this.torrentService.list().subscribe((list) => this.replaceAll(list));
    this.openSocket();
  }

  /** The backend has no "removed" push event, so the caller applies this locally
   * right after a successful delete rather than waiting for the next snapshot. */
  removeLocal(infoHash: string): void {
    this.torrentsByHash.update((map) => {
      const next = new Map(map);
      next.delete(infoHash);
      return next;
    });
    this.downloadRateTracker.delete(infoHash);
    this.uploadRateTracker.delete(infoHash);
    this.ratesByHash.update((map) => {
      const next = new Map(map);
      next.delete(infoHash);
      return next;
    });
  }

  private openSocket(): void {
    const protocol = location.protocol === 'https:' ? 'wss' : 'ws';
    this.socket = new WebSocket(`${protocol}://${location.host}/ws/torrents`);
    this.socket.onmessage = (event) => this.handleMessage(JSON.parse(event.data));
    this.socket.onclose = () => {
      this.socket = undefined;
      setTimeout(() => this.openSocket(), RECONNECT_DELAY_MS);
    };
  }

  private handleMessage(message: TorrentEventMessage): void {
    if (message.type === 'snapshot') {
      this.replaceAll(message.payload as Torrent[]);
    } else if (message.type === 'event') {
      this.recentLibraryEvents.update((events) =>
        [message.payload as LibraryEvent, ...events].slice(0, MAX_BUFFERED_LIBRARY_EVENTS),
      );
    } else {
      this.upsert(message.payload as Torrent);
    }
  }

  private replaceAll(list: Torrent[]): void {
    this.torrentsByHash.set(new Map(list.map((t) => [t.infoHash, t])));
    list.forEach((t) => this.recordReading(t));
  }

  /** Public so a caller that already has a fresh Torrent from a direct REST response
   * (e.g. a just-completed upload) can apply it immediately instead of waiting for the
   * next periodic snapshot or a state-changed push - see design_docs/0029. */
  upsert(torrent: Torrent): void {
    this.torrentsByHash.update((map) => {
      const next = new Map(map);
      next.set(torrent.infoHash, torrent);
      return next;
    });
    this.recordReading(torrent);
  }

  /** Download rate is derived from bytesReceived, not bytesDownloaded - the latter only
   * moves in whole-piece jumps (verified-complete pieces only), which reads as a stalled
   * 0 B/s for long stretches on torrents with large pieces even while data is genuinely
   * streaming in; bytesReceived moves continuously as blocks arrive. Both fields (and
   * bytesUploaded) only ever increase - see PieceManager/PeerConnection/TorrentSession -
   * so a negative delta shouldn't happen, but RateTracker clamps to 0 defensively regardless. */
  private recordReading(torrent: Torrent): void {
    const now = Date.now();
    const download = this.downloadRateTracker.record(torrent.infoHash, torrent.bytesReceived, now);
    const upload = this.uploadRateTracker.record(torrent.infoHash, torrent.bytesUploaded, now);
    const rates: Rates = {
      downloadRateBytesPerSec: download.current,
      uploadRateBytesPerSec: upload.current,
      downloadRateWindows: download.byWindow,
      uploadRateWindows: upload.byWindow,
    };
    this.ratesByHash.update((map) => {
      const next = new Map(map);
      next.set(torrent.infoHash, rates);
      return next;
    });
  }
}
