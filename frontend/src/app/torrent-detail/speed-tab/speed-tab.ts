import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { combineLatest, switchMap } from 'rxjs';

import { TransferHistory } from '../../models/stats.model';
import { TorrentEventsService } from '../../services/torrent-events.service';
import { TorrentService } from '../../services/torrent.service';
import { pollEvery } from '../../shared/poll';
import { RangeOption, RangeSwitch } from '../../shared/range-switch/range-switch';
import { TransferChart } from '../../shared/transfer-chart/transfer-chart';

type TorrentResolution = 'second' | 'minute';

const POLL_INTERVAL_MS: Record<TorrentResolution, number> = { second: 2_000, minute: 15_000 };

/**
 * The details panel's Speed tab (design_docs/0102): this torrent's download and upload over the
 * last minute or hour, with its own limits drawn as reference lines. Kept in memory on the
 * server only - a restart starts it afresh. Polls only while the tab is open (the panel's tabs
 * are lazy), restarting when the torrent or the window changes.
 */
@Component({
  selector: 'app-speed-tab',
  imports: [RangeSwitch, TransferChart],
  template: `
    <div class="filters">
      <app-range-switch label="Time window" [options]="ranges" [(value)]="resolution" />
    </div>
    <app-transfer-chart
      [class.loading]="loading()"
      [history]="history()"
      [label]="chartLabel()"
      [downloadLimit]="torrent()?.downloadLimitBytesPerSec ?? 0"
      [uploadLimit]="torrent()?.uploadLimitBytesPerSec ?? 0"
    />
  `,
  styles: `
    :host {
      display: block;
      padding-top: var(--space-3);
    }

    .filters {
      display: flex;
      margin-bottom: var(--space-3);
    }

    app-transfer-chart.loading {
      opacity: 0.5;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SpeedTab {
  private readonly torrentService = inject(TorrentService);
  private readonly events = inject(TorrentEventsService);

  readonly infoHash = input.required<string>();

  readonly ranges: readonly RangeOption<TorrentResolution>[] = [
    { value: 'second', label: '1 min' },
    { value: 'minute', label: '1 h' },
  ];
  readonly resolution = signal<TorrentResolution>('second');

  readonly torrent = computed(() => this.events.torrents().find((t) => t.infoHash === this.infoHash()));

  readonly history = toSignal<TransferHistory | null>(
    combineLatest([toObservable(this.infoHash), toObservable(this.resolution)]).pipe(
      switchMap(([infoHash, resolution]) =>
        pollEvery(POLL_INTERVAL_MS[resolution], () => this.torrentService.history(infoHash, resolution)),
      ),
    ),
    { initialValue: null },
  );

  readonly loading = computed(() => {
    const history = this.history();
    return history !== null && history.resolution !== this.resolution();
  });

  readonly chartLabel = computed(() => (this.resolution() === 'second' ? 'Speed, last minute' : 'Speed, last hour'));
}
