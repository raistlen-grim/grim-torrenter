import { Component, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';

import { AuthService } from './services/auth.service';
import { ThemeService } from './services/theme.service';
import { TorrentEventsService } from './services/torrent-events.service';
import { AppFooter } from './shell/app-footer/app-footer';
import { AppHeader } from './shell/app-header/app-header';
import { AppSidebar } from './shell/app-sidebar/app-sidebar';

@Component({
  selector: 'app-root',
  imports: [AppFooter, AppHeader, AppSidebar, RouterOutlet],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  private readonly router = inject(Router);
  private readonly torrentEventsService = inject(TorrentEventsService);

  /** The login route renders standalone, with none of the app shell's chrome (header/
   * sidebar/footer) - it's the one screen GrimTorrenter shows before it will trust who's
   * asking. Same NavigationEnd-driven "does the current route match X" pattern TorrentList's
   * own isDetailOpen already uses, just at the root component instead. See design_docs/0061. */
  readonly isLoginRoute = signal(false);

  constructor() {
    const authService = inject(AuthService);
    // ThemeService applies the right theme entirely from its own constructor - injecting it
    // is what starts that, same eager-singleton-start reasoning as TorrentEventsService below.
    inject(ThemeService);

    this.router.events.pipe(filter((event) => event instanceof NavigationEnd)).subscribe(() => {
      this.isLoginRoute.set(this.router.url.startsWith('/login'));
    });

    // Connect immediately, same as before this feature existed, unless auth is enabled and
    // there's no token yet to connect with - in that case LoginPage connects once login
    // succeeds instead. Fails open (connects anyway) on a status-check error, matching
    // authGuard's own reasoning: the real boundary is server-side regardless.
    authService.status().subscribe({
      next: (status) => {
        if (!status.authEnabled || authService.token()) {
          this.torrentEventsService.connect();
        }
      },
      error: () => this.torrentEventsService.connect(),
    });
  }
}
