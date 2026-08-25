import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';

import { TorrentEventsService } from '../../services/torrent-events.service';
import { StatusFilter, TorrentFilterService, matchesStatusFilter } from '../../services/torrent-filter.service';

interface FilterOption {
  value: StatusFilter;
  label: string;
  icon: string;
}

/** "Harvest" is taken verbatim from the style guide's lexicon - see
 * torrent-filter.service.ts. */
const FILTER_OPTIONS: FilterOption[] = [
  { value: 'all', label: 'All', icon: 'pi-list' },
  { value: 'downloading', label: 'Downloading', icon: 'pi-arrow-down' },
  { value: 'seeding', label: 'Seeding', icon: 'pi-arrow-up' },
  { value: 'paused', label: 'Paused', icon: 'pi-pause' },
  { value: 'error', label: 'Error', icon: 'pi-exclamation-triangle' },
  { value: 'harvest', label: 'Harvest', icon: 'pi-check-circle' },
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
