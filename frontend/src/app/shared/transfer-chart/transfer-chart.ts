import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';

import { TransferHistory } from '../../models/stats.model';
import { FormatRatePipe } from '../format-rate.pipe';
import { agoLabel, linePath, seriesMax, xTickIndexes, yTicks } from './chart-geometry';

const HEIGHT = 220;
const MARGIN = { top: 12, right: 12, bottom: 28, left: 72 };

/** One labelled point on the x axis. */
interface XTick {
  x: number;
  label: string;
}

/**
 * Download and upload over time, as two lines on one rate axis - the Transfer page's graph and
 * the details panel's Speed tab (design_docs/0102). Gaps (the app wasn't running) are breaks in
 * the line, not zeros. The rate limits applying now, if any, are drawn as faint reference lines:
 * they're the limits *now*, not a history of them, and their label says so.
 *
 * <p>Per the chart guidance: 2px lines in two validated chart-only colours (--chart-download,
 * --chart-upload; design_docs/0032's 2026-10-19 addendum), solid hairline gridlines, a legend
 * with line keys that also carries each series' latest value, a crosshair that snaps to the
 * nearest interval with one tooltip listing both series, the same readout from the keyboard
 * (arrow keys, Home/End), and a table view of every value so nothing is hover-only.
 *
 * <p>Sized to its container's width (a ResizeObserver), at a fixed height that includes the
 * x-axis labels. Holding the previous history while a new one loads is the caller's job - it
 * passes the last good value until the next arrives.
 */
@Component({
  selector: 'app-transfer-chart',
  imports: [DatePipe, FormatRatePipe],
  templateUrl: './transfer-chart.html',
  styleUrl: './transfer-chart.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TransferChart {
  private readonly formatRate = new FormatRatePipe();

  readonly history = input.required<TransferHistory | null>();
  /** What the chart shows, for its accessible name - e.g. "Transfer, last 2 hours". */
  readonly label = input.required<string>();
  /** The limits applying now (0 for none) - drawn as reference lines. */
  readonly downloadLimit = input(0);
  readonly uploadLimit = input(0);

  readonly height = HEIGHT;
  readonly margin = MARGIN;
  readonly width = signal(0);

  /** The interval under the crosshair, or null when the pointer/focus isn't on the chart. */
  readonly hoverIndex = signal<number | null>(null);

  constructor() {
    const host = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;
    const destroyRef = inject(DestroyRef);
    // Absent in a unit-test DOM; the chart then just doesn't size itself.
    if (typeof ResizeObserver !== 'undefined') {
      const observer = new ResizeObserver((entries) => this.width.set(Math.floor(entries[0].contentRect.width)));
      observer.observe(host);
      destroyRef.onDestroy(() => observer.disconnect());
    }
  }

  readonly count = computed(() => this.history()?.download.length ?? 0);
  readonly plotWidth = computed(() => Math.max(0, this.width() - MARGIN.left - MARGIN.right));
  readonly plotHeight = HEIGHT - MARGIN.top - MARGIN.bottom;

  readonly hasData = computed(() => {
    const history = this.history();
    return !!history && [...history.download, ...history.upload].some((value) => value !== null);
  });

  readonly ticks = computed(() => {
    const history = this.history();
    const max = history ? seriesMax(history.download, history.upload) : 0;
    return yTicks(Math.max(max, this.downloadLimit(), this.uploadLimit()));
  });

  private readonly yMax = computed(() => this.ticks()[this.ticks().length - 1]);

  readonly x = (index: number): number => {
    const count = this.count();
    return MARGIN.left + (count <= 1 ? this.plotWidth() : (index * this.plotWidth()) / (count - 1));
  };

  readonly y = (value: number): number => MARGIN.top + this.plotHeight - (value / this.yMax()) * this.plotHeight;

  readonly yTickMarks = computed(() =>
    this.ticks().map((value) => ({ y: this.y(value), label: this.formatRate.transform(value) })),
  );

  readonly xTickMarks = computed<XTick[]>(() => {
    const history = this.history();
    const count = this.count();
    if (!history || count === 0) {
      return [];
    }
    const every = Math.max(1, Math.round(this.tickSpacingSeconds(history.intervalSeconds, count) / history.intervalSeconds));
    return xTickIndexes(count, every).map((index) => ({
      x: this.x(index),
      label: agoLabel((count - 1 - index) * history.intervalSeconds),
    }));
  });

  readonly downloadPath = computed(() => linePath(this.history()?.download ?? [], this.x, this.y));
  readonly uploadPath = computed(() => linePath(this.history()?.upload ?? [], this.x, this.y));

  /** The newest non-gap value of each series - the legend's figure. */
  readonly latestDownload = computed(() => latest(this.history()?.download ?? []));
  readonly latestUpload = computed(() => latest(this.history()?.upload ?? []));

  readonly hover = computed(() => {
    const index = this.hoverIndex();
    const history = this.history();
    if (index === null || !history || index >= this.count()) {
      return null;
    }
    const download = history.download[index];
    const upload = history.upload[index];
    return {
      x: this.x(index),
      downloadY: download === null ? null : this.y(download),
      uploadY: upload === null ? null : this.y(upload),
      download,
      upload,
      /** The interval's start, for the readout's timestamp. */
      atMillis: this.intervalStart(history, index),
      /** Flip the tooltip to the crosshair's left in the right half, so it never runs off. */
      flip: this.x(index) > MARGIN.left + this.plotWidth() / 2,
    };
  });

  /** Seconds: "HH:mm:ss"; minutes: "HH:mm"; hours: weekday and hour. */
  readonly timeFormat = computed(() => {
    const interval = this.history()?.intervalSeconds ?? 1;
    return interval < 60 ? 'HH:mm:ss' : interval < 3600 ? 'HH:mm' : 'EEE HH:mm';
  });

  /** Every interval, newest first, for the table view. */
  readonly rows = computed(() => {
    const history = this.history();
    if (!history) {
      return [];
    }
    return history.download
      .map((download, index) => ({
        atMillis: this.intervalStart(history, index),
        download,
        upload: history.upload[index],
      }))
      .reverse();
  });

  onPointerMove(event: PointerEvent): void {
    const count = this.count();
    if (count === 0) {
      return;
    }
    const bounds = (event.currentTarget as Element).getBoundingClientRect();
    const offset = event.clientX - bounds.left - MARGIN.left;
    const step = count <= 1 ? 1 : this.plotWidth() / (count - 1);
    this.hoverIndex.set(clamp(Math.round(offset / step), 0, count - 1));
  }

  onKeydown(event: KeyboardEvent): void {
    const count = this.count();
    if (count === 0) {
      return;
    }
    const current = this.hoverIndex() ?? count - 1;
    const next =
      event.key === 'ArrowLeft'
        ? current - 1
        : event.key === 'ArrowRight'
          ? current + 1
          : event.key === 'Home'
            ? 0
            : event.key === 'End'
              ? count - 1
              : null;
    if (next !== null) {
      event.preventDefault();
      this.hoverIndex.set(clamp(next, 0, count - 1));
    }
  }

  /** Focus starts the readout on the newest interval. */
  onFocus(): void {
    if (this.count() > 0 && this.hoverIndex() === null) {
      this.hoverIndex.set(this.count() - 1);
    }
  }

  clearHover(): void {
    this.hoverIndex.set(null);
  }

  rate(value: number | null): string {
    return value === null ? 'No data' : this.formatRate.transform(value);
  }

  private intervalStart(history: TransferHistory, index: number): number {
    return history.endEpochMillis - (this.count() - index) * history.intervalSeconds * 1000;
  }

  /** Round label spacing for the window: 15 s across a minute, 30 min across hours, a day
   * across a week. */
  private tickSpacingSeconds(intervalSeconds: number, count: number): number {
    const span = intervalSeconds * count;
    if (span <= 120) {
      return 15;
    }
    if (span <= 3 * 3600) {
      return 30 * 60;
    }
    return 86_400;
  }
}

function latest(values: readonly (number | null)[]): number | null {
  for (let index = values.length - 1; index >= 0; index--) {
    if (values[index] !== null) {
      return values[index];
    }
  }
  return null;
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}
