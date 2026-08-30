import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { interval, startWith, switchMap } from 'rxjs';

import { ServiceStatus } from '../../models/system.model';
import { SystemService } from '../../services/system.service';
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

const SERVICES_POLL_INTERVAL_MS = 30_000;

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
  private readonly system = inject(SystemService);
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

  /** DHT/peer-server bind failures are stable for the whole process lifetime (see
   * design_docs/0059), so this cadence is about freshness-on-first-load, not chasing a
   * genuinely live transition - same 30s cadence AppFooter already uses for its own
   * system-stats polling. */
  /** Not private - app-sidebar.html reads services().length directly, to tell "no failures
   * because everything's healthy" apart from "no failures because the first poll hasn't
   * resolved yet" (still the [] initialValue) - the latter shouldn't flash a false all-clear
   * checkmark. */
  readonly services = toSignal(
    interval(SERVICES_POLL_INTERVAL_MS).pipe(
      startWith(0),
      switchMap(() => this.system.services()),
    ),
    { initialValue: [] as ServiceStatus[] },
  );

  readonly failedServiceCount = computed(() => this.services().filter((s) => s.state === 'FAILED').length);
}
