import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { switchMap } from 'rxjs';

import { HistoryResolution, TransferHistory } from '../models/stats.model';
import { StatsService } from '../services/stats.service';
import { TorrentEventsService } from '../services/torrent-events.service';
import { CapMarker } from '../shared/cap-marker/cap-marker';
import { FormatBytesPipe } from '../shared/format-bytes.pipe';
import { FormatRatePipe } from '../shared/format-rate.pipe';
import { pollEvery } from '../shared/poll';
import { RangeOption, RangeSwitch } from '../shared/range-switch/range-switch';
import { TransferChart } from '../shared/transfer-chart/transfer-chart';

/** How often each window refreshes - about as often as its newest interval changes. */
const POLL_INTERVAL_MS: Record<HistoryResolution, number> = { second: 2_000, minute: 15_000, hour: 60_000 };

const RANGE_LABEL: Record<HistoryResolution, string> = {
  second: 'last minute',
  minute: 'last 2 hours',
  hour: 'last 7 days',
};

/**
 * The Transfer page (design_docs/0102): the engine-wide rates, limits and ratio now, and a graph
 * of download and upload over the last minute, 2 hours or 7 days. The hourly history survives
 * restarts; gaps are times the app wasn't running.
 *
 * <p>The "now" figures come from the live "transfer" WebSocket message; the graph polls
 * GET /api/stats/history for the chosen window, and keeps showing the last one it had while a
 * new window loads or a poll fails.
 */
@Component({
  selector: 'app-transfer-page',
  imports: [CapMarker, FormatBytesPipe, FormatRatePipe, RangeSwitch, TransferChart],
  templateUrl: './transfer-page.html',
  styleUrl: './transfer-page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TransferPage {
  private readonly stats = inject(StatsService);
  private readonly events = inject(TorrentEventsService);

  readonly transfer = this.events.transfer;

  readonly ranges: readonly RangeOption<HistoryResolution>[] = [
    { value: 'second', label: '1 min' },
    { value: 'minute', label: '2 h' },
    { value: 'hour', label: '7 d' },
  ];
  readonly resolution = signal<HistoryResolution>('minute');

  readonly history = toSignal<TransferHistory | null>(
    toObservable(this.resolution).pipe(
      switchMap((resolution) => pollEvery(POLL_INTERVAL_MS[resolution], () => this.stats.history(resolution))),
    ),
    { initialValue: null },
  );

  /** Holds the previous window's graph while the new one loads, rather than flashing empty. */
  readonly loading = computed(() => {
    const history = this.history();
    return history !== null && history.resolution !== this.resolution();
  });

  readonly chartLabel = computed(() => `Transfer, ${RANGE_LABEL[this.resolution()]}`);

  readonly ratioDisplay = computed(() => {
    const ratio = this.transfer()?.ratio;
    return ratio === null || ratio === undefined ? '—' : ratio.toFixed(2);
  });
}
