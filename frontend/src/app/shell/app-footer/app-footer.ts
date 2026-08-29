import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { interval, startWith, switchMap } from 'rxjs';

import { DiskUsage, ResourceUsage } from '../../models/system.model';
import { SystemService } from '../../services/system.service';
import { TorrentEventsService } from '../../services/torrent-events.service';
import { FormatBytesPipe } from '../../shared/format-bytes.pipe';
import { FormatRatePipe } from '../../shared/format-rate.pipe';
import { pluralTorrentCount } from '../../shared/plural-torrent-count';

const SYSTEM_POLL_INTERVAL_MS = 30_000;

/**
 * Page footer, present on every route: torrent count, aggregate rates, lifetime ratio, disk
 * free space, and JVM heap/CPU usage - see design_docs/0043. Ratio, free-space, and
 * resource-usage are pre-formatted into display strings in the class (using a
 * manually-instantiated FormatBytesPipe, same pattern FormatRateWindowsPipe already uses)
 * rather than piping a nullable signal directly in the template, to sidestep a null
 * torrentCount()/diskUsage()/resourceUsage() value ever reaching a pipe that expects a plain
 * number.
 */
@Component({
  selector: 'app-footer',
  imports: [FormatRatePipe],
  templateUrl: './app-footer.html',
  styleUrl: './app-footer.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AppFooter {
  private readonly events = inject(TorrentEventsService);
  private readonly system = inject(SystemService);
  private readonly formatBytes = new FormatBytesPipe();

  readonly torrentCount = computed(() => this.events.torrents().length);
  readonly torrentCountDisplay = computed(() => pluralTorrentCount(this.torrentCount()));
  readonly totalDownloadRate = computed(() =>
    this.events.torrents().reduce((sum, t) => sum + t.downloadRateBytesPerSec, 0),
  );
  readonly totalUploadRate = computed(() =>
    this.events.torrents().reduce((sum, t) => sum + t.uploadRateBytesPerSec, 0),
  );

  /** Lifetime ratio across every torrent, not a per-torrent average - matches the style
   * guide's own summary row reading a single combined "Ratio 1.84". An em dash when
   * nothing has been downloaded yet, rather than a misleading 0.00 or a divide-by-zero
   * Infinity. */
  readonly ratioDisplay = computed(() => {
    const torrents = this.events.torrents();
    const totalDownloaded = torrents.reduce((sum, t) => sum + t.bytesDownloaded, 0);
    const totalUploaded = torrents.reduce((sum, t) => sum + t.bytesUploaded, 0);
    return totalDownloaded > 0 ? (totalUploaded / totalDownloaded).toFixed(2) : '—';
  });

  private readonly diskUsage = toSignal<DiskUsage | null>(
    interval(SYSTEM_POLL_INTERVAL_MS).pipe(
      startWith(0),
      switchMap(() => this.system.diskUsage()),
    ),
    { initialValue: null },
  );

  readonly freeSpaceDisplay = computed(() => {
    const usage = this.diskUsage();
    return usage ? `${this.formatBytes.transform(usage.freeBytes)} free` : '—';
  });

  private readonly resourceUsage = toSignal<ResourceUsage | null>(
    interval(SYSTEM_POLL_INTERVAL_MS).pipe(
      startWith(0),
      switchMap(() => this.system.resourceUsage()),
    ),
    { initialValue: null },
  );

  readonly heapDisplay = computed(() => {
    const usage = this.resourceUsage();
    if (!usage) {
      return '—';
    }
    return `${this.formatBytes.transform(usage.heapUsedBytes)} / ${this.formatBytes.transform(usage.heapMaxBytes)}`;
  });

  /** -1 is the JDK's own "can't determine this" sentinel, passed through unchanged from the
   * backend - shown as an em dash rather than a misleading "-100%". No "CPU" suffix - the
   * pi-microchip icon next to it in the template already labels it. */
  readonly cpuDisplay = computed(() => {
    const usage = this.resourceUsage();
    if (!usage || usage.processCpuLoad < 0) {
      return '—';
    }
    return `${Math.round(usage.processCpuLoad * 100)}%`;
  });
}
