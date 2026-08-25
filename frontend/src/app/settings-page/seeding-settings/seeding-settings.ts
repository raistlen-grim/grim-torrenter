import { ChangeDetectionStrategy, Component, effect, input } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { InputGroupModule } from 'primeng/inputgroup';
import { InputGroupAddonModule } from 'primeng/inputgroupaddon';
import { InputNumberModule } from 'primeng/inputnumber';
import { ToggleSwitchModule } from 'primeng/toggleswitch';
import { Subscription } from 'rxjs';

import { Settings } from '../../models/settings.model';

const MINUTES_PER_HOUR = 60;

/** seedTimeLimitMinutes (the wire unit, see settings.model.ts) -> hours (this group's display
 * unit) happens only here, at the form's construction/save boundary - same "convert only at
 * the edge, keep the wire unit as the unit of record everywhere else" pattern
 * rate-limit-settings already established for bytes/sec <-> KiB/s. */
function minutesToHours(minutes: number): number {
  return Math.round((minutes / MINUTES_PER_HOUR) * 10) / 10;
}

function hoursToMinutes(hours: number): number {
  return Math.round(hours * MINUTES_PER_HOUR);
}

export type SeedingSettingsForm = FormGroup<{
  ratioLimitEnabled: FormControl<boolean>;
  ratioLimit: FormControl<number>;
  timeLimitEnabled: FormControl<boolean>;
  timeLimitHours: FormControl<number>;
}>;

export function buildSeedingSettingsForm(settings: Settings): SeedingSettingsForm {
  return new FormGroup({
    ratioLimitEnabled: new FormControl(settings.seedRatioLimitEnabled, { nonNullable: true }),
    ratioLimit: new FormControl(
      { value: settings.seedRatioLimit, disabled: !settings.seedRatioLimitEnabled },
      { nonNullable: true },
    ),
    timeLimitEnabled: new FormControl(settings.seedTimeLimitEnabled, { nonNullable: true }),
    timeLimitHours: new FormControl(
      { value: minutesToHours(settings.seedTimeLimitMinutes), disabled: !settings.seedTimeLimitEnabled },
      { nonNullable: true },
    ),
  });
}

export function seedingSettingsPatch(value: {
  ratioLimitEnabled: boolean;
  ratioLimit: number;
  timeLimitEnabled: boolean;
  timeLimitHours: number;
}): Partial<Settings> {
  return {
    seedRatioLimitEnabled: value.ratioLimitEnabled,
    seedRatioLimit: value.ratioLimit,
    seedTimeLimitEnabled: value.timeLimitEnabled,
    seedTimeLimitMinutes: hoursToMinutes(value.timeLimitHours),
  };
}

/**
 * One settings group among possibly several on the page (see design_docs/0045) - the global
 * defaults for automatically stopping a torrent once it's seeded enough (whichever enabled
 * limit is reached first). A per-torrent override (see the torrent-row seeding-limits dialog)
 * can override either independently - this group only ever edits the global default. Both
 * limits default disabled, matching the rate limits' own opt-in convention: a limit that can
 * silently stop a torrent is a bigger surprise to default on than encryptionMode's PREFERRED
 * ever was. See design_docs/0054.
 */
@Component({
  selector: 'app-seeding-settings',
  imports: [InputGroupModule, InputGroupAddonModule, InputNumberModule, ReactiveFormsModule, ToggleSwitchModule],
  templateUrl: './seeding-settings.html',
  styleUrl: './seeding-settings.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SeedingSettings {
  readonly form = input.required<SeedingSettingsForm>();

  /** Same enable/disable-on-a-sibling-toggle's-value pattern rate-limit-settings' own
   * syncUnlimitedDisabled already established. */
  constructor() {
    effect((onCleanup) => {
      const controls = this.form().controls;
      const subscriptions = [
        syncEnabledDisabled(controls.ratioLimitEnabled, controls.ratioLimit),
        syncEnabledDisabled(controls.timeLimitEnabled, controls.timeLimitHours),
      ];
      onCleanup(() => subscriptions.forEach((subscription) => subscription.unsubscribe()));
    });
  }
}

function syncEnabledDisabled(enabled: FormControl<boolean>, value: FormControl<number>): Subscription {
  const apply = (isEnabled: boolean) => (isEnabled ? value.enable() : value.disable());
  apply(enabled.value);
  return enabled.valueChanges.subscribe(apply);
}
