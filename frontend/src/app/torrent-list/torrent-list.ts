import { DOCUMENT } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
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
import { IconFieldModule } from 'primeng/iconfield';
import { InputIconModule } from 'primeng/inputicon';
import { InputTextModule } from 'primeng/inputtext';
import { TableModule } from 'primeng/table';
import { ToastModule } from 'primeng/toast';
import { TooltipModule } from 'primeng/tooltip';
import { filter, map } from 'rxjs';

import { TorrentWithRate } from '../models/torrent.model';
import { TorrentEventsService } from '../services/torrent-events.service';
import {
  STATUS_FILTER_LABELS,
  TorrentFilterService,
  matchesSearchText,
  matchesStatusFilter,
} from '../services/torrent-filter.service';
import { TorrentService } from '../services/torrent.service';
import { FormatBytesPipe } from '../shared/format-bytes.pipe';
import { pluralTorrentCount } from '../shared/plural-torrent-count';
import { SkullMark } from '../shared/skull-mark/skull-mark';
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

/** Requires the actual btih infohash, not just the magnet: scheme - a bare
 * `magnet:?dn=foo` must fail validation. See ADD_CONTROL.md's "Validation" section. */
const MAGNET_PREFIX_RE = /^magnet:\?/i;
const BTIH_RE = /xt=urn:btih:([0-9a-fA-F]{40}|[A-Za-z2-7]{32})/i;
const HEX_INFOHASH_RE = /^[0-9a-fA-F]{40}$/;
const URL_RE = /^https?:\/\/\S+$/i;

interface ParsedMagnet {
  /** Only populated for a 40-char hex btih (the base32 form isn't converted client-side,
   * so a base32 magnet skips duplicate detection rather than mis-comparing). */
  infoHash: string | null;
  displayName: string | null;
  lengthBytes: number | null;
  trackerCount: number;
}

type AddState =
  | { kind: 'empty' }
  | { kind: 'invalid' }
  /** The design guide's "Add link" (fetch a .torrent from a URL) has no backend endpoint
   * yet - flagged when ADDENDUM_02 was reviewed as needing new server work plus an SSRF
   * consideration, not something to build silently. A recognised URL is shown as
   * unsupported rather than wired to a fetch that doesn't exist. */
  | { kind: 'torrent-url' }
  | ({ kind: 'magnet'; uri: string; duplicate: TorrentWithRate | null } & ParsedMagnet)
  | { kind: 'multi-magnet'; uris: string[]; firstName: string | null };

interface EchoState {
  tone: 'accent' | 'alarm';
  icon: string;
  name: string;
  meta: string;
  hint: string;
}

function normalizeMagnetCandidate(value: string): string | null {
  const stripped = value.trim().replace(/^[<'"]+/, '').replace(/[>'"]+$/, '');
  if (MAGNET_PREFIX_RE.test(stripped)) {
    return stripped;
  }
  if (HEX_INFOHASH_RE.test(stripped)) {
    return `magnet:?xt=urn:btih:${stripped}`;
  }
  return null;
}

function parseMagnetParams(uri: string): ParsedMagnet | null {
  const match = BTIH_RE.exec(uri);
  if (!match) {
    return null;
  }
  const infoHash = match[1].length === 40 ? match[1].toLowerCase() : null;
  let displayName: string | null = null;
  let lengthBytes: number | null = null;
  let trackerCount = 0;
  const query = uri.slice(uri.indexOf('?') + 1);
  for (const part of query.split('&')) {
    const eqIndex = part.indexOf('=');
    if (eqIndex === -1) {
      continue;
    }
    const key = part.slice(0, eqIndex);
    const value = decodeURIComponent(part.slice(eqIndex + 1));
    if (key === 'dn' && displayName === null) {
      displayName = value;
    } else if (key === 'xl' && lengthBytes === null) {
      const parsedLength = Number(value);
      lengthBytes = Number.isFinite(parsedLength) ? parsedLength : null;
    } else if (key === 'tr') {
      trackerCount++;
    }
  }
  return { infoHash, displayName, lengthBytes, trackerCount };
}

/** Newline-separated pastes are treated as a batch only when every line parses as a
 * magnet/infohash - a mix of valid and junk lines falls through to plain 'invalid'
 * rather than silently dropping the junk. See ADD_CONTROL.md's behaviour table. */
function resolveAddState(rawValue: string, torrents: readonly TorrentWithRate[]): AddState {
  const trimmed = rawValue.trim();
  if (!trimmed) {
    return { kind: 'empty' };
  }

  const lines = trimmed
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line.length > 0);

  if (lines.length > 1) {
    const uris = lines.map(normalizeMagnetCandidate);
    if (uris.every((uri): uri is string => uri !== null)) {
      const first = parseMagnetParams(uris[0]);
      return { kind: 'multi-magnet', uris, firstName: first?.displayName ?? null };
    }
    return { kind: 'invalid' };
  }

  const single = lines[0];
  const normalized = normalizeMagnetCandidate(single);
  if (normalized) {
    const parsed = parseMagnetParams(normalized);
    if (parsed) {
      const duplicate = parsed.infoHash
        ? (torrents.find((t) => t.infoHash.toLowerCase() === parsed.infoHash) ?? null)
        : null;
      return { kind: 'magnet', uri: normalized, duplicate, ...parsed };
    }
  }

  if (URL_RE.test(single)) {
    return { kind: 'torrent-url' };
  }

  return { kind: 'invalid' };
}

@Component({
  selector: 'app-torrent-list',
  imports: [
    ButtonModule,
    ConfirmDialogModule,
    IconFieldModule,
    InputIconModule,
    InputTextModule,
    RouterOutlet,
    SkullMark,
    StatusIndicator,
    TableModule,
    ToastModule,
    TooltipModule,
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
  private readonly document = inject(DOCUMENT);
  private readonly destroyRef = inject(DestroyRef);
  readonly filter = inject(TorrentFilterService);

  private readonly formatBytes = new FormatBytesPipe();

  readonly fileInput = viewChild<ElementRef<HTMLInputElement>>('fileInput');
  readonly addFieldInput = viewChild<ElementRef<HTMLInputElement>>('addFieldInput');

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

  readonly sortField = signal<SortField>('name');
  readonly sortDirection = signal<SortDirection>('asc');

  readonly hasActiveFilter = computed(
    () => this.filter.statusFilter() !== 'all' || this.filter.searchText().trim() !== '',
  );

  /** README.md's Copy section: `No matches for "debain"` - the literal example is search-text
   * specific, so that's the only case that gets the exact quoted-query form; a status-only
   * filter (no search text) with zero matches falls back to naming the filter instead, since
   * `No matches for ""` would read as broken. */
  readonly noMatchTitle = computed(() => {
    const query = this.filter.searchText().trim();
    if (query !== '') {
      return `No matches for "${query}"`;
    }
    return `No ${STATUS_FILTER_LABELS[this.filter.statusFilter()]} torrents`;
  });

  /** "a note that filters are also narrowing the list" (STYLE_GUIDE_NOTES.md's empty-state
   * spec) - only said when a search AND a status filter are both active; either alone gets
   * its own single-cause phrasing instead, so "also" never appears with nothing else to add
   * to. */
  readonly noMatchBody = computed(() => {
    const query = this.filter.searchText().trim();
    const statusFilter = this.filter.statusFilter();
    if (query !== '' && statusFilter !== 'all') {
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
  }

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
    this.messageService.add({ severity: 'info', summary: 'Pausing', detail: pluralTorrentCount(targets.length) });
  }

  resumeAll(): void {
    const targets = this.events.torrents().filter((t) => t.state === 'STOPPED');
    if (targets.length === 0) {
      return;
    }
    targets.forEach((t) => this.torrentService.resume(t.infoHash).subscribe());
    this.messageService.add({ severity: 'info', summary: 'Resuming', detail: pluralTorrentCount(targets.length) });
  }

  // --- Add control (ADD_CONTROL.md / ADDENDUM_02: one bonded field + primary, not the
  // former separate Add Torrent / Add Magnet buttons) ---------------------------------

  readonly addValue = signal('');
  readonly addPending = signal(false);
  readonly addError = signal<string | null>(null);
  readonly isDraggingFile = signal(false);

  readonly addState = computed<AddState>(() => resolveAddState(this.addValue(), this.events.torrents()));

  readonly addFieldTone = computed<'idle' | 'accent' | 'alarm'>(() => {
    switch (this.addState().kind) {
      case 'empty':
        return 'idle';
      case 'magnet':
      case 'multi-magnet':
        return 'accent';
      case 'invalid':
      case 'torrent-url':
        return 'alarm';
    }
  });

  readonly addPrimary = computed<{ icon: string; label: string; disabled: boolean }>(() => {
    const state = this.addState();
    if (this.addPending()) {
      const label =
        state.kind === 'multi-magnet' ? `Add ${state.uris.length} magnets` : state.kind === 'magnet' ? 'Add magnet' : 'Add file…';
      return { icon: 'pi pi-spinner pi-spin', label, disabled: true };
    }
    switch (state.kind) {
      case 'empty':
        return { icon: 'pi pi-file-plus', label: 'Add file…', disabled: false };
      case 'magnet':
        return state.duplicate
          ? { icon: 'pi pi-plus', label: 'Show existing', disabled: false }
          : { icon: 'pi pi-plus', label: 'Add magnet', disabled: false };
      case 'multi-magnet':
        return { icon: 'pi pi-plus', label: `Add ${state.uris.length} magnets`, disabled: false };
      case 'torrent-url':
        return { icon: 'pi pi-plus', label: 'Add link', disabled: true };
      case 'invalid':
        return { icon: 'pi pi-plus', label: 'Add magnet', disabled: true };
    }
  });

  readonly echo = computed<EchoState | null>(() => {
    const error = this.addError();
    if (error) {
      return { tone: 'alarm', icon: 'pi pi-exclamation-triangle', name: error, meta: '', hint: 'Enter to retry · Esc to clear' };
    }

    const state = this.addState();
    switch (state.kind) {
      case 'empty':
        return null;
      case 'invalid':
        return {
          tone: 'alarm',
          icon: 'pi pi-exclamation-triangle',
          name: "That isn't a magnet link or a .torrent file.",
          meta: '',
          hint: 'Paste starts magnet:?xt=urn:btih:…',
        };
      case 'torrent-url':
        return {
          tone: 'alarm',
          icon: 'pi pi-exclamation-triangle',
          name: "Adding by URL isn't supported yet.",
          meta: '',
          hint: 'Save the .torrent file and use Add file… instead',
        };
      case 'magnet': {
        if (state.duplicate) {
          return {
            tone: 'alarm',
            icon: 'pi pi-exclamation-triangle',
            name: `Already in the list — ${state.duplicate.name}`,
            meta: '',
            hint: 'Enter to open · Esc to clear',
          };
        }
        const name =
          state.displayName ?? (state.infoHash ? `btih:${state.infoHash.slice(0, 4)}…${state.infoHash.slice(-4)}` : 'Unnamed magnet link');
        const metaParts: string[] = [];
        if (state.lengthBytes !== null) {
          metaParts.push(this.formatBytes.transform(state.lengthBytes));
        }
        if (state.trackerCount > 0) {
          metaParts.push(`${state.trackerCount} tracker${state.trackerCount === 1 ? '' : 's'}`);
        }
        return { tone: 'accent', icon: 'pi pi-check', name, meta: metaParts.join(' · '), hint: 'Enter to add · Esc to clear' };
      }
      case 'multi-magnet':
        return {
          tone: 'accent',
          icon: 'pi pi-check',
          name: `${state.firstName ?? 'Magnet link'} and ${state.uris.length - 1} more`,
          meta: `${state.uris.length} magnet links`,
          hint: 'Enter to add · Esc to clear',
        };
    }
  });

  constructor() {
    const pasteHandler = (event: ClipboardEvent) => this.onGlobalPaste(event);
    const dragOverHandler = (event: DragEvent) => this.onWindowDragOver(event);
    const dragLeaveHandler = (event: DragEvent) => this.onWindowDragLeave(event);
    const dropHandler = (event: DragEvent) => this.onWindowDrop(event);

    // Global rather than scoped to the add field, per ADD_CONTROL.md: "Cmd/Ctrl-V anywhere
    // on the page ... This is the single most important interaction in the control." Must
    // ignore paste/drop events targeting another text input, textarea or contenteditable
    // (the filter field, a rename dialog) so this doesn't hijack them.
    this.document.addEventListener('paste', pasteHandler);
    this.document.defaultView?.addEventListener('dragover', dragOverHandler);
    this.document.defaultView?.addEventListener('dragleave', dragLeaveHandler);
    this.document.defaultView?.addEventListener('drop', dropHandler);
    this.destroyRef.onDestroy(() => {
      this.document.removeEventListener('paste', pasteHandler);
      this.document.defaultView?.removeEventListener('dragover', dragOverHandler);
      this.document.defaultView?.removeEventListener('dragleave', dragLeaveHandler);
      this.document.defaultView?.removeEventListener('drop', dropHandler);
    });
  }

  onAddInput(event: Event): void {
    this.addValue.set((event.target as HTMLInputElement).value);
    this.addError.set(null);
  }

  onAddKeydownEnter(): void {
    this.onPrimaryClick();
  }

  onAddKeydownEscape(): void {
    this.clearAdd();
  }

  clearAdd(): void {
    this.addValue.set('');
    this.addError.set(null);
  }

  onPrimaryClick(): void {
    if (this.addPending() || this.addPrimary().disabled) {
      return;
    }
    const state = this.addState();
    switch (state.kind) {
      case 'empty':
        this.fileInput()?.nativeElement.click();
        break;
      case 'magnet':
        if (state.duplicate) {
          this.router.navigate(['/torrents', state.duplicate.infoHash]);
        } else {
          this.submitMagnet(state.uri);
        }
        break;
      case 'multi-magnet':
        this.submitMultipleMagnets(state.uris);
        break;
      case 'torrent-url':
      case 'invalid':
        break;
    }
  }

  onFilesPicked(event: Event): void {
    const input = event.target as HTMLInputElement;
    const files = input.files ? Array.from(input.files) : [];
    files.forEach((file) => this.uploadFile(file));
    input.value = '';
  }

  private uploadFile(file: File): void {
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
      },
      error: (err: { error?: { error?: string } }) => {
        this.removePendingUpload(pendingId);
        this.messageService.add({
          severity: 'error',
          summary: 'Upload failed',
          detail: err?.error?.error ?? 'Could not add torrent',
        });
      },
    });
  }

  /** No torrent to show yet on success - metadata fetch happens in the background
   * (design_docs/0028). No success toast: per ADD_CONTROL.md, "the new row appearing *is*
   * the confirmation." A failure keeps the echo strip open in its alarm state instead of a
   * toast, naming the reason, per the same doc's behaviour table. */
  private submitMagnet(uri: string): void {
    this.addPending.set(true);
    this.torrentService.addMagnet(uri).subscribe({
      next: () => {
        this.addPending.set(false);
        this.addValue.set('');
      },
      error: (err: { error?: { error?: string } }) => {
        this.addPending.set(false);
        this.addError.set(err?.error?.error ?? 'Invalid magnet link');
      },
    });
  }

  private submitMultipleMagnets(uris: string[]): void {
    this.addPending.set(true);
    let remaining = uris.length;
    let failed = 0;
    uris.forEach((uri) => {
      this.torrentService.addMagnet(uri).subscribe({
        next: () => {
          remaining--;
          if (remaining === 0) {
            this.finishMultiAdd(failed);
          }
        },
        error: () => {
          failed++;
          remaining--;
          if (remaining === 0) {
            this.finishMultiAdd(failed);
          }
        },
      });
    });
  }

  private finishMultiAdd(failed: number): void {
    this.addPending.set(false);
    if (failed > 0) {
      this.addError.set(`${failed} of the pasted links couldn't be added.`);
    } else {
      this.addValue.set('');
    }
  }

  private onGlobalPaste(event: ClipboardEvent): void {
    const target = event.target as HTMLElement | null;
    if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable)) {
      return;
    }
    const text = event.clipboardData?.getData('text/plain');
    if (!text?.trim()) {
      return;
    }
    event.preventDefault();
    this.addValue.set(text);
    this.addError.set(null);
    this.addFieldInput()?.nativeElement.focus();
  }

  private onWindowDragOver(event: DragEvent): void {
    if (!event.dataTransfer?.types.includes('Files')) {
      return;
    }
    event.preventDefault();
    this.isDraggingFile.set(true);
  }

  private onWindowDragLeave(event: DragEvent): void {
    if (event.relatedTarget === null) {
      this.isDraggingFile.set(false);
    }
  }

  private onWindowDrop(event: DragEvent): void {
    if (event.dataTransfer?.types.includes('Files')) {
      event.preventDefault();
    }
    this.isDraggingFile.set(false);

    const files = event.dataTransfer?.files;
    if (files && files.length > 0) {
      Array.from(files)
        .filter((file) => file.name.toLowerCase().endsWith('.torrent'))
        .forEach((file) => this.uploadFile(file));
      return;
    }

    // Dropped text (rather than a file) that looks like a magnet fills the field so the
    // echo strip can confirm it before anything is added, rather than adding blind.
    const text = event.dataTransfer?.getData('text/plain');
    if (text && normalizeMagnetCandidate(text.trim())) {
      event.preventDefault();
      this.addValue.set(text.trim());
      this.addError.set(null);
    }
  }

  private removePendingUpload(id: string): void {
    this.pendingUploads.update((uploads) => uploads.filter((u) => u.id !== id));
  }
}
