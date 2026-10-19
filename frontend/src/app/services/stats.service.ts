import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { HistoryResolution, TransferHistory } from '../models/stats.model';
import { TransferStats } from '../models/torrent.model';

/** Engine-wide transfer figures and history - see design_docs/0102. The live figures also
 * arrive as the "transfer" WebSocket message (TorrentEventsService.transfer()); this is the REST
 * side, for history and a first read. */
@Injectable({ providedIn: 'root' })
export class StatsService {
  private readonly http = inject(HttpClient);

  transfer(): Observable<TransferStats> {
    return this.http.get<TransferStats>('/api/stats/transfer');
  }

  history(resolution: HistoryResolution): Observable<TransferHistory> {
    return this.http.get<TransferHistory>('/api/stats/history', { params: { resolution } });
  }
}
