import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { FormGroup } from '@angular/forms';
import { ConfirmationService, MessageService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { ConfirmDialogModule } from 'primeng/confirmdialog';
import { Observable } from 'rxjs';
import { ToastModule } from 'primeng/toast';

import { Settings } from '../models/settings.model';
import { SettingsService } from '../services/settings.service';
import { ThemeService } from '../services/theme.service';
import { HasUnsavedChanges } from '../services/unsaved-changes.guard';
import { apiErrorMessage } from '../shared/api-error';
import {
  AppearanceSettings,
  AppearanceSettingsForm,
  buildAppearanceSettingsForm,
  appearanceSettingsPatch,
} from './appearance-settings/appearance-settings';
import {
  BlocklistSettings,
  BlocklistSettingsForm,
  blocklistSettingsPatch,
  buildBlocklistSettingsForm,
} from './blocklist-settings/blocklist-settings';
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
  ProxySettings,
  ProxySettingsForm,
  buildProxySettingsForm,
  proxySettingsPatch,
} from './proxy-settings/proxy-settings';
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
  blocklist: BlocklistSettingsForm;
  proxy: ProxySettingsForm;
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
  | 'blocklist'
  | 'proxy'
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
    key: 'blocklist',
    label: 'Blocklist',
    icon: 'pi-ban',
    hint: 'Refuse connections to and from a list of IP ranges.',
  },
  {
    key: 'proxy',
    label: 'Proxy',
    icon: 'pi-shield',
    hint: 'Send outbound traffic through a SOCKS5 proxy.',
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
    BlocklistSettings,
    ButtonModule,
    ConfirmDialogModule,
    EventLogSettings,
    MagnetFetchSettings,
    NetworkSettings,
    ProxySettings,
    RateLimitSettings,
    SecuritySettings,
    SeedingSettings,
    ToastModule,
    WatchFolderSettings,
  ],
  templateUrl: './settings-page.html',
  styleUrl: './settings-page.scss',
  providers: [ConfirmationService, MessageService],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '(window:beforeunload)': 'onBeforeUnload($event)',
  },
})
export class SettingsPage implements HasUnsavedChanges {
  private readonly settingsService = inject(SettingsService);
  private readonly messageService = inject(MessageService);
  private readonly confirmationService = inject(ConfirmationService);
  private readonly themeService = inject(ThemeService);

  /** The last value loaded from, or saved to, the backend - the base every save's patch is
   * merged onto, so a group that hasn't been touched never has its fields clobbered by a
   * stale default. */
  private baseline: Settings | undefined;

  readonly groups = SETTINGS_GROUPS;
  readonly activeGroup = signal<SettingsGroupKey>('appearance');
  readonly activeGroupDef = computed(() => this.groups.find((g) => g.key === this.activeGroup())!);

  readonly form = signal<SettingsFormGroup | undefined>(undefined);
  readonly saving = signal(false);
  /** The initial load failed - the template offers a retry instead of "Loading…" forever. See
   * design_docs/0095. */
  readonly loadFailed = signal(false);

  constructor() {
    this.load();
    // The Appearance group previews a theme the moment it's picked. Leaving without saving must
    // put the saved one back, or the app keeps showing a theme the server doesn't have.
    // baseline is the last loaded-or-saved Settings, so after a save this is a no-op. See
    // design_docs/0096.
    inject(DestroyRef).onDestroy(() => {
      if (this.baseline) {
        this.themeService.preview(this.baseline.theme);
      }
    });
  }

  /** unsavedChangesGuard: asks before navigating away from edits that haven't been saved. */
  confirmLeave(): boolean | Observable<boolean> {
    if (!this.form()?.dirty || this.saving()) {
      return true;
    }
    return new Observable<boolean>((subscriber) => {
      const answer = (leave: boolean) => {
        subscriber.next(leave);
        subscriber.complete();
      };
      this.confirmationService.confirm({
        header: 'Leave without saving?',
        message: 'Your changes to settings will be lost.',
        icon: 'pi pi-exclamation-triangle',
        acceptLabel: 'Leave',
        rejectLabel: 'Stay',
        accept: () => answer(true),
        reject: () => answer(false),
      });
    });
  }

  /** Closing or reloading the tab - the browser shows its own generic prompt. */
  onBeforeUnload(event: BeforeUnloadEvent): void {
    if (this.form()?.dirty) {
      event.preventDefault();
    }
  }

  /** Builds the form once, from the initial GET. Also the Retry button's handler. */
  load(): void {
    this.loadFailed.set(false);
    this.settingsService.current().subscribe({
      next: (settings) => {
        this.baseline = settings;
        this.form.set(
          new FormGroup({
            appearance: buildAppearanceSettingsForm(settings),
            network: buildNetworkSettingsForm(settings),
            blocklist: buildBlocklistSettingsForm(settings),
            proxy: buildProxySettingsForm(settings),
            rateLimiting: buildRateLimitSettingsForm(settings),
            seeding: buildSeedingSettingsForm(settings),
            eventLog: buildEventLogSettingsForm(settings),
            watchFolder: buildWatchFolderSettingsForm(settings),
            magnetFetch: buildMagnetFetchSettingsForm(settings),
            security: buildSecuritySettingsForm(settings),
          }),
        );
      },
      error: () => this.loadFailed.set(true),
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
      ...blocklistSettingsPatch(value.blocklist),
      ...proxySettingsPatch(value.proxy),
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
      error: (error: unknown) => {
        this.saving.set(false);
        // The server's reason, e.g. a value out of range (design_docs/0094/0096).
        this.messageService.add({
          severity: 'error',
          summary: 'Could not save settings',
          detail: apiErrorMessage(error, 'Please try again.'),
          sticky: true,
        });
      },
    });
  }
}
