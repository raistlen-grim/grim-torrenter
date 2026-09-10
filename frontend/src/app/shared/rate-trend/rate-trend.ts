import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { FormatRatePipe } from '../format-rate.pipe';

const SPARK_WIDTH = 110;
const SPARK_HEIGHT = 26;
const SPARK_PADDING = 2;

/** Down cell's replacement for the old 5s/15s/60s rolling-average tooltip - a single
 * sparkline reads as "climbing / steady / stalling" at a glance, where three raw numbers
 * made the viewer do the trend math themselves. See style/torrent_list/
 * ADDENDUM_03_rate_tooltip.md and design_docs/0063.
 *
 * <p>Idle (rateBytesPerSec <= 0) never shows the tooltip - nothing to trend - and instead
 * renders the same em-dash the rate cell always used for "no activity."
 */
@Component({
  selector: 'app-rate-trend',
  imports: [FormatRatePipe],
  host: {
    '[attr.tabindex]': 'active() ? 0 : null',
    '[attr.aria-label]': 'active() ? ariaLabel() : null',
  },
  template: `
    @if (active()) {
      <span class="rate-value tabular-nums">{{ rateBytesPerSec() | formatRate }}</span>
      <div class="rate-tooltip" aria-hidden="true">
        <span class="tooltip-value tabular-nums">{{ rateBytesPerSec() | formatRate }}</span>
        <svg class="tooltip-spark" viewBox="0 0 110 26" preserveAspectRatio="none">
          <polyline [attr.points]="points()" />
        </svg>
        <span class="tooltip-caption">Last 60s</span>
      </div>
    } @else {
      <span class="rate-idle">&mdash;</span>
    }
  `,
  styleUrl: './rate-trend.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RateTrend {
  private readonly formatRate = new FormatRatePipe();

  readonly rateBytesPerSec = input.required<number>();
  /** Per-interval rate samples (bytes/sec) over the last ~60s, oldest first - see
   * RateTracker.record()'s own RateSnapshot.trend. */
  readonly trend = input<readonly number[]>([]);

  readonly active = computed(() => this.rateBytesPerSec() > 0);

  /** The tooltip's own visual content is aria-hidden (a decorative sparkline duplicating
   * this same number) - this is what a keyboard/screen-reader user gets instead, read on
   * focus since the tooltip itself only reveals on hover/focus via CSS (see rate-trend.scss's
   * :focus-visible rule, tabindex added on the host above). */
  readonly ariaLabel = computed(() => `${this.formatRate.transform(this.rateBytesPerSec())}, last 60 seconds`);

  /** SVG polyline points normalizing this.trend() into the sparkline's 110x26 box, 2px
   * top/bottom padding so the line never touches the edges. A single sample (or a flat
   * trend - every sample equal) draws as a centered horizontal line rather than pinning to
   * one edge, since there's no genuine slope to show either way. */
  readonly points = computed(() => {
    const samples = this.trend();
    if (samples.length === 0) {
      return '';
    }
    if (samples.length === 1) {
      return `${SPARK_PADDING},${SPARK_HEIGHT / 2} ${SPARK_WIDTH - SPARK_PADDING},${SPARK_HEIGHT / 2}`;
    }
    const min = Math.min(...samples);
    const max = Math.max(...samples);
    const range = max - min;
    const plottableHeight = SPARK_HEIGHT - 2 * SPARK_PADDING;
    return samples
      .map((sample, index) => {
        const x = (index / (samples.length - 1)) * SPARK_WIDTH;
        const y = range === 0 ? SPARK_HEIGHT / 2 : SPARK_PADDING + (1 - (sample - min) / range) * plottableHeight;
        return `${x.toFixed(1)},${y.toFixed(1)}`;
      })
      .join(' ');
  });
}
