import { ChangeDetectionStrategy, Component, effect, input } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { InputGroupModule } from 'primeng/inputgroup';
import { InputGroupAddonModule } from 'primeng/inputgroupaddon';
import { InputNumberModule } from 'primeng/inputnumber';
import { ToggleSwitchModule } from 'primeng/toggleswitch';
import { Subscription } from 'rxjs';

import { Settings } from '../../models/settings.model';

export type WatchFolderSettingsForm = FormGroup<{
  enabled: FormControl<boolean>;
  retentionDays: FormControl<number>;
  pollIntervalSeconds: FormControl<number>;
}>;

export function buildWatchFolderSettingsForm(settings: Settings): WatchFolderSettingsForm {
  return new FormGroup({
    enabled: new FormControl(settings.watchFolderEnabled, { nonNullable: true }),
    retentionDays: new FormControl(settings.watchFolderRetentionDays, { nonNullable: true }),
    pollIntervalSeconds: new FormControl(settings.watchFolderPollIntervalSeconds, { nonNullable: true }),
  });
}

export function watchFolderSettingsPatch(value: {
  enabled: boolean;
  retentionDays: number;
  pollIntervalSeconds: number;
}): Partial<Settings> {
  return {
    watchFolderEnabled: value.enabled,
    watchFolderRetentionDays: value.retentionDays,
    watchFolderPollIntervalSeconds: value.pollIntervalSeconds,
  };
}

/**
 * Enable/disable the watch-folder auto-add feature (design_docs/0056), how long resolved files
 * sit in its added/failed subfolders before being cleaned up, and how often the folder is
 * checked for new files. Its own group, not folded into Network - it's a distinct feature area
 * (file-based auto-add, not peer connectivity), matching the "one group per topic" convention
 * design_docs/0045 established. pollIntervalSeconds only takes effect on the backend's next
 * restart (an engine-wide scheduled task's period can't change mid-flight) - unlike enabled/
 * retentionDays, which are genuinely live - see this row's own description in the template.
 */
@Component({
  selector: 'app-watch-folder-settings',
  imports: [InputGroupModule, InputGroupAddonModule, InputNumberModule, ReactiveFormsModule, ToggleSwitchModule],
  templateUrl: './watch-folder-settings.html',
  styleUrl: './watch-folder-settings.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class WatchFolderSettings {
  readonly form = input.required<WatchFolderSettingsForm>();

  /** retentionDays/pollIntervalSeconds are only meaningful while the feature is enabled - same
   * disable-via-enable()/disable() pattern RateLimitSettings already uses for its own paired
   * controls, rather than a template [disabled] binding fighting formControlName for ownership
   * of the control's disabled state. */
  constructor() {
    effect((onCleanup) => {
      const controls = this.form().controls;
      const apply = (enabled: boolean) => {
        const toggle = (control: { enable(): void; disable(): void }) => (enabled ? control.enable() : control.disable());
        toggle(controls.retentionDays);
        toggle(controls.pollIntervalSeconds);
      };
      apply(controls.enabled.value);
      const subscription: Subscription = controls.enabled.valueChanges.subscribe(apply);
      onCleanup(() => subscription.unsubscribe());
    });
  }
}
