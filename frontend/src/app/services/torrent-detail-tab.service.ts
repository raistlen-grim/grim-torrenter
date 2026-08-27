import { Injectable, signal } from '@angular/core';

export type DetailTab = 'files' | 'peers' | 'trackers' | 'pieces';

/** One remembered tab per torrent, in-memory only (session, not persisted) - see the guide's
 * "Tab choice persists per torrent for the session." A plain component field wouldn't survive
 * closing the panel and reopening it on the same torrent later, since TorrentDetail is
 * destroyed and recreated whenever the torrents/:infoHash route deactivates and reactivates
 * (unlike navigating between two different open torrents, where the router reuses the same
 * instance) - this needs to outlive that. Same simple signal-service pattern as
 * TorrentFilterService. */
@Injectable({ providedIn: 'root' })
export class TorrentDetailTabService {
  private readonly tabs = signal(new Map<string, DetailTab>());

  get(infoHash: string): DetailTab | undefined {
    return this.tabs().get(infoHash);
  }

  set(infoHash: string, tab: DetailTab): void {
    this.tabs.update((map) => new Map(map).set(infoHash, tab));
  }
}
