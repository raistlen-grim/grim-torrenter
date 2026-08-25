import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { providePrimeNG } from 'primeng/config';

import { routes } from './app.routes';
import { GrimTorrenterPreset } from './theme/grimtorrenter-preset';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    // withComponentInputBinding: route params (e.g. :infoHash) bind directly to a
    // component's input() signals - see TorrentDetail.
    provideRouter(routes, withComponentInputBinding()),
    provideHttpClient(),
    providePrimeNG({ theme: { preset: GrimTorrenterPreset } })
  ]
};
