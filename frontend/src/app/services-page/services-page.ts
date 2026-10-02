import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { catchError, interval, of, startWith, switchMap, tap } from 'rxjs';

import { HealthReport } from '../models/system.model';
import { SystemService } from '../services/system.service';
import { healthCheckDisplay, healthGroupLabel } from '../shared/status-display';
import { StatusIndicator } from '../shared/status-indicator/status-indicator';

const HEALTH_POLL_INTERVAL_MS = 15_000;

/**
 * The Health page (design_docs/0086) - grown out of the original Services page
 * (design_docs/0059), whose three network services are now its first group. Still a fixed
 * checklist rather than an issue feed, for the same reason: named rows are self-documenting
 * about what is being watched, and an all-fine page needs no "no issues" copy. The class and
 * folder keep the Services name; only what the page shows, its title and its route changed.
 *
 * <p>Everything shown comes from GET /api/system/health as-is: the backend decides each row's
 * state and writes its message, this only maps names to labels and icons. Polls on its own,
 * independently of AppSidebar's badge poll, like every other polled view here; a failed poll
 * keeps the last report on screen rather than blanking the page.
 */
@Component({
  selector: 'app-services-page',
  imports: [StatusIndicator],
  templateUrl: './services-page.html',
  styleUrl: './services-page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ServicesPage {
  private readonly system = inject(SystemService);

  /** The most recent successful report - what a failed poll falls back to. */
  private lastReport: HealthReport | null = null;

  readonly report = toSignal(
    interval(HEALTH_POLL_INTERVAL_MS).pipe(
      startWith(0),
      switchMap(() =>
        this.system.health().pipe(
          tap((report) => (this.lastReport = report)),
          catchError(() => of(this.lastReport)),
        ),
      ),
    ),
    { initialValue: null },
  );

  readonly checkDisplay = healthCheckDisplay;
  readonly groupLabel = healthGroupLabel;
}
