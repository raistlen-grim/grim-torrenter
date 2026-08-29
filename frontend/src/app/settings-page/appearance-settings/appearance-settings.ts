import { ChangeDetectionStrategy, Component, effect, inject, input } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { SelectModule } from 'primeng/select';

import { Settings, ThemePreference } from '../../models/settings.model';
import { ThemeService } from '../../services/theme.service';

export type AppearanceSettingsForm = FormGroup<{
  theme: FormControl<ThemePreference>;
}>;

export function buildAppearanceSettingsForm(settings: Settings): AppearanceSettingsForm {
  return new FormGroup({
    theme: new FormControl(settings.theme, { nonNullable: true }),
  });
}

export function appearanceSettingsPatch(value: { theme: ThemePreference }): Partial<Settings> {
  return { theme: value.theme };
}

/**
 * One settings group among possibly several on the page (see design_docs/0045) - purely
 * presentational, bound to the FormGroup its own buildAppearanceSettingsForm() builds, same as
 * every other group. Theme is the one setting on this page with an instant visual effect
 * worth previewing before Save - see the constructor.
 */
@Component({
  selector: 'app-appearance-settings',
  imports: [ReactiveFormsModule, SelectModule],
  templateUrl: './appearance-settings.html',
  styleUrl: './appearance-settings.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AppearanceSettings {
  private readonly themeService = inject(ThemeService);

  readonly form = input.required<AppearanceSettingsForm>();

  protected readonly themeOptions: { label: string; value: ThemePreference }[] = [
    { label: 'System', value: 'SYSTEM' },
    { label: 'Light', value: 'LIGHT' },
    { label: 'Dark', value: 'DARK' },
  ];

  constructor() {
    // Instant visual feedback as the user picks a theme, ahead of the settings page's own
    // Save button - ThemeService.preview() doesn't persist anything itself (see its own
    // comment), save() below still does that the normal way via appearanceSettingsPatch().
    effect((onCleanup) => {
      const subscription = this.form().controls.theme.valueChanges.subscribe((value) => this.themeService.preview(value));
      onCleanup(() => subscription.unsubscribe());
    });
  }
}
