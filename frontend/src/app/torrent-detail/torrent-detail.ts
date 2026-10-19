import { DatePipe, DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, input, signal, viewChild } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { MenuItem } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { ContextMenu, ContextMenuModule } from 'primeng/contextmenu';
import { TabsModule } from 'primeng/tabs';
import { TooltipModule } from 'primeng/tooltip';
import { map } from 'rxjs';

import { Torrent } from '../models/torrent.model';
import { TorrentEventsService } from '../services/torrent-events.service';
import { TorrentActionsService } from '../services/torrent-actions.service';
import { DetailTab, TorrentDetailTabService } from '../services/torrent-detail-tab.service';
import { TorrentService } from '../services/torrent.service';
import { ActiveContextMenuRegistry } from '../shared/active-context-menu-registry';
import { CapMarker } from '../shared/cap-marker/cap-marker';
import { FormatBytesPipe } from '../shared/format-bytes.pipe';
import { FormatDurationPipe } from '../shared/format-duration.pipe';
import { FormatRatePipe } from '../shared/format-rate.pipe';
import { pollWhileInput } from '../shared/poll-while-input';
import { progressPercent } from '../shared/progress-percent';
import { TorrentCommands } from '../torrent-list/torrent-commands';
import { SeedingLimitsDialog } from '../torrent-list/torrent-row/seeding-limits-dialog/seeding-limits-dialog';
import { TorrentLabelsDialog } from '../torrent-list/torrent-row/torrent-labels-dialog/torrent-labels-dialog';
import { TorrentLimitsDialog } from '../torrent-list/torrent-row/torrent-limits-dialog/torrent-limits-dialog';
import { FilesTab } from './files-tab/files-tab';
import { PeersTab } from './peers-tab/peers-tab';
import { PieceMap } from './piece-map/piece-map';
import { SpeedTab } from './speed-tab/speed-tab';
import { TrackersTab } from './trackers-tab/trackers-tab';

const FILE_COUNT_POLL_INTERVAL_MS = 3000;

/**
 * The detail view shell: a header, fact grid, action footer, and a tabbed set of
 * self-contained detail endpoints (design_docs/0032's tasks 6-7; design_docs/0031). The
 * header reuses TorrentEventsService's existing live data rather than a dedicated "summary"
 * endpoint - everything shown here is already part of the list's own data.
 *
 * <p>The actions, their menu and their toasts are TorrentCommands', shared with TorrentRow, so the
 * two offer the same things and show the same pending state. That service comes from
 * TorrentList: this component always renders inside TorrentList's <router-outlet>, so
 * hierarchical DI finds it (and the toast and confirm dialog it uses). See design_docs/0101.
 */
@Component({
  selector: 'app-torrent-detail',
  imports: [
    ButtonModule,
    CapMarker,
    ContextMenuModule,
    DatePipe,
    DecimalPipe,
    FilesTab,
    FormatBytesPipe,
    FormatDurationPipe,
    FormatRatePipe,
    PeersTab,
    PieceMap,
    SeedingLimitsDialog,
    SpeedTab,
    TabsModule,
    TooltipModule,
    TorrentLabelsDialog,
    TorrentLimitsDialog,
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
  private readonly actions = inject(TorrentActionsService);
  private readonly commands = inject(TorrentCommands);
  private readonly activeContextMenus = inject(ActiveContextMenuRegistry);
  private readonly router = inject(Router);
  /** Instantiated directly (not via the template pipe binding) so avgDownloadTooltip()/
   * avgUploadTooltip() can format a rate for a plain title attribute string - same pattern
   * TrackersTab uses for DatePipe. See design_docs/0067. */
  private readonly formatRate = new FormatRatePipe();

  /** Bound directly from the :infoHash route param - see withComponentInputBinding() in
   * app.config.ts. */
  readonly infoHash = input.required<string>();

  readonly torrent = computed(() => this.events.torrents().find((t) => t.infoHash === this.infoHash()));

  readonly progressPercent = computed(() => progressPercent(this.torrent()?.progress ?? 0));

  readonly isVerifying = computed(() => this.torrent()?.state === 'VERIFYING');

  /** Files by default; Pieces only while there's actually something to watch happen
   * (downloading/verifying) - "never open a completed torrent on Pieces." Once the user picks
   * a tab for this torrent explicitly, that choice wins over the default from then on for the
   * rest of the session - see TorrentDetailTabService. A magnet still fetching metadata (the
   * guide's other reason to hide Pieces) never gets here: its row doesn't open the panel, and
   * neither do the keyboard shortcuts (design_docs/0070). */
  readonly activeTab = computed<DetailTab>(() => {
    const remembered = this.tabMemory.get(this.infoHash());
    if (remembered) {
      return remembered;
    }
    const state = this.torrent()?.state;
    return state === 'DOWNLOADING' || state === 'VERIFYING' ? 'pieces' : 'files';
  });

  /** p-tabs' own value type is the generic string | number | undefined a tab-content library
   * has to support - this app's own tab values are always one of the five DetailTab strings,
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

  /** Guide's fact-grid "Ratio" cell - lifetime uploaded/downloaded. Uses
   * lifetimeUploadedBytes (design_docs/0064), not the session-scoped bytesUploaded - a ratio
   * that reset to 0 on every restart was never a meaningful "how much have I contributed"
   * figure. bytesDownloaded itself needs no lifetime equivalent - verified-complete pieces
   * already stay on disk (and therefore counted) across restarts on their own. Em dash rather
   * than a divide-by-zero Infinity/NaN for a torrent that hasn't downloaded anything yet (a
   * fresh magnet, or 0% paused) - never render `∞`, per the guide's own voice rule. */
  readonly ratio = computed(() => {
    const torrent = this.torrent();
    if (!torrent || torrent.bytesDownloaded <= 0) {
      return null;
    }
    return torrent.lifetimeUploadedBytes / torrent.bytesDownloaded;
  });

  /** Down/Up fact cells' own tooltip - the lifetime average, a footnote on the current rate
   * rather than a fact of its own (unlike Active/Completed/Wasted, this has somewhere natural
   * to live already). bytesDownloaded (verified), matching the progress
   * bar/ratio's own basis - lifetime downloaded needs no separate tracking since completed
   * pieces already persist across restarts on their own. Empty string (no tooltip at all)
   * rather than a divide-by-zero for a torrent that's never actually run. See
   * design_docs/0067. */
  readonly avgDownloadTooltip = computed(() => this.averageRateTooltip((t) => t.bytesDownloaded));
  readonly avgUploadTooltip = computed(() => this.averageRateTooltip((t) => t.lifetimeUploadedBytes));

  private averageRateTooltip(bytes: (torrent: Torrent) => number): string {
    const torrent = this.torrent();
    if (!torrent || torrent.timeActiveMillis <= 0) {
      return '';
    }
    const average = bytes(torrent) / (torrent.timeActiveMillis / 1000);
    return `Average: ${this.formatRate.transform(average)}`;
  }

  /** Closing is just navigating away - see torrent-list.html's own comment on why the panel's
   * open/closed state is route-driven rather than a separate boolean. */
  close(): void {
    this.router.navigate(['/']);
  }

  /** Looked up per torrent from the shared TorrentActionsService, not one flag for the whole
   * panel: this component instance is reused as the route param changes, and a pause/resume
   * request can take a long time (it blocks on the tracker), so an action still in flight for
   * one torrent must not disable the footer for whichever torrent is selected next. Shared with
   * TorrentRow, so either one shows an action the other started. See design_docs/0033's addenda. */
  readonly pendingAction = computed(() => this.actions.pendingFor(this.infoHash()));
  readonly showSeedingLimitsDialog = signal(false);
  readonly showTorrentLimitsDialog = signal(false);
  readonly showTorrentLabelsDialog = signal(false);

  private readonly detailContextMenu = viewChild.required<ContextMenu>('detailMenu');

  /** The same menu as TorrentRow's (TorrentCommands). A remove from here also closes the
   * panel - the row that disappears there is the whole view here. */
  readonly contextMenuItems = computed<MenuItem[]>(() => {
    const torrent = this.torrent();
    if (!torrent) {
      return [];
    }
    const infoHash = torrent.infoHash;
    return this.commands.menuItems(
      torrent,
      {
        seedingLimits: () => this.showSeedingLimitsDialog.set(true),
        torrentLimits: () => this.showTorrentLimitsDialog.set(true),
        labels: () => this.showTorrentLabelsDialog.set(true),
      },
      () => this.closeIfStillShowing(infoHash),
    );
  });

  onMenuClick(event: MouseEvent): void {
    this.activeContextMenus.show(this.detailContextMenu(), event);
  }

  /** The footer's Pause/Resume button. */
  onToggle(): void {
    const torrent = this.torrent();
    if (!torrent) {
      return;
    }
    if (torrent.state === 'STOPPED') {
      this.commands.resume(torrent);
    } else {
      this.commands.pause(torrent);
    }
  }

  /** A slow remove can finish after the panel has moved on to another torrent - closing it then
   * would close the wrong torrent's panel. */
  private closeIfStillShowing(infoHash: string): void {
    if (this.infoHash() === infoHash) {
      this.router.navigate(['/']);
    }
  }
}
