import { Injectable, computed, inject, signal } from '@angular/core';

import { Torrent } from '../models/torrent.model';
import { LabelService } from './label.service';

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

/** Matches the torrent's name, or - when labelNames is given - any of its labels' display
 * names (design_docs/0077), so typing "movies" finds everything carrying that label too. */
export function matchesSearchText(torrent: Torrent, searchText: string, labelNames: readonly string[] = []): boolean {
  const trimmed = searchText.trim().toLowerCase();
  return (
    trimmed === '' ||
    torrent.name.toLowerCase().includes(trimmed) ||
    labelNames.some((name) => name.toLowerCase().includes(trimmed))
  );
}

/** "any": at least one of the selected labels; "all": every one. An empty selection matches
 * everything either way. See design_docs/0077. */
export type LabelMatchMode = 'any' | 'all';

export function matchesLabelFilter(torrent: Torrent, labelIds: readonly string[], mode: LabelMatchMode): boolean {
  if (labelIds.length === 0) {
    return true;
  }
  return mode === 'all'
    ? labelIds.every((id) => torrent.labelIds.includes(id))
    : labelIds.some((id) => torrent.labelIds.includes(id));
}

/** Shared filter state - the sidebar's status filter and the toolbar's name search each
 * write one signal here, and TorrentList composes both (a torrent must match both) when
 * building its rows. Deliberately in-memory only, not synced to the URL/query params - no
 * existing precedent in this app for that, and this matches the simpler signal-service
 * pattern TorrentEventsService already uses. See design_docs/0043. */
@Injectable({ providedIn: 'root' })
export class TorrentFilterService {
  private readonly labelService = inject(LabelService);

  readonly statusFilter = signal<StatusFilter>('all');
  readonly searchText = signal('');
  /** The labels selected in the sidebar (any number - click toggles one in or out). Read
   * `activeLabelIds` instead of this raw signal wherever filtering - see below. */
  readonly labelFilterIds = signal<readonly string[]>([]);
  /** How several selected labels combine: "any" (a torrent needs at least one; the default) or
   * "all" (it needs every one). Status and search always AND with the result. */
  readonly labelMatchMode = signal<LabelMatchMode>('any');

  /** labelFilterIds minus any label that no longer exists (deleted here, or from another
   * browser) - otherwise a deleted label would leave an invisible filter that matches nothing.
   * See design_docs/0077. */
  readonly activeLabelIds = computed(() => {
    const names = this.labelService.namesById();
    return this.labelFilterIds().filter((id) => names.has(id));
  });

  toggleLabel(id: string): void {
    this.labelFilterIds.update((ids) => (ids.includes(id) ? ids.filter((other) => other !== id) : [...ids, id]));
  }

  removeLabel(id: string): void {
    this.labelFilterIds.update((ids) => ids.filter((other) => other !== id));
  }

  clearLabels(): void {
    this.labelFilterIds.set([]);
  }
}
