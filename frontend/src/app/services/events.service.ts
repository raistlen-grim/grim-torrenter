import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { LibraryEvent } from '../models/events.model';

@Injectable({ providedIn: 'root' })
export class EventsService {
  private readonly http = inject(HttpClient);

  /** infoHash filters to one torrent's history - used by a future "view history" link from
   * the torrent detail drawer; the Events page itself always omits it. */
  list(infoHash?: string): Observable<LibraryEvent[]> {
    const params = infoHash ? new HttpParams().set('infoHash', infoHash) : undefined;
    return this.http.get<LibraryEvent[]>('/api/events', { params });
  }
}
