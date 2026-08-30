import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { InputNumberModule } from 'primeng/inputnumber';

import { Settings } from '../../models/settings.model';

export type MagnetFetchSettingsForm = FormGroup<{
  timeBudgetSeconds: FormControl<number>;
  candidatesPerRound: FormControl<number>;
  concurrencyLimit: FormControl<number>;
}>;

export function buildMagnetFetchSettingsForm(settings: Settings): MagnetFetchSettingsForm {
  return new FormGroup({
    timeBudgetSeconds: new FormControl(settings.magnetFetchTimeBudgetSeconds, { nonNullable: true }),
    candidatesPerRound: new FormControl(settings.magnetFetchCandidatesPerRound, { nonNullable: true }),
    concurrencyLimit: new FormControl(settings.magnetFetchConcurrencyLimit, { nonNullable: true }),
  });
}

export function magnetFetchSettingsPatch(
  value: { timeBudgetSeconds: number; candidatesPerRound: number; concurrencyLimit: number },
): Partial<Settings> {
  return {
    magnetFetchTimeBudgetSeconds: value.timeBudgetSeconds,
    magnetFetchCandidatesPerRound: value.candidatesPerRound,
    magnetFetchConcurrencyLimit: value.concurrencyLimit,
  };
}

/**
 * How hard a magnet add tries to find a peer with the metadata (design_docs/0028's addendum)
 * - its own group rather than folded into Network, since it governs magnet-add behavior
 * specifically, not general peer connectivity. Three plain number fields, no "Unlimited"
 * option, same reasoning as Event log's own retentionDays field - the backend silently
 * normalizes anything below 1 back to its own default rather than treating it as "unlimited"
 * (see Settings.java's own Javadoc), so each field's [min]="1" is what actually keeps a user
 * from seeing that substitution happen.
 */
@Component({
  selector: 'app-magnet-fetch-settings',
  imports: [InputNumberModule, ReactiveFormsModule],
  templateUrl: './magnet-fetch-settings.html',
  styleUrl: './magnet-fetch-settings.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MagnetFetchSettings {
  readonly form = input.required<MagnetFetchSettingsForm>();
}
