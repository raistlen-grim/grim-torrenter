import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';

import { Tracker } from '../../models/torrent.model';
import { TorrentService } from '../../services/torrent.service';
import { pollWhileInput } from '../../shared/poll-while-input';
import { StatusIndicator } from '../../shared/status-indicator/status-indicator';
import { trackerStateDisplay } from '../../shared/status-display';

const POLL_INTERVAL_MS = 3000;

/** Per-tracker status (URL, tier, WORKING/ERROR/UNKNOWN, last/next announce, last error,
 * seeders/leechers). Empty for a trackerless torrent. See design_docs/0031. Rendered as a
 * stacked card list rather than a wide table - see design_docs/0044's narrow-drawer
 * follow-up - a 7-column table has no reasonable layout at the drawer's ~430px content
 * width (a real tracker URL wrapped to one letter per line in the URL column before this). */
@Component({
  selector: 'app-trackers-tab',
  imports: [DatePipe, StatusIndicator],
  templateUrl: './trackers-tab.html',
  styleUrl: './trackers-tab.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TrackersTab {
  private readonly torrentService = inject(TorrentService);

  readonly infoHash = input.required<string>();

  readonly trackers = toSignal(
    pollWhileInput(this.infoHash, POLL_INTERVAL_MS, (infoHash) => this.torrentService.trackers(infoHash)),
    { initialValue: [] as Tracker[] },
  );

  readonly statusDisplay = trackerStateDisplay;
}
