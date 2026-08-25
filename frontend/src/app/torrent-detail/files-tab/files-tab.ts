import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ProgressBarModule } from 'primeng/progressbar';

import { TorrentFile } from '../../models/torrent.model';
import { TorrentService } from '../../services/torrent.service';
import { FormatBytesPipe } from '../../shared/format-bytes.pipe';
import { pollWhileInput } from '../../shared/poll-while-input';

const POLL_INTERVAL_MS = 3000;

/**
 * Per-file path, size, and download progress. Polls its own endpoint while mounted, same
 * pattern as Piece map/Peers - now that files() reports live progress (design_docs/0031
 * step 4), the file list can genuinely change over time, not just once per mount.
 *
 * <p>Rendered as a stacked card list (path on its own line, progress below) rather than a
 * 3-column table - see design_docs/0044's narrow-drawer follow-up; a full file path is
 * often too long to share a row with size/progress columns at the drawer's ~430px width.
 */
@Component({
  selector: 'app-files-tab',
  imports: [FormatBytesPipe, ProgressBarModule],
  templateUrl: './files-tab.html',
  styleUrl: './files-tab.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FilesTab {
  private readonly torrentService = inject(TorrentService);

  readonly infoHash = input.required<string>();

  readonly files = toSignal(
    pollWhileInput(this.infoHash, POLL_INTERVAL_MS, (infoHash) => this.torrentService.files(infoHash)),
    { initialValue: [] as TorrentFile[] },
  );
}
