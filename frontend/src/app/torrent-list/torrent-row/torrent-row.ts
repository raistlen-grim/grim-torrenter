import { ChangeDetectionStrategy, Component, computed, inject, input, signal, viewChild } from '@angular/core';
import { Router } from '@angular/router';
import { ConfirmationService, MenuItem, MessageService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { ContextMenu, ContextMenuModule } from 'primeng/contextmenu';
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
 *
 * <p>The row itself is the click target (style guide's row-anatomy redesign, see
 * design_docs/0032's second pass) - not the name, which is plain text now, never a link.
 * `tabindex="0"` plus `(keydown.enter)` make that keyboard-operable in place of the
 * `<a routerLink>` this replaced; `(click)` on the actions wrapper stops propagation so
 * clicking a row action doesn't also navigate.
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
    SeedingLimitsDialog,
    TooltipModule,
  ],
  templateUrl: './torrent-row.html',
  styleUrl: './torrent-row.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    tabindex: '0',
    '[class.row-pending]': 'pendingAction() !== null',
    '(contextmenu)': 'onContextMenu($event)',
    '(click)': 'navigateToDetail()',
    '(keydown.enter)': 'navigateToDetail()',
  },
})
export class TorrentRow {
  private readonly torrentService = inject(TorrentService);
  private readonly events = inject(TorrentEventsService);
  private readonly confirmationService = inject(ConfirmationService);
  private readonly messageService = inject(MessageService);
  private readonly activeContextMenus = inject(ActiveContextMenuRegistry);
  private readonly router = inject(Router);

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
  /** Split so the template can truncate the stem with an ellipsis while always keeping the
   * extension visible (style guide row anatomy: "the extension is preserved"). A plain
   * `lastIndexOf('.')` would cut `archive.tar.xz` down to `.xz` - COMPOUND_EXTENSIONS keeps
   * the handful of common two-part extensions whole instead. */
  private readonly nameSplit = computed(() => TorrentRow.splitFileName(this.name()));
  readonly nameStem = computed(() => this.nameSplit().stem);
  readonly nameExtension = computed(() => this.nameSplit().extension);
  readonly state = computed(() => this.torrent().state);
  readonly lastError = computed(() => this.torrent().lastError);
  readonly totalLength = computed(() => this.torrent().totalLength);
  /** Based on bytesDownloaded (verified-complete), not bytesReceived - ETA should reflect
   * how much legitimately-verified work remains, same basis as the progress bar/%, even
   * though the rate it's divided by is derived from the continuously-moving bytesReceived
   * (see TorrentEventsService). Byte totals themselves (bytesDownloaded/bytesUploaded) and
   * connectedPeers are no longer shown in the row - the style guide's row anatomy drops them
   * in favor of just a rate/percentage per column, moving totals to the details panel. */
  readonly bytesRemaining = computed(() => this.torrent().totalLength - this.torrent().bytesDownloaded);
  readonly downloadRateBytesPerSec = computed(() => this.torrent().downloadRateBytesPerSec);
  readonly downloadRateWindows = computed(() => this.torrent().downloadRateWindows);
  readonly uploadRateBytesPerSec = computed(() => this.torrent().uploadRateBytesPerSec);
  readonly uploadRateWindows = computed(() => this.torrent().uploadRateWindows);

  /** VERIFYING means the backend hasn't yet re-established which pieces are actually
   * complete after a restart (see design_docs/0026) - pause/resume/remove are disabled
   * until that settles, rather than acting on a torrent whose real state isn't known yet. */
  readonly isVerifying = computed(() => this.state() === 'VERIFYING');

  /** Rounds any genuinely nonzero progress up to at least 1 - Math.round alone would sit at
   * 0 for a long time on a large file (both in the Done% cell and the underlay's width),
   * looking indistinguishable from "hasn't started" even once real data has arrived. */
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

  navigateToDetail(): void {
    this.router.navigate(['/torrents', this.infoHash()]);
  }

  onContextMenu(event: MouseEvent): void {
    event.preventDefault();
    this.activeContextMenus.show(this.rowContextMenu(), event);
  }

  /** The row's `ellipsis` action button opens the identical menu as right-click (style
   * guide: "⋯ opens the same context menu as right-click") - `stopPropagation` here (rather
   * than on every button individually) is why the actions wrapper's own `(click)` handler in
   * the template exists, so this click doesn't also fire the row's own navigate-on-click. */
  onEllipsisClick(event: MouseEvent): void {
    this.activeContextMenus.show(this.rowContextMenu(), event);
  }

  /** `archive.tar.xz` keeps its whole compound extension; anything else just splits on the
   * final dot. A leading dot (`.gitignore`) or no dot at all has no extension to split off. */
  private static splitFileName(name: string): { stem: string; extension: string } {
    const lastDot = name.lastIndexOf('.');
    if (lastDot <= 0) {
      return { stem: name, extension: '' };
    }
    const secondLastDot = name.lastIndexOf('.', lastDot - 1);
    if (secondLastDot > 0) {
      const compound = name.slice(secondLastDot + 1).toLowerCase();
      if (compound === 'tar.gz' || compound === 'tar.bz2' || compound === 'tar.xz') {
        return { stem: name.slice(0, secondLastDot), extension: name.slice(secondLastDot) };
      }
    }
    return { stem: name.slice(0, lastDot), extension: name.slice(lastDot) };
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
