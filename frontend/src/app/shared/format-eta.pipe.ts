import { Pipe, PipeTransform } from '@angular/core';

import { Torrent } from '../models/torrent.model';
import { humanizeDuration } from './humanize-duration';

/** The fields an ETA is shown from - the server works all of them out (design_docs/0102). */
export type EtaFields = Pick<Torrent, 'etaSeconds' | 'etaStatus' | 'unavailablePieces'>;

/** The ETA cell's text: the largest two units for a real estimate (e.g. "4h 12m") - human,
 * never an infinity symbol, per the style guide - otherwise a short reason. Kept short because
 * the cell doesn't wrap; etaDetail() is the longer explanation for its tooltip. Nothing to show
 * (complete, or not running) is an em dash, like idle fields elsewhere in the row. */
export function etaText(eta: EtaFields): string {
  switch (eta.etaStatus) {
    case 'ESTIMATE':
      return eta.etaSeconds === null ? '—' : humanizeDuration(eta.etaSeconds);
    case 'NO_PEERS':
      return 'No peers';
    case 'UNAVAILABLE':
      return `${eta.unavailablePieces} unavailable`;
    case 'CHOKED':
      return 'Waiting';
    case 'STALLED':
      return 'Stalled';
    case 'COMPLETE':
    case 'NOT_RUNNING':
      return '—';
  }
}

/** Why the ETA reads as it does - the cell's tooltip. Empty where the text says it all. */
export function etaDetail(eta: EtaFields): string {
  switch (eta.etaStatus) {
    case 'NO_PEERS':
      return 'Not connected to any peers';
    case 'UNAVAILABLE': {
      const pieces = eta.unavailablePieces === 1 ? '1 piece' : `${eta.unavailablePieces} pieces`;
      return `${pieces} not held by any connected peer - it can't finish with this swarm`;
    }
    case 'CHOKED':
      return 'Peers have what is needed, but none is sending yet';
    case 'STALLED':
      return 'Nothing has arrived in the last minute';
    default:
      return '';
  }
}

@Pipe({ name: 'formatEta' })
export class FormatEtaPipe implements PipeTransform {
  transform(eta: EtaFields): string {
    return etaText(eta);
  }
}
