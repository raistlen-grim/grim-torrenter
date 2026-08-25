import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { TooltipModule } from 'primeng/tooltip';
import { map } from 'rxjs';

import { Peer } from '../../models/torrent.model';
import { PRIMARY_RATE_WINDOW, RATE_WINDOWS } from '../../services/torrent-events.service';
import { TorrentService } from '../../services/torrent.service';
import { FormatBytesPipe } from '../../shared/format-bytes.pipe';
import { FormatRateWindowsPipe } from '../../shared/format-rate-windows.pipe';
import { FormatRatePipe } from '../../shared/format-rate.pipe';
import { pollWhileInput } from '../../shared/poll-while-input';
import { RateTracker } from '../../shared/rate-tracker';
import { StatusIndicator } from '../../shared/status-indicator/status-indicator';

const POLL_INTERVAL_MS = 3000;

/** Peer plus a client-side rate, computed the same windowed-average way as the
 * session-level rate - see shared/rate-tracker.ts and design_docs/0031/0020/0025. */
export interface PeerWithRate extends Peer {
  downloadRateBytesPerSec: number;
  uploadRateBytesPerSec: number;
  downloadRateWindows: Record<string, number>;
  uploadRateWindows: Record<string, number>;
}

function peerKey(peer: Peer): string {
  return `${peer.address}:${peer.port}`;
}

/** Existing-field subset plus a client-side rate - no % piece availability or client-name
 * decoding yet (see design_docs/0031). Rendered as a stacked card list rather than a wide
 * table - see design_docs/0044's narrow-drawer follow-up. `peerId` (raw BEP 20 hex, not yet
 * decoded into a client name - design_docs/0031 left that as a deferred, isolated utility)
 * is dropped from this compact view entirely rather than squeezed in - it wasn't
 * human-meaningful even in the old wide table, just visible because there was a spare
 * column for it. */
@Component({
  selector: 'app-peers-tab',
  imports: [FormatBytesPipe, FormatRateWindowsPipe, FormatRatePipe, StatusIndicator, TooltipModule],
  templateUrl: './peers-tab.html',
  styleUrl: './peers-tab.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PeersTab {
  private readonly torrentService = inject(TorrentService);

  /** Scoped to this component's lifetime, not shared with TorrentEventsService's
   * session-level trackers - a peer's key (address:port) only makes sense within one
   * torrent's currently-open Peers tab, with no natural place in an app-wide singleton;
   * see design_docs/0031. */
  private readonly downloadRateTracker = new RateTracker(RATE_WINDOWS, PRIMARY_RATE_WINDOW);
  private readonly uploadRateTracker = new RateTracker(RATE_WINDOWS, PRIMARY_RATE_WINDOW);
  private trackedKeys = new Set<string>();

  readonly infoHash = input.required<string>();

  readonly peers = toSignal(
    pollWhileInput(this.infoHash, POLL_INTERVAL_MS, (infoHash) => this.torrentService.peers(infoHash)).pipe(
      map((peers) => this.withRates(peers)),
    ),
    { initialValue: [] as PeerWithRate[] },
  );

  private withRates(peers: Peer[]): PeerWithRate[] {
    const now = Date.now();
    this.forgetDisconnectedPeers(new Set(peers.map(peerKey)));
    return peers.map((peer) => {
      const key = peerKey(peer);
      const download = this.downloadRateTracker.record(key, peer.downloadedBytes, now);
      const upload = this.uploadRateTracker.record(key, peer.uploadedBytes, now);
      return {
        ...peer,
        downloadRateBytesPerSec: download.current,
        uploadRateBytesPerSec: upload.current,
        downloadRateWindows: download.byWindow,
        uploadRateWindows: upload.byWindow,
      };
    });
  }

  /** Without this, a peer that disconnects and later reconnects (or a different peer that
   * happens to reuse the same address:port) would resume from stale history instead of a
   * fresh rate, and a long-running session would otherwise accumulate history forever for
   * every peer ever seen on this torrent. */
  private forgetDisconnectedPeers(currentKeys: Set<string>): void {
    for (const key of this.trackedKeys) {
      if (!currentKeys.has(key)) {
        this.downloadRateTracker.delete(key);
        this.uploadRateTracker.delete(key);
      }
    }
    this.trackedKeys = currentKeys;
  }
}
