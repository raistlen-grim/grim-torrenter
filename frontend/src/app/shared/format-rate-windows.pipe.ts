import { Pipe, PipeTransform } from '@angular/core';

import { FormatBytesPipe } from './format-bytes.pipe';

/** Formats a RateTracker window breakdown (e.g. { '5s': 1234, '15s': 2000 }) into a
 * single-line summary for a secondary display like a tooltip - e.g.
 * "5s: 1.2 KB/s · 15s: 2.0 KB/s". Object key order is preserved (RateTracker always
 * builds its byWindow object in the order the windows were configured), so callers don't
 * need to sort. */
@Pipe({ name: 'formatRateWindows' })
export class FormatRateWindowsPipe implements PipeTransform {
  private readonly formatBytes = new FormatBytesPipe();

  transform(windows: Record<string, number>): string {
    return Object.entries(windows)
      .map(([label, rate]) => `${label}: ${this.formatBytes.transform(rate)}/s`)
      .join(' · ');
  }
}
