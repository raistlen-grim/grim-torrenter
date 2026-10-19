import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal, viewChild } from '@angular/core';
import { Router } from '@angular/router';
import { MenuItem } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { ContextMenu, ContextMenuModule } from 'primeng/contextmenu';

import { Torrent } from '../../models/torrent.model';
import { LabelService } from '../../services/label.service';
import { TorrentActionsService } from '../../services/torrent-actions.service';
import { splitFileName } from '../../shared/file-name';
import { FormatBytesPipe } from '../../shared/format-bytes.pipe';
import { CapMarker } from '../../shared/cap-marker/cap-marker';
import { FormatEtaPipe, etaDetail } from '../../shared/format-eta.pipe';
import { FormatRatePipe } from '../../shared/format-rate.pipe';
import { progressPercent } from '../../shared/progress-percent';
import { ActiveContextMenuRegistry } from '../../shared/active-context-menu-registry';
import { RateTrend } from '../../shared/rate-trend/rate-trend';
import { torrentStateDisplay } from '../../shared/status-display';
import { TorrentCommands } from '../torrent-commands';
import { SeedingLimitsDialog } from './seeding-limits-dialog/seeding-limits-dialog';
import { TorrentLabelsDialog } from './torrent-labels-dialog/torrent-labels-dialog';
import { TorrentLimitsDialog } from './torrent-limits-dialog/torrent-limits-dialog';

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
 * `tabindex="0"` makes the row focusable in place of the `<a routerLink>` this replaced; the
 * keys themselves (Space, Enter, arrows, `I` to open the details) are TorrentList's list-wide
 * shortcuts, not handled here (design_docs/0083); `(click)` on the actions wrapper stops propagation so
 * clicking a row action doesn't also navigate.
 */
/** More than this many labels collapse into a "+N" chip so a heavily-labelled torrent can't
 * crowd its own name out of the row. See design_docs/0077. */
const MAX_VISIBLE_LABEL_CHIPS = 2;

@Component({
  selector: 'tr[app-torrent-row]',
  imports: [
    ButtonModule,
    ContextMenuModule,
    FormatBytesPipe,
    CapMarker,
    FormatEtaPipe,
    FormatRatePipe,
    RateTrend,
    SeedingLimitsDialog,
    TorrentLabelsDialog,
    TorrentLimitsDialog,
  ],
  templateUrl: './torrent-row.html',
  styleUrl: './torrent-row.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    tabindex: '0',
    '[class.row-pending]': 'pendingAction() !== null',
    '[class.row-selected]': 'selected()',
    '[class.row-checked]': 'checked()',
    '(contextmenu)': 'onContextMenu($event)',
    '(click)': 'onRowClick($event)',
    '(mousedown)': 'onRowMouseDown($event)',
  },
})
export class TorrentRow {
  private readonly actions = inject(TorrentActionsService);
  private readonly commands = inject(TorrentCommands);
  private readonly activeContextMenus = inject(ActiveContextMenuRegistry);
  private readonly router = inject(Router);
  private readonly labelService = inject(LabelService);

  readonly torrent = input.required<Torrent>();

  /** True while the details panel is open (design_docs/0044's dock-and-push layout) - the
   * row sheds to state-icon/name/size/done, matching the guide's docking spec ("the table
   * gives up its rate columns so that nothing the user selected is hidden by the thing they
   * opened"). Driven from TorrentList's own isDetailOpen(), not this row's own state, since
   * every row sheds together regardless of which one is selected. */
  readonly compact = input(false);

  /** True when this row's torrent is the one the open detail drawer belongs to - driven from
   * TorrentList's own selectedInfoHash(), not derived locally, so every row agrees on which
   * one (if any) is selected without each re-deriving it from the route itself. Style guide's
   * "Row selected" token - see design_docs/0032's README and TODO.md's own description of it.
   * This is the single "open in the panel" row; the multi-select set is `checked` below. */
  readonly selected = input(false);

  /** True when this row is in TorrentList's multi-select set (design_docs/0083) - a separate
   * thing from `selected` above. The set itself lives in TorrentList; the row only reports the
   * gestures that change it. */
  readonly checked = input(false);

  /** The checkbox, or Ctrl/Cmd-click on the row: flip this row in the selection. */
  readonly toggleChecked = output<void>();

  /** Shift-click on the row: extend the selection from the anchor row to this one. */
  readonly extendChecked = output<void>();

  /** This row's details were asked for (click or Enter) - emitted alongside the navigation, so
   * TorrentList can give the panel back to the torrent if the selection summary has it. */
  readonly opened = output<void>();

  /** Set the instant Pause/Resume/Remove is started, cleared on response (success or
   * failure) - drives both the button's own loading/disabled state and a whole-row dim (see
   * host binding above), so this torrent visibly has something in flight against it rather
   * than looking like the click did nothing. Read from the shared TorrentActionsService, so an
   * action started from the details panel or Pause all/Resume all shows here too. See
   * design_docs/0033. */
  readonly pendingAction = computed(() => this.actions.pendingFor(this.torrent().infoHash));

  /** Toggled from the context menu below, hosting its own SeedingLimitsDialog instance in
   * this row's own template - same self-contained-per-row pattern as the row's own
   * p-contextMenu, rather than a new shared dialog service. See design_docs/0054. */
  readonly showSeedingLimitsDialog = signal(false);

  /** Same self-contained-per-row pattern as showSeedingLimitsDialog above. See design_docs/0072. */
  readonly showTorrentLimitsDialog = signal(false);

  /** Same self-contained-per-row pattern again. See design_docs/0077. */
  readonly showTorrentLabelsDialog = signal(false);

  /** Display names of this torrent's labels, resolved from the ids it carries (a rename shows
   * up here without the torrent changing). Ids with no matching label - deleted moments ago,
   * before the next snapshot caught up - simply don't render. Shown in compact mode (details
   * panel docked) too - checked against a real monitor, there's room, and that's exactly when
   * you're looking at one torrent's details. */
  private readonly labelNames = computed(() => {
    const names = this.labelService.namesById();
    return this.torrent().labelIds.flatMap((id) => names.get(id) ?? []);
  });
  readonly visibleLabelNames = computed(() => this.labelNames().slice(0, MAX_VISIBLE_LABEL_CHIPS));
  readonly hiddenLabelCount = computed(() => Math.max(0, this.labelNames().length - MAX_VISIBLE_LABEL_CHIPS));
  readonly hiddenLabelNames = computed(() => this.labelNames().slice(MAX_VISIBLE_LABEL_CHIPS).join(', '));

  readonly infoHash = computed(() => this.torrent().infoHash);
  readonly name = computed(() => this.torrent().name);
  /** Split so the template can truncate the stem while keeping the extension - see
   * shared/file-name.ts. */
  private readonly nameSplit = computed(() => splitFileName(this.name()));
  readonly nameStem = computed(() => this.nameSplit().stem);
  readonly nameExtension = computed(() => this.nameSplit().extension);
  readonly state = computed(() => this.torrent().state);
  readonly lastError = computed(() => this.torrent().lastError);
  readonly totalLength = computed(() => this.torrent().totalLength);
  /** The ETA and its reason come from the server (design_docs/0102); the cell shows a short
   * form and this longer explanation as its tooltip. Byte totals and connectedPeers aren't
   * shown in the row - the style guide's row anatomy drops them in favor of just a
   * rate/percentage per column, moving totals to the details panel. */
  readonly etaTitle = computed(() => etaDetail(this.torrent()));
  readonly downloadRateBytesPerSec = computed(() => this.torrent().downloadRateBytesPerSec);
  readonly downloadRateTrend = computed(() => this.torrent().downloadRateTrend);
  readonly uploadRateBytesPerSec = computed(() => this.torrent().uploadRateBytesPerSec);

  /** VERIFYING means the backend hasn't yet re-established which pieces are actually
   * complete after a restart (see design_docs/0026) - pause/resume/remove are disabled
   * until that settles, rather than acting on a torrent whose real state isn't known yet. */
  readonly isVerifying = computed(() => this.state() === 'VERIFYING');

  /** A pending magnet (design_docs/0070) - no TorrentSession exists yet, so pause/resume/
   * seeding-limits have nothing to act on. Remove still works (there's a real record to
   * delete); Copy magnet link still works (the info hash is already known). Deliberately not
   * folded into isVerifying() - the two states are mutually exclusive and disable a different
   * set of actions. */
  readonly isFetchingMetadata = computed(() => this.state() === 'FETCHING_METADATA');

  readonly progressPercent = computed(() => progressPercent(this.torrent().progress));

  /** Ink-weight + icon, not a colored severity badge - see design_docs/0033's style-guide
   * reconciliation ("one hue, one alarm," never a five-color state legend). */
  readonly stateDisplay = computed(() => torrentStateDisplay(this.state()));

  private readonly rowContextMenu = viewChild.required<ContextMenu>('rowMenu');

  /** The same menu as the details panel's (TorrentCommands). The "⋯" button opens it too. */
  readonly contextMenuItems = computed<MenuItem[]>(() =>
    this.commands.menuItems(this.torrent(), {
      seedingLimits: () => this.showSeedingLimitsDialog.set(true),
      torrentLimits: () => this.showTorrentLimitsDialog.set(true),
      labels: () => this.showTorrentLabelsDialog.set(true),
    }),
  );

  /** Plain click opens the details panel as it always has; the two modifier clicks are the
   * style guide's selection grammar (the style guide's "Interactions"). See design_docs/0083. */
  onRowClick(event: MouseEvent): void {
    if (event.shiftKey) {
      this.extendChecked.emit();
    } else if (event.ctrlKey || event.metaKey) {
      this.toggleChecked.emit();
    } else {
      this.navigateToDetail();
    }
  }

  /** A Shift-click would otherwise also select the page text between the two rows. */
  onRowMouseDown(event: MouseEvent): void {
    if (event.shiftKey) {
      event.preventDefault();
    }
  }

  /** No-op while fetching metadata - there's no session for the detail panel's tabs
   * (Files/Peers/Trackers/Pieces) to poll yet. See design_docs/0070. */
  navigateToDetail(): void {
    if (this.isFetchingMetadata()) {
      return;
    }
    this.opened.emit();
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

  onPause(): void {
    this.commands.pause(this.torrent());
  }

  onResume(): void {
    this.commands.resume(this.torrent());
  }
}
