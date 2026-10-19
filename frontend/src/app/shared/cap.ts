import { LimitSource } from '../models/torrent.model';
import { FormatRatePipe } from './format-rate.pipe';

const formatRate = new FormatRatePipe();

/** The cap marker's tooltip and accessible label: whose limit is holding this traffic back, and
 * how high it is. See design_docs/0102. */
export function capTitle(limitBytesPerSec: number, source: LimitSource): string {
  const limit = limitBytesPerSec > 0 ? `, ${formatRate.transform(limitBytesPerSec)}` : '';
  switch (source) {
    case 'GLOBAL':
      return `Capped by the global limit${limit}`;
    case 'SCHEDULED':
      return `Capped by the off-hours schedule${limit}`;
    case 'TORRENT':
      return `Capped by this torrent's limit${limit}`;
    case 'NONE':
      return 'Capped by a rate limit';
  }
}
