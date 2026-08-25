import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { DhtStatus } from '../models/system.model';

@Injectable({ providedIn: 'root' })
export class DhtService {
  private readonly http = inject(HttpClient);

  status(): Observable<DhtStatus> {
    return this.http.get<DhtStatus>('/api/dht/status');
  }
}
