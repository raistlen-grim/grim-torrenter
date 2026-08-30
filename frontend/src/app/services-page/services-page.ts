import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { interval, startWith, switchMap } from 'rxjs';

import { ServiceStatus } from '../models/system.model';
import { SystemService } from '../services/system.service';
import { serviceStatusDisplay } from '../shared/status-display';
import { StatusIndicator } from '../shared/status-indicator/status-indicator';

const SERVICES_POLL_INTERVAL_MS = 30_000;

/**
 * Engine-wide singleton subsystems only (DHT, the inbound peer server) - per-torrent status
 * stays on the torrent itself, not here. A green/red checklist rather than an issue feed: a
 * fixed set of named rows is self-documenting about what's actually being watched, and needs
 * no "no issues" empty-state copy since all-RUNNING rows already read as "nothing's wrong."
 * See design_docs/0059.
 *
 * <p>Polls independently of AppSidebar's own badge-count poll - no shared "live polled state"
 * service exists in this codebase yet, every consumer polls GET /api/system/services on its
 * own, same as AppHeader/AppFooter already do for DHT status and disk/resource usage.
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

  readonly services = toSignal(
    interval(SERVICES_POLL_INTERVAL_MS).pipe(
      startWith(0),
      switchMap(() => this.system.services()),
    ),
    { initialValue: [] as ServiceStatus[] },
  );

  readonly statusDisplay = serviceStatusDisplay;
}
