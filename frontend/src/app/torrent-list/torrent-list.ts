import { DOCUMENT } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, NavigationEnd, Router, RouterOutlet } from '@angular/router';
import { ConfirmationService, MessageService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { ConfirmDialogModule } from 'primeng/confirmdialog';
import { DialogModule } from 'primeng/dialog';
import { IconFieldModule } from 'primeng/iconfield';
import { InputIconModule } from 'primeng/inputicon';
import { InputTextModule } from 'primeng/inputtext';
import { TableModule } from 'primeng/table';
import { ToastModule } from 'primeng/toast';
import { TooltipModule } from 'primeng/tooltip';
import { Observable, catchError, filter, forkJoin, map, of } from 'rxjs';

import { Torrent } from '../models/torrent.model';
import { LabelService } from '../services/label.service';
import { TorrentActionsService } from '../services/torrent-actions.service';
import { TorrentEventsService } from '../services/torrent-events.service';
import {
  STATUS_FILTER_LABELS,
  TorrentFilterService,
  matchesLabelFilter,
  matchesSearchText,
  matchesStatusFilter,
} from '../services/torrent-filter.service';
import { FormatBytesPipe } from '../shared/format-bytes.pipe';
import { apiErrorMessage } from '../shared/api-error';
import { pluralTorrentCount } from '../shared/plural-torrent-count';
import { SkullMark } from '../shared/skull-mark/skull-mark';
import { StatusIndicator } from '../shared/status-indicator/status-indicator';
import { AddTorrentController, PendingUpload } from './add-torrent-controller';
import { BulkLabelsDialog } from './bulk-labels-dialog/bulk-labels-dialog';
import { SelectionSummary } from './selection-summary/selection-summary';
import { TorrentCommands } from './torrent-commands';
import { TorrentRow } from './torrent-row/torrent-row';

/** A discriminated union rather than injecting a synthetic "pending" torrent into
 * Torrent's own shape - a pending upload genuinely doesn't have most of that
 * shape (no infoHash yet, no state, no actions), so it gets its own minimal row
 * rendering instead of being forced through TorrentRow. See design_docs/0029. */
type TableRow = { kind: 'pending'; upload: PendingUpload } | { kind: 'torrent'; torrent: Torrent };

type SortField = 'name' | 'size' | 'status' | 'progress';
type SortDirection = 'asc' | 'desc';

/** A torrent Pause can act on: running, or still starting (design_docs/0098) - a pause then
 * cancels the start. */
function isPausable(torrent: Torrent): boolean {
  return torrent.state === 'DOWNLOADING' || torrent.state === 'SEEDING' || torrent.state === 'STARTING';
}

@Component({
  selector: 'app-torrent-list',
  imports: [
    BulkLabelsDialog,
    ButtonModule,
    ConfirmDialogModule,
    DialogModule,
    IconFieldModule,
    InputIconModule,
    InputTextModule,
    RouterOutlet,
    SelectionSummary,
    SkullMark,
    StatusIndicator,
    TableModule,
    ToastModule,
    TooltipModule,
    TorrentRow,
  ],
  providers: [ConfirmationService, MessageService, AddTorrentController, TorrentCommands],
  templateUrl: './torrent-list.html',
  styleUrl: './torrent-list.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '(document:keydown.escape)': 'onDocumentEscape($event)',
    '(document:keydown)': 'onDocumentKeydown($event)',
  },
})
export class TorrentList {
  private readonly actions = inject(TorrentActionsService);
  private readonly messageService = inject(MessageService);
  private readonly events = inject(TorrentEventsService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly document = inject(DOCUMENT);
  private readonly host: ElementRef<HTMLElement> = inject(ElementRef);
  readonly filter = inject(TorrentFilterService);
  private readonly labelService = inject(LabelService);
  /** The add control (see AddTorrentController); its markup is in this component's template. */
  readonly add = inject(AddTorrentController);

  private readonly formatBytes = new FormatBytesPipe();

  readonly fileInput = viewChild<ElementRef<HTMLInputElement>>('fileInput');
  readonly addFieldInput = viewChild<ElementRef<HTMLInputElement>>('addFieldInput');
  readonly searchInput = viewChild<ElementRef<HTMLInputElement>>('searchInput');

  /** Drives the detail drawer's open/closed state directly from whether the
   * torrents/:infoHash child route is currently matched, rather than a separate boolean
   * signal - opening/closing then behaves like any other navigation (back button, refresh,
   * bookmarkable URL). See design_docs/0044. */
  readonly isDetailOpen = toSignal(
    this.router.events.pipe(
      filter((e): e is NavigationEnd => e instanceof NavigationEnd),
      map(() => this.route.firstChild !== null),
    ),
    { initialValue: this.route.firstChild !== null },
  );

  /** Which row (if any) the open detail drawer belongs to - null when the drawer's closed.
   * Same NavigationEnd-driven pattern as isDetailOpen above, read from the child route's own
   * :infoHash param rather than a separate signal, so it can't drift from the drawer's real
   * open/closed state. Drives TorrentRow's row-selected highlight per TODO.md's "mark the row
   * whose torrent the details panel is currently showing" and the style guide's own "Row
   * selected" token. */
  readonly selectedInfoHash = toSignal(
    this.router.events.pipe(
      filter((e): e is NavigationEnd => e instanceof NavigationEnd),
      map(() => this.route.firstChild?.snapshot?.paramMap.get('infoHash') ?? null),
    ),
    // `snapshot?.` on both reads: when this component is created while a child route is already
    // being activated (a page load or refresh directly on /torrents/:infoHash), firstChild
    // exists but its snapshot isn't populated yet, so a plain `.snapshot.paramMap` throws in
    // this initializer and the whole list fails to construct. The NavigationEnd stream above
    // fills in the real value as soon as that navigation finishes.
    { initialValue: this.route.firstChild?.snapshot?.paramMap.get('infoHash') ?? null },
  );

  /** Navigating away is what actually closes the drawer - isDetailOpen recomputes false as
   * a result, same as any other route change. */
  closeDetail(): void {
    this.router.navigate(['/']);
  }

  readonly sortField = signal<SortField>('name');
  readonly sortDirection = signal<SortDirection>('asc');

  readonly hasActiveFilter = computed(
    () =>
      this.filter.statusFilter() !== 'all' ||
      this.filter.searchText().trim() !== '' ||
      this.filter.activeLabelIds().length > 0,
  );

  /** The chips strip above the list (design_docs/0077) shows the two sidebar-driven filters -
   * status and labels - so it's always visible what, besides the search text, is narrowing the
   * list. The search box shows its own text and needs no chip. */
  readonly statusFilterLabel = computed(() => STATUS_FILTER_LABELS[this.filter.statusFilter()]);
  readonly activeLabelChips = computed(() => {
    const names = this.labelService.namesById();
    return this.filter.activeLabelIds().map((id) => ({ id, name: names.get(id) ?? '' }));
  });
  readonly hasFilterChips = computed(
    () => this.filter.statusFilter() !== 'all' || this.filter.activeLabelIds().length > 0,
  );

  /** Clears the sidebar-driven filters but not the search text - that has its own box. */
  clearFilterChips(): void {
    this.filter.statusFilter.set('all');
    this.filter.clearLabels();
  }

  /** The style guide's Copy section: `No matches for "debain"` - the literal example is search-text
   * specific, so that's the only case that gets the exact quoted-query form; a status-only
   * filter (no search text) with zero matches falls back to naming the filter instead, since
   * `No matches for ""` would read as broken. */
  readonly noMatchTitle = computed(() => {
    const query = this.filter.searchText().trim();
    if (query !== '') {
      return `No matches for "${query}"`;
    }
    const labelPhrase = this.activeLabelPhrase();
    if (labelPhrase !== '' && this.filter.statusFilter() === 'all') {
      return `No torrents labeled ${labelPhrase}`;
    }
    return `No ${STATUS_FILTER_LABELS[this.filter.statusFilter()]} torrents`;
  });

  /** `"TV"`, `"TV" or "Movie"` (match any) / `"TV" and "Movie"` (match all) - empty when no
   * label filter is active. */
  private readonly activeLabelPhrase = computed(() => {
    const names = this.activeLabelChips().map((chip) => `"${chip.name}"`);
    return names.join(this.filter.labelMatchMode() === 'all' ? ' and ' : ' or ');
  });

  /** "a note that filters are also narrowing the list" (the style guide's empty-state
   * spec) - only said when a search AND a status filter are both active; either alone gets
   * its own single-cause phrasing instead, so "also" never appears with nothing else to add
   * to. */
  readonly noMatchBody = computed(() => {
    const query = this.filter.searchText().trim();
    const statusFilter = this.filter.statusFilter();
    if (query !== '' && (statusFilter !== 'all' || this.filter.activeLabelIds().length > 0)) {
      return 'The other filters are also narrowing the list.';
    }
    if (statusFilter !== 'all' && this.filter.activeLabelIds().length > 0) {
      return `The ${STATUS_FILTER_LABELS[statusFilter]} filter is also narrowing the list.`;
    }
    if (query !== '') {
      return 'Try a different search, or clear it.';
    }
    return 'Try a different filter, or view all torrents.';
  });

  clearFilters(): void {
    this.filter.statusFilter.set('all');
    this.filter.searchText.set('');
    this.filter.clearLabels();
  }

  /** Pending uploads first, so a newly-clicked upload appears at the top rather than
   * wherever the sort happens to place it - and unaffected by the status/search filter,
   * since a still-uploading torrent doesn't have a state or name to filter on yet. */
  readonly rows = computed<TableRow[]>(() => {
    const statusFilter = this.filter.statusFilter();
    const searchText = this.filter.searchText();
    const labelIds = this.filter.activeLabelIds();
    const labelMode = this.filter.labelMatchMode();
    const labelNames = this.labelService.namesById();
    const field = this.sortField();
    const direction = this.sortDirection();

    const filtered = this.events
      .torrents()
      .filter(
        (t) =>
          matchesStatusFilter(t, statusFilter) &&
          matchesLabelFilter(t, labelIds, labelMode) &&
          matchesSearchText(
            t,
            searchText,
            t.labelIds.flatMap((id) => labelNames.get(id) ?? []),
          ),
      );
    const sign = direction === 'asc' ? 1 : -1;
    const sorted = [...filtered].sort((a, b) => sign * this.compare(a, b, field));

    return [
      ...this.add.pendingUploads().map((upload): TableRow => ({ kind: 'pending', upload })),
      ...sorted.map((torrent): TableRow => ({ kind: 'torrent', torrent })),
    ];
  });

  /** p-table's default row identity is the row object itself ((index, item) => item) -
   * unrelated to dataKey, which only drives selection/expansion. torrents() rebuilds every
   * torrent as a new object on every 2s snapshot even when nothing changed (see
   * torrent-events.service.ts), so without this every row - and everything inside it,
   * including an open p-contextMenu overlay - gets destroyed and recreated on every tick
   * instead of having its bindings updated in place. See design_docs/0027. */
  trackByRow(_index: number, row: TableRow): string {
    return row.kind === 'pending' ? `pending-${row.upload.id}` : row.torrent.infoHash;
  }

  /** Not PrimeNG's built-in pSortableColumn/sortField mechanism - p-table's rows here are
   * TableRow, a discriminated union, and its built-in sort resolves a flat field path
   * against the row object itself, which doesn't fit a union where half the rows have no
   * `.torrent` to resolve against. See design_docs/0043. */
  toggleSort(field: SortField): void {
    if (this.sortField() === field) {
      this.sortDirection.update((direction) => (direction === 'asc' ? 'desc' : 'asc'));
    } else {
      this.sortField.set(field);
      this.sortDirection.set('asc');
    }
  }

  /** An inactive column always previews `pi-sort-amount-up` - toggleSort() below always
   * resets a freshly-clicked column to ascending, so the hover-revealed hint icon (see
   * .sort-header CSS) shows exactly what clicking would do, rather than a neutral
   * bidirectional glyph. The style guide is explicit that this glyph must NOT be shown at
   * rest on every header ("six identical arrow pairs tell the user nothing about which
   * column is actually sorted") - only the active column's icon is meant to be visible by
   * default; everything else appears on hover only, via CSS. The active column's own icon
   * still uses this same function, now showing its real current direction. */
  sortIcon(field: SortField): string {
    if (this.sortField() !== field) {
      return 'pi-sort-amount-up';
    }
    return this.sortDirection() === 'asc' ? 'pi-sort-amount-up' : 'pi-sort-amount-down';
  }

  private compare(a: Torrent, b: Torrent, field: SortField): number {
    switch (field) {
      case 'name':
        return a.name.localeCompare(b.name);
      case 'size':
        return a.totalLength - b.totalLength;
      case 'status':
        return a.state.localeCompare(b.state);
      case 'progress':
        return a.progress - b.progress;
    }
  }

  onSearchInput(event: Event): void {
    this.filter.searchText.set((event.target as HTMLInputElement).value);
  }

  /** Esc in the filter field clears it - first in the guide's Esc order, ahead of closing the
   * panel or clearing the selection (see onDocumentEscape()). */
  onSearchEscape(event: Event): void {
    event.stopPropagation();
    this.filter.searchText.set('');
  }

  // --- Multi-select (design_docs/0083) -------------------------------------------------
  // A set of ticked rows that drives the selection bar. Deliberately separate from
  // selectedInfoHash() above (the one row the details panel is showing): a plain click still
  // opens the panel, and the panel never reacts to this set.

  /** Raw set of ticked info hashes. May hold torrents the current filter is hiding, or that
   * have since been removed - everything that acts on the selection goes through
   * selectedTorrents() below, which only ever returns rows actually in the list right now. */
  readonly selection = signal<ReadonlySet<string>>(new Set());

  /** Where a Shift-click range starts from: the last row ticked or toggled on its own. */
  private selectionAnchor: string | null = null;

  /** The torrents currently in the list, in display order - what "select all" and a
   * Shift-click range are measured against. */
  readonly visibleTorrents = computed(() =>
    this.rows().flatMap((row) => (row.kind === 'torrent' ? [row.torrent] : [])),
  );

  readonly selectedTorrents = computed(() => {
    const selection = this.selection();
    return selection.size === 0 ? [] : this.visibleTorrents().filter((t) => selection.has(t.infoHash));
  });

  readonly selectedCount = computed(() => this.selectedTorrents().length);
  readonly selectionLabel = computed(() => `${pluralTorrentCount(this.selectedCount())} selected`);

  readonly allVisibleSelected = computed(
    () => this.visibleTorrents().length > 0 && this.selectedCount() === this.visibleTorrents().length,
  );
  readonly someVisibleSelected = computed(() => this.selectedCount() > 0 && !this.allVisibleSelected());

  /** What Pause/Resume in the selection bar would actually act on - the same state rules as
   * Pause all/Resume all, minus anything that already has a request in flight. The buttons
   * are disabled when these are empty. */
  readonly pausableSelection = computed(() =>
    this.selectedTorrents().filter(
      (t) => isPausable(t) && this.actions.pendingFor(t.infoHash) === null,
    ),
  );
  readonly resumableSelection = computed(() =>
    this.selectedTorrents().filter((t) => t.state === 'STOPPED' && this.actions.pendingFor(t.infoHash) === null),
  );

  /** A magnet still fetching metadata has no session to label yet - the row's own Labels item
   * is disabled for the same reason. */
  readonly labelableSelection = computed(() =>
    this.selectedTorrents().filter((t) => t.state !== 'FETCHING_METADATA'),
  );
  readonly showBulkLabelsDialog = signal(false);

  toggleSelection(infoHash: string): void {
    this.selection.update((current) => {
      const next = new Set(current);
      if (!next.delete(infoHash)) {
        next.add(infoHash);
      }
      return next;
    });
    this.selectionAnchor = infoHash;
  }

  /** Adds every row between the anchor and this one (inclusive) to the selection. With no
   * usable anchor - nothing ticked yet, or the anchor row has left the list - it behaves like
   * a plain toggle and this row becomes the anchor. */
  extendSelection(infoHash: string): void {
    const order = this.visibleTorrents().map((t) => t.infoHash);
    const from = this.selectionAnchor === null ? -1 : order.indexOf(this.selectionAnchor);
    const to = order.indexOf(infoHash);
    if (from === -1 || to === -1) {
      this.toggleSelection(infoHash);
      return;
    }
    const range = order.slice(Math.min(from, to), Math.max(from, to) + 1);
    this.selection.update((current) => new Set([...current, ...range]));
  }

  /** Header checkbox: select everything in the current filter, or clear if it already is. */
  toggleSelectAll(): void {
    if (this.allVisibleSelected()) {
      this.clearSelection();
    } else {
      this.selection.set(new Set(this.visibleTorrents().map((t) => t.infoHash)));
    }
  }

  clearSelection(): void {
    this.selection.set(new Set());
    this.selectionAnchor = null;
    this.summaryRequested.set(false);
  }

  /** The selection bar's Details toggle (design_docs/0083's second addendum). The summary is
   * only ever opened by asking for it - ticking rows alone never changes what the panel shows. */
  readonly summaryRequested = signal(false);

  /** Shown while asked for and there is still something selected; an emptied selection closes
   * it by itself. */
  readonly summaryOpen = computed(() => this.summaryRequested() && this.selectedCount() > 0);

  /** The panel column is open for either occupant - a torrent's details (route-driven, as
   * before) or the selection summary. Everything that's about layout (the grid column, the
   * rows shedding columns) follows this; isDetailOpen() stays specifically "a torrent is open". */
  readonly isPanelOpen = computed(() => this.isDetailOpen() || this.summaryOpen());

  toggleSummary(): void {
    this.summaryRequested.update((requested) => !requested);
  }

  /** Opening a torrent's details - by clicking its row, or its entry in the summary - is the
   * more recent request, so the summary gives the panel back. Driven by the click itself, not
   * by the navigation: clicking the torrent that is already open underneath navigates nowhere,
   * and still has to bring its details back. */
  onTorrentOpened(): void {
    this.summaryRequested.set(false);
  }

  openTorrentDetail(infoHash: string): void {
    this.summaryRequested.set(false);
    this.router.navigate(['/torrents', infoHash]);
  }

  /** Esc inside the panel, and the second step of the page-wide Esc order: whichever occupant
   * is showing closes. A summary shown over an open torrent closes first, revealing it. */
  closePanel(): void {
    if (this.summaryOpen()) {
      this.summaryRequested.set(false);
    } else {
      this.closeDetail();
    }
  }

  /** The selection stays in place after Pause/Resume so a second action can follow. */
  pauseSelected(): void {
    this.runOnEach(this.pausableSelection(), (infoHash) => this.actions.pause(infoHash), 'pause');
  }

  resumeSelected(): void {
    this.runOnEach(this.resumableSelection(), (infoHash) => this.actions.resume(infoHash), 'resume');
  }

  readonly showRemoveDialog = signal(false);
  readonly removeAlsoDeleteData = signal(false);
  /** The selection as it was when the dialog opened - what the dialog describes and what
   * confirming it removes, even if the list changes underneath while it is open. */
  private readonly removeTargets = signal<Torrent[]>([]);

  /** The style guide's "Copy": `Remove 3 torrents?`, and the body/button below - the singular forms
   * are this app's own, the guide only spells out the plural. */
  readonly removeDialogTitle = computed(() => `Remove ${pluralTorrentCount(this.removeTargets().length)}?`);
  readonly removeDialogBody = computed(
    () =>
      `${this.removeTargets().length === 1 ? 'It stops' : 'They stop'} seeding immediately. ` +
      'Downloaded files stay on disk unless you also delete data.',
  );
  readonly removeConfirmLabel = computed(() =>
    this.removeTargets().length === 1 ? 'Remove torrent' : 'Remove torrents',
  );
  /** `Also delete 14.2 GB of data` - the size is what has actually been downloaded, not the
   * torrents' full size. */
  readonly removeDeleteDataLabel = computed(() => {
    const bytes = this.removeTargets().reduce((sum, t) => sum + t.bytesDownloaded, 0);
    return bytes > 0 ? `Also delete ${this.formatBytes.transform(bytes)} of data` : 'Also delete downloaded data';
  });

  openRemoveSelected(): void {
    this.openRemoveFor(this.selectedTorrents());
  }

  private openRemoveFor(targets: Torrent[]): void {
    if (targets.length === 0) {
      return;
    }
    this.removeTargets.set(targets);
    this.removeAlsoDeleteData.set(false);
    this.showRemoveDialog.set(true);
  }

  onRemoveDeleteDataChange(event: Event): void {
    this.removeAlsoDeleteData.set((event.target as HTMLInputElement).checked);
  }

  confirmRemoveSelected(): void {
    const targets = this.removeTargets();
    const deleteData = this.removeAlsoDeleteData();
    this.showRemoveDialog.set(false);
    // The panel has nothing left to show once its torrent is gone.
    const openInPanel = this.selectedInfoHash();
    if (openInPanel !== null && targets.some((t) => t.infoHash === openInPanel)) {
      this.closeDetail();
    }
    this.runOnEach(targets, (infoHash) => this.actions.remove(infoHash, deleteData), 'remove');
    this.clearSelection();
  }

  /** One request per torrent through TorrentActionsService (so each row shows its own pending
   * state), all at once, with a single toast if any fail rather than one per torrent. */
  private runOnEach(
    targets: Torrent[],
    action: (infoHash: string) => Observable<void>,
    verb: 'pause' | 'resume' | 'remove',
  ): void {
    if (targets.length === 0) {
      return;
    }
    forkJoin(
      targets.map((t) =>
        action(t.infoHash).pipe(
          map((): unknown => null),
          catchError((error: unknown) => of(error ?? 'failed')),
        ),
      ),
    ).subscribe((results) => {
      const failures = results.filter((error) => error !== null);
      if (failures.length > 0) {
        // The first failure's reason stands for the batch - one toast, not one per torrent.
        this.messageService.add({
          severity: 'error',
          summary: `Could not ${verb} ${pluralTorrentCount(failures.length)}`,
          detail: apiErrorMessage(failures[0], 'Please try again.'),
        });
      }
    });
  }

  // --- List keyboard shortcuts (design_docs/0083's third addendum) ----------------------
  // The style guide's "Interactions": Delete/Backspace, Up/Down, `/` and `I`, plus Space and Enter as
  // the user asked for them (the guide has Space pause/resume). The guide has one
  // "selection"; here the ticked set and the current row are separate, so each shortcut acts
  // on the ticked rows when there are any and on the current row otherwise.

  /** The torrent rows as rendered, in the same order as visibleTorrents() - pending-upload
   * placeholder rows aren't app-torrent-row elements, so the two line up index for index. */
  private rowElements(): HTMLElement[] {
    return Array.from(this.host.nativeElement.querySelectorAll<HTMLElement>('tr[app-torrent-row]'));
  }

  /** Index of the row keyboard focus is in, or -1. */
  private focusedRowIndex(): number {
    const active = this.document.activeElement;
    const row = active instanceof HTMLElement ? active.closest<HTMLElement>('tr[app-torrent-row]') : null;
    return row === null ? -1 : this.rowElements().indexOf(row);
  }

  /** "The current row": the one with keyboard focus, else the one whose details are open. */
  private currentRowIndex(): number {
    const focused = this.focusedRowIndex();
    if (focused !== -1) {
      return focused;
    }
    const open = this.selectedInfoHash();
    return open === null ? -1 : this.visibleTorrents().findIndex((t) => t.infoHash === open);
  }

  /** What Enter and Delete act on: the ticked rows if any, otherwise the current row. */
  private shortcutTargets(): Torrent[] {
    const selected = this.selectedTorrents();
    if (selected.length > 0) {
      return selected;
    }
    const current = this.visibleTorrents()[this.currentRowIndex()];
    return current ? [current] : [];
  }

  /** Space: tick or untick the current row - with Shift, extend the ticked range to it, the
   * keyboard equivalent of Shift-click. */
  private toggleCurrentRow(extend: boolean): void {
    const current = this.visibleTorrents()[this.currentRowIndex()];
    if (!current) {
      return;
    }
    if (extend) {
      this.extendSelection(current.infoHash);
    } else {
      this.toggleSelection(current.infoHash);
    }
  }

  /** Enter: pause whatever in the targets is running; if nothing is, resume what's paused. */
  private pauseOrResume(targets: Torrent[]): void {
    const idle = (t: Torrent) => this.actions.pendingFor(t.infoHash) === null;
    const running = targets.filter((t) => isPausable(t) && idle(t));
    if (running.length > 0) {
      this.runOnEach(running, (infoHash) => this.actions.pause(infoHash), 'pause');
      return;
    }
    const paused = targets.filter((t) => t.state === 'STOPPED' && idle(t));
    this.runOnEach(paused, (infoHash) => this.actions.resume(infoHash), 'resume');
  }

  /** Up/Down: move keyboard focus one row (from the current row; from the top or bottom edge
   * when there is none). While a torrent's details are open the panel follows, as the guide
   * asks; the selection summary and the ticked set are left alone. */
  private moveRowFocus(step: 1 | -1): void {
    const rows = this.rowElements();
    if (rows.length === 0) {
      return;
    }
    const current = this.currentRowIndex();
    const next = current === -1 ? (step === 1 ? 0 : rows.length - 1) : Math.min(rows.length - 1, Math.max(0, current + step));
    rows[next].focus();
    const torrent = this.visibleTorrents()[next];
    if (torrent && this.isDetailOpen() && !this.summaryOpen() && torrent.state !== 'FETCHING_METADATA') {
      this.router.navigate(['/torrents', torrent.infoHash]);
    }
  }

  /** `I`: details for what's selected - the summary for 2+ ticked rows, otherwise the one
   * ticked (or current) torrent's own details. */
  private openDetailsForShortcut(): void {
    const targets = this.shortcutTargets();
    if (this.selectedCount() >= 2) {
      this.toggleSummary();
    } else if (targets.length === 1 && targets[0].state !== 'FETCHING_METADATA') {
      this.openTorrentDetail(targets[0].infoHash);
    }
  }

  /** Only when the key press is "on the list": not with a modifier held, not while a dialog or
   * menu is open, not in a field or on a button/link/tab (Space and the arrows mean something
   * else there), and not inside the details panel, which has its own keyboard handling. */
  onDocumentKeydown(event: KeyboardEvent): void {
    if (event.defaultPrevented || event.ctrlKey || event.metaKey || event.altKey) {
      return;
    }
    const target = event.target as HTMLElement | null;
    // A row's (or the header's) own checkbox is part of the list, not a field to type in:
    // ticking one leaves focus on it, and the shortcuts have to keep working from there. Only
    // Space stays with the checkbox itself - it ticks it natively, which is the same thing the
    // Space shortcut does for a focused row.
    const onSelectCheckbox = target?.matches('input.select-checkbox') ?? false;
    if (onSelectCheckbox && event.key === ' ') {
      return;
    }
    if (
      !onSelectCheckbox &&
      target?.closest('input, textarea, select, button, a, [contenteditable], [role="tab"], .detail-panel')
    ) {
      return;
    }
    if (this.document.querySelector('.p-dialog, .p-contextmenu-root-list')) {
      return;
    }
    switch (event.key) {
      case ' ':
        event.preventDefault();
        this.toggleCurrentRow(event.shiftKey);
        break;
      case 'Enter':
        event.preventDefault();
        this.pauseOrResume(this.shortcutTargets());
        break;
      case 'Delete':
      case 'Backspace':
        event.preventDefault();
        this.openRemoveFor(this.shortcutTargets());
        break;
      case 'ArrowDown':
        event.preventDefault();
        this.moveRowFocus(1);
        break;
      case 'ArrowUp':
        event.preventDefault();
        this.moveRowFocus(-1);
        break;
      case '/':
        event.preventDefault();
        this.searchInput()?.nativeElement.focus();
        break;
      case 'i':
      case 'I':
        event.preventDefault();
        this.openDetailsForShortcut();
        break;
    }
  }

  /** The guide's Esc order (the style guide's "Interactions"), extended for the add field: clear
   * the filter if it's focused, else clear the add field if it has text, else close the panel,
   * else clear the selection. The first is the filter's own handler; this is the other three.
   * The add field also clears itself on Esc while focused - this covers focus having moved on
   * since a paste, so its echo strip's "Esc to clear" holds (design_docs/0032). Left alone while
   * a dialog or menu is open (Esc belongs to that) and while typing in any other field. */
  onDocumentEscape(event: Event): void {
    const target = event.target as HTMLElement | null;
    // Same exception as onDocumentKeydown(): focus resting on a just-ticked checkbox is still
    // "on the list", so Esc must work from there.
    if (!target?.matches('input.select-checkbox') && target?.closest('input, textarea, select, [contenteditable]')) {
      return;
    }
    if (this.document.querySelector('.p-dialog, .p-contextmenu-root-list')) {
      return;
    }
    if (this.add.value().length > 0) {
      this.add.clear();
    } else if (this.isPanelOpen()) {
      this.closePanel();
    } else if (this.selection().size > 0) {
      this.clearSelection();
    }
  }

  /** The toolbar's Pause all/Resume all: every torrent in the list, not just the filtered or
   * ticked ones. Same path as the selection bar - one request per torrent through
   * TorrentActionsService (so each row shows its own pending state), skipping torrents that
   * already have a request in flight, and one summary toast if any fail. See design_docs/0043
   * and 0101. */
  pauseAll(): void {
    const idle = (t: Torrent) => this.actions.pendingFor(t.infoHash) === null;
    this.runOnEach(
      this.events.torrents().filter((t) => isPausable(t) && idle(t)),
      (infoHash) => this.actions.pause(infoHash),
      'pause',
    );
  }

  resumeAll(): void {
    const idle = (t: Torrent) => this.actions.pendingFor(t.infoHash) === null;
    this.runOnEach(
      this.events.torrents().filter((t) => t.state === 'STOPPED' && idle(t)),
      (infoHash) => this.actions.resume(infoHash),
      'resume',
    );
  }

  constructor() {
    this.add.attachElements(
      () => this.fileInput()?.nativeElement,
      () => this.addFieldInput()?.nativeElement,
    );
  }
}
