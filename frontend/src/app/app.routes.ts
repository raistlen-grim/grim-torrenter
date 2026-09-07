import { Routes } from '@angular/router';

import { authGuard } from './services/auth.guard';

export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./login-page/login-page').then((m) => m.LoginPage),
  },
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () => import('./torrent-list/torrent-list').then((m) => m.TorrentList),
    // TorrentDetail is a *child* of TorrentList's own route, not a sibling - see
    // design_docs/0044. TorrentList renders a <router-outlet> of its own (inside a slide-
    // out drawer), so navigating to /torrents/:infoHash mounts TorrentDetail into that
    // outlet without unmounting/remounting TorrentList itself, and the list stays visible
    // (and interactive) behind the drawer the whole time.
    children: [
      {
        path: 'torrents/:infoHash',
        loadComponent: () => import('./torrent-detail/torrent-detail').then((m) => m.TorrentDetail),
      },
    ],
  },
  {
    path: 'services',
    canActivate: [authGuard],
    loadComponent: () => import('./services-page/services-page').then((m) => m.ServicesPage),
  },
  {
    path: 'events',
    canActivate: [authGuard],
    loadComponent: () => import('./events-page/events-page').then((m) => m.EventsPage),
  },
  {
    path: 'settings',
    canActivate: [authGuard],
    loadComponent: () => import('./settings-page/settings-page').then((m) => m.SettingsPage),
  },
];
