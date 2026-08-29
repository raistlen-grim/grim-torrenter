import { Injectable, signal } from '@angular/core';

import { Torrent } from '../models/torrent.model';

export type StatusFilter = 'all' | 'downloading' | 'seeding' | 'paused' | 'error' | 'harvest';

/** "Harvest" is the completed-downloads filter - name taken verbatim from the style guide's
 * lexicon ("the completed-downloads view - one nav label, used consistently"). Matched on
 * piece counts, not `state === 'SEEDING'` or `progress >= 1` - a paused-but-fully-downloaded
 * torrent is still "harvested" even though it isn't actively seeding, and piece counts avoid
 * a floating-point-equality question `progress` (a derived fraction) would raise. See
 * design_docs/0043. */
export function matchesStatusFilter(torrent: Torrent, filter: StatusFilter): boolean {
  switch (filter) {
    case 'all':
      return true;
    case 'downloading':
      return torrent.state === 'DOWNLOADING';
    case 'seeding':
      return torrent.state === 'SEEDING';
    case 'paused':
      return torrent.state === 'STOPPED';
    case 'error':
      return torrent.state === 'ERROR';
    case 'harvest':
      return torrent.totalPieces > 0 && torrent.completedPieces === torrent.totalPieces;
  }
}

/** Shared with AppSidebar (its own nav labels) and TorrentList (its empty-state copy when a
 * filter excludes every torrent) - one place for the label text so the two can't drift apart
 * saying the same filter two different ways. */
export const STATUS_FILTER_LABELS: Record<StatusFilter, string> = {
  all: 'All',
  downloading: 'Downloading',
  seeding: 'Seeding',
  paused: 'Paused',
  error: 'Error',
  harvest: 'Harvest',
};

export function matchesSearchText(torrent: Torrent, searchText: string): boolean {
  const trimmed = searchText.trim().toLowerCase();
  return trimmed === '' || torrent.name.toLowerCase().includes(trimmed);
}

/** Shared filter state - the sidebar's status filter and the toolbar's name search each
 * write one signal here, and TorrentList composes both (a torrent must match both) when
 * building its rows. Deliberately in-memory only, not synced to the URL/query params - no
 * existing precedent in this app for that, and this matches the simpler signal-service
 * pattern TorrentEventsService already uses. See design_docs/0043. */
@Injectable({ providedIn: 'root' })
export class TorrentFilterService {
  readonly statusFilter = signal<StatusFilter>('all');
  readonly searchText = signal('');
}
