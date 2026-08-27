import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, LOCALE_ID, computed, inject, input } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { TooltipModule } from 'primeng/tooltip';

import { Tracker } from '../../models/torrent.model';
import { TorrentEventsService } from '../../services/torrent-events.service';
import { TorrentService } from '../../services/torrent.service';
import { pollWhileInput } from '../../shared/poll-while-input';

const POLL_INTERVAL_MS = 3000;

/**
 * Per-tracker status (URL, tier, WORKING/ERROR/UNKNOWN, last/next announce, last error,
 * seeders/leechers). Empty for a trackerless torrent. See design_docs/0031.
 *
 * <p>Working trackers collapse into one summary line rather than each getting a row - README's
 * own reasoning: "Forty-three trackers is a list nobody reads. Working ones collapse into one
 * line; only the failing ones are enumerated." Per-tracker seeders/leechers/tier and announce
 * times move into a tooltip on each still-individually-listed (non-working) tracker rather
 * than a column - not shown at all for a collapsed working tracker, matching the guide's own
 * "nobody reads it while it's healthy" philosophy rather than working around it.
 */
@Component({
  selector: 'app-trackers-tab',
  imports: [TooltipModule],
  templateUrl: './trackers-tab.html',
  styleUrl: './trackers-tab.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TrackersTab {
  private readonly torrentService = inject(TorrentService);
  private readonly events = inject(TorrentEventsService);
  /** Constructed directly rather than injected - DatePipe has no providedIn: 'root' of its
   * own, so inject(DatePipe) would throw without a components-level provider for it; this
   * needs only the app's LOCALE_ID, which is injectable. */
  private readonly datePipe = new DatePipe(inject(LOCALE_ID));

  readonly infoHash = input.required<string>();

  readonly trackers = toSignal(
    pollWhileInput(this.infoHash, POLL_INTERVAL_MS, (infoHash) => this.torrentService.trackers(infoHash)),
    { initialValue: [] as Tracker[] },
  );

  readonly workingCount = computed(() => this.trackers().filter((t) => t.status === 'WORKING').length);

  /** Anything not confirmed WORKING is listed individually - both a real ERROR and an
   * UNKNOWN tracker that simply hasn't announced yet, since neither has earned the "collapse
   * into the healthy summary" treatment. */
  readonly notWorkingTrackers = computed(() => this.trackers().filter((t) => t.status !== 'WORKING'));

  /** [DHT]·[PeX] only, not the guide's own [DHT]·[PeX]·[LSD] - Local Service Discovery isn't
   * implemented anywhere in this engine at all (unlike the fact-grid's dropped fields, this
   * one has no data to surface even in principle yet). PeX has no per-torrent toggle in this
   * engine - it's unconditionally advertised on every connection - so it always reads
   * Enabled. */
  readonly usesDht = computed(() => {
    const torrent = this.events.torrents().find((t) => t.infoHash === this.infoHash());
    return torrent ? torrent.usesDht || torrent.dhtBackstopActive : false;
  });

  reasonFor(tracker: Tracker): string {
    if (tracker.lastError) {
      return tracker.lastError;
    }
    return tracker.status === 'UNKNOWN' ? 'Not yet announced' : 'Unknown error';
  }

  announceTooltip(tracker: Tracker): string {
    const parts: string[] = [];
    if (tracker.seeders !== null) {
      parts.push(`${tracker.seeders} seeders`);
    }
    if (tracker.leechers !== null) {
      parts.push(`${tracker.leechers} leechers`);
    }
    parts.push(`Last: ${tracker.lastAnnouncedAt ? this.datePipe.transform(tracker.lastAnnouncedAt, 'short') : '—'}`);
    parts.push(`Next: ${tracker.nextAnnounceAt ? this.datePipe.transform(tracker.nextAnnounceAt, 'short') : '—'}`);
    return parts.join(' · ');
  }
}
