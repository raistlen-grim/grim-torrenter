import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, LOCALE_ID, computed, inject, input } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { TooltipModule } from 'primeng/tooltip';

import { Tracker } from '../../models/torrent.model';
import { TorrentEventsService } from '../../services/torrent-events.service';
import { TorrentService } from '../../services/torrent.service';
import { humanizeDuration } from '../../shared/humanize-duration';
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

  /** Now the guide's full [DHT]·[PeX]·[LSD] (design_docs/0062 added LSD). PeX has no
   * per-torrent toggle in this engine - it's unconditionally advertised on every connection
   * (except for a private torrent, BEP 27 - not distinguished in this label either) - so it
   * always reads Enabled.
   *
   * <p>torrent.usesDht now directly means "DHT is eligible as a peer source for this
   * torrent" (design_docs/0036's own 2026-09-06 revision) - true for any non-private torrent
   * with DHT configured, tracker-bearing or not, so no longer needs OR-ing with
   * dhtBackstopActive (a separate, tracker-health-only signal) to cover the
   * degraded-tracker case; usesDht alone already does. */
  readonly usesDht = computed(() => {
    const torrent = this.events.torrents().find((t) => t.infoHash === this.infoHash());
    return torrent ? torrent.usesDht : false;
  });

  /** Same per-torrent-eligibility shape as usesDht above, sourced from
   * TorrentSession.usesLsd() (design_docs/0062) - true whenever LSD was running at the engine
   * level and the torrent isn't private (BEP 27). */
  readonly usesLsd = computed(() => {
    const torrent = this.events.torrents().find((t) => t.infoHash === this.infoHash());
    return torrent ? torrent.usesLsd : false;
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
    if (tracker.peers !== null) {
      parts.push(`${tracker.peers} peers`);
    }
    parts.push(`Last: ${tracker.lastAnnouncedAt ? this.datePipe.transform(tracker.lastAnnouncedAt, 'short') : '—'}`);
    parts.push(this.reannounceCountdown(tracker));
    return parts.join(' · ');
  }

  /** A relative countdown ("Re-announces in 4m 12s") rather than an absolute timestamp - what
   * a power user actually wants ("how long until this is checked again"), not a time they'd
   * have to do the subtraction on themselves. Recomputed on each 3s poll tick, same cadence
   * this tab already re-renders on - not a live per-second ticker. See design_docs/0067. */
  private reannounceCountdown(tracker: Tracker): string {
    if (!tracker.nextAnnounceAt) {
      return 'Next: —';
    }
    const secondsRemaining = Math.round((new Date(tracker.nextAnnounceAt).getTime() - Date.now()) / 1000);
    return secondsRemaining <= 0 ? 'Re-announces any moment' : `Re-announces in ${humanizeDuration(secondsRemaining)}`;
  }
}
