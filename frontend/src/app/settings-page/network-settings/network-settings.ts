import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { InputNumberModule } from 'primeng/inputnumber';
import { SelectModule } from 'primeng/select';
import { ToggleSwitchModule } from 'primeng/toggleswitch';

import { EncryptionMode, Settings } from '../../models/settings.model';

export type NetworkSettingsForm = FormGroup<{
  dhtEnabled: FormControl<boolean>;
  acceptIncomingConnections: FormControl<boolean>;
  encryptionMode: FormControl<EncryptionMode>;
  trackerlessDhtReannounceIntervalSeconds: FormControl<number>;
  dhtRefreshIntervalSeconds: FormControl<number>;
}>;

export function buildNetworkSettingsForm(settings: Settings): NetworkSettingsForm {
  return new FormGroup({
    dhtEnabled: new FormControl(settings.dhtEnabled, { nonNullable: true }),
    acceptIncomingConnections: new FormControl(settings.acceptIncomingConnections, { nonNullable: true }),
    encryptionMode: new FormControl(settings.encryptionMode, { nonNullable: true }),
    trackerlessDhtReannounceIntervalSeconds: new FormControl(settings.trackerlessDhtReannounceIntervalSeconds, {
      nonNullable: true,
    }),
    dhtRefreshIntervalSeconds: new FormControl(settings.dhtRefreshIntervalSeconds, { nonNullable: true }),
  });
}

export function networkSettingsPatch(value: {
  dhtEnabled: boolean;
  acceptIncomingConnections: boolean;
  encryptionMode: EncryptionMode;
  trackerlessDhtReannounceIntervalSeconds: number;
  dhtRefreshIntervalSeconds: number;
}): Partial<Settings> {
  return {
    dhtEnabled: value.dhtEnabled,
    acceptIncomingConnections: value.acceptIncomingConnections,
    encryptionMode: value.encryptionMode,
    trackerlessDhtReannounceIntervalSeconds: value.trackerlessDhtReannounceIntervalSeconds,
    dhtRefreshIntervalSeconds: value.dhtRefreshIntervalSeconds,
  };
}

/**
 * One settings group among possibly several on the page (see design_docs/0045) - purely
 * presentational, bound to the FormGroup its own buildNetworkSettingsForm() builds. dhtEnabled
 * and acceptIncomingConnections only take effect after a restart (DhtNode/PeerServer are each
 * created once, at engine construction - see Settings' own Javadoc and design_docs/0041);
 * encryptionMode is live instead (design_docs/0052) - each row's own description calls out
 * which applies, rather than a single group-level hint that would be wrong for one of them.
 * trackerlessDhtReannounceIntervalSeconds is also live, but only takes effect on a torrent's
 * next start() - see Settings.java's own Javadoc (design_docs/0036's own addendum).
 * dhtRefreshIntervalSeconds is live too, but takes effect on the engine's next
 * construction/restart rather than a torrent's next start() - see Settings.java's own Javadoc
 * (design_docs/0028's own 2026-08-30 addendum).
 */
@Component({
  selector: 'app-network-settings',
  imports: [InputNumberModule, ReactiveFormsModule, SelectModule, ToggleSwitchModule],
  templateUrl: './network-settings.html',
  styleUrl: './network-settings.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NetworkSettings {
  readonly form = input.required<NetworkSettingsForm>();

  protected readonly encryptionModeOptions: { label: string; value: EncryptionMode }[] = [
    { label: 'Disabled', value: 'DISABLED' },
    { label: 'Preferred', value: 'PREFERRED' },
    { label: 'Required', value: 'REQUIRED' },
  ];
}
