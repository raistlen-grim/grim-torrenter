import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';

import { AuthService } from '../../services/auth.service';
import { DhtService } from '../../services/dht.service';
import { TorrentEventsService } from '../../services/torrent-events.service';
import { CapMarker } from '../../shared/cap-marker/cap-marker';
import { FormatRatePipe } from '../../shared/format-rate.pipe';
import { pollEvery } from '../../shared/poll';
import { SkullMark } from '../../shared/skull-mark/skull-mark';
import { StatusIndicator } from '../../shared/status-indicator/status-indicator';

const DHT_POLL_INTERVAL_MS = 5000;

/**
 * Sticky top bar, present on every route. Styled after the style guide's own document
 * chrome (a dark bar using the darkest primary step) - see design_docs/0043.
 *
 * <p>Aggregate rates are the server's engine-wide figures (the "transfer" message,
 * design_docs/0102), not a sum of the rows here - 0 until the first message. The DHT pill
 * polls GET /api/dht/status the whole time this header is mounted, which - being the
 * header - is always, matching DhtResource's own "polled on demand only while something's
 * displaying it" intent (design_docs/0028).
 */
@Component({
  selector: 'app-header',
  imports: [CapMarker, FormatRatePipe, RouterLink, SkullMark, StatusIndicator],
  templateUrl: './app-header.html',
  styleUrl: './app-header.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AppHeader {
  readonly events = inject(TorrentEventsService);
  private readonly dht = inject(DhtService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  /** Only while auth is on and this browser holds a token - with auth off there's no session to
   * end. authGuard refreshes authEnabled on every guarded navigation. See design_docs/0095. */
  readonly canLogOut = computed(() => this.auth.authEnabled() === true && this.auth.token() !== null);

  readonly totalDownloadRate = computed(() => this.events.transfer()?.downloadRateBytesPerSec ?? 0);
  readonly totalUploadRate = computed(() => this.events.transfer()?.uploadRateBytesPerSec ?? 0);

  readonly dhtStatus = toSignal(
    pollEvery(DHT_POLL_INTERVAL_MS, () => this.dht.status()),
    { initialValue: { enabled: false, nodeCount: 0 } },
  );

  /** Ends the session server-side (which also closes its WebSocket there), stops this tab's
   * socket so it doesn't keep reconnecting with no token, and goes to the login page. Done on
   * failure too - logout() clears the token either way. */
  logOut(): void {
    this.auth.logout().subscribe({
      complete: () => this.leave(),
      error: () => this.leave(),
    });
  }

  private leave(): void {
    this.events.disconnect();
    this.router.navigateByUrl('/login');
  }
}
