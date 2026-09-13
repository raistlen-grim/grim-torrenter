import { ChangeDetectionStrategy, Component, effect, inject, input, output, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MessageService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { InputNumberModule } from 'primeng/inputnumber';
import { SelectModule } from 'primeng/select';
import { finalize, forkJoin } from 'rxjs';

import { TorrentLimitOverride } from '../../../models/torrent.model';
import { SettingsService } from '../../../services/settings.service';
import { TorrentService } from '../../../services/torrent.service';
import { LimitMode, modeFor, modeOptions, sentinelFor, syncValueDisabled } from '../limit-mode';

/** bytes/sec (the model's unit of record) <-> KiB/s (this dialog's display unit) - same
 * conversion idiom rate-limit-settings already uses for the global rate-limit fields, applied
 * here only at the form boundary; the sentinel itself (passed to/from sentinelFor/modeFor)
 * always stays in bytes/sec so -1/0 keep meaning inherit/no-limit regardless of unit. */
const BYTES_PER_KIB = 1024;

function bytesToKib(bytesPerSec: number): number {
  return Math.round(bytesPerSec / BYTES_PER_KIB);
}

function kibToBytes(kibPerSec: number): number {
  return Math.round(kibPerSec * BYTES_PER_KIB);
}

/**
 * A torrent's override of the global bandwidth/connection-count defaults (design_docs/0072) -
 * structurally the same shape as SeedingLimitsDialog (design_docs/0054), three rows instead of
 * two, sharing that dialog's LimitMode/modeFor/sentinelFor/modeOptions/syncValueDisabled helpers
 * (extracted to ../limit-mode.ts) rather than duplicating them a second time.
 *
 * <p>Unlike the seeding-limits dialog, a non-inherit bandwidth row here doesn't narrow the
 * global cap - it replaces it for this torrent (RateLimiters.forTorrent(), design_docs/0072).
 * The Max connections row's own copy calls out that it only takes effect the next time this
 * torrent is constructed (a backend restart or a remove-and-re-add), not on pause/resume -
 * distinct from the two bandwidth rows, which are fully live.
 */
@Component({
  selector: 'app-torrent-limits-dialog',
  imports: [ButtonModule, DialogModule, InputNumberModule, ReactiveFormsModule, SelectModule],
  templateUrl: './torrent-limits-dialog.html',
  styleUrl: './torrent-limits-dialog.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TorrentLimitsDialog {
  private readonly torrentService = inject(TorrentService);
  private readonly settingsService = inject(SettingsService);
  private readonly messageService = inject(MessageService);

  readonly infoHash = input.required<string>();
  readonly torrentName = input.required<string>();
  readonly visible = input.required<boolean>();
  readonly visibleChange = output<boolean>();

  readonly loaded = signal(false);
  readonly saving = signal(false);

  /** Rebuilt each time the dialog opens, in case the global defaults changed elsewhere (e.g.
   * the settings page) since it was last opened - same reasoning as SeedingLimitsDialog's own
   * mode-options signals. */
  readonly uploadModeOptions = signal(modeOptions('Use default'));
  readonly downloadModeOptions = signal(modeOptions('Use default'));
  readonly connectionsModeOptions = signal(modeOptions('Use default'));

  readonly form = new FormGroup({
    uploadMode: new FormControl<LimitMode>('default', { nonNullable: true }),
    uploadValueKib: new FormControl(500, { nonNullable: true }),
    downloadMode: new FormControl<LimitMode>('default', { nonNullable: true }),
    downloadValueKib: new FormControl(500, { nonNullable: true }),
    connectionsMode: new FormControl<LimitMode>('default', { nonNullable: true }),
    connectionsValue: new FormControl(30, { nonNullable: true }),
  });

  constructor() {
    syncValueDisabled(this.form.controls.uploadMode, this.form.controls.uploadValueKib);
    syncValueDisabled(this.form.controls.downloadMode, this.form.controls.downloadValueKib);
    syncValueDisabled(this.form.controls.connectionsMode, this.form.controls.connectionsValue);

    effect(() => {
      if (this.visible() && !this.loaded()) {
        this.load();
      }
      if (!this.visible()) {
        this.loaded.set(false);
      }
    });
  }

  private load(): void {
    forkJoin([this.torrentService.limits(this.infoHash()), this.settingsService.current()]).subscribe({
      next: ([override, settings]) => {
        this.uploadModeOptions.set(
          modeOptions(
            settings.uploadRateLimitBytesPerSec > 0
              ? `Use default (${bytesToKib(settings.uploadRateLimitBytesPerSec)} KiB/s)`
              : 'Use default (currently unlimited)',
          ),
        );
        this.downloadModeOptions.set(
          modeOptions(
            settings.downloadRateLimitBytesPerSec > 0
              ? `Use default (${bytesToKib(settings.downloadRateLimitBytesPerSec)} KiB/s)`
              : 'Use default (currently unlimited)',
          ),
        );
        this.connectionsModeOptions.set(modeOptions(`Use default (${settings.maxConnectionsPerTorrent})`));
        this.form.setValue({
          uploadMode: modeFor(override.uploadBytesPerSecOverride),
          uploadValueKib:
            override.uploadBytesPerSecOverride > 0
              ? bytesToKib(override.uploadBytesPerSecOverride)
              : bytesToKib(settings.uploadRateLimitBytesPerSec),
          downloadMode: modeFor(override.downloadBytesPerSecOverride),
          downloadValueKib:
            override.downloadBytesPerSecOverride > 0
              ? bytesToKib(override.downloadBytesPerSecOverride)
              : bytesToKib(settings.downloadRateLimitBytesPerSec),
          connectionsMode: modeFor(override.maxConnectionsOverride),
          connectionsValue:
            override.maxConnectionsOverride > 0 ? override.maxConnectionsOverride : settings.maxConnectionsPerTorrent,
        });
        this.loaded.set(true);
      },
      error: () => {
        this.messageService.add({ severity: 'error', summary: 'Could not load torrent limits' });
        this.close();
      },
    });
  }

  save(): void {
    const value = this.form.getRawValue();
    const override: TorrentLimitOverride = {
      uploadBytesPerSecOverride: sentinelFor(value.uploadMode, kibToBytes(value.uploadValueKib)),
      downloadBytesPerSecOverride: sentinelFor(value.downloadMode, kibToBytes(value.downloadValueKib)),
      maxConnectionsOverride: sentinelFor(value.connectionsMode, value.connectionsValue),
    };

    this.saving.set(true);
    this.torrentService
      .updateLimits(this.infoHash(), override)
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: () => {
          this.messageService.add({ severity: 'success', summary: 'Torrent limits saved', detail: this.torrentName() });
          this.close();
        },
        error: () =>
          this.messageService.add({
            severity: 'error',
            summary: 'Could not save torrent limits',
            detail: this.torrentName(),
          }),
      });
  }

  close(): void {
    this.visibleChange.emit(false);
  }
}
