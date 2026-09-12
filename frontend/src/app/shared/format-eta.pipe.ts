import { Pipe, PipeTransform } from '@angular/core';

import { humanizeDuration } from './humanize-duration';

/**
 * Humanizes remaining time as the largest two relevant units (e.g. "4h 12m", "18m 4s"),
 * dropping to one unit only at the seconds tier - matches the style guide's own rule:
 * ETA is human, never an infinity symbol, and "unknown" reads "Stalled" rather than a
 * blank or a raw number. Nothing to show at all (already complete) reads as an em dash,
 * consistent with how idle fields elsewhere in the row are shown.
 */
@Pipe({ name: 'formatEta' })
export class FormatEtaPipe implements PipeTransform {
  transform(bytesRemaining: number, rateBytesPerSec: number): string {
    if (bytesRemaining <= 0) {
      return '—';
    }
    if (rateBytesPerSec <= 0) {
      return 'Stalled';
    }
    return humanizeDuration(Math.ceil(bytesRemaining / rateBytesPerSec));
  }
}
