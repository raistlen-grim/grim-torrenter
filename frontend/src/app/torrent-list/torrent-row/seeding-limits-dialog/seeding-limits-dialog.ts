import { ChangeDetectionStrategy, Component, effect, inject, input, output, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MessageService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { InputNumberModule } from 'primeng/inputnumber';
import { SelectModule } from 'primeng/select';
import { finalize, forkJoin } from 'rxjs';

import { SeedingLimitOverride } from '../../../models/torrent.model';
import { SettingsService } from '../../../services/settings.service';
import { TorrentService } from '../../../services/torrent.service';

const MINUTES_PER_HOUR = 60;

/** Mirrors SeedingLimitOverride's own sentinel convention (design_docs/0054) as a form-
 * friendly 3-way choice, rather than exposing the raw negative/zero/positive number directly
 * - "Use default"/"Custom"/"No limit" is what a user actually picks between; the sentinel
 * encoding is this component's own implementation detail to translate to and from. */
type LimitMode = 'default' | 'custom' | 'unlimited';

function modeFor(sentinel: number): LimitMode {
  if (sentinel < 0) {
    return 'default';
  }
  return sentinel === 0 ? 'unlimited' : 'custom';
}

function sentinelFor(mode: LimitMode, customValue: number): number {
  if (mode === 'default') {
    return -1;
  }
  return mode === 'unlimited' ? 0 : customValue;
}

function modeOptions(defaultLabel: string): { label: string; value: LimitMode }[] {
  return [
    { label: defaultLabel, value: 'default' },
    { label: 'Custom', value: 'custom' },
    { label: 'No limit', value: 'unlimited' },
  ];
}

/**
 * A torrent's override of the global seeding-limit defaults (design_docs/0054) - the first
 * modal-with-a-form in this frontend (only ConfirmationService's simple accept/reject prompt
 * existed before this). Each TorrentRow hosts its own instance, toggled by a local signal from
 * its context menu - same self-contained-per-row pattern the row's own p-contextMenu already
 * uses, rather than a new shared dialog service.
 *
 * <p>The 3-state "use default / custom / no limit" control has no prior precedent in this
 * frontend either - only a 2-state value-plus-"Unlimited"-checkbox pattern existed
 * (rate-limit-settings). Built as a p-select paired with a number input enabled only when
 * "Custom" is selected, extending that same enable-on-a-sibling-control's-value idea from 2
 * states to 3.
 */
@Component({
  selector: 'app-seeding-limits-dialog',
  imports: [ButtonModule, DialogModule, InputNumberModule, ReactiveFormsModule, SelectModule],
  templateUrl: './seeding-limits-dialog.html',
  styleUrl: './seeding-limits-dialog.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SeedingLimitsDialog {
  private readonly torrentService = inject(TorrentService);
  private readonly settingsService = inject(SettingsService);
  private readonly messageService = inject(MessageService);

  readonly infoHash = input.required<string>();
  readonly torrentName = input.required<string>();
  readonly visible = input.required<boolean>();
  readonly visibleChange = output<boolean>();

  readonly loaded = signal(false);
  readonly saving = signal(false);

  /** The "Use default" option's own label is resolved with the real current default baked
   * in (e.g. "Use default (2x)") before ever reaching p-select, rather than via a custom
   * item template - simpler, and avoids depending on a templating API this component has no
   * other precedent for in this codebase (network-settings' own p-select usage is
   * options-array-only, no custom templates). Rebuilt each time the dialog opens, in case the
   * global default changed elsewhere (e.g. the settings page) since it was last opened. */
  readonly ratioModeOptions = signal(modeOptions('Use default'));
  readonly timeModeOptions = signal(modeOptions('Use default'));

  readonly form = new FormGroup({
    ratioMode: new FormControl<LimitMode>('default', { nonNullable: true }),
    ratioValue: new FormControl(2, { nonNullable: true }),
    timeMode: new FormControl<LimitMode>('default', { nonNullable: true }),
    timeValueHours: new FormControl(24, { nonNullable: true }),
  });

  constructor() {
    // form is a fixed instance for this component's whole lifetime (only its value changes,
    // via load()'s setValue() below), so this only needs setting up once - unlike
    // NetworkSettings/RateLimitSettings, which re-subscribe per input change since their form
    // itself is an input that can be swapped out.
    syncValueDisabled(this.form.controls.ratioMode, this.form.controls.ratioValue);
    syncValueDisabled(this.form.controls.timeMode, this.form.controls.timeValueHours);

    // Loads once per time the dialog opens, not on every change-detection pass - re-fetches
    // fresh (rather than reusing a stale earlier load) since the global defaults or the
    // override itself may have changed elsewhere (e.g. the settings page) since last opened.
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
    forkJoin([this.torrentService.seedingLimits(this.infoHash()), this.settingsService.current()]).subscribe({
      next: ([override, settings]) => {
        this.ratioModeOptions.set(
          modeOptions(
            settings.seedRatioLimitEnabled ? `Use default (${settings.seedRatioLimit}x)` : 'Use default (currently off)',
          ),
        );
        this.timeModeOptions.set(
          modeOptions(
            settings.seedTimeLimitEnabled
              ? `Use default (${(settings.seedTimeLimitMinutes / MINUTES_PER_HOUR).toFixed(1)}h)`
              : 'Use default (currently off)',
          ),
        );
        this.form.setValue({
          ratioMode: modeFor(override.ratioLimit),
          ratioValue: override.ratioLimit > 0 ? override.ratioLimit : settings.seedRatioLimit,
          timeMode: modeFor(override.timeLimitMinutes),
          timeValueHours:
            override.timeLimitMinutes > 0
              ? override.timeLimitMinutes / MINUTES_PER_HOUR
              : settings.seedTimeLimitMinutes / MINUTES_PER_HOUR,
        });
        this.loaded.set(true);
      },
      error: () => {
        this.messageService.add({ severity: 'error', summary: 'Could not load seeding limits' });
        this.close();
      },
    });
  }

  save(): void {
    const value = this.form.getRawValue();
    const override: SeedingLimitOverride = {
      ratioLimit: sentinelFor(value.ratioMode, value.ratioValue),
      timeLimitMinutes: sentinelFor(value.timeMode, Math.round(value.timeValueHours * MINUTES_PER_HOUR)),
    };

    this.saving.set(true);
    this.torrentService
      .updateSeedingLimits(this.infoHash(), override)
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: () => {
          this.messageService.add({ severity: 'success', summary: 'Seeding limits saved', detail: this.torrentName() });
          this.close();
        },
        error: () =>
          this.messageService.add({
            severity: 'error',
            summary: 'Could not save seeding limits',
            detail: this.torrentName(),
          }),
      });
  }

  close(): void {
    this.visibleChange.emit(false);
  }
}

/** The value field only makes sense while its mode is 'custom' - disabled otherwise, same
 * enable/disable-on-a-sibling-control's-value idea rate-limit-settings' own
 * syncUnlimitedDisabled already established for its 2-state case. */
function syncValueDisabled(mode: FormControl<LimitMode>, value: FormControl<number>): void {
  const apply = (currentMode: LimitMode) => (currentMode === 'custom' ? value.enable() : value.disable());
  apply(mode.value);
  mode.valueChanges.subscribe(apply);
}
