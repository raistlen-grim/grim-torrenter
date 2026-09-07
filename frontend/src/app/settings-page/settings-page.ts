import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormGroup } from '@angular/forms';
import { MessageService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { ToastModule } from 'primeng/toast';

import { Settings } from '../models/settings.model';
import { SettingsService } from '../services/settings.service';
import {
  AppearanceSettings,
  AppearanceSettingsForm,
  buildAppearanceSettingsForm,
  appearanceSettingsPatch,
} from './appearance-settings/appearance-settings';
import {
  EventLogSettings,
  EventLogSettingsForm,
  buildEventLogSettingsForm,
  eventLogSettingsPatch,
} from './event-log-settings/event-log-settings';
import {
  MagnetFetchSettings,
  MagnetFetchSettingsForm,
  buildMagnetFetchSettingsForm,
  magnetFetchSettingsPatch,
} from './magnet-fetch-settings/magnet-fetch-settings';
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
  SecuritySettings,
  SecuritySettingsForm,
  buildSecuritySettingsForm,
  securitySettingsPatch,
} from './security-settings/security-settings';
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
  appearance: AppearanceSettingsForm;
  network: NetworkSettingsForm;
  rateLimiting: RateLimitSettingsForm;
  seeding: SeedingSettingsForm;
  eventLog: EventLogSettingsForm;
  watchFolder: WatchFolderSettingsForm;
  magnetFetch: MagnetFetchSettingsForm;
  security: SecuritySettingsForm;
}>;

type SettingsGroupKey =
  | 'appearance'
  | 'network'
  | 'rateLimiting'
  | 'seeding'
  | 'eventLog'
  | 'watchFolder'
  | 'magnetFetch'
  | 'security';

/** Labels/icons/hints for the settings-page nav (a plain vertical list, not PrimeNG Tabs - see
 * design_docs/0045's own addendum: at this page's width, seven labels including multi-word
 * ones like "Rate limiting"/"Magnet fetching" don't fit a horizontal tab strip without
 * wrapping or scrolling). Order matches the groups' existing top-to-bottom order. `hint` is
 * the group's intro copy, now rendered once by this page (SETTINGS_PAGE.md) rather than
 * duplicated as each child component's own `.group-hint` - present on every group, not just
 * the 4 of 7 that had one before.
 *
 * `icon` is a PrimeIcons class, not the design's own Lucide set - design_docs/0032's
 * PrimeIcons-over-Lucide rule, confirmed still standing for this page (user decision,
 * 2026-09-04). 5 of 7 have an exact PrimeIcons equivalent; "scroll-text" (Event log) and
 * "magnet" (Magnet fetching) don't - substituted with pi-history (matching the real Events
 * sidebar nav item's own icon) and pi-link (a magnet link is literally a link) rather than
 * pulling in a second icon system for 2 icons. */
const SETTINGS_GROUPS: { key: SettingsGroupKey; label: string; icon: string; hint: string }[] = [
  {
    key: 'appearance',
    label: 'Appearance',
    icon: 'pi-palette',
    hint: 'Controls how GrimTorrenter looks. Applies immediately, no restart.',
  },
  {
    key: 'network',
    label: 'Network',
    icon: 'pi-wifi',
    hint: 'Peer discovery and protocol behavior.',
  },
  {
    key: 'rateLimiting',
    label: 'Rate limiting',
    icon: 'pi-gauge',
    hint: 'Caps on transfer speed, always or on a schedule.',
  },
  {
    key: 'seeding',
    label: 'Seeding',
    icon: 'pi-arrow-up',
    hint: "When to stop serving data you've already downloaded.",
  },
  {
    key: 'eventLog',
    label: 'Event log',
    icon: 'pi-history',
    hint: 'How long GrimTorrenter keeps its own activity log.',
  },
  {
    key: 'watchFolder',
    label: 'Watch folder',
    icon: 'pi-folder',
    hint: 'Automatically add torrents dropped into a folder.',
  },
  {
    key: 'magnetFetch',
    label: 'Magnet fetching',
    icon: 'pi-link',
    hint: "How hard to try before giving up on a magnet link's metadata.",
  },
  {
    key: 'security',
    label: 'Security',
    icon: 'pi-lock',
    hint: 'Password-protect the REST API and web UI.',
  },
];

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
    AppearanceSettings,
    ButtonModule,
    EventLogSettings,
    MagnetFetchSettings,
    NetworkSettings,
    RateLimitSettings,
    SecuritySettings,
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

  readonly groups = SETTINGS_GROUPS;
  readonly activeGroup = signal<SettingsGroupKey>('appearance');
  readonly activeGroupDef = computed(() => this.groups.find((g) => g.key === this.activeGroup())!);

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
            appearance: buildAppearanceSettingsForm(settings),
            network: buildNetworkSettingsForm(settings),
            rateLimiting: buildRateLimitSettingsForm(settings),
            seeding: buildSeedingSettingsForm(settings),
            eventLog: buildEventLogSettingsForm(settings),
            watchFolder: buildWatchFolderSettingsForm(settings),
            magnetFetch: buildMagnetFetchSettingsForm(settings),
            security: buildSecuritySettingsForm(settings),
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
      ...appearanceSettingsPatch(value.appearance),
      ...networkSettingsPatch(value.network),
      ...rateLimitSettingsPatch(value.rateLimiting),
      ...seedingSettingsPatch(value.seeding),
      ...eventLogSettingsPatch(value.eventLog),
      ...watchFolderSettingsPatch(value.watchFolder),
      ...magnetFetchSettingsPatch(value.magnetFetch),
      ...securitySettingsPatch(value.security),
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
