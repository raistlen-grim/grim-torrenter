import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, effect, inject, input, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MessageService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { InputGroupModule } from 'primeng/inputgroup';
import { InputGroupAddonModule } from 'primeng/inputgroupaddon';
import { InputNumberModule } from 'primeng/inputnumber';
import { PasswordModule } from 'primeng/password';
import { ToggleSwitchModule } from 'primeng/toggleswitch';
import { Subscription, switchMap } from 'rxjs';

import { Settings } from '../../models/settings.model';
import { AuthService } from '../../services/auth.service';

export type SecuritySettingsForm = FormGroup<{
  enabled: FormControl<boolean>;
  tokenTtlDays: FormControl<number>;
}>;

export function buildSecuritySettingsForm(settings: Settings): SecuritySettingsForm {
  return new FormGroup({
    enabled: new FormControl(settings.authEnabled, { nonNullable: true }),
    tokenTtlDays: new FormControl(settings.authTokenTtlDays, { nonNullable: true }),
  });
}

export function securitySettingsPatch(value: { enabled: boolean; tokenTtlDays: number }): Partial<Settings> {
  return { authEnabled: value.enabled, authTokenTtlDays: value.tokenTtlDays };
}

/**
 * The "require a password" toggle saves through the same Settings PUT every other group uses
 * - but the password itself deliberately doesn't, and can't: it's its own immediate-effect
 * PUT /api/auth/password (design_docs/0061), not a field on the Settings record at all (a
 * password hash must never be reachable through GET /api/settings, which echoes the whole
 * record back verbatim). So this component owns a second, independent form/submit path
 * alongside the one SettingsPage drives for every other group - the same "not every
 * settings-page control is a plain Settings field" precedent design_docs/0054's seeding-limits
 * dialog already set, just inline here rather than a modal.
 *
 * <p>After a successful password save, immediately logs in with the new password too - so the
 * browser holds a fresh valid token before the user has a chance to also flip "Require a
 * password" on and save, which would otherwise 401 the very next request this same page makes.
 */
@Component({
  selector: 'app-security-settings',
  imports: [
    ButtonModule,
    InputGroupModule,
    InputGroupAddonModule,
    InputNumberModule,
    PasswordModule,
    ReactiveFormsModule,
    ToggleSwitchModule,
  ],
  templateUrl: './security-settings.html',
  styleUrl: './security-settings.scss',
  providers: [MessageService],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SecuritySettings {
  private readonly authService = inject(AuthService);
  private readonly messageService = inject(MessageService);

  readonly form = input.required<SecuritySettingsForm>();

  /** Unknown until the status check below resolves - starts false so the current-password
   * field (meaningless before any password exists) doesn't flash in before we actually know
   * it's needed. */
  readonly hasExistingPassword = signal(false);

  readonly passwordForm = new FormGroup({
    currentPassword: new FormControl('', { nonNullable: true }),
    newPassword: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.minLength(8)] }),
  });
  readonly savingPassword = signal(false);

  /** True whenever "Require a password" is switched on but no password actually exists yet -
   * SettingsResource itself would reject that combination with 400. Drives both a visible
   * inline reason (the template) and the control's own validity, which is what actually
   * blocks saving - see the constructor's own effect. */
  readonly passwordRequiredError = signal(false);

  constructor() {
    this.authService.status().subscribe((status) => this.hasExistingPassword.set(status.passwordSet));

    // "Require a password" is always clickable - unlike an earlier version of this component,
    // which disabled the toggle itself until a password existed. That hid the precondition
    // behind an easy-to-miss greyed-out control (a real user confusion, reported and fixed -
    // see design_docs/0061's own UX note). Instead, this marks the control *invalid* when
    // toggled on with no password set: SettingsPage's global Save button already disables
    // itself whenever any group's form is invalid, so this combination can never actually be
    // persisted, and passwordRequiredError below drives a specific, visible reason why -
    // rather than a generic "Could not save settings" only discoverable after clicking Save.
    // Same disable-via-valueChanges-subscription shape as the tokenTtlDays effect below, just
    // driving setErrors()/a signal instead of enable()/disable().
    effect((onCleanup) => {
      const control = this.form().controls.enabled;
      const hasPassword = this.hasExistingPassword();
      const evaluate = () => {
        const invalid = control.value && !hasPassword;
        control.setErrors(invalid ? { passwordRequired: true } : null);
        this.passwordRequiredError.set(invalid);
      };
      evaluate();
      const subscription: Subscription = control.valueChanges.subscribe(evaluate);
      onCleanup(() => subscription.unsubscribe());
    });

    // tokenTtlDays is only meaningful while a password is actually required - same
    // disable-via-enable()/disable()-on-valueChanges pattern WatchFolderSettings already uses
    // for its own paired retentionDays/pollIntervalSeconds controls.
    effect((onCleanup) => {
      const controls = this.form().controls;
      const apply = (enabled: boolean) => (enabled ? controls.tokenTtlDays.enable() : controls.tokenTtlDays.disable());
      apply(controls.enabled.value);
      const subscription: Subscription = controls.enabled.valueChanges.subscribe(apply);
      onCleanup(() => subscription.unsubscribe());
    });
  }

  savePassword(): void {
    if (this.passwordForm.invalid || this.savingPassword()) {
      return;
    }
    this.savingPassword.set(true);
    const { currentPassword, newPassword } = this.passwordForm.getRawValue();

    this.authService
      .setPassword(newPassword, currentPassword || undefined)
      .pipe(switchMap(() => this.authService.login(newPassword)))
      .subscribe({
        next: () => {
          this.savingPassword.set(false);
          this.hasExistingPassword.set(true);
          this.passwordForm.reset();
          this.messageService.add({ severity: 'success', summary: 'Password saved' });
        },
        error: (error: unknown) => {
          this.savingPassword.set(false);
          const isCurrentPasswordWrong = error instanceof HttpErrorResponse && error.status === 401;
          this.messageService.add({
            severity: 'error',
            summary: isCurrentPasswordWrong ? 'Current password is incorrect' : 'Could not save password',
            sticky: true,
          });
        },
      });
  }
}
