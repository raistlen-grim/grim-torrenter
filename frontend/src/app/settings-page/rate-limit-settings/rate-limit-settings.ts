import { ChangeDetectionStrategy, Component, effect, input } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { InputGroupModule } from 'primeng/inputgroup';
import { InputGroupAddonModule } from 'primeng/inputgroupaddon';
import { InputNumberModule } from 'primeng/inputnumber';
import { ToggleSwitchModule } from 'primeng/toggleswitch';
import { Subscription } from 'rxjs';

import { Settings } from '../../models/settings.model';

const BYTES_PER_KIB = 1024;

/** Falls back for a settings.json written before design_docs/0046 existed - the backend
 * defaults a missing rateLimitScheduleStart/End to null (schedule was disabled, so nothing
 * server-side ever reads them), which would otherwise render as blank native time inputs
 * the first time this page loads against an old file. Matches Settings.java's own
 * DEFAULT_SCHEDULE_START/END placeholders. */
const DEFAULT_SCHEDULE_START = '23:00';
const DEFAULT_SCHEDULE_END = '07:00';

/** 0 (or negative) means unlimited on the backend (Settings' own Javadoc) - kept as exactly
 * 0 here rather than letting rounding turn a genuine "unlimited" into a tiny nonzero cap. */
function bytesToKib(bytesPerSec: number): number {
  return bytesPerSec > 0 ? Math.round(bytesPerSec / BYTES_PER_KIB) : 0;
}

function kibToBytes(kibPerSec: number): number {
  return kibPerSec > 0 ? Math.round(kibPerSec * BYTES_PER_KIB) : 0;
}

export type RateLimitSettingsForm = FormGroup<{
  uploadLimitKibPerSec: FormControl<number>;
  uploadEnabled: FormControl<boolean>;
  downloadLimitKibPerSec: FormControl<number>;
  downloadEnabled: FormControl<boolean>;
  burstSeconds: FormControl<number>;
  scheduleEnabled: FormControl<boolean>;
  scheduleStart: FormControl<string>;
  scheduleEnd: FormControl<string>;
  scheduleUploadLimitKibPerSec: FormControl<number>;
  scheduleUploadEnabled: FormControl<boolean>;
  scheduleDownloadLimitKibPerSec: FormControl<number>;
  scheduleDownloadEnabled: FormControl<boolean>;
}>;

/** bytes/sec (the model's unit of record, see settings.model.ts) -> KiB/s (this group's
 * display unit) happens only here, at the form's construction. uploadEnabled/downloadEnabled
 * (and their schedule-window counterparts) are a UI-only convenience derived from the loaded
 * value (> 0), not a separate field on Settings - "capped" and "a positive byte value" are the
 * same fact, just easier to act on as a toggle than as a magic number. `true` means "the cap is
 * active," matching Seeding's own ratioLimitEnabled/timeLimitEnabled naming exactly - renamed
 * from the previous uploadUnlimited/downloadUnlimited (inverted sense) as part of the settings
 * redesign unifying every "number + enable-toggle" row onto one shape/semantic (SETTINGS_PAGE.md,
 * design_docs/0045's addendum): the design's toggle is "on = capped" everywhere on the page, so
 * this group's own fields needed to match rather than being the one place "on" meant the
 * opposite. The schedule window's start/end are plain "HH:mm" strings straight from Settings - a
 * native <input type="time"> reads/writes that exact format, so no conversion is needed there
 * (see design_docs/0046). */
export function buildRateLimitSettingsForm(settings: Settings): RateLimitSettingsForm {
  const uploadEnabled = settings.uploadRateLimitBytesPerSec > 0;
  const downloadEnabled = settings.downloadRateLimitBytesPerSec > 0;
  const scheduleEnabled = settings.rateLimitScheduleEnabled;
  const scheduleUploadEnabled = settings.scheduledUploadRateLimitBytesPerSec > 0;
  const scheduleDownloadEnabled = settings.scheduledDownloadRateLimitBytesPerSec > 0;

  return new FormGroup({
    uploadLimitKibPerSec: new FormControl(
      { value: bytesToKib(settings.uploadRateLimitBytesPerSec), disabled: !uploadEnabled },
      { nonNullable: true },
    ),
    uploadEnabled: new FormControl(uploadEnabled, { nonNullable: true }),
    downloadLimitKibPerSec: new FormControl(
      { value: bytesToKib(settings.downloadRateLimitBytesPerSec), disabled: !downloadEnabled },
      { nonNullable: true },
    ),
    downloadEnabled: new FormControl(downloadEnabled, { nonNullable: true }),
    burstSeconds: new FormControl(Math.max(0, settings.rateLimitBurstSeconds), { nonNullable: true }),

    scheduleEnabled: new FormControl(scheduleEnabled, { nonNullable: true }),
    scheduleStart: new FormControl(
      { value: settings.rateLimitScheduleStart ?? DEFAULT_SCHEDULE_START, disabled: !scheduleEnabled },
      { nonNullable: true },
    ),
    scheduleEnd: new FormControl(
      { value: settings.rateLimitScheduleEnd ?? DEFAULT_SCHEDULE_END, disabled: !scheduleEnabled },
      { nonNullable: true },
    ),
    scheduleUploadLimitKibPerSec: new FormControl(
      {
        value: bytesToKib(settings.scheduledUploadRateLimitBytesPerSec),
        disabled: !scheduleEnabled || !scheduleUploadEnabled,
      },
      { nonNullable: true },
    ),
    scheduleUploadEnabled: new FormControl(
      { value: scheduleUploadEnabled, disabled: !scheduleEnabled },
      { nonNullable: true },
    ),
    scheduleDownloadLimitKibPerSec: new FormControl(
      {
        value: bytesToKib(settings.scheduledDownloadRateLimitBytesPerSec),
        disabled: !scheduleEnabled || !scheduleDownloadEnabled,
      },
      { nonNullable: true },
    ),
    scheduleDownloadEnabled: new FormControl(
      { value: scheduleDownloadEnabled, disabled: !scheduleEnabled },
      { nonNullable: true },
    ),
  });
}

export function rateLimitSettingsPatch(value: {
  uploadLimitKibPerSec: number;
  uploadEnabled: boolean;
  downloadLimitKibPerSec: number;
  downloadEnabled: boolean;
  burstSeconds: number;
  scheduleEnabled: boolean;
  scheduleStart: string;
  scheduleEnd: string;
  scheduleUploadLimitKibPerSec: number;
  scheduleUploadEnabled: boolean;
  scheduleDownloadLimitKibPerSec: number;
  scheduleDownloadEnabled: boolean;
}): Partial<Settings> {
  return {
    uploadRateLimitBytesPerSec: value.uploadEnabled ? kibToBytes(value.uploadLimitKibPerSec) : 0,
    downloadRateLimitBytesPerSec: value.downloadEnabled ? kibToBytes(value.downloadLimitKibPerSec) : 0,
    rateLimitBurstSeconds: value.burstSeconds,
    rateLimitScheduleEnabled: value.scheduleEnabled,
    rateLimitScheduleStart: value.scheduleStart,
    rateLimitScheduleEnd: value.scheduleEnd,
    scheduledUploadRateLimitBytesPerSec: value.scheduleUploadEnabled ? kibToBytes(value.scheduleUploadLimitKibPerSec) : 0,
    scheduledDownloadRateLimitBytesPerSec: value.scheduleDownloadEnabled
      ? kibToBytes(value.scheduleDownloadLimitKibPerSec)
      : 0,
  };
}

/**
 * Its own group (rather than folded into Network) because more rate-limiting settings were
 * anticipated - a burst allowance is now built (design_docs/0053), per-torrent overrides are
 * still a natural future addition - and grouping by topic means those slot in here without
 * reshuffling the page. See design_docs/0045 and design_docs/0042's "not built in this pass"
 * note this page fills in.
 *
 * <p>The scheduled off-hours window (design_docs/0046) lives inline in this same component
 * rather than as its own nested settings group - it's a sub-feature of rate limiting, not a
 * new topic, and today's scope (a single daily window) doesn't earn a further split. Revisit
 * if it grows into multiple rules.
 */
@Component({
  selector: 'app-rate-limit-settings',
  imports: [InputGroupModule, InputGroupAddonModule, InputNumberModule, ReactiveFormsModule, ToggleSwitchModule],
  templateUrl: './rate-limit-settings.html',
  styleUrl: './rate-limit-settings.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RateLimitSettings {
  readonly form = input.required<RateLimitSettingsForm>();

  /** Reactive Forms discourages toggling a control's disabled state via a template
   * [disabled] binding once it's also bound through formControlName (Angular logs a warning
   * - the directive already owns that state); enable()/disable() here is the supported way,
   * driven by the paired enable-toggle's (or the schedule's own enabled toggle's) value. */
  constructor() {
    effect((onCleanup) => {
      const controls = this.form().controls;
      const subscriptions = [
        syncEnabledDisabled(controls.uploadEnabled, controls.uploadLimitKibPerSec),
        syncEnabledDisabled(controls.downloadEnabled, controls.downloadLimitKibPerSec),
        syncScheduleDisabled(controls),
      ];
      onCleanup(() => subscriptions.forEach((subscription) => subscription.unsubscribe()));
    });
  }
}

function syncEnabledDisabled(enabled: FormControl<boolean>, limit: FormControl<number>): Subscription {
  const apply = (isEnabled: boolean) => (isEnabled ? limit.enable() : limit.disable());
  apply(enabled.value);
  return enabled.valueChanges.subscribe(apply);
}

/** The schedule's fields have two disabling inputs, not one: the section's own "enabled"
 * toggle (which gates every field in it), and - for the two limit fields specifically -
 * their own enable-toggle on top of that. Re-evaluated from both controls' current values on
 * either one changing, rather than trying to express "disabled because of A, then separately
 * because of B" as two independent subscriptions that could race and leave a control in the
 * wrong state. */
function syncScheduleDisabled(controls: RateLimitSettingsForm['controls']): Subscription {
  const applySectionEnabled = (enabled: boolean) => {
    setDisabled(controls.scheduleStart, !enabled);
    setDisabled(controls.scheduleEnd, !enabled);
    setDisabled(controls.scheduleUploadEnabled, !enabled);
    setDisabled(controls.scheduleDownloadEnabled, !enabled);
    applyLimitFieldsEnabled();
  };
  const applyLimitFieldsEnabled = () => {
    const enabled = controls.scheduleEnabled.value;
    setDisabled(controls.scheduleUploadLimitKibPerSec, !enabled || !controls.scheduleUploadEnabled.value);
    setDisabled(controls.scheduleDownloadLimitKibPerSec, !enabled || !controls.scheduleDownloadEnabled.value);
  };

  applySectionEnabled(controls.scheduleEnabled.value);

  const subscription = new Subscription();
  subscription.add(controls.scheduleEnabled.valueChanges.subscribe(applySectionEnabled));
  subscription.add(controls.scheduleUploadEnabled.valueChanges.subscribe(applyLimitFieldsEnabled));
  subscription.add(controls.scheduleDownloadEnabled.valueChanges.subscribe(applyLimitFieldsEnabled));
  return subscription;
}

function setDisabled(control: FormControl<boolean> | FormControl<number> | FormControl<string>, disabled: boolean): void {
  if (disabled) {
    control.disable();
  } else {
    control.enable();
  }
}
