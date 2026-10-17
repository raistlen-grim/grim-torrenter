import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';

import { HealthReport } from '../models/system.model';
import { SystemService } from '../services/system.service';
import { healthCheckDisplay, healthGroupLabel } from '../shared/status-display';
import { pollEvery } from '../shared/poll';
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

  /** A failed poll keeps the most recent successful report (pollEvery skips the tick). */
  readonly report = toSignal<HealthReport | null>(
    pollEvery(HEALTH_POLL_INTERVAL_MS, () => this.system.health()),
    { initialValue: null },
  );

  readonly checkDisplay = healthCheckDisplay;
  readonly groupLabel = healthGroupLabel;
}
