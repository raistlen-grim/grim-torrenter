import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ProgressBarModule } from 'primeng/progressbar';
import { TabsModule } from 'primeng/tabs';

import { TorrentEventsService } from '../services/torrent-events.service';
import { FormatBytesPipe } from '../shared/format-bytes.pipe';
import { FormatEtaPipe } from '../shared/format-eta.pipe';
import { StatusIndicator } from '../shared/status-indicator/status-indicator';
import { torrentStateDisplay } from '../shared/status-display';
import { FilesTab } from './files-tab/files-tab';
import { PeersTab } from './peers-tab/peers-tab';
import { PieceMap } from './piece-map/piece-map';
import { TrackersTab } from './trackers-tab/trackers-tab';

/**
 * The detail view shell: a header plus a tabbed set of self-contained detail endpoints
 * (see design_docs/0031). The header reuses TorrentEventsService's existing live data
 * rather than a dedicated "summary" endpoint - everything shown here (name, state,
 * progress) is already part of the list's own data; a summary endpoint would only earn
 * its keep once there's a field that isn't already available client-side (DHT flag,
 * tracker counts - still backend-blocked, see design_docs/0031's build order).
 */
@Component({
  selector: 'app-torrent-detail',
  imports: [
    FilesTab,
    FormatBytesPipe,
    FormatEtaPipe,
    PeersTab,
    PieceMap,
    ProgressBarModule,
    RouterLink,
    StatusIndicator,
    TabsModule,
    TrackersTab,
  ],
  templateUrl: './torrent-detail.html',
  styleUrl: './torrent-detail.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TorrentDetail {
  private readonly events = inject(TorrentEventsService);

  /** Bound directly from the :infoHash route param - see withComponentInputBinding() in
   * app.config.ts. */
  readonly infoHash = input.required<string>();

  readonly torrent = computed(() => this.events.torrents().find((t) => t.infoHash === this.infoHash()));

  /** Same verified-progress basis as the progress bar - see torrent-row.ts's own
   * bytesRemaining for why this isn't bytesReceived. */
  readonly bytesRemaining = computed(() => {
    const torrent = this.torrent();
    return torrent ? torrent.totalLength - torrent.bytesDownloaded : 0;
  });

  readonly progressPercent = computed(() => {
    const torrent = this.torrent();
    if (!torrent || torrent.progress <= 0) {
      return 0;
    }
    return Math.max(1, Math.round(torrent.progress * 100));
  });

  /** Ink-weight + icon, not a colored severity badge - see design_docs/0033's style-guide
   * reconciliation ("one hue, one alarm," never a five-color state legend). Falls back to
   * STOPPED's ("Paused") display while torrent() is still undefined - only matters for the
   * single frame before the first snapshot arrives, since the whole header is behind an
   * @if (torrent(); as torrent) in the template. */
  readonly stateDisplay = computed(() => torrentStateDisplay(this.torrent()?.state ?? 'STOPPED'));
}
