import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { providePrimeNG } from 'primeng/config';

import { routes } from './app.routes';
import { authInterceptor } from './services/auth.interceptor';
import { GrimTorrenterPreset } from './theme/grimtorrenter-preset';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    // withComponentInputBinding: route params (e.g. :infoHash) bind directly to a
    // component's input() signals - see TorrentDetail.
    provideRouter(routes, withComponentInputBinding()),
    // authInterceptor attaches the bearer token (design_docs/0061) and redirects to /login
    // on a 401 - see its own doc comment.
    provideHttpClient(withInterceptors([authInterceptor])),
    // darkModeSelector: an attribute, not the default 'system' - ThemeService resolves the
    // SYSTEM/LIGHT/DARK preference itself (matchMedia for SYSTEM) and writes the result to
    // this same attribute, so PrimeNG's own dark tokens and this app's own (styles.scss) stay
    // in sync off one source of truth rather than PrimeNG tracking the OS independently.
    providePrimeNG({ theme: { preset: GrimTorrenterPreset, options: { darkModeSelector: '[data-theme="dark"]' } } })
  ]
};
