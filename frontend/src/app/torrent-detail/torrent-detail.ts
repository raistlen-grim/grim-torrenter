import { DatePipe, DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, input, signal, viewChild } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { ConfirmationService, MenuItem, MessageService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { ContextMenu, ContextMenuModule } from 'primeng/contextmenu';
import { TabsModule } from 'primeng/tabs';
import { TooltipModule } from 'primeng/tooltip';
import { finalize, map } from 'rxjs';

import { TorrentEventsService } from '../services/torrent-events.service';
import { DetailTab, TorrentDetailTabService } from '../services/torrent-detail-tab.service';
import { TorrentService } from '../services/torrent.service';
import { ActiveContextMenuRegistry } from '../shared/active-context-menu-registry';
import { FormatBytesPipe } from '../shared/format-bytes.pipe';
import { FormatRatePipe } from '../shared/format-rate.pipe';
import { pollWhileInput } from '../shared/poll-while-input';
import { SeedingLimitsDialog } from '../torrent-list/torrent-row/seeding-limits-dialog/seeding-limits-dialog';
import { FilesTab } from './files-tab/files-tab';
import { PeersTab } from './peers-tab/peers-tab';
import { PieceMap } from './piece-map/piece-map';
import { TrackersTab } from './trackers-tab/trackers-tab';

const FILE_COUNT_POLL_INTERVAL_MS = 3000;

/**
 * The detail view shell: a header, fact grid, action footer, and a tabbed set of
 * self-contained detail endpoints (design_docs/0032's tasks 6-7; design_docs/0031). The
 * header reuses TorrentEventsService's existing live data rather than a dedicated "summary"
 * endpoint - everything shown here is already part of the list's own data.
 *
 * <p>Pause/resume/remove are duplicated here rather than factored into a shared service with
 * TorrentRow - same self-contained-per-instance precedent SeedingLimitsDialog's own embedding
 * already follows (see TorrentRow's own comment). ConfirmationService/MessageService are
 * injected, not provided here - this component is always rendered inside TorrentList's
 * <router-outlet> (see torrent-list.html), so it resolves TorrentList's own instances via
 * Angular's hierarchical DI and its existing <p-toast>/<p-confirmDialog> already catch
 * whatever this component adds to them, the same way TorrentRow already does.
 */
@Component({
  selector: 'app-torrent-detail',
  imports: [
    ButtonModule,
    ContextMenuModule,
    DatePipe,
    DecimalPipe,
    FilesTab,
    FormatBytesPipe,
    FormatRatePipe,
    PeersTab,
    PieceMap,
    SeedingLimitsDialog,
    TabsModule,
    TooltipModule,
    TrackersTab,
  ],
  templateUrl: './torrent-detail.html',
  styleUrl: './torrent-detail.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TorrentDetail {
  private readonly events = inject(TorrentEventsService);
  private readonly tabMemory = inject(TorrentDetailTabService);
  private readonly torrentService = inject(TorrentService);
  private readonly confirmationService = inject(ConfirmationService);
  private readonly messageService = inject(MessageService);
  private readonly activeContextMenus = inject(ActiveContextMenuRegistry);
  private readonly router = inject(Router);

  /** Bound directly from the :infoHash route param - see withComponentInputBinding() in
   * app.config.ts. */
  readonly infoHash = input.required<string>();

  readonly torrent = computed(() => this.events.torrents().find((t) => t.infoHash === this.infoHash()));

  readonly progressPercent = computed(() => {
    const torrent = this.torrent();
    if (!torrent || torrent.progress <= 0) {
      return 0;
    }
    return Math.max(1, Math.round(torrent.progress * 100));
  });

  readonly isVerifying = computed(() => this.torrent()?.state === 'VERIFYING');

  /** Files by default; Pieces only while there's actually something to watch happen
   * (downloading/verifying) - "never open a completed torrent on Pieces." Once the user picks
   * a tab for this torrent explicitly, that choice wins over the default from then on for the
   * rest of the session - see TorrentDetailTabService. A magnet still fetching metadata (the
   * guide's other reason to hide Pieces) can't actually happen here: this app only ever
   * registers a torrent - making it visible/selectable at all - once its metadata is already
   * fully known (see design_docs/0028), so there's no in-between state to guard against. */
  readonly activeTab = computed<DetailTab>(() => {
    const remembered = this.tabMemory.get(this.infoHash());
    if (remembered) {
      return remembered;
    }
    const state = this.torrent()?.state;
    return state === 'DOWNLOADING' || state === 'VERIFYING' ? 'pieces' : 'files';
  });

  /** p-tabs' own value type is the generic string | number | undefined a tab-content library
   * has to support - this app's own tab values are always one of the four DetailTab strings,
   * so anything else here would mean PrimeNG itself emitted something unexpected. */
  onTabChange(tab: string | number | undefined): void {
    if (typeof tab === 'string') {
      this.tabMemory.set(this.infoHash(), tab as DetailTab);
    }
  }

  /** The only one of the tab strip's four counts with no field already on the torrent
   * snapshot (Peers/Trackers/Pieces all read connectedPeers/trackerCount/totalPieces
   * straight off it) - a second, independent poll of the same files() endpoint FilesTab
   * itself polls, traded for not restructuring FilesTab to take its data as an input just to
   * share one fetch, or adding a backend field for what's otherwise a cosmetic tab-strip
   * count. See design_docs/0032's task 7 notes. */
  readonly fileCount = toSignal(
    pollWhileInput(this.infoHash, FILE_COUNT_POLL_INTERVAL_MS, (infoHash) =>
      this.torrentService.files(infoHash).pipe(map((files) => files.length)),
    ),
    { initialValue: 0 },
  );

  /** Guide's fact-grid "Ratio" cell - uploaded/downloaded, derived client-side (no backend
   * field). Em dash rather than a divide-by-zero Infinity/NaN for a torrent that hasn't
   * downloaded anything yet (a fresh magnet, or 0% paused) - never render `∞`, per the
   * guide's own voice rule. */
  readonly ratio = computed(() => {
    const torrent = this.torrent();
    if (!torrent || torrent.bytesDownloaded <= 0) {
      return null;
    }
    return torrent.bytesUploaded / torrent.bytesDownloaded;
  });

  /** Closing is just navigating away - see torrent-list.html's own comment on why the panel's
   * open/closed state is route-driven rather than a separate boolean. */
  close(): void {
    this.router.navigate(['/']);
  }

  readonly pendingAction = signal<'pause' | 'resume' | 'remove' | null>(null);
  readonly showSeedingLimitsDialog = signal(false);

  private readonly detailContextMenu = viewChild.required<ContextMenu>('detailMenu');

  /** Same trimmed set as TorrentRow's own contextMenuItems - see that component's comment
   * for why (only what's actually backed by something real today, design_docs/0043). */
  readonly contextMenuItems = computed<MenuItem[]>(() => {
    const torrent = this.torrent();
    const disabled = this.isVerifying() || this.pendingAction() !== null;
    const toggleItem: MenuItem =
      torrent?.state === 'STOPPED'
        ? { label: 'Resume', icon: 'pi pi-play', disabled, command: () => this.onResume() }
        : { label: 'Pause', icon: 'pi pi-pause', disabled, command: () => this.onPause() };
    return [
      toggleItem,
      { label: 'Copy magnet link', icon: 'pi pi-copy', command: () => this.copyMagnetLink() },
      { label: 'Seeding limits…', icon: 'pi pi-gauge', command: () => this.showSeedingLimitsDialog.set(true) },
      { separator: true },
      { label: 'Remove', icon: 'pi pi-trash', disabled, command: () => this.onRemove() },
      {
        label: 'Remove and delete files',
        icon: 'pi pi-exclamation-triangle',
        disabled,
        command: () => this.confirmRemoveWithData(),
      },
    ];
  });

  onMenuClick(event: MouseEvent): void {
    this.activeContextMenus.show(this.detailContextMenu(), event);
  }

  copyMagnetLink(): void {
    const torrent = this.torrent();
    if (!torrent) {
      return;
    }
    const magnetUri = `magnet:?xt=urn:btih:${torrent.infoHash}&dn=${encodeURIComponent(torrent.name)}`;
    navigator.clipboard.writeText(magnetUri).then(
      () => this.messageService.add({ severity: 'success', summary: 'Magnet link copied', detail: torrent.name }),
      () => this.messageService.add({ severity: 'error', summary: 'Could not copy magnet link', detail: torrent.name }),
    );
  }

  onPause(): void {
    const infoHash = this.infoHash();
    this.pendingAction.set('pause');
    this.torrentService
      .pause(infoHash)
      .pipe(finalize(() => this.pendingAction.set(null)))
      .subscribe({ error: () => this.notifyActionFailed('pause') });
  }

  onResume(): void {
    const infoHash = this.infoHash();
    this.pendingAction.set('resume');
    this.torrentService
      .resume(infoHash)
      .pipe(finalize(() => this.pendingAction.set(null)))
      .subscribe({ error: () => this.notifyActionFailed('resume') });
  }

  /** Unlike TorrentRow's own onRemove(), this also navigates back to the list - the row
   * that disappears on removal there is the whole view here, and there's nothing left to
   * show once torrent() stops resolving. */
  onRemove(): void {
    const infoHash = this.infoHash();
    this.pendingAction.set('remove');
    this.torrentService
      .remove(infoHash)
      .pipe(finalize(() => this.pendingAction.set(null)))
      .subscribe({
        next: () => {
          this.events.removeLocal(infoHash);
          this.router.navigate(['/']);
        },
        error: () => this.notifyActionFailed('remove'),
      });
  }

  private confirmRemoveWithData(): void {
    const torrent = this.torrent();
    const infoHash = this.infoHash();
    if (!torrent) {
      return;
    }
    this.confirmationService.confirm({
      header: 'Delete downloaded files?',
      message: `This will permanently delete the downloaded files for "${torrent.name}". This cannot be undone.`,
      icon: 'pi pi-exclamation-triangle',
      acceptLabel: 'Delete',
      rejectLabel: 'Cancel',
      acceptButtonProps: { severity: 'danger' },
      accept: () => {
        this.pendingAction.set('remove');
        this.torrentService
          .remove(infoHash, true)
          .pipe(finalize(() => this.pendingAction.set(null)))
          .subscribe({
            next: () => {
              this.events.removeLocal(infoHash);
              this.router.navigate(['/']);
            },
            error: () => this.notifyActionFailed('remove'),
          });
      },
    });
  }

  private notifyActionFailed(action: 'pause' | 'resume' | 'remove'): void {
    this.messageService.add({
      severity: 'error',
      summary: `Could not ${action} torrent`,
      detail: `"${this.torrent()?.name}" - please try again.`,
    });
  }
}
