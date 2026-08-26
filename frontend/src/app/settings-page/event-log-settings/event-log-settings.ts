import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { InputNumberModule } from 'primeng/inputnumber';

import { Settings } from '../../models/settings.model';

export type EventLogSettingsForm = FormGroup<{
  retentionDays: FormControl<number>;
}>;

export function buildEventLogSettingsForm(settings: Settings): EventLogSettingsForm {
  return new FormGroup({
    retentionDays: new FormControl(settings.eventLogRetentionDays, { nonNullable: true }),
  });
}

export function eventLogSettingsPatch(value: { retentionDays: number }): Partial<Settings> {
  return {
    eventLogRetentionDays: value.retentionDays,
  };
}

/**
 * One field: how many days of library events (design_docs/0055) to keep. Its own group
 * rather than folded into an existing one, since it governs a different subsystem (the
 * events feed) than Network/Rate limiting/Seeding. No "Unlimited" option, unlike the rate
 * limit fields' own paired checkbox - the backend silently normalizes anything below 1 to a
 * default of 30 rather than rejecting it (see Settings.java's own Javadoc), so this field's
 * own [min]="1" is what actually keeps a user from seeing that substitution happen.
 */
@Component({
  selector: 'app-event-log-settings',
  imports: [InputNumberModule, ReactiveFormsModule],
  templateUrl: './event-log-settings.html',
  styleUrl: './event-log-settings.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EventLogSettings {
  readonly form = input.required<EventLogSettingsForm>();
}
