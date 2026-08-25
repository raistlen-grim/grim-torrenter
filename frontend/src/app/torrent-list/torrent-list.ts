import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, NavigationEnd, Router, RouterOutlet } from '@angular/router';
import { ConfirmationService, MessageService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { ConfirmDialogModule } from 'primeng/confirmdialog';
import { FileUpload, FileUploadHandlerEvent, FileUploadModule } from 'primeng/fileupload';
import { IconFieldModule } from 'primeng/iconfield';
import { InputIconModule } from 'primeng/inputicon';
import { InputTextModule } from 'primeng/inputtext';
import { TableModule } from 'primeng/table';
import { ToastModule } from 'primeng/toast';
import { filter, map } from 'rxjs';

import { TorrentWithRate } from '../models/torrent.model';
import { TorrentEventsService } from '../services/torrent-events.service';
import { TorrentFilterService, matchesSearchText, matchesStatusFilter } from '../services/torrent-filter.service';
import { TorrentService } from '../services/torrent.service';
import { StatusIndicator } from '../shared/status-indicator/status-indicator';
import { TorrentRow } from './torrent-row/torrent-row';

interface PendingUpload {
  id: string;
  fileName: string;
}

/** A discriminated union rather than injecting a synthetic "pending" torrent into
 * TorrentWithRate's own shape - a pending upload genuinely doesn't have most of that
 * shape (no infoHash yet, no state, no actions), so it gets its own minimal row
 * rendering instead of being forced through TorrentRow. See design_docs/0029. */
type TableRow = { kind: 'pending'; upload: PendingUpload } | { kind: 'torrent'; torrent: TorrentWithRate };

type SortField = 'name' | 'size' | 'status' | 'progress';
type SortDirection = 'asc' | 'desc';

@Component({
  selector: 'app-torrent-list',
  imports: [
    ButtonModule,
    ConfirmDialogModule,
    FileUploadModule,
    IconFieldModule,
    InputIconModule,
    InputTextModule,
    ReactiveFormsModule,
    RouterOutlet,
    StatusIndicator,
    TableModule,
    ToastModule,
    TorrentRow,
  ],
  providers: [ConfirmationService, MessageService],
  templateUrl: './torrent-list.html',
  styleUrl: './torrent-list.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TorrentList {
  private readonly torrentService = inject(TorrentService);
  private readonly messageService = inject(MessageService);
  private readonly events = inject(TorrentEventsService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  readonly filter = inject(TorrentFilterService);

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

  /** Navigating away is what actually closes the drawer - isDetailOpen recomputes false as
   * a result, same as any other route change. */
  closeDetail(): void {
    this.router.navigate(['/']);
  }

  private readonly pendingUploads = signal<PendingUpload[]>([]);

  readonly magnetUriControl = new FormControl('', { nonNullable: true });

  readonly sortField = signal<SortField>('name');
  readonly sortDirection = signal<SortDirection>('asc');

  readonly hasActiveFilter = computed(
    () => this.filter.statusFilter() !== 'all' || this.filter.searchText().trim() !== '',
  );

  /** Pending uploads first, so a newly-clicked upload appears at the top rather than
   * wherever the sort happens to place it - and unaffected by the status/search filter,
   * since a still-uploading torrent doesn't have a state or name to filter on yet. */
  readonly rows = computed<TableRow[]>(() => {
    const statusFilter = this.filter.statusFilter();
    const searchText = this.filter.searchText();
    const field = this.sortField();
    const direction = this.sortDirection();

    const filtered = this.events
      .torrents()
      .filter((t) => matchesStatusFilter(t, statusFilter) && matchesSearchText(t, searchText));
    const sign = direction === 'asc' ? 1 : -1;
    const sorted = [...filtered].sort((a, b) => sign * this.compare(a, b, field));

    return [
      ...this.pendingUploads().map((upload): TableRow => ({ kind: 'pending', upload })),
      ...sorted.map((torrent): TableRow => ({ kind: 'torrent', torrent })),
    ];
  });

  /** p-table's default row identity is the row object itself ((index, item) => item) -
   * unrelated to dataKey, which only drives selection/expansion. torrents() rebuilds every
   * torrent as a new object on every 2s snapshot even when nothing changed (see
   * torrent-events.service.ts), so without this every row - and everything inside it,
   * including an open p-splitButton overlay - gets destroyed and recreated on every tick
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

  /** pi-sort-alt when this column isn't the active sort, else an up/down arrow matching
   * the current direction - the guide's own rule that "the sorted column header takes the
   * accent" is applied via the [class.active] binding in the template instead. */
  sortIcon(field: SortField): string {
    if (this.sortField() !== field) {
      return 'pi-sort-alt';
    }
    return this.sortDirection() === 'asc' ? 'pi-sort-amount-up' : 'pi-sort-amount-down';
  }

  private compare(a: TorrentWithRate, b: TorrentWithRate, field: SortField): number {
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

  /** Loops the existing per-torrent pause() call rather than needing a new bulk backend
   * endpoint - fired concurrently, not sequentially. No per-row pending-spinner feedback
   * the way a single row's own button click gets (TorrentRow.pendingAction is private to
   * each row instance); affected rows still visibly update via the next state-changed push
   * or snapshot. See design_docs/0043. */
  pauseAll(): void {
    const targets = this.events.torrents().filter((t) => t.state === 'DOWNLOADING' || t.state === 'SEEDING');
    if (targets.length === 0) {
      return;
    }
    targets.forEach((t) => this.torrentService.pause(t.infoHash).subscribe());
    this.messageService.add({ severity: 'info', summary: 'Pausing', detail: `${targets.length} torrent(s)` });
  }

  resumeAll(): void {
    const targets = this.events.torrents().filter((t) => t.state === 'STOPPED');
    if (targets.length === 0) {
      return;
    }
    targets.forEach((t) => this.torrentService.resume(t.infoHash).subscribe());
    this.messageService.add({ severity: 'info', summary: 'Resuming', detail: `${targets.length} torrent(s)` });
  }

  onUpload(event: FileUploadHandlerEvent, fileUpload: FileUpload): void {
    const file = event.files[0];
    if (!file) {
      return;
    }
    const pendingId = crypto.randomUUID();
    this.pendingUploads.update((uploads) => [...uploads, { id: pendingId, fileName: file.name }]);

    this.torrentService.upload(file).subscribe({
      next: (response) => {
        this.removePendingUpload(pendingId);
        // Applied immediately rather than waiting for the next periodic snapshot -
        // that's the whole point of showing a placeholder in the meantime.
        this.events.upsert(response.torrent);
        if (response.alreadyExisted) {
          this.messageService.add({
            severity: 'info',
            summary: 'Already added',
            detail: `"${response.torrent.name}" is already in your list.`,
          });
        }
        fileUpload.clear();
      },
      error: (err: { error?: { error?: string } }) => {
        this.removePendingUpload(pendingId);
        this.messageService.add({
          severity: 'error',
          summary: 'Upload failed',
          detail: err?.error?.error ?? 'Could not add torrent',
        });
        fileUpload.clear();
      },
    });
  }

  /** No torrent to show yet on success - metadata fetch happens in the background
   * (design_docs/0028), same accepted add-to-visible latency as a file upload. The
   * success toast exists so pasting at least gets unambiguous acknowledgement rather
   * than silently doing nothing from the user's point of view. */
  onAddMagnet(): void {
    const magnetUri = this.magnetUriControl.value.trim();
    if (!magnetUri) {
      return;
    }
    this.torrentService.addMagnet(magnetUri).subscribe({
      next: () => {
        this.magnetUriControl.reset('');
        this.messageService.add({
          severity: 'success',
          summary: 'Magnet link added',
          detail: 'Fetching metadata from peers…',
        });
      },
      error: (err: { error?: { error?: string } }) => {
        this.messageService.add({
          severity: 'error',
          summary: 'Could not add magnet link',
          detail: err?.error?.error ?? 'Invalid magnet link',
        });
      },
    });
  }

  private removePendingUpload(id: string): void {
    this.pendingUploads.update((uploads) => uploads.filter((u) => u.id !== id));
  }
}
