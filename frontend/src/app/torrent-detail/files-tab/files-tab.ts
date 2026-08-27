import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';

import { TorrentFile } from '../../models/torrent.model';
import { TorrentService } from '../../services/torrent.service';
import { FormatBytesPipe } from '../../shared/format-bytes.pipe';
import { pollWhileInput } from '../../shared/poll-while-input';

const POLL_INTERVAL_MS = 3000;

const IMAGE_EXTENSIONS = new Set(['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp', 'svg', 'avif']);
const VIDEO_EXTENSIONS = new Set(['mp4', 'mkv', 'avi', 'mov', 'webm', 'wmv', 'flv', 'm4v']);
const ARCHIVE_EXTENSIONS = new Set(['zip', 'rar', '7z', 'tar', 'gz', 'bz2', 'xz']);

/** PrimeIcons has no direct file-text/file-video/file-archive equivalents to the guide's
 * suggested Lucide glyphs - pi-image/pi-video/pi-box substitute, pi-file for everything else
 * (the guide's own icon-substitution allowance: "substitute freely if the app already
 * standardises on different glyphs for the same meanings"). */
function fileTypeIcon(pathSegments: string[]): string {
  const name = pathSegments[pathSegments.length - 1] ?? '';
  const dot = name.lastIndexOf('.');
  const ext = dot > 0 ? name.slice(dot + 1).toLowerCase() : '';
  if (IMAGE_EXTENSIONS.has(ext)) {
    return 'pi-image';
  }
  if (VIDEO_EXTENSIONS.has(ext)) {
    return 'pi-video';
  }
  if (ARCHIVE_EXTENSIONS.has(ext)) {
    return 'pi-box';
  }
  return 'pi-file';
}

/**
 * Per-file path, size, and download progress. Polls its own endpoint while mounted, same
 * pattern as Piece map/Peers - now that files() reports live progress (design_docs/0031
 * step 4), the file list can genuinely change over time, not just once per mount.
 *
 * <p>Per-file priority (the guide's "skip, normal, first" right-click menu) and multi-select
 * are not built - no backend priority concept exists at all today, and multi-select is
 * explicitly out of scope for this whole restyle pass. See design_docs/0032's task 7 notes.
 */
@Component({
  selector: 'app-files-tab',
  imports: [FormatBytesPipe],
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

  readonly fileTypeIcon = fileTypeIcon;

  filePercent(file: TorrentFile): number {
    return file.length > 0 ? Math.round((file.bytesDownloaded / file.length) * 100) : 100;
  }

  filePath(file: TorrentFile): string {
    return file.pathSegments.join('/');
  }
}
