import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { BlocklistStatus } from '../models/blocklist.model';

/** The IP blocklist's status and manual reload (design_docs/0078). The settings that drive it
 * (enabled/source/refresh interval) go through SettingsService like every other setting. */
@Injectable({ providedIn: 'root' })
export class BlocklistService {
  private readonly http = inject(HttpClient);

  status(): Observable<BlocklistStatus> {
    return this.http.get<BlocklistStatus>('/api/blocklist');
  }

  /** Starts a reload and returns the status as it stands right then (loading: true) - the load
   * itself runs in the background, so keep polling status(). */
  reload(): Observable<BlocklistStatus> {
    return this.http.post<BlocklistStatus>('/api/blocklist/reload', null);
  }
}
