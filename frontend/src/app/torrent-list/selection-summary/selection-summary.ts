import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';

import { TorrentState, TorrentWithRate } from '../../models/torrent.model';
import { FormatBytesPipe } from '../../shared/format-bytes.pipe';
import { FormatRatePipe } from '../../shared/format-rate.pipe';
import { pluralTorrentCount } from '../../shared/plural-torrent-count';
import { torrentStateDisplay } from '../../shared/status-display';

/** The order the "count by state" line lists states in - running first, problems last. */
const STATE_ORDER: TorrentState[] = ['DOWNLOADING', 'SEEDING', 'VERIFYING', 'STOPPED', 'ERROR', 'FETCHING_METADATA'];

/**
 * The details panel's view of a multi-selection (design_docs/0083's second addendum): combined
 * size, progress, rates, ratio and peers, a count by state, and the selected torrents
 * themselves. The guide asks for "an aggregate summary (combined size, combined rates, count by
 * state)" when 2+ rows are selected; here it is opened explicitly from the selection bar
 * rather than taking the panel over as rows are ticked.
 *
 * <p>Purely a view over the torrents it is handed - everything is summed from fields already on
 * the list's own snapshot, no request of its own. No actions either: the selection bar, which
 * is what opened this and stays visible beside it, already carries them.
 */
@Component({
  selector: 'app-selection-summary',
  imports: [DecimalPipe, FormatBytesPipe, FormatRatePipe],
  templateUrl: './selection-summary.html',
  styleUrl: './selection-summary.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SelectionSummary {
  readonly torrents = input.required<TorrentWithRate[]>();
  readonly closed = output<void>();
  /** A torrent in the list below was clicked - the parent opens its own details. */
  readonly torrentOpened = output<string>();

  readonly title = computed(() => `${pluralTorrentCount(this.torrents().length)} selected`);

  readonly totalLength = computed(() => this.sum((t) => t.totalLength));
  readonly bytesDownloaded = computed(() => this.sum((t) => t.bytesDownloaded));
  readonly downloadRate = computed(() => this.sum((t) => t.downloadRateBytesPerSec));
  readonly uploadRate = computed(() => this.sum((t) => t.uploadRateBytesPerSec));
  readonly connectedPeers = computed(() => this.sum((t) => t.connectedPeers));

  /** Bytes done over bytes total across the selection - a large torrent weighs more than a
   * small one, unlike an average of the individual percentages. Same "never show 0 once
   * anything has arrived" rounding as the row's own percentage. */
  readonly progressPercent = computed(() => {
    const total = this.totalLength();
    const done = this.bytesDownloaded();
    if (total <= 0 || done <= 0) {
      return 0;
    }
    return Math.min(100, Math.max(1, Math.round((done / total) * 100)));
  });

  /** Total uploaded over total downloaded - the selection's own ratio, not a mean of ratios.
   * Null (an em dash) while nothing has been downloaded, same as the single-torrent panel. */
  readonly ratio = computed(() => {
    const downloaded = this.bytesDownloaded();
    return downloaded > 0 ? this.sum((t) => t.lifetimeUploadedBytes) / downloaded : null;
  });

  /** `2 downloading · 1 seeding · 1 paused` - the same state wording the rows use. */
  readonly stateCounts = computed(() => {
    const torrents = this.torrents();
    return STATE_ORDER.flatMap((state) => {
      const count = torrents.filter((t) => t.state === state).length;
      return count > 0 ? [`${count} ${torrentStateDisplay(state).label.toLowerCase()}`] : [];
    }).join(' · ');
  });

  stateLabel(state: TorrentState): string {
    return torrentStateDisplay(state).label;
  }

  private sum(value: (torrent: TorrentWithRate) => number): number {
    return this.torrents().reduce((total, torrent) => total + value(torrent), 0);
  }
}
