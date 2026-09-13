import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, LOCALE_ID, Signal, computed, inject, input, output } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { DialogModule } from 'primeng/dialog';
import { EMPTY, Observable, interval, startWith, switchMap } from 'rxjs';

import { Peer, Tracker } from '../../../models/torrent.model';
import { TorrentService } from '../../../services/torrent.service';

const POLL_INTERVAL_MS = 3000;

/** Polls fetch() every POLL_INTERVAL_MS while visible is true, stopping (no request in flight)
 * the moment it becomes false - unlike shared/poll-while-input.ts, which is keyed to a
 * Signal<string> and restarts on value change, this gates on a plain boolean. Kept local to
 * this component rather than promoted into that shared file - both call sites needing it live
 * here, and it's not yet clear another dialog will need the same shape. See design_docs/0073. */
function pollWhileVisible<T>(visible: Signal<boolean>, fetch: () => Observable<T>, initialValue: T): Signal<T> {
  return toSignal(
    toObservable(visible).pipe(
      switchMap((isVisible) => (isVisible ? interval(POLL_INTERVAL_MS).pipe(startWith(0), switchMap(fetch)) : EMPTY)),
    ),
    { initialValue },
  );
}

/** No existing "counts as seeding" convention anywhere in this frontend (checked peers-tab and
 * torrent.model.ts) - a peer with the complete torrent (percentAvailable === 1) is a seed by
 * BitTorrent's own definition; >= guards the same way RateLimiter's own <= 0 checks do, against
 * a value landing exactly on the boundary from the wrong side. */
function isSeeding(peer: Peer): boolean {
  return peer.percentAvailable >= 1;
}

/** Fixed row order, always all five shown (even at 0) - same "always show, don't hide a zero"
 * predictability as the tab's own DHT/PeX/LSD Enabled/Disabled line. UNKNOWN is relabeled -
 * it's an internal engine term (design_docs/0066) that only ever means "an incoming connection
 * we didn't independently discover," not a source a user configured. */
const PEER_SOURCES: { key: Peer['source']; label: string }[] = [
  { key: 'TRACKER', label: 'Tracker' },
  { key: 'DHT', label: 'DHT' },
  { key: 'PEX', label: 'PeX' },
  { key: 'LSD', label: 'LSD' },
  { key: 'UNKNOWN', label: 'Other (incoming)' },
];

interface PeerSourceCount {
  label: string;
  connected: number;
  seeding: number;
}

/**
 * The richer, secondary view of a torrent's trackers and peer-discovery health
 * (design_docs/0073) - opened identically from both `TrackersTab` and `PeersTab`, each of which
 * keeps its own main view exactly as simple as before this dialog existed. One combined dialog,
 * not two - the peer-source breakdown already treats "Tracker" as one of its own rows, so the
 * two datasets are inherently related, not separate concerns (and this mirrors qBittorrent's
 * own layout, which shows DHT/PeX/LSD *inside* its trackers view, not as a separate screen).
 *
 * <p>Fully self-sufficient - fetches both trackers and peers itself, each via its own
 * visibility-gated poll (pollWhileVisible() above), so any host tab only ever needs to pass
 * `infoHash`/`visible`/`visibleChange` and gets an identical result regardless of which tab
 * opened it.
 */
@Component({
  selector: 'app-tracker-details-dialog',
  imports: [DialogModule],
  templateUrl: './tracker-details-dialog.html',
  styleUrl: './tracker-details-dialog.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TrackerDetailsDialog {
  private readonly torrentService = inject(TorrentService);
  private readonly datePipe = new DatePipe(inject(LOCALE_ID));

  readonly infoHash = input.required<string>();
  readonly visible = input.required<boolean>();
  readonly visibleChange = output<boolean>();

  readonly trackers = pollWhileVisible<Tracker[]>(this.visible, () => this.torrentService.trackers(this.infoHash()), []);
  private readonly peers = pollWhileVisible<Peer[]>(this.visible, () => this.torrentService.peers(this.infoHash()), []);

  readonly peerSourceCounts = computed<PeerSourceCount[]>(() => {
    const peers = this.peers();
    return PEER_SOURCES.map(({ key, label }) => {
      const forSource = peers.filter((peer) => peer.source === key);
      return { label, connected: forSource.length, seeding: forSource.filter(isSeeding).length };
    });
  });

  lastAnnouncedDisplay(tracker: Tracker): string {
    return tracker.lastAnnouncedAt ? (this.datePipe.transform(tracker.lastAnnouncedAt, 'short') ?? '—') : '—';
  }

  close(): void {
    this.visibleChange.emit(false);
  }
}
