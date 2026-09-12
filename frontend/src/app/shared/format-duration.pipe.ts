import { Pipe, PipeTransform } from '@angular/core';

import { humanizeDuration } from './humanize-duration';

/**
 * Humanizes an elapsed duration (milliseconds in, e.g. TorrentSession.timeActiveMillis() -
 * design_docs/0064) the same day/hour/minute/second way FormatEtaPipe does for remaining time,
 * via the shared humanizeDuration() - but "< 1m" for anything under a minute rather than a raw
 * seconds count, matching real clients' own convention for a torrent that's barely started
 * (unlike an ETA, where showing exact seconds as work wraps up is actually useful).
 */
@Pipe({ name: 'formatDuration' })
export class FormatDurationPipe implements PipeTransform {
  transform(totalMillis: number): string {
    const totalSeconds = Math.floor(totalMillis / 1000);
    if (totalSeconds < 60) {
      return '< 1m';
    }
    return humanizeDuration(totalSeconds);
  }
}
