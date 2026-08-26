import { ChangeDetectionStrategy, Component, effect, input } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { InputNumberModule } from 'primeng/inputnumber';
import { ToggleSwitchModule } from 'primeng/toggleswitch';
import { Subscription } from 'rxjs';

import { Settings } from '../../models/settings.model';

export type WatchFolderSettingsForm = FormGroup<{
  enabled: FormControl<boolean>;
  retentionDays: FormControl<number>;
}>;

export function buildWatchFolderSettingsForm(settings: Settings): WatchFolderSettingsForm {
  return new FormGroup({
    enabled: new FormControl(settings.watchFolderEnabled, { nonNullable: true }),
    retentionDays: new FormControl(settings.watchFolderRetentionDays, { nonNullable: true }),
  });
}

export function watchFolderSettingsPatch(value: { enabled: boolean; retentionDays: number }): Partial<Settings> {
  return {
    watchFolderEnabled: value.enabled,
    watchFolderRetentionDays: value.retentionDays,
  };
}

/**
 * Enable/disable the watch-folder auto-add feature (design_docs/0056) and how long resolved
 * files sit in its added/failed subfolders before being cleaned up. Its own group, not folded
 * into Network - it's a distinct feature area (file-based auto-add, not peer connectivity),
 * matching the "one group per topic" convention design_docs/0045 established.
 */
@Component({
  selector: 'app-watch-folder-settings',
  imports: [InputNumberModule, ReactiveFormsModule, ToggleSwitchModule],
  templateUrl: './watch-folder-settings.html',
  styleUrl: './watch-folder-settings.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class WatchFolderSettings {
  readonly form = input.required<WatchFolderSettingsForm>();

  /** retentionDays is only meaningful while the feature is enabled - same disable-via-
   * enable()/disable() pattern RateLimitSettings already uses for its own paired controls,
   * rather than a template [disabled] binding fighting formControlName for ownership of the
   * control's disabled state. */
  constructor() {
    effect((onCleanup) => {
      const controls = this.form().controls;
      const apply = (enabled: boolean) => (enabled ? controls.retentionDays.enable() : controls.retentionDays.disable());
      apply(controls.enabled.value);
      const subscription: Subscription = controls.enabled.valueChanges.subscribe(apply);
      onCleanup(() => subscription.unsubscribe());
    });
  }
}
