import { ChangeDetectionStrategy, Component, DestroyRef, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable, toSignal } from '@angular/core/rxjs-interop';
import { AbstractControl, FormControl, FormGroup, ReactiveFormsModule, ValidationErrors } from '@angular/forms';
import { ButtonModule } from 'primeng/button';
import { InputNumberModule } from 'primeng/inputnumber';
import { InputTextModule } from 'primeng/inputtext';
import { ToggleSwitchModule } from 'primeng/toggleswitch';
import { EMPTY, catchError, interval, map, merge, startWith, switchMap } from 'rxjs';

import { ProxyStatus, ProxyTestResult } from '../../models/proxy.model';
import { Settings } from '../../models/settings.model';
import { ProxyService } from '../../services/proxy.service';

const STATUS_POLL_INTERVAL_MS = 5000;

export type ProxySettingsForm = FormGroup<{
  enabled: FormControl<boolean>;
  host: FormControl<string>;
  port: FormControl<number>;
  username: FormControl<string>;
  blockUnsupported: FormControl<boolean>;
}>;

/** An enabled proxy needs a host and a valid port - the backend refuses it otherwise (a 400), so
 * this makes Save unavailable first, with a visible reason, like the Blocklist and Security groups. */
function proxyIncompleteWhenEnabled(group: AbstractControl): ValidationErrors | null {
  const enabled = group.get('enabled')?.value as boolean | undefined;
  const host = ((group.get('host')?.value as string | undefined) ?? '').trim();
  const port = group.get('port')?.value as number | undefined;
  const portValid = typeof port === 'number' && port >= 1 && port <= 65535;
  return enabled && (host === '' || !portValid) ? { proxyIncomplete: true } : null;
}

export function buildProxySettingsForm(settings: Settings): ProxySettingsForm {
  return new FormGroup(
    {
      enabled: new FormControl(settings.proxyEnabled, { nonNullable: true }),
      host: new FormControl(settings.proxyHost, { nonNullable: true }),
      port: new FormControl(settings.proxyPort || 1080, { nonNullable: true }),
      username: new FormControl(settings.proxyUsername, { nonNullable: true }),
      blockUnsupported: new FormControl(settings.proxyBlockUnsupported, { nonNullable: true }),
    },
    { validators: [proxyIncompleteWhenEnabled] },
  );
}

export function proxySettingsPatch(value: {
  enabled: boolean;
  host: string;
  port: number;
  username: string;
  blockUnsupported: boolean;
}): Partial<Settings> {
  return {
    proxyEnabled: value.enabled,
    proxyHost: value.host.trim(),
    proxyPort: value.port,
    proxyUsername: value.username.trim(),
    proxyBlockUnsupported: value.blockUnsupported,
  };
}

/**
 * The SOCKS5 proxy (design_docs/0079): the settings themselves are one form group saved with the
 * rest of the page; the password is deliberately separate - write-only, saved immediately through
 * its own endpoint, never shown - and "Test" checks the *saved* proxy end to end. The status
 * (whether a password exists, and whether a restart is still needed for the block switch to take
 * effect) is polled from GET /api/proxy while the page is open; the backend computes all of it and
 * this only words it.
 */
@Component({
  selector: 'app-proxy-settings',
  imports: [ButtonModule, InputNumberModule, InputTextModule, ReactiveFormsModule, ToggleSwitchModule],
  templateUrl: './proxy-settings.html',
  styleUrl: './proxy-settings.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProxySettings {
  private readonly proxyService = inject(ProxyService);

  readonly form = input.required<ProxySettingsForm>();
  readonly status = signal<ProxyStatus | undefined>(undefined);

  /** Never part of the settings form - see the class comment. */
  readonly password = new FormControl('', { nonNullable: true });
  readonly savingPassword = signal(false);
  readonly passwordError = signal<string | null>(null);

  readonly testing = signal(false);
  readonly testResult = signal<ProxyTestResult | undefined>(undefined);

  /** The bits of form state the template reacts to, refreshed on every change. */
  readonly formState = toSignal(
    toObservable(this.form).pipe(
      switchMap((form) =>
        merge(form.valueChanges, form.statusChanges).pipe(
          startWith(null),
          map(() => ({
            incomplete: form.hasError('proxyIncomplete'),
            enabled: form.controls.enabled.value,
            blockUnsupported: form.controls.blockUnsupported.value,
            dirty: form.dirty,
          })),
        ),
      ),
    ),
    { initialValue: { incomplete: false, enabled: false, blockUnsupported: true, dirty: false } },
  );

  constructor() {
    interval(STATUS_POLL_INTERVAL_MS)
      .pipe(
        startWith(0),
        // A failed poll just skips this tick, so the status recovers on its own.
        switchMap(() => this.proxyService.status().pipe(catchError(() => EMPTY))),
        takeUntilDestroyed(inject(DestroyRef)),
      )
      .subscribe((status) => this.status.set(status));
  }

  savePassword(): void {
    const value = this.password.value;
    if (value === '' || this.savingPassword()) {
      return;
    }
    this.savingPassword.set(true);
    this.passwordError.set(null);
    this.proxyService.setPassword(value).subscribe({
      next: (status) => {
        this.status.set(status);
        this.password.reset('');
        this.savingPassword.set(false);
        this.testResult.set(undefined);
      },
      error: () => {
        this.savingPassword.set(false);
        this.passwordError.set('Could not save the password - please try again.');
      },
    });
  }

  removePassword(): void {
    if (this.savingPassword()) {
      return;
    }
    this.savingPassword.set(true);
    this.passwordError.set(null);
    this.proxyService.clearPassword().subscribe({
      next: (status) => {
        this.status.set(status);
        this.savingPassword.set(false);
        this.testResult.set(undefined);
      },
      error: () => {
        this.savingPassword.set(false);
        this.passwordError.set('Could not remove the password - please try again.');
      },
    });
  }

  test(): void {
    if (this.testing()) {
      return;
    }
    this.testing.set(true);
    this.testResult.set(undefined);
    this.proxyService.test().subscribe({
      next: (result) => {
        this.testResult.set(result);
        this.testing.set(false);
      },
      error: () => {
        this.testResult.set({ reachable: false, udpSupported: false, message: 'The test could not be run.' });
        this.testing.set(false);
      },
    });
  }
}
