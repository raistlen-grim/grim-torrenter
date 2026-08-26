import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
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
    path: 'events',
    loadComponent: () => import('./events-page/events-page').then((m) => m.EventsPage),
  },
  {
    path: 'settings',
    loadComponent: () => import('./settings-page/settings-page').then((m) => m.SettingsPage),
  },
];
