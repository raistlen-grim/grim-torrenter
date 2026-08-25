import { ChangeDetectionStrategy, Component, computed, inject, input, signal, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ConfirmationService, MenuItem, MessageService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { ContextMenu, ContextMenuModule } from 'primeng/contextmenu';
import { ProgressBarModule } from 'primeng/progressbar';
import { SplitButtonModule } from 'primeng/splitbutton';
import { TooltipModule } from 'primeng/tooltip';
import { finalize } from 'rxjs';

import { TorrentWithRate } from '../../models/torrent.model';
import { TorrentEventsService } from '../../services/torrent-events.service';
import { TorrentService } from '../../services/torrent.service';
import { FormatBytesPipe } from '../../shared/format-bytes.pipe';
import { FormatEtaPipe } from '../../shared/format-eta.pipe';
import { FormatRateWindowsPipe } from '../../shared/format-rate-windows.pipe';
import { FormatRatePipe } from '../../shared/format-rate.pipe';
import { ActiveContextMenuRegistry } from '../../shared/active-context-menu-registry';
import { StatusIndicator } from '../../shared/status-indicator/status-indicator';
import { torrentStateDisplay } from '../../shared/status-display';
import { SeedingLimitsDialog } from './seeding-limits-dialog/seeding-limits-dialog';

/**
 * One torrent's row. An attribute selector on `tr` (not an element selector) so its
 * template - a plain sequence of `<td>`s - becomes the `<tr>`'s own children rather than
 * being nested inside an extra custom element, which Angular's HTML content-model rules
 * would otherwise foster out of the table entirely. See design_docs/0027.
 *
 * <p>Each displayed field is its own computed signal derived from the `torrent` input
 * rather than being read inline in the template: `torrent()` is a brand-new object on
 * every 2s snapshot even when nothing changed (TorrentEventsService), but a computed only
 * notifies its consumers when its own value actually differs, so unrelated fields ticking
 * (e.g. upload rate while state/progress are steady) no longer touches bindings that don't
 * depend on them.
 */
@Component({
  selector: 'tr[app-torrent-row]',
  imports: [
    ButtonModule,
    ContextMenuModule,
    FormatBytesPipe,
    FormatEtaPipe,
    FormatRateWindowsPipe,
    FormatRatePipe,
    ProgressBarModule,
    RouterLink,
    SeedingLimitsDialog,
    SplitButtonModule,
    StatusIndicator,
    TooltipModule,
  ],
  templateUrl: './torrent-row.html',
  styleUrl: './torrent-row.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '[class.row-pending]': 'pendingAction() !== null',
    '(contextmenu)': 'onContextMenu($event)',
  },
})
export class TorrentRow {
  private readonly torrentService = inject(TorrentService);
  private readonly events = inject(TorrentEventsService);
  private readonly confirmationService = inject(ConfirmationService);
  private readonly messageService = inject(MessageService);
  private readonly activeContextMenus = inject(ActiveContextMenuRegistry);

  readonly torrent = input.required<TorrentWithRate>();

  /** Set the instant Pause/Resume/Remove is clicked, cleared on response (success or
   * failure) via finalize - drives both the clicked button's own loading/disabled state
   * and a whole-row dim (see host binding above), so this torrent visibly has something
   * in flight against it rather than looking like the click did nothing. See
   * design_docs/0033. */
  readonly pendingAction = signal<'pause' | 'resume' | 'remove' | null>(null);

  /** Toggled from the context menu below, hosting its own SeedingLimitsDialog instance in
   * this row's own template - same self-contained-per-row pattern as the row's own
   * p-contextMenu, rather than a new shared dialog service. See design_docs/0054. */
  readonly showSeedingLimitsDialog = signal(false);

  readonly infoHash = computed(() => this.torrent().infoHash);
  readonly name = computed(() => this.torrent().name);
  readonly state = computed(() => this.torrent().state);
  readonly lastError = computed(() => this.torrent().lastError);
  readonly bytesDownloaded = computed(() => this.torrent().bytesDownloaded);
  readonly totalLength = computed(() => this.torrent().totalLength);
  /** Based on bytesDownloaded (verified-complete), not bytesReceived - ETA should reflect
   * how much legitimately-verified work remains, same basis as the progress bar/%, even
   * though the rate it's divided by is derived from the continuously-moving bytesReceived
   * (see TorrentEventsService). */
  readonly bytesRemaining = computed(() => this.torrent().totalLength - this.torrent().bytesDownloaded);
  readonly downloadRateBytesPerSec = computed(() => this.torrent().downloadRateBytesPerSec);
  readonly downloadRateWindows = computed(() => this.torrent().downloadRateWindows);
  readonly bytesUploaded = computed(() => this.torrent().bytesUploaded);
  readonly uploadRateBytesPerSec = computed(() => this.torrent().uploadRateBytesPerSec);
  readonly uploadRateWindows = computed(() => this.torrent().uploadRateWindows);
  readonly connectedPeers = computed(() => this.torrent().connectedPeers);

  /** VERIFYING means the backend hasn't yet re-established which pieces are actually
   * complete after a restart (see design_docs/0026) - pause/resume/remove are disabled
   * until that settles, rather than acting on a torrent whose real state isn't known yet. */
  readonly isVerifying = computed(() => this.state() === 'VERIFYING');

  /** Rounds any genuinely nonzero progress up to at least 1 - p-progressBar hides its
   * value display entirely at exactly 0, and Math.round alone would sit at 0 for a long
   * time on a large file, looking indistinguishable from "hasn't started". */
  readonly progressPercent = computed(() => {
    const progress = this.torrent().progress;
    if (progress <= 0) {
      return 0;
    }
    return Math.max(1, Math.round(progress * 100));
  });

  /** Ink-weight + icon, not a colored severity badge - see design_docs/0033's style-guide
   * reconciliation ("one hue, one alarm," never a five-color state legend). */
  readonly stateDisplay = computed(() => torrentStateDisplay(this.state()));

  /** Built once, not cached in a Map keyed by infoHash like before this component existed -
   * this instance is already scoped to exactly one torrent for its whole lifetime (see
   * trackByInfoHash in torrent-list.ts), so the instance itself is the cache key. */
  readonly removeMenuItems: MenuItem[] = [
    {
      label: 'Remove and delete files',
      icon: 'pi pi-exclamation-triangle',
      command: () => this.confirmRemoveWithData(),
    },
  ];

  private readonly rowContextMenu = viewChild.required<ContextMenu>('rowMenu');

  /** Trimmed from the style guide's full §08 spec (Pause, Open folder, Limit rate…, Add
   * label…, Copy magnet, Remove…) to only what's actually backed by something real today -
   * see design_docs/0043. Supplements the row's existing inline buttons rather than
   * replacing them, reusing their exact same handlers so there's one source of truth per
   * action. */
  readonly contextMenuItems = computed<MenuItem[]>(() => {
    const disabled = this.isVerifying() || this.pendingAction() !== null;
    const toggleItem: MenuItem =
      this.state() === 'STOPPED'
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

  onContextMenu(event: MouseEvent): void {
    event.preventDefault();
    this.activeContextMenus.show(this.rowContextMenu(), event);
  }

  /** A magnet URI needs nothing beyond the info hash to be valid - dn is a display-name
   * hint, not required. Built client-side; no backend endpoint needed. */
  copyMagnetLink(): void {
    const magnetUri = `magnet:?xt=urn:btih:${this.infoHash()}&dn=${encodeURIComponent(this.name())}`;
    navigator.clipboard.writeText(magnetUri).then(
      () => this.messageService.add({ severity: 'success', summary: 'Magnet link copied', detail: this.name() }),
      () => this.messageService.add({ severity: 'error', summary: 'Could not copy magnet link', detail: this.name() }),
    );
  }

  onPause(): void {
    this.pendingAction.set('pause');
    this.torrentService
      .pause(this.infoHash())
      .pipe(finalize(() => this.pendingAction.set(null)))
      .subscribe({ error: () => this.notifyActionFailed('pause') });
  }

  onResume(): void {
    this.pendingAction.set('resume');
    this.torrentService
      .resume(this.infoHash())
      .pipe(finalize(() => this.pendingAction.set(null)))
      .subscribe({ error: () => this.notifyActionFailed('resume') });
  }

  onRemove(): void {
    this.pendingAction.set('remove');
    const infoHash = this.infoHash();
    this.torrentService
      .remove(infoHash)
      .pipe(finalize(() => this.pendingAction.set(null)))
      .subscribe({
        next: () => this.events.removeLocal(infoHash),
        error: () => this.notifyActionFailed('remove'),
      });
  }

  private confirmRemoveWithData(): void {
    const infoHash = this.infoHash();
    this.confirmationService.confirm({
      header: 'Delete downloaded files?',
      message: `This will permanently delete the downloaded files for "${this.name()}". This cannot be undone.`,
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
            next: () => this.events.removeLocal(infoHash),
            error: () => this.notifyActionFailed('remove'),
          });
      },
    });
  }

  /** Pause/Resume/Remove previously failed silently (no toast existed for them at all,
   * unlike upload/magnet-add) - a failed action clearing its pending state with no
   * explanation would read as even more confusing than doing nothing, so this closes that
   * gap the same way 0029's upload/magnet toasts already do. */
  private notifyActionFailed(action: 'pause' | 'resume' | 'remove'): void {
    this.messageService.add({
      severity: 'error',
      summary: `Could not ${action} torrent`,
      detail: `"${this.name()}" - please try again.`,
    });
  }
}
