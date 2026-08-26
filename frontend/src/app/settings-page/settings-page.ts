import { ChangeDetectionStrategy, Component, effect, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormGroup } from '@angular/forms';
import { MessageService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { ToastModule } from 'primeng/toast';

import { Settings } from '../models/settings.model';
import { SettingsService } from '../services/settings.service';
import {
  EventLogSettings,
  EventLogSettingsForm,
  buildEventLogSettingsForm,
  eventLogSettingsPatch,
} from './event-log-settings/event-log-settings';
import {
  NetworkSettings,
  NetworkSettingsForm,
  buildNetworkSettingsForm,
  networkSettingsPatch,
} from './network-settings/network-settings';
import {
  RateLimitSettings,
  RateLimitSettingsForm,
  buildRateLimitSettingsForm,
  rateLimitSettingsPatch,
} from './rate-limit-settings/rate-limit-settings';
import {
  SeedingSettings,
  SeedingSettingsForm,
  buildSeedingSettingsForm,
  seedingSettingsPatch,
} from './seeding-settings/seeding-settings';
import {
  WatchFolderSettings,
  WatchFolderSettingsForm,
  buildWatchFolderSettingsForm,
  watchFolderSettingsPatch,
} from './watch-folder-settings/watch-folder-settings';

type SettingsFormGroup = FormGroup<{
  network: NetworkSettingsForm;
  rateLimiting: RateLimitSettingsForm;
  seeding: SeedingSettingsForm;
  eventLog: EventLogSettingsForm;
  watchFolder: WatchFolderSettingsForm;
}>;

/**
 * Container for the settings page: loads the current Settings once, builds one form group
 * per topic (see the network-settings/rate-limit-settings sub-components), and saves them
 * all in a single PUT - the backend only exposes one atomic Settings update, so there's no
 * per-group save. Adding a new settings group later means adding a new
 * buildXSettingsForm()/xSettingsPatch() pair and a new `<app-x-settings>` in the template,
 * without touching the groups already here. See design_docs/0045.
 */
@Component({
  selector: 'app-settings-page',
  imports: [
    ButtonModule,
    EventLogSettings,
    NetworkSettings,
    RateLimitSettings,
    SeedingSettings,
    ToastModule,
    WatchFolderSettings,
  ],
  templateUrl: './settings-page.html',
  styleUrl: './settings-page.scss',
  providers: [MessageService],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SettingsPage {
  private readonly settingsService = inject(SettingsService);
  private readonly messageService = inject(MessageService);

  /** The last value loaded from, or saved to, the backend - the base every save's patch is
   * merged onto, so a group that hasn't been touched never has its fields clobbered by a
   * stale default. */
  private baseline: Settings | undefined;

  readonly loadedSettings = toSignal(this.settingsService.current());
  readonly form = signal<SettingsFormGroup | undefined>(undefined);
  readonly saving = signal(false);

  constructor() {
    // Builds the form exactly once, the moment the initial GET resolves - loadedSettings()
    // only ever emits once (a plain HTTP GET, not a live stream), so there's no risk of this
    // clobbering in-progress edits with a second emission.
    effect(() => {
      const settings = this.loadedSettings();
      if (settings && !this.form()) {
        this.baseline = settings;
        this.form.set(
          new FormGroup({
            network: buildNetworkSettingsForm(settings),
            rateLimiting: buildRateLimitSettingsForm(settings),
            seeding: buildSeedingSettingsForm(settings),
            eventLog: buildEventLogSettingsForm(settings),
            watchFolder: buildWatchFolderSettingsForm(settings),
          }),
        );
      }
    });
  }

  save(): void {
    const form = this.form();
    if (!form || !this.baseline || form.invalid) {
      return;
    }
    const value = form.getRawValue();
    const updated: Settings = {
      ...this.baseline,
      ...networkSettingsPatch(value.network),
      ...rateLimitSettingsPatch(value.rateLimiting),
      ...seedingSettingsPatch(value.seeding),
      ...eventLogSettingsPatch(value.eventLog),
      ...watchFolderSettingsPatch(value.watchFolder),
    };

    this.saving.set(true);
    this.settingsService.update(updated).subscribe({
      next: (saved) => {
        this.baseline = saved;
        form.markAsPristine();
        this.saving.set(false);
        this.messageService.add({ severity: 'success', summary: 'Settings saved' });
      },
      error: () => {
        this.saving.set(false);
        this.messageService.add({ severity: 'error', summary: 'Could not save settings', sticky: true });
      },
    });
  }
}
