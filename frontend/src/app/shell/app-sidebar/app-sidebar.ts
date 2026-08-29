import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';

import { TorrentEventsService } from '../../services/torrent-events.service';
import { STATUS_FILTER_LABELS, StatusFilter, TorrentFilterService, matchesStatusFilter } from '../../services/torrent-filter.service';

interface FilterOption {
  value: StatusFilter;
  label: string;
  icon: string;
}

/** "Harvest" is taken verbatim from the style guide's lexicon - see
 * torrent-filter.service.ts, which also owns these labels (shared with TorrentList's
 * empty-state copy). */
const FILTER_OPTIONS: FilterOption[] = [
  { value: 'all', label: STATUS_FILTER_LABELS.all, icon: 'pi-list' },
  { value: 'downloading', label: STATUS_FILTER_LABELS.downloading, icon: 'pi-arrow-down' },
  { value: 'seeding', label: STATUS_FILTER_LABELS.seeding, icon: 'pi-arrow-up' },
  { value: 'paused', label: STATUS_FILTER_LABELS.paused, icon: 'pi-pause' },
  { value: 'error', label: STATUS_FILTER_LABELS.error, icon: 'pi-exclamation-triangle' },
  { value: 'harvest', label: STATUS_FILTER_LABELS.harvest, icon: 'pi-check-circle' },
];

/**
 * The status-filter nav. Every item is a routerLink="/" that also selects the filter, so
 * picking one from the detail or settings view navigates back to the (now-filtered) list -
 * see design_docs/0043. Counts always reflect every torrent regardless of the active name
 * search (TorrentFilterService.searchText), only the status predicate - the sidebar answers
 * "what kind," the toolbar search answers "which one."
 */
@Component({
  selector: 'app-sidebar',
  imports: [RouterLink, RouterLinkActive],
  templateUrl: './app-sidebar.html',
  styleUrl: './app-sidebar.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AppSidebar {
  private readonly events = inject(TorrentEventsService);
  readonly filter = inject(TorrentFilterService);

  readonly options = FILTER_OPTIONS;

  readonly counts = computed<Record<StatusFilter, number>>(() => {
    const torrents = this.events.torrents();
    return Object.fromEntries(
      FILTER_OPTIONS.map((option) => [
        option.value,
        torrents.filter((t) => matchesStatusFilter(t, option.value)).length,
      ]),
    ) as Record<StatusFilter, number>;
  });

  select(value: StatusFilter): void {
    this.filter.statusFilter.set(value);
  }
}
