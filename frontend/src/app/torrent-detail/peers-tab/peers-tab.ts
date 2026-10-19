import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';

import { Peer } from '../../models/torrent.model';
import { TorrentService } from '../../services/torrent.service';
import { FormatRatePipe } from '../../shared/format-rate.pipe';
import { activityDetail, comparePeersByActivity } from '../../shared/peer-activity';
import { pollWhileInput } from '../../shared/poll-while-input';
import { RateTrend } from '../../shared/rate-trend/rate-trend';
import { TrackerDetailsDialog } from '../trackers-tab/tracker-details-dialog/tracker-details-dialog';

const POLL_INTERVAL_MS = 3000;

function peerKey(peer: Peer): string {
  return `${peer.address}:${peer.port}`;
}

/** Each peer as the server reports it, rates included (design_docs/0102). `peerId` (raw BEP 20 hex, not yet decoded
 * into a client name - design_docs/0031 left that as a deferred, isolated utility) is still
 * absent; the guide's "Done" column (per-peer completion percentage) is now filled in via
 * `percentAvailable` - see design_docs/0067.
 *
 * <p>One line per peer (design_docs/0076): an activity marker (computed by the backend), the
 * address, the connection badges, and live speeds only while data is moving. Everything else -
 * choke/interest state, percent done, relevance, byte totals - lives in the details dialog's
 * per-peer table and the marker's tooltip, not on the row.
 *
 * <p>Also opens TrackerDetailsDialog (design_docs/0073, shared with TrackersTab) via a small
 * trigger at the top - the peer-source connected/seeding breakdown it shows is peer-discovery
 * information just as relevant from this tab as from the Trackers tab, and the dialog is fully
 * self-sufficient (fetches its own data), so hosting it here needs nothing beyond the toggle
 * signal below. */
@Component({
  selector: 'app-peers-tab',
  imports: [FormatRatePipe, RateTrend, TrackerDetailsDialog],
  templateUrl: './peers-tab.html',
  styleUrl: './peers-tab.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PeersTab {
  private readonly torrentService = inject(TorrentService);

  /** Opens TrackerDetailsDialog - same self-contained-per-host toggle pattern as
   * TrackersTab's own showTrackerDetailsDialog. See design_docs/0073. */
  readonly showTrackerDetailsDialog = signal(false);

  readonly infoHash = input.required<string>();
  readonly peerKey = peerKey;
  readonly activityDetail = activityDetail;

  /** Single-letter badges, same compact convention real clients use for this (qBittorrent's
   * own X/H/L flags) - the drawer's 430px width has no room for a spelled-out "Source" column.
   * UNKNOWN is never rendered at all (see the template) rather than getting a letter of its
   * own. See design_docs/0066. */
  sourceLabel(source: Peer['source']): string {
    switch (source) {
      case 'TRACKER':
        return 'T';
      case 'DHT':
        return 'H';
      case 'PEX':
        return 'X';
      case 'LSD':
        return 'L';
      default:
        return '';
    }
  }

  /** The badge's own tooltip text - spelled out, unlike sourceLabel()'s single letter. */
  sourceName(source: Peer['source']): string {
    switch (source) {
      case 'TRACKER':
        return 'Tracker';
      case 'DHT':
        return 'DHT';
      case 'PEX':
        return 'PEX';
      case 'LSD':
        return 'Local Service Discovery';
      default:
        return '';
    }
  }

  /** Same "hide the uninteresting/default case" convention as UNKNOWN source above - TCP is
   * the common case and gets no badge; only µTP (still experimental, design_docs/0074) is worth
   * calling out. See design_docs/0066's own addendum. */
  transportTypeLabel(transportType: Peer['transportType']): string {
    return transportType === 'UTP' ? 'µ' : '';
  }

  transportTypeName(transportType: Peer['transportType']): string {
    return transportType === 'UTP' ? 'µTP connection' : '';
  }

  /** Done/relevance no longer have a column - the row's own tooltip, and the full table in the
   * details dialog, carry them instead. See design_docs/0076. */
  rowTitle(peer: Peer): string {
    return `${Math.round(peer.percentAvailable * 100)}% of the torrent · ${Math.round(peer.relevance * 100)}% of what we still need`;
  }

  private readonly peers = toSignal(
    pollWhileInput(this.infoHash, POLL_INTERVAL_MS, (infoHash) => this.torrentService.peers(infoHash)),
    { initialValue: [] as Peer[] },
  );

  /** Active peers first, idle last, then by lifetime bytes moved - see comparePeersByActivity()
   * and design_docs/0076. Replaces the old "peers you are serving matter most" upload-rate
   * order, which reshuffled rows on every poll. */
  readonly sortedPeers = computed(() => [...this.peers()].sort(comparePeersByActivity));
}
