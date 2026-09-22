import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable, toSignal } from '@angular/core/rxjs-interop';
import { AbstractControl, FormControl, FormGroup, ReactiveFormsModule, ValidationErrors } from '@angular/forms';
import { ButtonModule } from 'primeng/button';
import { InputGroupModule } from 'primeng/inputgroup';
import { InputGroupAddonModule } from 'primeng/inputgroupaddon';
import { InputNumberModule } from 'primeng/inputnumber';
import { InputTextModule } from 'primeng/inputtext';
import { ToggleSwitchModule } from 'primeng/toggleswitch';
import { EMPTY, catchError, interval, map, merge, startWith, switchMap } from 'rxjs';

import { BlocklistStatus } from '../../models/blocklist.model';
import { Settings } from '../../models/settings.model';
import { BlocklistService } from '../../services/blocklist.service';

const STATUS_POLL_INTERVAL_MS = 5000;

export type BlocklistSettingsForm = FormGroup<{
  enabled: FormControl<boolean>;
  source: FormControl<string>;
  refreshHours: FormControl<number>;
}>;

/** Enabling the blocklist with nothing to load from is refused by the backend too (a 400) - this
 * makes Save unavailable first, with a visible reason, the same way the Security group treats
 * "Require a password" with no password set. */
function sourceRequiredWhenEnabled(group: AbstractControl): ValidationErrors | null {
  const enabled = group.get('enabled')?.value as boolean | undefined;
  const source = ((group.get('source')?.value as string | undefined) ?? '').trim();
  return enabled && source === '' ? { sourceRequired: true } : null;
}

export function buildBlocklistSettingsForm(settings: Settings): BlocklistSettingsForm {
  return new FormGroup(
    {
      enabled: new FormControl(settings.blocklistEnabled, { nonNullable: true }),
      source: new FormControl(settings.blocklistSource, { nonNullable: true }),
      refreshHours: new FormControl(settings.blocklistRefreshHours, { nonNullable: true }),
    },
    { validators: [sourceRequiredWhenEnabled] },
  );
}

export function blocklistSettingsPatch(value: {
  enabled: boolean;
  source: string;
  refreshHours: number;
}): Partial<Settings> {
  return {
    blocklistEnabled: value.enabled,
    blocklistSource: value.source.trim(),
    blocklistRefreshHours: value.refreshHours,
  };
}

/**
 * The IP blocklist (design_docs/0078): an enable toggle, a source (a file path or an http(s) URL),
 * how often to re-download a URL, and a live status line with a Reload button. All three
 * settings are live, but the backend only checks for a change about once a minute, so the row
 * copy says so and points at Reload for "now". The status is polled from GET /api/blocklist
 * while this page is open - the backend computes everything shown here; this only words it.
 */
@Component({
  selector: 'app-blocklist-settings',
  imports: [
    ButtonModule,
    DatePipe,
    InputGroupModule,
    InputGroupAddonModule,
    InputNumberModule,
    InputTextModule,
    ReactiveFormsModule,
    ToggleSwitchModule,
  ],
  templateUrl: './blocklist-settings.html',
  styleUrl: './blocklist-settings.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BlocklistSettings {
  private readonly blocklistService = inject(BlocklistService);

  readonly form = input.required<BlocklistSettingsForm>();
  readonly status = signal<BlocklistStatus | undefined>(undefined);

  readonly sourceRequiredError = toSignal(
    toObservable(this.form).pipe(
      switchMap((form) =>
        merge(form.valueChanges, form.statusChanges).pipe(
          startWith(null),
          map(() => form.hasError('sourceRequired')),
        ),
      ),
    ),
    { initialValue: false },
  );

  /** One sentence for the status line - the alarm-toned error, when there is one, is shown
   * separately so it can be styled and read as a problem rather than a state. */
  readonly statusLine = computed(() => {
    const status = this.status();
    if (!status) {
      return 'Checking…';
    }
    if (status.loading) {
      return 'Loading…';
    }
    if (!status.enabled) {
      return 'Off';
    }
    if (status.rangeCount > 0) {
      return `Enforcing ${status.rangeCount.toLocaleString()} IP ranges`;
    }
    return status.lastError ? 'Not loaded' : 'Waiting to load…';
  });

  constructor() {
    interval(STATUS_POLL_INTERVAL_MS)
      .pipe(
        startWith(0),
        // A failed poll (say a moment of no connectivity) just skips this tick rather than
        // ending the stream, so the status line recovers on its own.
        switchMap(() => this.blocklistService.status().pipe(catchError(() => EMPTY))),
        takeUntilDestroyed(inject(DestroyRef)),
      )
      .subscribe((status) => this.status.set(status));
  }

  reload(): void {
    this.blocklistService.reload().subscribe({ next: (status) => this.status.set(status) });
  }
}
