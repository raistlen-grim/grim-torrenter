import { ChangeDetectionStrategy, Component, inject, input, linkedSignal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { HttpErrorResponse } from '@angular/common/http';
import { MessageService } from 'primeng/api';

import { FilePriority, TorrentFile } from '../../models/torrent.model';
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
 * <p>Each file has a priority select (Skip/Low/Medium/High, design_docs/0075) - a native
 * select rather than the guide's right-click menu, since this dock has no room for a menu
 * trigger per row and native selects need no overlay handling. The whole priority array is
 * PUT at once and the response (the real, updated file list) replaces the displayed rows
 * straight away rather than waiting up to a poll interval; the backend decides what's valid
 * (its only realistic 400 is skipping every file), this component just reports a rejection. Multi-select
 * is explicitly out of scope for the restyle pass (design_docs/0032's task 7 notes).
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
  private readonly messageService = inject(MessageService);

  readonly infoHash = input.required<string>();

  private readonly polledFiles = toSignal(
    pollWhileInput(this.infoHash, POLL_INTERVAL_MS, (infoHash) => this.torrentService.files(infoHash)),
    { initialValue: [] as TorrentFile[] },
  );

  /** The polled list, but writable - a successful priority change sets it directly from the
   * PUT response, and the next poll simply overwrites it again. */
  readonly files = linkedSignal(() => this.polledFiles());

  readonly priorityOptions: { value: FilePriority; label: string }[] = [
    { value: 'SKIP', label: 'Skip' },
    { value: 'LOW', label: 'Low' },
    { value: 'MEDIUM', label: 'Normal' },
    { value: 'HIGH', label: 'High' },
  ];

  readonly fileTypeIcon = fileTypeIcon;

  filePercent(file: TorrentFile): number {
    return file.length > 0 ? Math.round((file.bytesDownloaded / file.length) * 100) : 100;
  }

  onPriorityChange(index: number, priority: FilePriority, select: HTMLSelectElement): void {
    const current = this.files();
    const priorities = current.map((file, i) => (i === index ? priority : file.priority));
    this.torrentService.setFilePriorities(this.infoHash(), priorities).subscribe({
      next: (updated) => this.files.set(updated),
      error: (error: HttpErrorResponse) => {
        select.value = current[index].priority;
        this.messageService.add({
          severity: 'error',
          summary: 'Could not change file priority',
          detail: error.status === 400 ? 'At least one file must stay wanted.' : 'Please try again.',
        });
      },
    });
  }

  filePath(file: TorrentFile): string {
    return file.pathSegments.join('/');
  }
}
